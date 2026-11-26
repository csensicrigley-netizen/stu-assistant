package com.stuassistant.app.nativeapp

import android.content.Context
import android.util.Base64
import com.stuassistant.app.nativeapp.local.LocalJwClient
import com.stuassistant.app.nativeapp.local.LocalMedJwClient
import com.stuassistant.app.nativeapp.local.LocalMyStuClient
import com.stuassistant.app.nativeapp.local.LocalSchoolError
import com.stuassistant.app.nativeapp.local.LocalTimetableImporter
import com.stuassistant.app.nativeapp.local.MedCaptcha
import com.stuassistant.app.nativeapp.local.SchoolChallengeHandler
import com.stuassistant.app.nativeapp.local.SessionProbeStatus
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Result returned to UI after direct-to-school local binding. */
data class LocalCampusBindResult(
    val bound: List<String>,
    val errors: Map<String, NativeApiError>,
)

/**
 * Pure-local store:
 * - All personal credentials, sessions, timetables, tasks, exams, grades, plans and calendar items
 *   are stored exclusively in Android Keystore AES-256-GCM encrypted local storage.
 * - Direct-to-school sync runs inside this device (`LocalJwClient`, `LocalMyStuClient`, `LocalMedJwClient`).
 * - Public server (`stu.oyishi.asia`) is accessed ONLY via `NativeApi`'s hard public allowlist
 *   (notices, bus, charging, announcements, version check, and user-initiated PII-free `today` export).
 */
