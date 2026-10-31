package com.stuassistant.app.nativeapp

import android.content.Context
import android.util.Base64
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Owns raw API records, account-scoped persistence and the campus sync lifecycle. */
class NativeStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("stu.native.settings.v1", Context.MODE_PRIVATE)
    private val secure = NativeSecureStorage(app)
    private val api = NativeApi()
    private val lock = Any()
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val planMutex = Mutex()
    private var generation = 0L
    private var planVersion = 0L
    private var planReadVersion = 0L
    private var closed = false
    private var raw = JSONObject()
    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<NativeState> = mutableState.asStateFlow()

    init {
        if (state.value.token != null) jobs.launch { runCatching { refresh("auto") } }
    }

    suspend fun request(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        auth: Boolean = true,
    ): JSONObject = requestOwned(session(), path, method, body, auth)

    suspend fun requestText(path: String): String {
        val owner = session()
        requireSignedIn(owner)
        return try {
            api.requestText(owner, path).also { ensureCurrent(owner) }
        } catch (error: NativeApiError) {
            handleAuthenticationError(owner, error)
            throw error
        }
    }

    suspend fun login(email: String, passphrase: String, register: Boolean = false) {
        withContext(Dispatchers.IO) {
        val owner = session()
        val response = requestOwned(owner, if (register) "/v1/auth/register" else "/v1/auth/login",
            "POST", JSONObject().put("email", email.trim()).put("passphrase", passphrase), false)
        val token = response.optString("token").takeIf { it.isNotBlank() }
            ?: throw invalidResponse("登录响应缺少 token")
        val user = response.optJSONObject("user")?.takeIf { it.optString("id").isNotBlank() }
            ?: throw invalidResponse("登录响应缺少用户身份")
        synchronized(lock) {
            ensureCurrent(owner)
            // No plaintext token fallback if the Keystore cannot persist this session.
            secure.write(sessionKey(owner.server), JSONObject().put("token", token).put("user", copy(user)))
            generation++
            planVersion++
            val base = appearanceState(owner.server).copy(token = token, user = copy(user))
            var problem: String? = null
            raw = try { secure.readCache(accountScope(owner.server, user.optString("id"))) ?: JSONObject() }
                catch (error: NativeApiError) { problem = error.detail; JSONObject() }
            val loaded = present(base.copy(error = problem), raw)
            mutableState.value = try { withSchool(loaded) }
                catch (error: NativeApiError) { loaded.copy(error = error.detail) }
        }
        // The authenticated UI can continue to school binding immediately. Data/sync failure
        // belongs to the signed-in store, not to the already successful login/register call.
        jobs.launch {
            try { refresh("auto") }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Refresh already published its error. */ }
        }
        }
    }

    fun enterPreview() {
        synchronized(lock) {
            if (closed) return
            generation++
            planVersion++
            val base = appearanceState(state.value.server).copy(preview = true, sample = true, mode = "preview")
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

    fun setServer(server: String) {
        val normalized = try { NativeApi.normalizeServer(server) }
            catch (error: Exception) { setError(message(error)); return }
        synchronized(lock) {
            if (closed || (normalized == state.value.server && !state.value.preview)) return
            if (!prefs.edit().putString("server", normalized).commit()) {
                setError("服务器地址未能保存")
                return
            }
            generation++
            planVersion++
            mutableState.value = loadSession(normalized)
        }
    }

    fun setAppearance(theme: String? = null, palette: String? = null, strength: Int? = null) {
        synchronized(lock) {
            if (closed) return
            val nextTheme = theme?.takeIf { it in setOf("system", "light", "dark") } ?: state.value.theme
            val nextPalette = palette?.takeIf { it in setOf("sweet", "classic") } ?: state.value.palette
            val nextStrength = strength?.coerceIn(0, 100) ?: state.value.hapticStrength
            if (nextTheme == state.value.theme && nextPalette == state.value.palette && nextStrength == state.value.hapticStrength) return
            prefs.edit().putString("theme", nextTheme).putString("palette", nextPalette)
                .putInt("strength", nextStrength).apply()
            mutableState.value = state.value.copy(theme = nextTheme, palette = nextPalette,
                hapticStrength = nextStrength)
        }
    }

    fun rememberSchool(id: String, password: String, remember: Boolean) {
        synchronized(lock) {
            try {
                val owner = session()
                val uid = userId(state.value) ?: throw NativeApiError("not_signed_in", 401, "请先登录助手账号")
                requireSignedIn(owner)
                val key = schoolKey(owner.server, uid)
                if (remember) {
                    if (id.isBlank() || password.isEmpty()) throw NativeApiError("invalid_input", 400, "请填写学校账号和密码")
                    secure.write(key, JSONObject().put("id", id.trim()).put("password", password).put("remember", true))
                } else secure.remove(key)
                mutableState.value = state.value.copy(schoolId = id.trim(), rememberSchool = remember)
            } catch (error: Exception) { setError(message(error)) }
        }
    }

    fun savedSchoolPassword(): String? = synchronized(lock) {
        val current = state.value
        if (current.preview || current.token == null || !current.rememberSchool) return@synchronized null
        val uid = userId(current) ?: return@synchronized null
        try { secure.read(schoolKey(current.server, uid))?.optString("password")?.takeIf { it.isNotEmpty() } }
        catch (error: Exception) { setError(message(error)); null }
    }

    fun getSavedSchoolPassword(): String? = savedSchoolPassword()

    /** Clears only this account's device copy; server credential retention is a separate contract. */
    fun clearSchool() {
        synchronized(lock) {
            try {
                userId(state.value)?.let { secure.remove(schoolKey(state.value.server, it)) }
                mutableState.value = state.value.copy(schoolId = "", rememberSchool = false)
            } catch (error: Exception) { setError(message(error)) }
        }
    }

    fun toggleTask(id: String) {
        synchronized(lock) {
            if (closed || id.isBlank()) return
            val owner = session()
            val done = state.value.done.toMutableSet().apply { if (!add(id)) remove(id) }
            val next = copy(raw).put("done", JSONArray(done.toList()))
            commit(owner, next)
        }
    }

    /** read is read-only; auto adds the existing 30-minute freshness policy. */
    suspend fun refresh(how: String = "read") {
        refreshOwned(session(), how)
    }

    /** A confirmed server mutation must refresh even if its page leaves composition. */
    fun reloadAfterMutation() {
        val owner = try { session() } catch (_: NativeApiError) { return }
        jobs.launch {
            try { refreshOwned(owner, "read") }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* The store exposes the failed read; never replay the write. */ }
        }
    }

    private suspend fun refreshOwned(requestedOwner: NativeSession, how: String) {
        withContext(Dispatchers.IO) {
        if (how !in setOf("read", "auto", "sync", "force")) throw NativeApiError("invalid_input", 400, "未知刷新方式")
        refreshMutex.withLock {
        val owner = requestedOwner
        ensureCurrent(owner)
        try {
            if (state.value.preview) { ensureCurrent(owner); return@withLock }
            requireSignedIn(owner)
            update(owner) { it.copy(loading = true, error = null) }
            var syncProblem: Exception? = null
            if (how == "sync" || how == "force") {
                try { synchronizeSources(owner, how == "force") }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { syncProblem = error }
            }
            readResources(owner)
            if (how == "auto" && shouldSync()) {
                try { synchronizeSources(owner, false); readResources(owner) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { syncProblem = error }
            }
            syncProblem?.let { update(owner) { current -> current.copy(error = message(it)) }; throw it }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            update(owner) { it.copy(error = message(error)) }
            throw error
        } finally {
            update(owner) { it.copy(loading = false, syncing = raw.optBoolean("syncing", false)) }
        }
        }
        }
    }

    suspend fun loadPlans(start: String? = null, end: String? = null) {
        withContext(Dispatchers.IO) { loadPlansOwned(session(), start, end) }
    }

    suspend fun createPlan(fields: JSONObject) = planMutation { owner ->
        val row = requestOwned(owner, "/v1/plan", "POST", planFields(fields))
        requirePlan(row)
        mutateRaw(owner) { next -> next.put("plans", JSONArray(rows(next, "plans") + row)) }
    }

    suspend fun togglePlan(id: String, done: Boolean) = planMutation { owner ->
        val row = requestOwned(owner, "/v1/plan/${encoded(id)}/toggle", "POST", JSONObject().put("done", done))
        requirePlan(row)
        mutateRaw(owner) { next ->
            val existing = rows(next, "plans")
            next.put("plans", JSONArray(if (existing.any { it.optString("id") == id })
                existing.map { if (it.optString("id") == id) row else it } else existing + row))
        }
    }

    suspend fun removePlan(id: String) = planMutation { owner ->
        val response = requestOwned(owner, "/v1/plan/${encoded(id)}", "DELETE")
        if (!response.optBoolean("ok")) throw invalidResponse("服务器没有确认删除计划")
        mutateRaw(owner) { next -> next.put("plans", JSONArray(rows(next, "plans").filterNot { it.optString("id") == id })) }
    }

    suspend fun reorderPlans(date: String, ids: List<String>) = planMutation { owner ->
        val response = requestOwned(owner, "/v1/plan/reorder", "POST", JSONObject().put("date", date).put("ids", JSONArray(ids)))
        if (!response.optBoolean("ok")) throw invalidResponse("服务器没有确认计划排序")
        val order = ids.withIndex().associate { it.value to it.index }
        mutateRaw(owner) { next -> next.put("plans", JSONArray(rows(next, "plans").map { row ->
            if (row.optString("date") == date && row.optString("id") in order)
                copy(row).put("sort", order.getValue(row.optString("id"))) else row
        })) }
    }

    suspend fun logout() {
        val owner = session()
        var problem: String? = null
        if (owner.token != null) try { requestOwned(owner, "/v1/account/logout", "POST") }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { problem = "本机已退出；服务器退出请求未完成：${message(error)}" }
        synchronized(lock) { if (isCurrent(owner)) clearSession(owner, true, problem) }
    }

    suspend fun deleteAccount() {
        val owner = session()
        val response = requestOwned(owner, "/v1/account", "DELETE")
        if (!response.optBoolean("ok")) throw invalidResponse("服务器没有确认删除账号")
        synchronized(lock) { ensureCurrent(owner); clearSession(owner, true, null) }
    }

    fun legacyMigrationDone(): Boolean = prefs.getInt("legacyMigrationVersion", 0) >= 1

    /** Called only with the trusted old app origin's explicit localStorage allowlist. */
    fun importLegacy(values: JSONObject) {
        synchronized(lock) {
            if (legacyMigrationDone() || closed) return
            val editor = prefs.edit()
            legacyValue(values, "stu.theme").takeIf { it in setOf("system", "light", "dark") }?.let { editor.putString("theme", it) }
            legacyValue(values, "stu.palette").takeIf { it in setOf("sweet", "classic") }?.let { editor.putString("palette", it) }
            val strength = legacyValue(values, "stu.haptics.strength").toIntOrNull()
                ?: if (legacyValue(values, "stu.haptics") == "off") 0 else null
            strength?.let { editor.putInt("strength", it.coerceIn(0, 100)) }
            legacyValue(values, "stu.update.skip").toLongOrNull()?.takeIf { it >= 0 }?.let { code ->
                if (!app.getSharedPreferences("native-updates", Context.MODE_PRIVATE).edit().putLong("skip", code).commit())
                    throw NativeApiError("storage_error", 0, "旧更新提醒设置未能保存，请重试")
            }
            val server = NativeApi.normalizeServer(legacyValue(values, "stu.api").ifBlank { state.value.server })
            if (server.isNotEmpty()) editor.putString("server", server)
            val token = legacyValue(values, "stu.token").takeIf { it.isNotBlank() && it != "null" }
            val uid = token?.let(::tokenSubject)
            if (token != null && uid != null && server.isNotEmpty()) {
                val user = JSONObject().put("id", uid).put("email", legacyValue(values, "stu.account"))
                secure.write(sessionKey(server), JSONObject().put("token", token).put("user", user))
                // Old cache lacks a server namespace: refetch it; only local completion IDs migrate.
                val oldDone = legacyValue(values, "stu.done.v2:$uid").takeIf { it.isNotBlank() }
                val next = secure.readCache(accountScope(server, uid)) ?: JSONObject()
                if (oldDone != null) next.put("done", JSONArray(oldDone))
                secure.writeCache(accountScope(server, uid), next)
                val account = legacyValue(values, "stu.account")
                if (account.isNotBlank()) {
                    val oldSchool = legacyValue(values, "stu.school:$server|$account")
                    if (oldSchool.isNotBlank()) {
                        val saved = JSONObject(oldSchool)
                        val id = saved.optString("account")
                        val password = saved.optString("password")
                        if (id.isNotBlank() && password.isNotEmpty()) secure.write(schoolKey(server, uid),
                            JSONObject().put("id", id).put("password", password).put("remember", true))
                    }
                }
            } else if (token != null) {
                // Preserve an encrypted recovery snapshot; never guess the old server/identity.
                val pending = JSONObject()
                val account = legacyValue(values, "stu.account")
                val allowed = setOf("stu.api", "stu.token", "stu.account", "stu.theme", "stu.palette", "stu.haptics.strength", "stu.haptics")
                for (key in values.keys()) if (key in allowed || (uid != null && key == "stu.done.v2:$uid") ||
                    (account.isNotBlank() && key.startsWith("stu.school:") && key.endsWith("|$account"))) {
                    (values.opt(key) as? String)?.let { pending.put(key, it) }
                }
                secure.write("legacy.pending", pending)
            }
            if (!editor.putInt("legacyMigrationVersion", 1).commit()) throw NativeApiError("storage_error", 0, "旧设置迁移未能保存，请重试")
            generation++
            planVersion++
            mutableState.value = loadSession(server)
            if (token != null && (uid == null || server.isEmpty())) setError("旧登录数据缺少服务器或身份，请重新登录；旧数据已保留")
        }
    }

    fun close() {
        synchronized(lock) { if (closed) return; closed = true; generation++ }
        jobs.cancel()
        api.close()
    }

    private suspend fun requestOwned(owner: NativeSession, path: String, method: String = "GET", body: JSONObject? = null, auth: Boolean = true): JSONObject {
        ensureCurrent(owner)
        if (auth) requireSignedIn(owner)
        return try {
            api.request(owner, path, method, body, auth).also { ensureCurrent(owner) }
        } catch (error: NativeApiError) {
            if (auth) handleAuthenticationError(owner, error)
            throw error
        }
    }

    private fun handleAuthenticationError(owner: NativeSession, error: NativeApiError) {
        if (error.status == 401 && error.code in setOf("invalid_token", "user_gone", "missing_bearer")) synchronized(lock) {
            if (isCurrent(owner)) clearSession(owner, false, "登录已过期，请重新登录")
        }
    }

    private suspend fun readResources(owner: NativeSession) {
        val (version, readVersion) = synchronized(lock) { planVersion to ++planReadVersion }
        val paths = linkedMapOf("timetable" to "/v1/timetable", "tasks" to "/v1/feed?kinds=task,homework&limit=120",
            "exams" to "/v1/exams", "grades" to "/v1/grades", "notices" to "/v1/notices?limit=10",
            "sources" to "/v1/status", "user" to "/v1/me", "plans" to "/v1/plan")
        val results = coroutineScope { paths.map { (key, path) -> async {
            key to try { Result.success(validateResource(key, requestOwned(owner, path))) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { Result.failure<JSONObject>(error) }
        } }.map { it.await() }.toMap() }
        ensureCurrent(owner)
        val successfulData = results.filterKeys { it !in setOf("sources", "user") }.values.any { it.isSuccess }
        val failures = results.values.mapNotNull { it.exceptionOrNull() }
        synchronized(lock) {
            ensureCurrent(owner)
            val next = copy(raw)
            for ((key, result) in results) result.getOrNull()?.let { response ->
                when (key) {
                    "timetable" -> next.put(key, if (response.optBoolean("empty")) JSONObject.NULL else response)
                    "tasks", "notices" -> next.put(key, JSONArray(flattenFeed(response.getJSONArray("items"), key == "notices")))
                    "exams", "grades" -> next.put(key, response.getJSONArray("rows"))
                    "sources" -> next.put("sources", response.getJSONArray("sources")).put("syncing", response.optBoolean("syncing"))
                    "plans" -> if (version == planVersion && readVersion == planReadVersion) next.put("plans", response.getJSONArray("items")).put("planSummary", response.getJSONObject("summary"))
                    else -> next.put(key, response)
                }
            }
            if (successfulData && failures.isEmpty()) next.put("syncedAt", System.currentTimeMillis())
            val problem = failures.firstOrNull()?.let(::message)
            commit(owner, next, problem, if (successfulData) "live" else "cached")
        }
        if (!successfulData) throw (failures.firstOrNull() ?: invalidResponse("没有读取到业务数据"))
    }

    private suspend fun synchronizeSources(owner: NativeSession, force: Boolean) {
        update(owner) { it.copy(syncing = true) }
        val started = requestOwned(owner, "/v1/sync?sources=public,jw,mystu&force=$force", "POST")
        if (!started.optBoolean("demo")) followSync(owner)
        val status = requestOwned(owner, "/v1/status")
        val expired = rows(status, "sources").filter { it.optInt("code", -99) == 3 && it.optString("source") in setOf("jw", "mystu") }
            .map { it.optString("source") }
        ensureCurrent(owner)
        val current = state.value
        val password = savedSchoolPassword()
        if (expired.isNotEmpty() && password != null && current.schoolId.isNotBlank()) {
            val relogin = requestOwned(owner, "/v1/relogin", "POST", JSONObject().put("services", JSONArray(expired))
                .put("student_id", current.schoolId).put("password", password))
            if (!relogin.optBoolean("ok")) throw NativeApiError("login_failed", 401, "学校连接未恢复，请重新连接")
            requestOwned(owner, "/v1/sync?sources=${encoded(expired.joinToString(","))}&force=true", "POST")
            followSync(owner)
        }
    }

    private suspend fun followSync(owner: NativeSession) {
        val started = System.nanoTime()
        var mark: String? = null
        while ((System.nanoTime() - started) / 1_000_000 < 240_000) {
            delay(2_000)
            val status = validateResource("sources", requestOwned(owner, "/v1/status"))
            val nextMark = status.getJSONArray("sources").toString()
            synchronized(lock) {
                ensureCurrent(owner)
                commit(owner, copy(raw).put("sources", status.getJSONArray("sources")).put("syncing", status.optBoolean("syncing")))
            }
            if (mark != nextMark) readResources(owner)
            mark = nextMark
            if (!status.optBoolean("syncing")) return
        }
        throw NativeApiError("sync_timeout", 0, "同步仍在后台进行，请稍后刷新")
    }

    private fun shouldSync(): Boolean = synchronized(lock) {
        val current = state.value
        val bound = current.user?.optJSONArray("bound") ?: return@synchronized false
        val watched = setOf("public") + (0 until bound.length()).mapNotNull { bound.optString(it).takeIf { name -> name in setOf("jw", "mystu") } }
        if ((0 until bound.length()).any { bound.optString(it) == "jw" } && current.timetable == null) return@synchronized true
        current.sources.any { source -> source.optString("source") in watched &&
            (source.isNull("at") || System.currentTimeMillis() - source.optDouble("at", 0.0) * 1000 > 30 * 60_000) }
    }

    private suspend fun loadPlansOwned(owner: NativeSession, start: String? = null, end: String? = null) {
        if (state.value.preview) throw NativeApiError("preview_read_only", 0, "登录后可保存个人计划")
        val (version, readVersion) = synchronized(lock) { planVersion to ++planReadVersion }
        val query = listOfNotNull(start?.let { "start=${encoded(it)}" }, end?.let { "end=${encoded(it)}" }).joinToString("&")
        try {
            val response = validateResource("plans", requestOwned(owner, "/v1/plan${if (query.isBlank()) "" else "?$query"}"))
            synchronized(lock) {
                ensureCurrent(owner)
                if (version == planVersion && readVersion == planReadVersion) {
                    val received = rows(response, "items")
                    // Keep a full collection in the shared store; screens apply their own date
                    // filter. A bounded refresh replaces only the dates actually requested.
                    val kept = if (start == null && end == null) emptyList() else rows(raw, "plans").filter { row ->
                        val date = row.optString("date")
                        (start != null && date < start) || (end != null && date > end)
                    }
                    commit(owner, copy(raw).put("plans", JSONArray((kept + received).distinctBy { it.optString("id") }))
                        .put("planSummary", response.getJSONObject("summary")), error = null)
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { update(owner) { it.copy(error = message(error)) }; throw error }
    }

    private suspend fun planMutation(action: suspend (NativeSession) -> Unit) {
        withContext(Dispatchers.IO) {
        planMutex.withLock {
            val owner = session()
            if (state.value.preview) throw NativeApiError("preview_read_only", 0, "登录后可保存个人计划")
            requireSignedIn(owner)
            synchronized(lock) { planVersion++ }
            update(owner) { it.copy(error = null) }
            try {
                action(owner)
                synchronized(lock) { ensureCurrent(owner); planVersion++ }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                synchronized(lock) { if (isCurrent(owner)) planVersion++ }
                update(owner) { it.copy(error = message(error)) }
                throw error
            }
            // The server already acknowledged the write. A subsequent read failure must not
            // tell the UI that creation failed and encourage a duplicate POST.
            try { loadPlansOwned(owner) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { update(owner) { it.copy(error = "计划已保存，概览刷新失败：${message(error)}") } }
        }
        }
    }

    private fun initialState(): NativeState {
        val configured = try { app.assets.open("native-config.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()).optString("server") } }
            catch (_: Exception) { "" }
        val server = try { NativeApi.normalizeServer(prefs.getString("server", configured) ?: configured) } catch (_: Exception) { "" }
        return loadSession(server)
    }

    private fun loadSession(server: String): NativeState {
        raw = JSONObject()
        var base = appearanceState(server)
        try {
            val saved = if (server.isBlank()) null else secure.read(sessionKey(server))
            val token = saved?.optString("token")?.takeIf { it.isNotBlank() }
            val user = saved?.optJSONObject("user")
            base = base.copy(token = token, user = user)
            val uid = userId(base)
            if (token != null && uid != null) raw = secure.readCache(accountScope(server, uid)) ?: JSONObject()
            return withSchool(present(base, raw))
        } catch (error: Exception) { return base.copy(error = message(error)) }
    }

    private fun appearanceState(server: String) = NativeState(server = server,
        theme = prefs.getString("theme", "system")?.takeIf { it in setOf("system", "light", "dark") } ?: "system",
        palette = prefs.getString("palette", "sweet")?.takeIf { it in setOf("sweet", "classic") } ?: "sweet",
        hapticStrength = prefs.getInt("strength", 100).coerceIn(0, 100))

    private fun withSchool(base: NativeState): NativeState {
        val uid = userId(base) ?: return base
        val saved = secure.read(schoolKey(base.server, uid)) ?: return base
        return base.copy(schoolId = saved.optString("id"), rememberSchool = saved.optBoolean("remember") && saved.optString("password").isNotEmpty())
    }

    private fun present(base: NativeState, data: JSONObject): NativeState {
        val user = data.optJSONObject("user") ?: base.user
        val timetable = data.optJSONObject("timetable")
        val ownTimetable = (timetable?.optJSONArray("courses")?.length() ?: 0) > 0
        val bound = user?.optJSONArray("bound")
        val campusBound = bound != null && (0 until bound.length()).any { bound.optString(it) in setOf("jw", "mystu") }
        val sample = base.preview || (bound != null && !campusBound && !ownTimetable)
        val shown = if (sample && !base.preview) previewData() else data
        val notices = rows(data, "notices").ifEmpty { if (sample) rows(shown, "notices") else emptyList() }
        return base.copy(user = user, sample = sample, timetable = if (ownTimetable) timetable else shown.optJSONObject("timetable"),
            tasks = rows(shown, "tasks"), exams = rows(shown, "exams"), grades = rows(shown, "grades"), notices = notices,
            plans = rows(data, "plans"), planSummary = data.optJSONObject("planSummary") ?: emptySummary(),
            sources = rows(data, "sources"), done = stringSet(data.optJSONArray("done")),
            syncedAt = if (data.has("syncedAt") && !data.isNull("syncedAt")) data.optLong("syncedAt") else null)
    }

    private fun previewData(): JSONObject {
        val data = try { app.assets.open("native-preview.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) } }
            catch (error: Exception) { throw NativeApiError("preview_unavailable", 0, "示例数据未能读取：${error.javaClass.simpleName}") }
        val zone = ZoneId.of("Asia/Shanghai")
        val generated = data.optLong("generatedAt", System.currentTimeMillis())
        val shift = ChronoUnit.DAYS.between(Instant.ofEpochMilli(generated).atZone(zone).toLocalDate(), LocalDate.now(zone))
        if (shift != 0L) {
            for ((key, field) in listOf("tasks" to "due_at", "notices" to "published_at")) {
                for (row in rows(data, key)) {
                    val value = row.optString(field)
                    if (value.length >= 10) try { row.put(field, LocalDate.parse(value.take(10)).plusDays(shift).toString() + value.drop(10)) }
                        catch (_: Exception) { /* Keep unparseable school text intact. */ }
                    if (key == "tasks" && !row.isNull("dueMs") && row.has("dueMs")) row.put("dueMs", row.optLong("dueMs") + shift * 86_400_000)
                }
            }
        }
        return data
    }

    private fun mutateRaw(owner: NativeSession, edit: (JSONObject) -> Unit) = synchronized(lock) {
        ensureCurrent(owner)
        val next = copy(raw)
        edit(next)
        commit(owner, next)
    }

    private fun commit(owner: NativeSession, next: JSONObject, error: String? = state.value.error, mode: String = state.value.mode) {
        ensureCurrent(owner)
        if (next.toString() == raw.toString() && error == state.value.error && mode == state.value.mode) return
        var problem = error
        val uid = userId(state.value)
        if (!state.value.preview && uid != null) try { secure.writeCache(accountScope(owner.server, uid), next) }
            catch (failure: Exception) { problem = "本机缓存未能保存：${message(failure)}" }
        raw = next
        mutableState.value = present(state.value.copy(mode = mode, error = problem), raw)
    }

    private fun clearSession(owner: NativeSession, forget: Boolean, error: String?) {
        val uid = userId(state.value)
        var problem = error
        val cleanup = mutableListOf<() -> Unit>()
        if (owner.server.isNotBlank()) cleanup += { secure.remove(sessionKey(owner.server)) }
        if (forget) {
            if (uid != null) cleanup += { secure.removeCache(accountScope(owner.server, uid)) }
            cleanup += { secure.clearPrefix("school:") }
            cleanup += { secure.remove("legacy.pending") }
        }
        cleanup.forEach { operation -> try { operation() }
            catch (failure: Exception) { problem = "本机登录数据未能完全清除：${message(failure)}" } }
        generation++
        planVersion++
        raw = JSONObject()
        mutableState.value = appearanceState(owner.server).copy(error = problem)
    }

    private fun session(): NativeSession = synchronized(lock) {
        if (closed) throw NativeApiError("store_closed", 0, "页面已经关闭")
        NativeSession(state.value.server, state.value.token, generation)
    }

    private fun isCurrent(owner: NativeSession) = !closed && owner.generation == generation && owner.server == state.value.server && owner.token == state.value.token
    private fun ensureCurrent(owner: NativeSession) = synchronized(lock) {
        if (!isCurrent(owner)) throw NativeApiError("session_changed", 0, "账号或服务器已切换")
    }
    private fun requireSignedIn(owner: NativeSession) {
        if (owner.token.isNullOrBlank() || state.value.preview) throw NativeApiError("not_signed_in", 401, "请先登录助手账号")
    }
    private fun update(owner: NativeSession, transform: (NativeState) -> NativeState) = synchronized(lock) {
        if (isCurrent(owner)) mutableState.value = transform(state.value)
    }
    private fun setError(error: String) { synchronized(lock) { mutableState.value = state.value.copy(error = error) } }

    private fun validateResource(key: String, response: JSONObject): JSONObject {
        val valid = when (key) {
            "timetable" -> response.optBoolean("empty") || response.optJSONArray("courses") != null
            "tasks", "notices" -> response.optJSONArray("items") != null
            "exams", "grades" -> response.optJSONArray("rows") != null
            "sources" -> response.optJSONArray("sources") != null
            "plans" -> response.optJSONArray("items") != null && response.optJSONObject("summary") != null
            "user" -> response.optString("id").isNotBlank()
            else -> false
        }
        if (!valid) throw invalidResponse("$key 响应结构不完整")
        val records = when (key) {
            "timetable" -> response.optJSONArray("courses")
            "tasks", "notices", "plans" -> response.optJSONArray("items")
            "exams", "grades" -> response.optJSONArray("rows")
            "sources" -> response.optJSONArray("sources")
            else -> null
        }
        if (records != null) for (index in 0 until records.length()) {
            val row = records.optJSONObject(index) ?: throw invalidResponse("$key 包含无效记录")
            if (key in setOf("tasks", "notices") && (row.optJSONObject("detail") == null || row.isNull("id")))
                throw invalidResponse("$key 记录缺少身份或 detail")
            if (key == "plans") requirePlan(row)
        }
        return response
    }

    private fun flattenFeed(items: JSONArray, notice: Boolean): List<JSONObject> = (0 until items.length()).map { index ->
        val item = items.optJSONObject(index) ?: throw invalidResponse("事项响应包含无效记录")
        val detail = item.optJSONObject("detail") ?: throw invalidResponse("事项响应缺少 detail")
        copy(detail).put("id", item.opt("id")?.toString() ?: "").put("title", item.optString("title"))
            .put("source", item.optString("source")).also { row ->
                if (notice) {
                    val seen = item.optDouble("updated_at", Double.NaN)
                    row.put("seenMs", if (seen.isFinite()) seen * 1000 else JSONObject.NULL)
                }
            }
    }

    private fun requirePlan(row: JSONObject) { if (row.optString("id").isBlank()) throw invalidResponse("计划响应缺少 id") }
    private fun planFields(fields: JSONObject): JSONObject = JSONObject().also { selected ->
        for (key in listOf("date", "title", "note", "start", "end", "done", "priority", "sort")) if (fields.has(key)) selected.put(key, fields.get(key))
    }
    private fun userId(value: NativeState) = value.user?.optString("id")?.takeIf { it.isNotBlank() } ?: value.token?.let(::tokenSubject)
    private fun tokenSubject(token: String): String? = try {
        JSONObject(String(Base64.decode(token.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)).optString("sub").takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
    private fun legacyValue(values: JSONObject, key: String): String = values.opt(key) as? String ?: ""
    private fun sessionKey(server: String) = "session:${hash(server)}"
    private fun schoolKey(server: String, uid: String) = "school:${hash(accountScope(server, uid))}"
    private fun accountScope(server: String, uid: String) = "$server\n$uid"
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun rows(data: JSONObject, key: String): List<JSONObject> = data.optJSONArray(key)?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } } ?: emptyList()
    private fun stringSet(array: JSONArray?) = if (array == null) emptySet() else (0 until array.length()).mapNotNull { array.optString(it).takeIf { value -> value.isNotBlank() } }.toSet()
    private fun copy(value: JSONObject) = JSONObject(value.toString())
    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun message(error: Throwable) = if (error is NativeApiError) error.detail else error.message ?: "请求没有完成"
    private fun invalidResponse(detail: String) = NativeApiError("invalid_response", 0, detail)
    private fun emptySummary() = JSONObject().put("today_total", 0).put("today_done", 0).put("today_open", 0).put("overdue", 0).put("upcoming", JSONArray())
}