class NativeStore internal constructor(
    context: Context,
    private val configuredServerForTests: String? = null,
    private val allowServerChangesForTests: Boolean = false,
    private val legacyServerForTests: String? = null,
    private val jwClient: LocalJwClient = LocalJwClient(),
    private val myStuClient: LocalMyStuClient = LocalMyStuClient(),
    private val medJwClient: LocalMedJwClient = LocalMedJwClient(),
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("stu.native.settings.v1", Context.MODE_PRIVATE)
    private val secure = NativeSecureStorage(app)
    private val api = NativeApi()
    private val lock = Any()
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val planMutex = Mutex()
    private val calendarWriteMutex = Mutex()
    private var generation = 0L
    private var closed = false
    private var raw = JSONObject()
    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<NativeState> = mutableState.asStateFlow()

    init {
        if (!state.value.preview && state.value.server.isNotBlank()) {
            jobs.launch { runCatching { refresh("read") } }
        }
    }

    /** Public-only server request. Hard-blocked for any authenticated or non-public route. */
    suspend fun request(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        auth: Boolean = false,
    ): JSONObject {
        if (auth) throw NativeApiError("blocked_non_public_route", 403, "纯本地模式禁止向服务器发送认证请求")
        return requestPublic(session(), path, method, body)
    }

    /** Generate a 5-minute single-use export code for `today` from a strictly PII-free local timetable snapshot. */
    suspend fun exportTodayTimetable(semester: String? = null): NativeTodayExportTicket = withContext(Dispatchers.IO) {
        val owner = session()
        val current = state.value
        if (current.preview || current.sample) {
            throw NativeApiError("no_timetable", 404, "当前还没有可导出的课表，请先同步或手动导入课表。")
        }
        val activeSource = current.activeTimetableSource ?: "manual"
        val campus = when (activeSource) {
            "jw" -> "main"
            "med" -> "med"
            "manual" -> when (current.manualSchool?.lowercase(Locale.ROOT)) {
                "med" -> "med"
                "main" -> "main"
                else -> "manual"
            }
            else -> current.timetable?.optString("campus").orEmpty().ifBlank { "manual" }
        }
        val sanitized = sanitizeTimetableForTodayExport(
            raw = current.timetable,
            campus = campus,
            semesterOverride = semester,
        ) ?: throw NativeApiError("no_timetable", 404, "当前还没有可导出的课表，请先同步或手动导入课表。")

        val response = requestPublic(owner, "/v1/public/export/today", "POST", sanitized)
        val code = response.optString("code").trim()
        val deepLink = response.optString("deep_link").trim()
        if (code.isBlank() || !isAllowedTodayDeepLink(deepLink)) {
            throw invalidResponse("导出响应缺少有效的导入码或跳转链接")
        }
        NativeTodayExportTicket(
            code = code,
            expiresIn = response.optInt("expires_in", 300),
            deepLink = deepLink,
            semester = response.optString("semester").trim(),
            campus = response.optString("campus").trim(),
            courseCount = response.optInt("course_count", 0),
        )
    }

    // -------------------------------------------------------------------------
    // Local Calendar Items (100% on-device encrypted, no login required)
    // -------------------------------------------------------------------------

    private fun calendarKey(): String {
        if (state.value.preview) throw NativeApiError("preview", 0, "体验模式不能保存真实事项")
        return LOCAL_CALENDAR_KEY
    }

    suspend fun calendarItems(): List<JSONObject> = withContext(Dispatchers.IO) {
        synchronized(lock) { academicObjects(secure.readCache(calendarKey())?.optJSONArray("items")).map(::copy) }
    }

    /** One encrypted atomic batch; stable batch IDs prevent double-confirm duplicates. */
    suspend fun saveCalendar(batchId: String, drafts: List<JSONObject>, expectedAccount: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val key = calendarKey()
            if (key != expectedAccount) throw NativeApiError("account_changed", 0, "状态已切换，请重新打开")
            val document = secure.readCache(key) ?: JSONObject().put("items", JSONArray())
            val items = academicObjects(document.optJSONArray("items"))
            if (items.any { it.optString("batch") == batchId }) return@synchronized
            require(drafts.isNotEmpty() && drafts.size <= 8)
            val added = drafts.map { draft ->
                require(draft.optString("title").isNotBlank())
                JSONObject().put("id", UUID.randomUUID().toString()).put("batch", batchId)
                    .put("draft", copy(draft)).put("done", false).put("created_at", System.currentTimeMillis())
            }
            secure.writeCache(key, document.put("items", JSONArray(items + added)))
            mutableState.value = state.value.copy(
                calendarRevision = state.value.calendarRevision + 1,
                calendarLastBatch = batchId,
                calendarFocusDate = CalendarDrafts.date(drafts.first()),
            )
        }
    }

    fun calendarAccount(): String = synchronized(lock) { calendarKey() }

    internal fun queueCalendarWrite(onFinished: (Exception?) -> Unit, write: suspend (String) -> Unit) {
        val account = calendarAccount()
        jobs.launch(start = CoroutineStart.UNDISPATCHED) {
            val failure = try {
                calendarWriteMutex.withLock { write(account) }
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                error
            }
            withContext(Dispatchers.Main.immediate) { onFinished(failure) }
        }
    }

    suspend fun toggleCalendar(id: String, expectedAccount: String) =
        updateCalendarDone(id, expectedAccount) { !it }

    internal suspend fun setCalendarDone(id: String, done: Boolean, expectedAccount: String) =
        updateCalendarDone(id, expectedAccount) { done }

    private suspend fun updateCalendarDone(id: String, expectedAccount: String, change: (Boolean) -> Boolean) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val key = calendarKey()
            if (key != expectedAccount) throw NativeApiError("account_changed")
            val document = secure.readCache(key) ?: return@synchronized
            val record = academicObjects(document.optJSONArray("items"))
                .firstOrNull { it.optString("id") == id } ?: return@synchronized
            val old = record.optBoolean("done")
            val next = change(old)
            if (old == next) return@synchronized
            record.put("done", next)
            secure.writeCache(key, document)
            mutableState.value = state.value.copy(calendarRevision = state.value.calendarRevision + 1)
        }
    }

    suspend fun removeCalendar(id: String, expectedAccount: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val key = calendarKey()
            if (key != expectedAccount) throw NativeApiError("account_changed", 0, "状态已切换，请重新打开")
            val document = secure.readCache(key) ?: return@synchronized
            if (!removeCalendarRecordFromDocument(document, id)) return@synchronized
            secure.writeCache(key, document)
            mutableState.value = state.value.copy(calendarRevision = state.value.calendarRevision + 1)
        }
    }

    suspend fun updateCalendarDraft(id: String, draft: JSONObject, expectedAccount: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val key = calendarKey()
            if (key != expectedAccount) throw NativeApiError("account_changed", 0, "状态已切换，请重新打开")
            require(id.isNotBlank() && draft.optString("title").isNotBlank())
            val document = secure.readCache(key) ?: return@synchronized
            val items = academicObjects(document.optJSONArray("items"))
            var updated = false
            val next = items.map { row ->
                if (row.optString("id") == id) {
                    updated = true
                    copy(row).put("draft", copy(draft))
                } else row
            }
            if (!updated) return@synchronized
            secure.writeCache(key, document.put("items", JSONArray(next)))
            mutableState.value = state.value.copy(calendarRevision = state.value.calendarRevision + 1)
        }
    }

    suspend fun upsertCalendarRecord(record: JSONObject, index: Int? = null, expectedAccount: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val key = calendarKey()
            if (key != expectedAccount) throw NativeApiError("account_changed", 0, "状态已切换，请重新打开")
            val id = record.optString("id")
            require(id.isNotBlank() && (record.optJSONObject("draft")?.optString("title")?.isNotBlank() == true))
            val document = secure.readCache(key) ?: JSONObject().put("items", JSONArray())
            val items = academicObjects(document.optJSONArray("items")).toMutableList()
            val existingIdx = items.indexOfFirst { it.optString("id") == id }
            val clean = copy(record)
            if (existingIdx >= 0) {
                items[existingIdx] = clean
            } else if (index != null) {
                items.add(index.coerceIn(0, items.size), clean)
            } else {
                items.add(clean)
            }
            secure.writeCache(key, document.put("items", JSONArray(items)))
            val focus = clean.optJSONObject("draft")?.let(CalendarDrafts::date) ?: state.value.calendarFocusDate
            mutableState.value = state.value.copy(
                calendarRevision = state.value.calendarRevision + 1,
                calendarFocusDate = focus,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Local Plans CRUD (100% on-device encrypted, replaces /v1/plan)
    // -------------------------------------------------------------------------

    suspend fun loadPlans(start: String? = null, end: String? = null): List<JSONObject> = withContext(Dispatchers.IO) {
        planMutex.withLock {
            synchronized(lock) {
                if (state.value.preview) return@synchronized emptyList()
                val all = rows(raw, "plans")
                val summary = computeLocalPlanSummary(all)
                if (raw.optJSONObject("planSummary")?.toString() != summary.toString()) {
                    val next = copy(raw).put("planSummary", summary)
                    commit(session(), next)
                }
                all.filter { row ->
                    val date = row.optString("date")
                    (start == null || date >= start) && (end == null || date <= end)
                }.map(::copy)
            }
        }
    }

    suspend fun createPlan(fields: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        planMutex.withLock {
            if (state.value.preview) throw NativeApiError("preview_read_only", 0, "体验模式不能保存个人计划")
            val title = fields.optString("title").trim()
            val date = fields.optString("date").trim()
            if (title.isBlank() || date.isBlank()) {
                throw NativeApiError("invalid_input", 400, "请填写计划标题和日期")
            }
            synchronized(lock) {
                val owner = session()
                val existing = rows(raw, "plans")
                val nextSort = (existing.filter { it.optString("date") == date }
                    .maxOfOrNull { it.optInt("sort", 0) } ?: -1) + 1
                val row = normalizeLocalPlanRow(fields, defaultSort = nextSort)
                val updated = existing + row
                val next = copy(raw)
                    .put("plans", JSONArray(updated))
                    .put("planSummary", computeLocalPlanSummary(updated))
                commit(owner, next, error = null)
                copy(row)
            }
        }
    }

    suspend fun updatePlan(id: String, fields: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        planMutex.withLock {
            if (state.value.preview) throw NativeApiError("preview_read_only", 0, "体验模式不能保存个人计划")
            if (id.isBlank()) throw NativeApiError("invalid_input", 400, "计划标识无效")
            synchronized(lock) {
                val owner = session()
                val existing = rows(raw, "plans")
                var updatedRow: JSONObject? = null
                val nextRows = existing.map { row ->
                    if (row.optString("id") == id) {
                        val merged = copy(row)
                        for (key in PLAN_MUTABLE_KEYS) {
                            if (fields.has(key)) merged.put(key, fields.get(key))
                        }
                        if (merged.optString("title").trim().isBlank()) {
                            throw NativeApiError("invalid_input", 400, "计划标题不能为空")
                        }
                        merged.put("updated_at", System.currentTimeMillis())
                        updatedRow = merged
                        merged
                    } else row
                }
                val result = updatedRow ?: throw NativeApiError("plan_not_found", 404, "这条计划已经不在了")
                val next = copy(raw)
                    .put("plans", JSONArray(nextRows))
                    .put("planSummary", computeLocalPlanSummary(nextRows))
                commit(owner, next, error = null)
                copy(result)
            }
        }
    }

    suspend fun togglePlan(id: String, done: Boolean) = withContext(Dispatchers.IO) {
        planMutex.withLock {
            if (state.value.preview) throw NativeApiError("preview_read_only", 0, "体验模式不能保存个人计划")
            synchronized(lock) {
                val owner = session()
                val existing = rows(raw, "plans")
                var found = false
                val nextRows = existing.map { row ->
                    if (row.optString("id") == id) {
                        found = true
                        copy(row).put("done", done).put("updated_at", System.currentTimeMillis())
                    } else row
                }
                if (!found) throw NativeApiError("plan_not_found", 404, "这条计划已经不在了")
                val next = copy(raw)
                    .put("plans", JSONArray(nextRows))
                    .put("planSummary", computeLocalPlanSummary(nextRows))
                commit(owner, next, error = null)
            }
        }
    }

    suspend fun removePlan(id: String) = withContext(Dispatchers.IO) {
        planMutex.withLock {
            if (state.value.preview) throw NativeApiError("preview_read_only", 0, "体验模式不能保存个人计划")
            synchronized(lock) {
                val owner = session()
                val existing = rows(raw, "plans")
                val nextRows = existing.filterNot { it.optString("id") == id }
                val next = copy(raw)
                    .put("plans", JSONArray(nextRows))
                    .put("planSummary", computeLocalPlanSummary(nextRows))
                commit(owner, next, error = null)
            }
        }
    }

    suspend fun reorderPlans(date: String, ids: List<String>) = withContext(Dispatchers.IO) {
        planMutex.withLock {
            if (state.value.preview) throw NativeApiError("preview_read_only", 0, "体验模式不能保存个人计划")
            synchronized(lock) {
                val owner = session()
                val order = ids.withIndex().associate { it.value to it.index }
                val nextRows = rows(raw, "plans").map { row ->
                    if (row.optString("date") == date && row.optString("id") in order) {
                        copy(row).put("sort", order.getValue(row.optString("id")))
                            .put("updated_at", System.currentTimeMillis())
                    } else row
                }
                val next = copy(raw)
                    .put("plans", JSONArray(nextRows))
                    .put("planSummary", computeLocalPlanSummary(nextRows))
                commit(owner, next, error = null)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Direct-to-School Local Campus Binding & Sync (JW + MySTU)
    // -------------------------------------------------------------------------

    suspend fun bindCampusLocal(
        studentId: String,
        password: String,
        remember: Boolean,
        services: List<String> = listOf("jw", "mystu"),
        onChallenge: SchoolChallengeHandler? = null,
    ): LocalCampusBindResult = withContext(Dispatchers.IO) {
        val cleanId = studentId.trim()
        if (cleanId.length < 3 || password.isEmpty()) {
            throw NativeApiError("invalid_authentication_input", 400, "请填写校园账号和学校统一认证密码。")
        }
        val targetServices = services.filter { it in setOf("jw", "mystu") }.distinct()
            .ifEmpty { listOf("jw", "mystu") }

        if (state.value.preview) exitPreview()
        val owner = session()

        val boundNow = mutableListOf<String>()
        val errors = linkedMapOf<String, NativeApiError>()
        val sessions = readCookieSessions()

        if ("jw" in targetServices) {
            try {
                val loginRes = jwClient.login(cleanId, password, onChallenge)
                sessions.put("jw", loginRes.sessionJson)
                writeCookieSessions(sessions)
                boundNow += "jw"
                runCatching {
                    val sync = jwClient.syncAll(loginRes.sessionJson)
                    sessions.put("jw", sync.updatedSessionJson)
                    writeCookieSessions(sessions)
                    applyJwSyncResult(owner, sync)
                }.onFailure { err ->
                    if (err is CancellationException) throw err
                    recordSourceStatus(owner, "jw", stateFromError(err), codeFromError(err))
                }
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                errors["jw"] = toApiError(err)
            }
        }

        if ("mystu" in targetServices) {
            try {
                val loginRes = myStuClient.login(cleanId, password, onChallenge)
                sessions.put("mystu", loginRes.sessionJson)
                writeCookieSessions(sessions)
                boundNow += "mystu"
                runCatching {
                    val sync = myStuClient.fetchTasksAndCourses(loginRes.sessionJson)
                    sessions.put("mystu", sync.updatedSessionJson)
                    writeCookieSessions(sessions)
                    applyMyStuSyncResult(owner, sync)
                }.onFailure { err ->
                    if (err is CancellationException) throw err
                    recordSourceStatus(owner, "mystu", stateFromError(err), codeFromError(err))
                }
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                errors["mystu"] = toApiError(err)
            }
        }

        if (boundNow.isEmpty()) {
            val first = errors.values.firstOrNull() ?: NativeApiError("login_failed", 401, "未能连接学校系统，请检查学号和密码。")
            throw first
        }

        rememberSchool(cleanId, password, remember)
        synchronized(lock) {
            ensureCurrent(owner)
            val next = copy(raw)
            val boundSet = (stringSet(next.optJSONArray("boundSources")) + boundNow).toMutableSet()
            next.put("boundSources", JSONArray(boundSet.toList()))
            next.put("onboarded", true)
            next.put("syncedAt", System.currentTimeMillis())
            commit(owner, next, error = null, mode = "live")
        }
        // Best-effort public notices fetch after connecting.
        jobs.launch { runCatching { refreshPublicNotices(owner) } }

        LocalCampusBindResult(bound = boundNow, errors = errors)
    }

    // -------------------------------------------------------------------------
    // Direct-to-School Local Medical College JW (SUMC)
    // -------------------------------------------------------------------------

    fun medStatusLocal(): JSONObject = synchronized(lock) {
        val saved = runCatching { secure.read(LOCAL_MED_SESSION_KEY) }.getOrNull()
        val token = saved?.optString("token")?.takeIf { it.isNotBlank() }
        if (token == null) {
            JSONObject().put("linked", false)
        } else {
            JSONObject()
                .put("linked", true)
                .put("username", saved.optString("username"))
                .put("name", saved.optString("name"))
                .put("mode", saved.optString("mode"))
        }
    }

    suspend fun fetchMedCaptchaLocal(): MedCaptcha = withContext(Dispatchers.IO) {
        try {
            medJwClient.fetchCaptcha()
        } catch (err: CancellationException) {
            throw err
        } catch (err: Exception) {
            throw toApiError(err)
        }
    }

    suspend fun bindMedLocal(
        mode: String,
        username: String,
        password: String,
        verifyCode: String = "",
        yzmCode: String = "",
        semester: String = "",
    ): JSONObject = withContext(Dispatchers.IO) {
        if (state.value.preview) exitPreview()
        val owner = session()
        val cleanUser = username.trim()
        val result = try {
            if (mode == "local") {
                medJwClient.loginLocal(cleanUser, password, verifyCode.trim(), yzmCode.trim())
            } else {
                medJwClient.loginCas(cleanUser, password)
            }
        } catch (err: CancellationException) {
            throw err
        } catch (err: Exception) {
            throw toApiError(err)
        }
        val sessionObj = JSONObject()
            .put("token", result.token)
            .put("username", result.user.account.ifBlank { cleanUser })
            .put("name", result.user.name)
            .put("mode", mode)
            .put("savedAt", System.currentTimeMillis())
        secure.write(LOCAL_MED_SESSION_KEY, sessionObj)
        synchronized(lock) {
            ensureCurrent(owner)
            val next = copy(raw)
            val boundSet = stringSet(next.optJSONArray("boundSources")) + "med"
            next.put("boundSources", JSONArray(boundSet.toList()))
            next.put("onboarded", true)
            commit(owner, next, error = null)
        }
        val summary = if (semester.isNotBlank()) syncMedLocal(semester) else JSONObject().put("ok", true)
        JSONObject()
            .put("ok", true)
            .put("linked", true)
            .put("username", sessionObj.optString("username"))
            .put("name", sessionObj.optString("name"))
            .put("summary", summary)
    }

    suspend fun syncMedLocal(semester: String = ""): JSONObject = withContext(Dispatchers.IO) {
        if (state.value.preview) throw NativeApiError("preview", 0, "请先退出示例模式再同步医学院教务。")
        val owner = session()
        val saved = secure.read(LOCAL_MED_SESSION_KEY)
        val token = saved?.optString("token")?.takeIf { it.isNotBlank() }
            ?: throw NativeApiError("not_linked", 400, "还没连过医学院账号，先把账号密码填上。")
        val resolvedTerm = semester.trim().ifBlank {
            (AcademicCalendar.position()?.term ?: AcademicCalendar.currentOrNext(AcademicClock.moment().date)).id
        }
        val doc = try {
            medJwClient.fetchTimetable(token, resolvedTerm)
        } catch (err: CancellationException) {
            throw err
        } catch (err: Exception) {
            val mapped = toApiError(err)
            if (mapped.code == "med_expired") {
                recordSourceStatus(owner, "med", "need_login", 3)
            } else {
                recordSourceStatus(owner, "med", "failed", 1)
            }
            throw mapped
        }
        val gradesResult = runCatching { medJwClient.fetchGrades(token) }.getOrNull()
        val examsResult = runCatching { medJwClient.fetchExams(token) }.getOrNull()
        synchronized(lock) {
            ensureCurrent(owner)
            val next = copy(raw)
            putSourceTimetable(next, "med", doc, prefer = true)
            gradesResult?.optJSONArray("rows")?.let { if (it.length() > 0) next.put("grades", it) }
            examsResult?.optJSONArray("rows")?.let { if (it.length() > 0) next.put("exams", it) }
            val boundSet = stringSet(next.optJSONArray("boundSources")) + "med"
            next.put("boundSources", JSONArray(boundSet.toList()))
            updateSourceEntry(next, "med", "ok", 0)
            next.put("onboarded", true)
            next.put("syncedAt", System.currentTimeMillis())
            commit(owner, next, error = null, mode = "live")
        }
        LocalMedJwClient.syncSummary(doc)
    }

    suspend fun unlinkMedLocal() = withContext(Dispatchers.IO) {
        val owner = session()
        secure.remove(LOCAL_MED_SESSION_KEY)
        synchronized(lock) {
            ensureCurrent(owner)
            val next = copy(raw)
            val boundSet = stringSet(next.optJSONArray("boundSources")) - "med"
            next.put("boundSources", JSONArray(boundSet.toList()))
            val sources = rows(next, "sources").filterNot { it.optString("source") == "med" }
            next.put("sources", JSONArray(sources))
            commit(owner, next, error = null)
        }
    }

    // -------------------------------------------------------------------------
    // Offline Local Manual Timetable Importer
    // -------------------------------------------------------------------------

    fun importManualTextLocal(text: String, format: String = "paste", semester: String): JSONObject =
        LocalTimetableImporter.parseText(text, format = format, semester = semester)

    fun importManualCsvLocal(csv: String, semester: String): JSONObject =
        LocalTimetableImporter.parseCsv(csv, semester = semester)

    fun csvTemplateLocal(): String = LocalTimetableImporter.templateCsv()

    suspend fun saveManualTimetableLocal(
        doc: JSONObject,
        school: String?,
        note: String = "",
    ): JSONObject = withContext(Dispatchers.IO) {
        if (state.value.preview) exitPreview()
        val owner = session()
        val campus = when (school?.lowercase(Locale.ROOT)) {
            "med" -> "med"
            "main" -> "main"
            else -> "manual"
        }
        val validated = LocalTimetableImporter.validateDoc(doc, campus = campus)
        if (!validated.optBoolean("ok")) {
            return@withContext validated
        }
        val canonicalDoc = validated.getJSONObject("doc")
        val resolvedSemester = canonicalDoc.optString("semester")
        synchronized(lock) {
            ensureCurrent(owner)
            val next = copy(raw)
            putSourceTimetable(next, "manual", canonicalDoc, prefer = true)
            next.put(
                "manualMeta",
                JSONObject()
                    .put("school", school ?: "other")
                    .put("semester", resolvedSemester)
                    .put("note", note.trim()),
            )
            val boundSet = stringSet(next.optJSONArray("boundSources")) + "manual"
            next.put("boundSources", JSONArray(boundSet.toList()))
            updateSourceEntry(next, "manual", "ok", 0)
            next.put("onboarded", true)
            next.put("syncedAt", System.currentTimeMillis())
            commit(owner, next, error = null, mode = "live")
        }
        JSONObject()
            .put("ok", true)
            .put("semester", resolvedSemester)
            .put("school", school ?: "other")
            .put("stats", validated.optJSONObject("stats") ?: JSONObject())
    }

    suspend fun clearManualTimetableLocal() = withContext(Dispatchers.IO) {
        val owner = session()
        synchronized(lock) {
            ensureCurrent(owner)
            val next = copy(raw)
            val map = next.optJSONObject("timetables")?.let(::copy) ?: JSONObject()
            map.remove("manual")
            next.put("timetables", map)
            next.remove("manualMeta")
            if (next.optString("preferredTimetableSource") == "manual") {
                next.remove("preferredTimetableSource")
            }
            val boundSet = stringSet(next.optJSONArray("boundSources")) - "manual"
            next.put("boundSources", JSONArray(boundSet.toList()))
            val sources = rows(next, "sources").filterNot { it.optString("source") == "manual" }
            next.put("sources", JSONArray(sources))
            commit(owner, next, error = null)
        }
    }

    fun selectTimetableSource(source: String) {
        if (source !in setOf("jw", "med", "manual")) return
        synchronized(lock) {
            if (closed || state.value.preview) return
            val owner = session()
            val next = copy(raw).put("preferredTimetableSource", source)
            commit(owner, next)
        }
    }

    // -------------------------------------------------------------------------
    // Onboarding, Preview, Appearance, Credentials, Reset
    // -------------------------------------------------------------------------

    fun completeOnboarding() {
        synchronized(lock) {
            if (closed || state.value.preview || state.value.onboarded) return
            val owner = session()
            val next = copy(raw).put("onboarded", true)
            commit(owner, next)
        }
    }

    fun enterPreview() {
        synchronized(lock) {
            if (closed) return
            generation++
            val base = appearanceState(state.value.server).copy(
                preview = true,
                sample = true,
                mode = "preview",
                onboarded = true,
            )
            try {
                raw = previewData()
                raw.put("plans", JSONArray()).put("planSummary", emptySummary())
                mutableState.value = present(base, raw)
            } catch (error: Exception) {
                raw = JSONObject()
                mutableState.value = base.copy(error = message(error))
            }
        }
    }

    fun exitPreview() {
        synchronized(lock) {
            if (closed || !state.value.preview) return
            generation++
            mutableState.value = loadLocalVault(state.value.server)
        }
    }

    /** Fixture-only seam. Product instances always use the packaged service configuration. */
    internal fun setServer(server: String) {
        if (configuredServerForTests == null || !allowServerChangesForTests) {
            throw NativeApiError("server_fixed", 0, "服务地址由应用统一配置")
        }
        val normalized = try {
            val candidate = NativeApi.normalizeServer(server)
            if (candidate.isNotBlank()) candidate else NativeApi.normalizeServer(configuredServer())
        } catch (error: Exception) {
            setError(message(error))
            return
        }
        synchronized(lock) {
            if (closed || (normalized == state.value.server && !state.value.preview)) return
            if (!prefs.edit().putString("server", normalized).commit()) {
                setError("服务器地址未能保存")
                return
            }
            generation++
            mutableState.value = loadLocalVault(normalized)
        }
    }

    fun setAppearance(
        theme: String? = null,
        palette: String? = null,
        strength: Int? = null,
        style: String? = null,
    ) {
        synchronized(lock) {
            if (closed) return
            val nextTheme = theme?.takeIf { it in setOf("system", "light", "dark") } ?: state.value.theme
            val nextPalette = palette?.takeIf { it in NATIVE_PALETTE_KEYS } ?: state.value.palette
            val nextStyle = style?.takeIf { it in NATIVE_STYLE_KEYS } ?: state.value.style
            val nextStrength = strength?.coerceIn(0, 100) ?: state.value.hapticStrength
            if (nextTheme == state.value.theme &&
                nextPalette == state.value.palette &&
                nextStyle == state.value.style &&
                nextStrength == state.value.hapticStrength
            ) return
            prefs.edit()
                .putString("theme", nextTheme)
                .putString("palette", nextPalette)
                .putString("style", nextStyle)
                .putInt("strength", nextStrength)
                .apply()
            mutableState.value = state.value.copy(
                theme = nextTheme,
                palette = nextPalette,
                style = nextStyle,
                hapticStrength = nextStrength,
            )
        }
    }

    fun rememberSchool(id: String, password: String, remember: Boolean) {
        synchronized(lock) {
            try {
                if (state.value.preview) throw NativeApiError("preview", 0, "体验模式不能保存凭据")
                val cleanId = id.trim()
                if (remember) {
                    if (cleanId.isBlank() || password.isEmpty()) {
                        throw NativeApiError("invalid_input", 400, "请填写学校账号和密码")
                    }
                    secure.write(
                        LOCAL_SCHOOL_KEY,
                        JSONObject().put("id", cleanId).put("password", password).put("remember", true),
                    )
                } else {
                    if (cleanId.isNotBlank()) {
                        secure.write(
                            LOCAL_SCHOOL_KEY,
                            JSONObject().put("id", cleanId).put("remember", false),
                        )
                    } else {
                        secure.remove(LOCAL_SCHOOL_KEY)
                    }
                }
                mutableState.value = state.value.copy(schoolId = cleanId, rememberSchool = remember)
            } catch (error: Exception) {
                setError(message(error))
            }
        }
    }

    fun savedSchoolPassword(): String? = synchronized(lock) {
        val current = state.value
        if (current.preview || !current.rememberSchool) return@synchronized null
        try {
            secure.read(LOCAL_SCHOOL_KEY)?.optString("password")?.takeIf { it.isNotEmpty() }
        } catch (error: Exception) {
            setError(message(error))
            null
        }
    }

    fun getSavedSchoolPassword(): String? = savedSchoolPassword()

    /** Clears only the saved on-device campus password copy (keeps active cookies & timetables). */
    fun clearSchool() {
        synchronized(lock) {
            try {
                val existingId = state.value.schoolId
                if (existingId.isNotBlank()) {
                    secure.write(LOCAL_SCHOOL_KEY, JSONObject().put("id", existingId).put("remember", false))
                } else {
                    secure.remove(LOCAL_SCHOOL_KEY)
                }
                mutableState.value = state.value.copy(rememberSchool = false)
            } catch (error: Exception) {
                setError(message(error))
            }
        }
    }

    /** Clears all on-device campus passwords, business cookies and MedJW session tokens while keeping cached timetables & items. */
    suspend fun clearCampusCredentials() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (closed) return@synchronized
            val owner = session()
            val ops = listOf<() -> Unit>(
                { secure.remove(LOCAL_SCHOOL_KEY) },
                { secure.remove(LOCAL_COOKIES_KEY) },
                { secure.remove(LOCAL_MED_SESSION_KEY) },
                { secure.clearPrefix("school:") },
                { secure.clearPrefix("session:") },
            )
            var problem: String? = null
            ops.forEach { op ->
                try { op() } catch (err: Exception) { problem = "部分本机凭据未能清除：${message(err)}" }
            }
            val next = copy(raw)
            val keptSources = stringSet(next.optJSONArray("boundSources")).filter { it == "manual" }
            next.put("boundSources", JSONArray(keptSources))
            val sources = rows(next, "sources").filter { it.optString("source") in setOf("public", "manual") }
            next.put("sources", JSONArray(sources))
            commit(owner, next, error = problem)
            mutableState.value = mutableState.value.copy(
                schoolId = "",
                rememberSchool = false,
                medLinked = false,
                medUsername = "",
                medName = "",
            )
        }
    }

    /** Wipes every local encrypted record (timetables, items, plans, credentials, cookies, legacy caches). */
    suspend fun resetAllLocalData() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (closed) return@synchronized
            val server = state.value.server
            var problem: String? = null
            val ops = listOf<() -> Unit>(
                { secure.remove(LOCAL_SCHOOL_KEY) },
                { secure.remove(LOCAL_COOKIES_KEY) },
                { secure.remove(LOCAL_MED_SESSION_KEY) },
                { secure.removeCache(LOCAL_DATA_SCOPE) },
                { secure.removeCache(LOCAL_CALENDAR_KEY) },
                { secure.clearPrefix("school:") },
                { secure.clearPrefix("session:") },
                { secure.clearPrefix("server-archive:") },
                { secure.clearPrefix("legacy-retained:") },
                { secure.remove("legacy.pending") },
            )
            ops.forEach { op ->
                try { op() } catch (err: Exception) { problem = "部分本地数据未能完全清除：${message(err)}" }
            }
            generation++
            raw = JSONObject()
            mutableState.value = appearanceState(server).copy(
                user = localUserObject(emptySet()),
                onboarded = false,
                error = problem,
            )
        }
    }

    fun toggleTask(id: String) = updateTaskDone(id) { !it }

    internal fun setTaskDone(id: String, done: Boolean) = updateTaskDone(id) { done }

    private fun updateTaskDone(id: String, change: (Boolean) -> Boolean) {
        synchronized(lock) {
            if (closed || id.isBlank()) return
            val wasDone = id in state.value.done
            val isDone = change(wasDone)
            if (isDone == wasDone) return
            val owner = session()
            val done = if (isDone) state.value.done + id else state.value.done - id
            val next = copy(raw).put("done", JSONArray(done.toList()))
            commit(owner, next)
        }
    }

    // -------------------------------------------------------------------------
    // Refresh: Public notices (anonymous) + Direct-to-School Local Sync
    // -------------------------------------------------------------------------

    /**
     * - `read`: fetches public notices from `stu.oyishi.asia/v1/public/notices` only.
     * - `auto`: fetches public notices and locally refreshes stale (>30m) bound school sources.
     * - `sync`: fetches public notices and locally syncs bound sources (with 60s per-source cooldown).
     * - `force`: fetches public notices and forces immediate local sync of all bound sources.
     */
    suspend fun refresh(how: String = "read") {
        refreshOwned(session(), how)
    }

    fun reloadAfterMutation() {
        val owner = try { session() } catch (_: NativeApiError) { return }
        jobs.launch {
            try { refreshOwned(owner, "read") }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Store already records any error. */ }
        }
    }

    private suspend fun refreshOwned(requestedOwner: NativeSession, how: String) {
        withContext(Dispatchers.IO) {
            if (how !in setOf("read", "auto", "sync", "force")) {
                throw NativeApiError("invalid_input", 400, "未知刷新方式")
            }
            refreshMutex.withLock {
                val owner = requestedOwner
                ensureCurrent(owner)
                if (state.value.preview) return@withLock
                update(owner) { it.copy(loading = true, error = null) }
                try {
                    val syncDirect = when (how) {
                        "sync", "force" -> true
                        "auto" -> shouldSyncLocalSources()
                        else -> false
                    }
                    var firstFailure: Exception? = null
                    coroutineScope {
                        val publicJob = async {
                            try { refreshPublicNotices(owner) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { firstFailure = firstFailure ?: e }
                        }
                        val localJob = if (syncDirect) {
                            async {
                                update(owner) { it.copy(syncing = true) }
                                try { synchronizeLocalSources(owner, force = how == "force") }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { firstFailure = firstFailure ?: e }
                            }
                        } else null
                        publicJob.await()
                        localJob?.await()
                    }
                    firstFailure?.let { err ->
                        update(owner) { current -> current.copy(error = message(err)) }
                        if (how == "sync" || how == "force") throw err
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    update(owner) { it.copy(error = message(error)) }
                    throw error
                } finally {
                    update(owner) { it.copy(loading = false, syncing = false) }
                }
            }
        }
    }

    private suspend fun refreshPublicNotices(owner: NativeSession) {
        if (owner.server.isBlank()) return
        val response = requestPublic(owner, "/v1/public/notices?limit=30")
        val items = response.optJSONArray("items") ?: throw invalidResponse("公开通知响应结构不完整")
        val flat = flattenFeed(items, notice = true)
        synchronized(lock) {
            ensureCurrent(owner)
            val next = copy(raw)
            next.put("notices", JSONArray(flat))
            updateSourceEntry(next, "public", "ok", 0)
            if (next.optLong("syncedAt", 0L) <= 0L) next.put("syncedAt", System.currentTimeMillis())
            commit(owner, next, error = null, mode = "live")
        }
    }

    private suspend fun synchronizeLocalSources(owner: NativeSession, force: Boolean) {
        val bound = synchronized(lock) { stringSet(raw.optJSONArray("boundSources")) }
        val nowMs = System.currentTimeMillis()
        var firstError: Exception? = null

        if ("jw" in bound && (force || sourceNeedsSync("jw", nowMs))) {
            try { syncJwWithAutoRelogin(owner) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { firstError = firstError ?: toApiError(e) }
        }
        if ("mystu" in bound && (force || sourceNeedsSync("mystu", nowMs))) {
            try { syncMyStuWithAutoRelogin(owner) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { firstError = firstError ?: toApiError(e) }
        }
        if ("med" in bound && (force || sourceNeedsSync("med", nowMs))) {
            try { syncMedLocal() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { firstError = firstError ?: toApiError(e) }
        }

        if (firstError != null) throw firstError!!
    }

    private suspend fun syncJwWithAutoRelogin(owner: NativeSession) {
        val sessions = readCookieSessions()
        var jwSession = sessions.optJSONObject("jw")
        if (jwSession == null || jwClient.probeSession(jwSession) == SessionProbeStatus.EXPIRED) {
            val savedId = state.value.schoolId
            val savedPassword = savedSchoolPassword()
            if (savedId.isNotBlank() && savedPassword != null) {
                val relogin = try {
                    jwClient.login(savedId, savedPassword, challengeHandler = null)
                } catch (err: CancellationException) {
                    throw err
                } catch (err: Exception) {
                    recordSourceStatus(owner, "jw", stateFromError(err), codeFromError(err))
                    throw toApiError(err)
                }
                jwSession = relogin.sessionJson
                sessions.put("jw", jwSession)
                writeCookieSessions(sessions)
            } else {
                recordSourceStatus(owner, "jw", "need_login", 3)
                throw NativeApiError("login_expired", 401, "教务本机登录会话已过期，请重新连接。")
            }
        }
        val sync = try {
            jwClient.syncAll(jwSession)
        } catch (err: LocalSchoolError) {
            if (err.code in setOf("login_expired", "needs_login", "session_invalid")) {
                val savedId = state.value.schoolId
                val savedPassword = savedSchoolPassword()
                if (savedId.isNotBlank() && savedPassword != null) {
                    val relogin = try {
                        jwClient.login(savedId, savedPassword, challengeHandler = null)
                    } catch (reloginErr: CancellationException) {
                        throw reloginErr
                    } catch (reloginErr: Exception) {
                        recordSourceStatus(owner, "jw", stateFromError(reloginErr), codeFromError(reloginErr))
                        throw toApiError(reloginErr)
                    }
                    sessions.put("jw", relogin.sessionJson)
                    writeCookieSessions(sessions)
                    try {
                        jwClient.syncAll(relogin.sessionJson)
                    } catch (syncErr: CancellationException) {
                        throw syncErr
                    } catch (syncErr: Exception) {
                        recordSourceStatus(owner, "jw", stateFromError(syncErr), codeFromError(syncErr))
                        throw toApiError(syncErr)
                    }
                } else {
                    recordSourceStatus(owner, "jw", "need_login", 3)
                    throw toApiError(err)
                }
            } else {
                recordSourceStatus(owner, "jw", stateFromError(err), codeFromError(err))
                throw toApiError(err)
            }
        }
        sessions.put("jw", sync.updatedSessionJson)
        writeCookieSessions(sessions)
        applyJwSyncResult(owner, sync)
    }

    private suspend fun syncMyStuWithAutoRelogin(owner: NativeSession) {
        val sessions = readCookieSessions()
        var msSession = sessions.optJSONObject("mystu")
        if (msSession == null || myStuClient.probeSession(msSession) == SessionProbeStatus.EXPIRED) {
            val savedId = state.value.schoolId
            val savedPassword = savedSchoolPassword()
            if (savedId.isNotBlank() && savedPassword != null) {
                val relogin = try {
                    myStuClient.login(savedId, savedPassword, challengeHandler = null)
                } catch (err: CancellationException) {
                    throw err
                } catch (err: Exception) {
                    recordSourceStatus(owner, "mystu", stateFromError(err), codeFromError(err))
                    throw toApiError(err)
                }
                msSession = relogin.sessionJson
                sessions.put("mystu", msSession)
                writeCookieSessions(sessions)
            } else {
                recordSourceStatus(owner, "mystu", "need_login", 3)
                throw NativeApiError("login_expired", 401, "MySTU 本机登录会话已过期，请重新连接。")
            }
        }
        val sync = try {
            myStuClient.fetchTasksAndCourses(msSession)
        } catch (err: LocalSchoolError) {
            if (err.code in setOf("login_expired", "needs_login", "session_invalid", "access_denied")) {
                val savedId = state.value.schoolId
                val savedPassword = savedSchoolPassword()
                if (savedId.isNotBlank() && savedPassword != null) {
                    val relogin = try {
                        myStuClient.login(savedId, savedPassword, challengeHandler = null)
                    } catch (reloginErr: CancellationException) {
                        throw reloginErr
                    } catch (reloginErr: Exception) {
                        recordSourceStatus(owner, "mystu", stateFromError(reloginErr), codeFromError(reloginErr))
                        throw toApiError(reloginErr)
                    }
                    sessions.put("mystu", relogin.sessionJson)
                    writeCookieSessions(sessions)
                    try {
                        myStuClient.fetchTasksAndCourses(relogin.sessionJson)
                    } catch (syncErr: CancellationException) {
                        throw syncErr
                    } catch (syncErr: Exception) {
                        recordSourceStatus(owner, "mystu", stateFromError(syncErr), codeFromError(syncErr))
                        throw toApiError(syncErr)
                    }
                } else {
                    recordSourceStatus(owner, "mystu", "need_login", 3)
                    throw toApiError(err)
                }
            } else {
                recordSourceStatus(owner, "mystu", stateFromError(err), codeFromError(err))
                throw toApiError(err)
            }
        }
        sessions.put("mystu", sync.updatedSessionJson)
        writeCookieSessions(sessions)
        applyMyStuSyncResult(owner, sync)
    }

    private fun applyJwSyncResult(owner: NativeSession, sync: com.stuassistant.app.nativeapp.local.LocalJwSyncResult) {
        synchronized(lock) {
            ensureCurrent(owner)
            val next = copy(raw)
            putSourceTimetable(next, "jw", sync.timetable, prefer = !next.has("preferredTimetableSource"))
            if (sync.grades.length() > 0 || !next.has("grades")) next.put("grades", sync.grades)
            if (sync.exams.length() > 0 || !next.has("exams")) next.put("exams", sync.exams)
            updateSourceEntry(next, "jw", "ok", 0)
            next.put("syncedAt", System.currentTimeMillis())
            commit(owner, next, error = null, mode = "live")
        }
    }

    private fun applyMyStuSyncResult(owner: NativeSession, sync: com.stuassistant.app.nativeapp.local.LocalMyStuSyncResult) {
        synchronized(lock) {
            ensureCurrent(owner)
            val next = copy(raw)
            next.put("tasks", sync.tasks)
            updateSourceEntry(next, "mystu", "ok", 0)
            next.put("syncedAt", System.currentTimeMillis())
            commit(owner, next, error = null, mode = "live")
        }
    }

    private fun sourceNeedsSync(source: String, nowMs: Long): Boolean = synchronized(lock) {
        val entry = rows(raw, "sources").firstOrNull { it.optString("source") == source } ?: return@synchronized true
        val atSec = entry.optDouble("at", Double.NaN)
        if (!atSec.isFinite() || atSec <= 0.0) return@synchronized true
        nowMs - (atSec * 1000.0).toLong() > 60_000L
    }

    private fun shouldSyncLocalSources(): Boolean = synchronized(lock) {
        val bound = stringSet(raw.optJSONArray("boundSources")).filter { it in setOf("jw", "mystu", "med") }
        if (bound.isEmpty()) return@synchronized false
        val timetables = raw.optJSONObject("timetables")
        if ("jw" in bound && (timetables?.optJSONObject("jw")?.optJSONArray("courses")?.length() ?: 0) == 0) {
            return@synchronized true
        }
        val bySource = rows(raw, "sources").associateBy { it.optString("source") }
        val nowMs = System.currentTimeMillis()
        bound.any { src ->
            val entry = bySource[src] ?: return@any true
            val atSec = entry.optDouble("at", Double.NaN)
            !atSec.isFinite() || atSec <= 0.0 || nowMs - (atSec * 1000.0).toLong() > 30 * 60_000L
        }
    }

    // -------------------------------------------------------------------------
    // Legacy Storage Migration (v1.1.11 + Capacitor WebView)
    // -------------------------------------------------------------------------

    fun legacyMigrationDone(): Boolean = prefs.getInt("legacyMigrationVersion", 0) >= 1

    /**
     * Imports settings, local task completion, and encrypted school password from the
     * previous Capacitor WebView origin without storing any server JWT.
     */
    fun importLegacy(values: JSONObject) {
        synchronized(lock) {
            if (legacyMigrationDone() || closed) return
            val editor = prefs.edit()
            legacyValue(values, "stu.theme").takeIf { it in setOf("system", "light", "dark") }?.let { editor.putString("theme", it) }
            legacyValue(values, "stu.palette").takeIf { it in NATIVE_PALETTE_KEYS }?.let { editor.putString("palette", it) }
            val strength = legacyValue(values, "stu.haptics.strength").toIntOrNull()
                ?: if (legacyValue(values, "stu.haptics") == "off") 0 else null
            strength?.let { editor.putInt("strength", it.coerceIn(0, 100)) }
            legacyValue(values, "stu.update.skip").toLongOrNull()?.takeIf { it >= 0 }?.let { code ->
                if (!app.getSharedPreferences("native-updates", Context.MODE_PRIVATE).edit().putLong("skip", code).commit()) {
                    throw NativeApiError("storage_error", 0, "旧更新提醒设置未能保存，请重试")
                }
            }
            val token = legacyValue(values, "stu.token").takeIf { it.isNotBlank() && it != "null" }
            val uid = token?.let(::tokenSubject)
            val next = secure.readCache(LOCAL_DATA_SCOPE) ?: JSONObject()
            var migratedData = false
            val doneKeys = values.keys().asSequence().filter { it.startsWith("stu.done.v2:") }.toList()
            val mergedDone = stringSet(next.optJSONArray("done")).toMutableSet()
            if (uid != null) {
                legacyValue(values, "stu.done.v2:$uid").takeIf { it.isNotBlank() }?.let { rawDone ->
                    runCatching { mergedDone += stringSet(JSONArray(rawDone)) }
                }
            }
            doneKeys.forEach { k ->
                legacyValue(values, k).takeIf { it.isNotBlank() }?.let { rawDone ->
                    runCatching { mergedDone += stringSet(JSONArray(rawDone)) }
                }
            }
            if (mergedDone.isNotEmpty()) {
                next.put("done", JSONArray(mergedDone.toList()))
                migratedData = true
            }
            val account = legacyValue(values, "stu.account")
            val schoolKeyCandidate = values.keys().asSequence().firstOrNull { k ->
                k.startsWith("stu.school:") && (account.isBlank() || k.endsWith("|$account"))
            }
            if (schoolKeyCandidate != null && secure.read(LOCAL_SCHOOL_KEY) == null) {
                runCatching {
                    val saved = JSONObject(legacyValue(values, schoolKeyCandidate))
                    val id = saved.optString("account").trim()
                    val password = saved.optString("password")
                    if (id.isNotBlank() && password.isNotEmpty()) {
                        secure.write(
                            LOCAL_SCHOOL_KEY,
                            JSONObject().put("id", id).put("password", password).put("remember", true),
                        )
                        migratedData = true
                    }
                }
            }
            if (migratedData) {
                next.put("onboarded", true)
                secure.writeCache(LOCAL_DATA_SCOPE, next)
            }
            if (!editor.putInt("legacyMigrationVersion", 1).commit()) {
                throw NativeApiError("storage_error", 0, "旧设置迁移未能保存，请重试")
            }
            generation++
            mutableState.value = loadLocalVault(state.value.server)
        }
    }

    /**
     * One-time upgrade migration from v1.1.11 account-scoped encrypted storage into the
     * pure-local vault. Preserves calendar items, completion marks, cached timetable/tasks/
     * exams/grades/plans, and locally remembered campus password, while purging old server JWTs
     * so users never hit `token_revoked`.
     */
    private fun migrateV1ToLocalIfNeeded(configured: String, previous: String) {
        if (prefs.getInt("localMigrationVersion", 0) >= 1) return
        val candidateServers = linkedSetOf<String>().apply {
            if (configured.isNotBlank()) add(configured)
            if (previous.isNotBlank()) add(previous)
            legacyServer().takeIf { it.isNotBlank() }?.let { runCatching { NativeApi.normalizeServer(it) }.getOrNull()?.let(::add) }
        }
        val localDoc = runCatching { secure.readCache(LOCAL_DATA_SCOPE) }.getOrNull() ?: JSONObject()
        val localCalDoc = runCatching { secure.readCache(LOCAL_CALENDAR_KEY) }.getOrNull() ?: JSONObject().put("items", JSONArray())
        val calItems = academicObjects(localCalDoc.optJSONArray("items")).toMutableList()
        val calIds = calItems.map { it.optString("id") }.toMutableSet()
        val mergedDone = stringSet(localDoc.optJSONArray("done")).toMutableSet()
        var migratedAny = false

        for (srv in candidateServers) {
            val savedSession = runCatching { secure.read(sessionKey(srv)) }.getOrNull() ?: continue
            val token = savedSession.optString("token").takeIf { it.isNotBlank() }
            val user = savedSession.optJSONObject("user")
            val uid = user?.optString("id")?.takeIf { it.isNotBlank() } ?: token?.let(::tokenSubject)
            if (uid != null) {
                val scope = accountScope(srv, uid)
                val oldCache = runCatching { secure.readCache(scope) }.getOrNull()
                if (oldCache != null) {
                    mergedDone += stringSet(oldCache.optJSONArray("done"))
                    val oldTimetable = oldCache.optJSONObject("timetable")
                    if (oldTimetable != null && (oldTimetable.optJSONArray("courses")?.length() ?: 0) > 0) {
                        val oldSource = when {
                            oldTimetable.optJSONObject("coverage")?.optString("source") == "manual" -> "manual"
                            oldTimetable.optString("campus") == "med" -> "med"
                            else -> "jw"
                        }
                        val existingTts = localDoc.optJSONObject("timetables")
                        if (existingTts == null || existingTts.optJSONObject(oldSource) == null) {
                            putSourceTimetable(localDoc, oldSource, oldTimetable, prefer = true)
                        }
                        migratedAny = true
                    }
                    for (arrayKey in listOf("tasks", "exams", "grades", "notices", "plans")) {
                        val arr = oldCache.optJSONArray(arrayKey)
                        if (arr != null && arr.length() > 0 && (localDoc.optJSONArray(arrayKey)?.length() ?: 0) == 0) {
                            localDoc.put(arrayKey, JSONArray(arr.toString()))
                            migratedAny = true
                        }
                    }
                    if (!localDoc.has("syncedAt") && oldCache.has("syncedAt") && !oldCache.isNull("syncedAt")) {
                        localDoc.put("syncedAt", oldCache.optLong("syncedAt"))
                    }
                }
                val oldCal = runCatching { secure.readCache("calendar-v1:$scope") }.getOrNull()
                if (oldCal != null) {
                    academicObjects(oldCal.optJSONArray("items")).forEach { row ->
                        val id = row.optString("id")
                        if (id.isNotBlank() && calIds.add(id)) {
                            calItems.add(row)
                            migratedAny = true
                        }
                    }
                }
                val oldSchool = runCatching { secure.read(schoolKey(srv, uid)) }.getOrNull()
                if (oldSchool != null && runCatching { secure.read(LOCAL_SCHOOL_KEY) }.getOrNull() == null) {
                    val id = oldSchool.optString("id").trim()
                    val pw = oldSchool.optString("password")
                    val remember = oldSchool.optBoolean("remember") && pw.isNotEmpty()
                    if (id.isNotBlank()) {
                        val obj = JSONObject().put("id", id).put("remember", remember)
                        if (remember) obj.put("password", pw)
                        runCatching { secure.write(LOCAL_SCHOOL_KEY, obj) }
                        migratedAny = true
                    }
                }
            }
            // Purge obsolete server JWT session so users never encounter token_revoked.
            runCatching { secure.remove(sessionKey(srv)) }
        }
        runCatching { secure.clearPrefix("session:") }

        if (mergedDone.isNotEmpty()) {
            localDoc.put("done", JSONArray(mergedDone.toList()))
            migratedAny = true
        }
        if (migratedAny) {
            localDoc.put("onboarded", true)
            localDoc.put("planSummary", computeLocalPlanSummary(rows(localDoc, "plans")))
            runCatching { secure.writeCache(LOCAL_DATA_SCOPE, localDoc) }
            if (calItems.isNotEmpty()) {
                runCatching { secure.writeCache(LOCAL_CALENDAR_KEY, localCalDoc.put("items", JSONArray(calItems))) }
            }
        }
        prefs.edit().putInt("localMigrationVersion", 1).commit()
    }

    fun close() {
        synchronized(lock) { if (closed) return; closed = true; generation++ }
        jobs.cancel()
        api.close()
    }

    // -------------------------------------------------------------------------
    // Internal State & Persistence Helpers
    // -------------------------------------------------------------------------

    private suspend fun requestPublic(
        owner: NativeSession,
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
    ): JSONObject {
        ensureCurrent(owner)
        return api.request(owner, path, method, body).also { ensureCurrent(owner) }
    }

    private fun configuredServer(): String = try {
        configuredServerForTests ?: app.assets.open("native-config.json").bufferedReader(Charsets.UTF_8)
            .use { JSONObject(it.readText()).optString("server") }
    } catch (_: Exception) { "" }

    private fun legacyServer(): String = try {
        legacyServerForTests ?: configuredServerForTests ?: app.assets.open("native-config.json").bufferedReader(Charsets.UTF_8)
            .use { JSONObject(it.readText()).optString("legacyServer") }
    } catch (_: Exception) { "" }

    private fun initialState(): NativeState {
        val configured = try {
            NativeApi.normalizeServer(configuredServer()).takeIf { it.isNotBlank() }
                ?: throw NativeApiError("server_required")
        } catch (_: Exception) {
            return appearanceState("").copy(
                user = localUserObject(emptySet()),
                error = "公共服务配置缺失，离线本地功能仍可正常使用",
            )
        }
        val previous = try { NativeApi.normalizeServer(prefs.getString("server", "").orEmpty()) }
            catch (_: Exception) { "" }
        if (previous != configured) {
            runCatching { prefs.edit().putString("server", configured).commit() }
        }
        migrateV1ToLocalIfNeeded(configured, previous)
        return loadLocalVault(configured)
    }

    private fun loadLocalVault(server: String): NativeState {
        raw = JSONObject()
        val base = appearanceState(server)
        return try {
            raw = secure.readCache(LOCAL_DATA_SCOPE) ?: JSONObject()
            withLocalCredentials(present(base, raw))
        } catch (error: Exception) {
            base.copy(user = localUserObject(emptySet()), error = message(error))
        }
    }

    private fun appearanceState(server: String) = NativeState(
        server = server,
        token = null,
        theme = prefs.getString("theme", "system")?.takeIf { it in setOf("system", "light", "dark") } ?: "system",
        palette = normalizeNativePalette(prefs.getString("palette", NATIVE_DEFAULT_PALETTE)),
        style = normalizeNativeStyle(prefs.getString("style", NATIVE_DEFAULT_STYLE)),
        hapticStrength = prefs.getInt("strength", 100).coerceIn(0, 100),
    )

    private fun withLocalCredentials(base: NativeState): NativeState {
        val savedSchool = runCatching { secure.read(LOCAL_SCHOOL_KEY) }.getOrNull()
        val savedMed = runCatching { secure.read(LOCAL_MED_SESSION_KEY) }.getOrNull()
        val medToken = savedMed?.optString("token")?.takeIf { it.isNotBlank() }
        return base.copy(
            schoolId = savedSchool?.optString("id").orEmpty(),
            rememberSchool = savedSchool?.optBoolean("remember") == true &&
                savedSchool.optString("password").isNotEmpty(),
            medLinked = medToken != null,
            medUsername = savedMed?.optString("username").orEmpty(),
            medName = savedMed?.optString("name").orEmpty(),
        )
    }

    private fun present(base: NativeState, data: JSONObject): NativeState {
        if (base.preview) {
            return base.copy(
                token = null,
                user = null,
                sample = true,
                onboarded = true,
                timetable = data.optJSONObject("timetable"),
                tasks = rows(data, "tasks"),
                exams = rows(data, "exams"),
                grades = rows(data, "grades"),
                notices = rows(data, "notices"),
                plans = rows(data, "plans"),
                planSummary = data.optJSONObject("planSummary") ?: emptySummary(),
                sources = rows(data, "sources"),
                done = stringSet(data.optJSONArray("done")),
                syncedAt = null,
            )
        }
        val (activeSource, activeTimetable, availableSources) = selectActiveTimetable(data)
        val boundSources = stringSet(data.optJSONArray("boundSources")).toMutableSet()
        if (availableSources.contains("manual")) boundSources += "manual"
        val plans = rows(data, "plans")
        val planSummary = computeLocalPlanSummary(plans)
        val manualMeta = data.optJSONObject("manualMeta")
        val ownTimetable = (activeTimetable?.optJSONArray("courses")?.length() ?: 0) > 0
        val onboarded = data.optBoolean("onboarded", false) || ownTimetable || boundSources.isNotEmpty()
        return base.copy(
            token = null,
            user = localUserObject(boundSources),
            sample = false,
            onboarded = onboarded,
            boundSources = boundSources,
            availableTimetableSources = availableSources,
            activeTimetableSource = activeSource,
            timetable = activeTimetable,
            tasks = rows(data, "tasks"),
            exams = rows(data, "exams"),
            grades = rows(data, "grades"),
            notices = rows(data, "notices"),
            plans = plans,
            planSummary = planSummary,
            sources = rows(data, "sources"),
            done = stringSet(data.optJSONArray("done")),
            manualSchool = manualMeta?.optString("school")?.takeIf { it.isNotBlank() },
            manualSemester = manualMeta?.optString("semester")?.takeIf { it.isNotBlank() },
            syncedAt = if (data.has("syncedAt") && !data.isNull("syncedAt")) data.optLong("syncedAt") else null,
        )
    }

    private fun previewData(): JSONObject {
        val data = try {
            app.assets.open("native-preview.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } catch (_: Exception) {
            throw NativeApiError("preview_unavailable", 0, "示例数据未能读取，请重新打开应用后再试。")
        }
        val zone = ZoneId.of("Asia/Shanghai")
        val generated = data.optLong("generatedAt", System.currentTimeMillis())
        val shift = ChronoUnit.DAYS.between(Instant.ofEpochMilli(generated).atZone(zone).toLocalDate(), LocalDate.now(zone))
        if (shift != 0L) {
            for ((key, field) in listOf("tasks" to "due_at", "notices" to "published_at")) {
                for (row in rows(data, key)) {
                    val value = row.optString(field)
                    if (value.length >= 10) try {
                        row.put(field, LocalDate.parse(value.take(10)).plusDays(shift).toString() + value.drop(10))
                    } catch (_: Exception) {
                        /* Keep unparseable text intact. */
                    }
                    if (key == "tasks" && !row.isNull("dueMs") && row.has("dueMs")) {
                        row.put("dueMs", row.optLong("dueMs") + shift * 86_400_000)
                    }
                }
            }
        }
        return data
    }

    private fun commit(
        owner: NativeSession,
        next: JSONObject,
        error: String? = state.value.error,
        mode: String = state.value.mode,
    ) {
        ensureCurrent(owner)
        if (next.toString() == raw.toString() && error == state.value.error && mode == state.value.mode) return
        var problem = error
        if (!state.value.preview) {
            try { secure.writeCache(LOCAL_DATA_SCOPE, next) }
            catch (failure: Exception) { problem = "本机加密数据未能保存：${message(failure)}" }
        }
        raw = next
        mutableState.value = withLocalCredentials(present(state.value.copy(mode = mode, error = problem), raw))
    }

    private fun putSourceTimetable(doc: JSONObject, source: String, timetable: JSONObject, prefer: Boolean) {
        val map = doc.optJSONObject("timetables")?.let(::copy) ?: JSONObject()
        map.put(source, copy(timetable))
        doc.put("timetables", map)
        val times = doc.optJSONObject("timetableUpdatedAt")?.let(::copy) ?: JSONObject()
        times.put(source, System.currentTimeMillis())
        doc.put("timetableUpdatedAt", times)
        if (prefer || !doc.has("preferredTimetableSource")) {
            doc.put("preferredTimetableSource", source)
        }
    }

    private fun updateSourceEntry(doc: JSONObject, source: String, state: String, code: Int) {
        val existing = rows(doc, "sources").filterNot { it.optString("source") == source }.toMutableList()
        existing.add(
            JSONObject()
                .put("source", source)
                .put("state", state)
                .put("code", code)
                .put("at", System.currentTimeMillis() / 1000.0),
        )
        doc.put("sources", JSONArray(existing))
    }

    private fun recordSourceStatus(owner: NativeSession, source: String, state: String, code: Int) {
        synchronized(lock) {
            if (!isCurrent(owner)) return
            val next = copy(raw)
            updateSourceEntry(next, source, state, code)
            commit(owner, next)
        }
    }

    private fun readCookieSessions(): JSONObject =
        runCatching { secure.read(LOCAL_COOKIES_KEY) }.getOrNull() ?: JSONObject()

    private fun writeCookieSessions(sessions: JSONObject) {
        secure.write(LOCAL_COOKIES_KEY, sessions)
    }

    private fun session(): NativeSession = synchronized(lock) {
        if (closed) throw NativeApiError("store_closed", 0, "页面已经关闭")
        NativeSession(state.value.server, generation)
    }

    private fun isCurrent(owner: NativeSession) =
        !closed && owner.generation == generation && owner.server == state.value.server

    private fun ensureCurrent(owner: NativeSession) = synchronized(lock) {
        if (!isCurrent(owner)) throw NativeApiError("session_changed", 0, "状态已切换，请重试")
    }

    private fun update(owner: NativeSession, transform: (NativeState) -> NativeState) = synchronized(lock) {
        if (isCurrent(owner)) mutableState.value = transform(state.value)
    }

    private fun setError(error: String) {
        synchronized(lock) { mutableState.value = state.value.copy(error = error) }
    }

    private fun flattenFeed(items: JSONArray, notice: Boolean): List<JSONObject> = (0 until items.length()).map { index ->
        val item = items.optJSONObject(index) ?: throw invalidResponse("通知响应包含无效记录")
        val detail = item.optJSONObject("detail") ?: JSONObject()
        copy(detail)
            .put("id", item.opt("id")?.toString() ?: "")
            .put("title", item.optString("title"))
            .put("source", item.optString("source").ifBlank { "public" })
            .also { row ->
                if (notice) {
                    val seen = item.optDouble("updated_at", Double.NaN)
                    row.put("seenMs", if (seen.isFinite()) seen * 1000 else JSONObject.NULL)
                }
            }
    }

    private fun toApiError(error: Throwable): NativeApiError = when (error) {
        is NativeApiError -> error
        is LocalSchoolError -> NativeApiError(error.code, error.httpStatus, error.detail)
        else -> NativeApiError("network", 0, formatLocalError(error))
    }

    private fun stateFromError(error: Throwable): String = when ((error as? LocalSchoolError)?.code ?: (error as? NativeApiError)?.code) {
        "login_expired", "needs_login", "session_invalid", "med_expired",
        "invalid_credentials", "bad_credentials", "login_rejected",
        "captcha_required", "totp_required", "interaction_required", "cas_captcha" -> "need_login"
        "source_unavailable", "request_timeout", "request_deadline",
        "network_error", "network", "timeout", "med_unreachable" -> "maintenance"
        else -> "failed"
    }

    private fun codeFromError(error: Throwable): Int = when (stateFromError(error)) {
        "need_login" -> 3
        "maintenance" -> 2
        else -> 1
    }

    private fun localUserObject(bound: Set<String>): JSONObject = JSONObject()
        .put("id", "local")
        .put("local", true)
        .put("bound", JSONArray(bound.toList()))

    private fun tokenSubject(token: String): String? = try {
        JSONObject(String(Base64.decode(token.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8))
            .optString("sub").takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    private fun legacyValue(values: JSONObject, key: String): String = values.opt(key) as? String ?: ""
    private fun sessionKey(server: String) = "session:${hash(server)}"
    private fun schoolKey(server: String, uid: String) = "school:${hash(accountScope(server, uid))}"
    private fun accountScope(server: String, uid: String) = "$server\n$uid"
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun rows(data: JSONObject, key: String): List<JSONObject> = data.optJSONArray(key)?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } } ?: emptyList()
    private fun stringSet(array: JSONArray?) = if (array == null) emptySet() else (0 until array.length()).mapNotNull { array.optString(it).takeIf { value -> value.isNotBlank() } }.toSet()
    private fun copy(value: JSONObject) = JSONObject(value.toString())
    private fun message(error: Throwable) = formatLocalError(error)
    private fun invalidResponse(detail: String) = NativeApiError("invalid_response", 0, detail)
    private fun emptySummary() = JSONObject().put("today_total", 0).put("today_done", 0).put("today_open", 0).put("overdue", 0).put("upcoming", JSONArray())

    companion object {
        internal const val LOCAL_DATA_SCOPE = "local-v1:data"
        internal const val LOCAL_CALENDAR_KEY = "calendar-v1:local"
        internal const val LOCAL_SCHOOL_KEY = "local:school"
        internal const val LOCAL_COOKIES_KEY = "local:cookies"
        internal const val LOCAL_MED_SESSION_KEY = "local:med"
        private val PLAN_MUTABLE_KEYS = listOf("date", "title", "note", "start", "end", "done", "priority", "sort")
    }
}

/** Pure helper: selects the active timetable from multi-source local vault (`manual`, `med`, `jw`). */
internal fun selectActiveTimetable(data: JSONObject): Triple<String?, JSONObject?, List<String>> {
    val map = data.optJSONObject("timetables")
    val ordered = listOf("jw", "med", "manual")
    val available = mutableListOf<String>()
    if (map != null) {
        for (src in ordered) {
            val doc = map.optJSONObject(src)
            if (doc != null && (doc.optJSONArray("courses")?.length() ?: 0) > 0) {
                available += src
            }
        }
    }
    if (available.isEmpty()) {
        val fallback = data.optJSONObject("timetable")
        if (fallback != null && (fallback.optJSONArray("courses")?.length() ?: 0) > 0) {
            return Triple("jw", fallback, listOf("jw"))
        }
        return Triple(null, null, emptyList())
    }
    val preferred = data.optString("preferredTimetableSource").takeIf { it in available }
    val chosen = preferred ?: run {
        val times = data.optJSONObject("timetableUpdatedAt")
        available.maxWithOrNull(
            compareBy<String> { times?.optLong(it, 0L) ?: 0L }
                .thenBy { when (it) { "manual" -> 3; "med" -> 2; else -> 1 } },
        ) ?: available.first()
    }
    return Triple(chosen, map?.optJSONObject(chosen), available)
}

/** Pure helper: creates a canonical local personal plan row with UUID and timestamps. */
internal fun normalizeLocalPlanRow(fields: JSONObject, defaultSort: Int = 0): JSONObject {
    val now = System.currentTimeMillis()
    val id = fields.optString("id").trim().ifBlank { "plan-${UUID.randomUUID()}" }
    return JSONObject()
        .put("id", id)
        .put("date", fields.optString("date").trim())
        .put("title", fields.optString("title").trim())
        .put("note", fields.optString("note").trim())
        .put("start", if (fields.has("start") && !fields.isNull("start")) fields.optString("start").trim() else JSONObject.NULL)
        .put("end", if (fields.has("end") && !fields.isNull("end")) fields.optString("end").trim() else JSONObject.NULL)
        .put("done", fields.optBoolean("done", false))
        .put("priority", fields.optInt("priority", 0).coerceIn(0, 2))
        .put("sort", if (fields.has("sort")) fields.optInt("sort", defaultSort) else defaultSort)
        .put("created_at", now)
        .put("updated_at", now)
}

/** Pure helper: computes today/overdue/upcoming plan counters locally without any backend round-trip. */
internal fun computeLocalPlanSummary(
    plans: List<JSONObject>,
    today: String = AcademicClock.moment().date,
): JSONObject {
    val todayRows = plans.filter { it.optString("date") == today }
    val todayDone = todayRows.count { it.optBoolean("done") }
    val overdue = plans.count { row ->
        val d = row.optString("date")
        !row.optBoolean("done") && d.isNotBlank() && d < today
    }
    val upcoming = plans.filter { row ->
        val d = row.optString("date")
        !row.optBoolean("done") && d.isNotBlank() && d > today
    }.sortedWith(compareBy<JSONObject> { it.optString("date") }.thenBy { it.optInt("sort", 0) })
        .take(5)
        .map { JSONObject(it.toString()) }
    return JSONObject()
        .put("today_total", todayRows.size)
        .put("today_done", todayDone)
        .put("today_open", todayRows.size - todayDone)
        .put("overdue", overdue)
        .put("upcoming", JSONArray(upcoming))
}

/**
 * Pure helper: builds a strictly whitelisted, zero-PII timetable snapshot for `POST /v1/public/export/today`.
 * Strips all non-timetable keys (student_id, email, name, tokens, cookies, etc.) and enforces size bounds.
 */
internal fun sanitizeTimetableForTodayExport(
    raw: JSONObject?,
    campus: String,
    semesterOverride: String? = null,
): JSONObject? {
    val doc = AcademicTimetable.normalize(raw) ?: return null
    val resolvedSemester = semesterOverride?.trim()?.takeIf { it.isNotBlank() } ?: doc.semester
    val resolvedCampus = when (campus.trim().lowercase(Locale.ROOT)) {
        "main", "jw" -> "main"
        "med" -> "med"
        else -> "manual"
    }
    val courses = JSONArray()
    for (course in doc.courses.take(80)) {
        val meetings = JSONArray()
        for (meeting in course.meetings.take(30)) {
            if (meeting.weekday !in 1..7 || meeting.weeks.isEmpty() || meeting.periods.isEmpty()) continue
            val m = JSONObject()
                .put("id", meeting.id)
                .put("weekday", meeting.weekday)
                .put("weeks", JSONArray(meeting.weeks))
                .put("periods", JSONArray(meeting.periods))
                .put("location", meeting.location)
                .put("teacher", meeting.teacher)
                .put("raw_time", meeting.rawTime)
            meetings.put(m)
        }
        if (meetings.length() == 0) continue
        courses.put(
            JSONObject()
                .put("id", course.id)
                .put("title", course.title)
                .put("course_code", course.code)
                .put("class_name", course.className)
                .put("teacher", course.teacher)
                .put("meetings", meetings),
        )
    }
    if (courses.length() == 0) return null
    return JSONObject().apply {
        put("semester", resolvedSemester)
        put("campus", resolvedCampus)
        if (!doc.checkedAt.isNullOrBlank()) put("checked_at", doc.checkedAt)
        put("courses", courses)
    }
}
