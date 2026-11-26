package com.stuassistant.app.nativeapp.local

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/** Parsed MySTU course record. */
data class MyStuCourse(
    val id: String,
    val title: String,
    val teacher: String,
    val url: String,
    val courseCode: String,
    val year: Int?,
    val term: Int?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", "course-$id")
        .put("external_id", "course-$id")
        .put("kind", "course")
        .put("title", title)
        .put("teacher", teacher)
        .put("url", url)
        .put("courseCode", courseCode.ifEmpty { JSONObject.NULL })
        .put("year", year ?: JSONObject.NULL)
        .put("term", term ?: JSONObject.NULL)
        .put("source", "mystu")
}

/** Parsed Moodle / ELC assignment detail metadata. */
data class MyStuAssignmentDetail(
    val dueMs: Long?,
    val dueAt: String?,
    val schoolStatus: String,
    val description: String,
)

/** Verified login result for MySTU (`my.stu.edu.cn`). */
data class LocalMyStuLoginResult(
    val sessionJson: JSONObject,
    val accountFingerprint: String,
    val courseCount: Int,
    val moodleBootstrapped: Map<String, Boolean>,
)

/** Combined MySTU courses & flattened tasks sync result. */
data class LocalMyStuSyncResult(
    val courses: JSONArray,
    val tasks: JSONArray,
    val warnings: List<String>,
    val updatedSessionJson: JSONObject,
)

/**
 * Direct-to-school client & pure parsers for MySTU (`my.stu.edu.cn`) courses and Moodle/ELC
 * assignments.
 *
 * Combines:
 * - `backend/app/mystu_api.py` (resilient JSON API fetch + Asia/Shanghai timestamp parsing + Moodle due-date fallback)
 * - `backend/app/mystu_parser.py` (multi-semester `(id, year, term)` deduplication fix)
 * - `stu_mcp/features/mystu/parser.py` (ELC HTML & Moodle assignment detail structured parsing)
 *
 * Output task JSON objects are already flat and directly compatible with `AcademicRecords.task()`
 * (`id`, `title`, `course`, `source="mystu"`, `url`, `due_at`, `dueMs`, `school_status`).
 */
class LocalMyStuClient(
    private val httpClient: OkHttpClient = LocalSchoolHttp.defaultClient(),
) {
    /** Authenticates with STU CAS (including Moodle bootstrap) and returns business-only `my.stu.edu.cn` cookies. */
    suspend fun login(
        studentId: String,
        password: String,
        challengeHandler: SchoolChallengeHandler? = null,
    ): LocalMyStuLoginResult {
        val result = LocalSchoolHttp.loginCas(
            service = "mystu",
            studentId = studentId,
            password = password,
            challengeHandler = challengeHandler,
            baseClient = httpClient,
        )
        val bootstrap = linkedMapOf<String, Boolean>()
        val bsObj = result.proof.optJSONObject("moodle_bootstrap")
        if (bsObj != null) {
            for (k in bsObj.keys()) {
                bootstrap[k] = bsObj.optBoolean(k, false)
            }
        }
        return LocalMyStuLoginResult(
            sessionJson = result.sessionJson,
            accountFingerprint = result.accountFingerprint,
            courseCount = result.proof.optInt("course_count", 0),
            moodleBootstrapped = bootstrap,
        )
    }

    /** Probes whether a saved MySTU session is live via `/user/validate?ver=1.1`. */
    suspend fun probeSession(sessionJson: JSONObject): SessionProbeStatus {
        val cookies = sessionJson.optJSONArray("cookies")
        if (cookies == null || cookies.length() == 0) return SessionProbeStatus.EXPIRED
        return try {
            val session = SchoolHttpSession("mystu", sessionJson, baseClient = httpClient, allowLegacyHttp = false)
            val payload = session.getJson(VALIDATE_URL)
            val hasIdentity = payload.optString("username").isNotBlank() ||
                payload.optString("displayName").isNotBlank() ||
                payload.optString("id").isNotBlank()
            if (hasIdentity) SessionProbeStatus.LIVE else SessionProbeStatus.EXPIRED
        } catch (err: CancellationException) {
            throw err
        } catch (err: LocalSchoolError) {
            when (err.code) {
                "login_expired", "needs_login", "session_invalid", "access_denied" -> SessionProbeStatus.EXPIRED
                "source_unavailable", "network_error", "request_timeout", "rate_limited" -> SessionProbeStatus.MAINTENANCE
                else -> SessionProbeStatus.EXPIRED
            }
        } catch (_: Exception) {
            SessionProbeStatus.MAINTENANCE
        }
    }

    suspend fun checkSession(sessionJson: JSONObject): Boolean =
        probeSession(sessionJson) == SessionProbeStatus.LIVE

    /**
     * Fetches MySTU courses and flattened assignment/quiz tasks (including Moodle detail page
     * due-date enrichment for up to [detailLimit] tasks missing `dueMs`).
     */
    suspend fun fetchTasksAndCourses(
        sessionJson: JSONObject,
        limit: Int = 40,
        courseLimit: Int = 12,
        detailLimit: Int = 12,
        fetchDetails: Boolean = true,
    ): LocalMyStuSyncResult {
        val cookies = sessionJson.optJSONArray("cookies")
        if (cookies == null || cookies.length() == 0) {
            throw LocalSchoolError("login_expired", "MySTU 会话为空，请先连接校园账号。", "mystu")
        }

        val session = SchoolHttpSession("mystu", sessionJson, baseClient = httpClient, allowLegacyHttp = false)
        val validatePayload = session.getJson(VALIDATE_URL)
        if (validatePayload.optString("username").isBlank() &&
            validatePayload.optString("displayName").isBlank() &&
            validatePayload.optString("id").isBlank()
        ) {
            throw LocalSchoolError("login_expired", "MySTU 会话已过期，请重新连接。", "mystu")
        }

        val coursePayload = session.getJson(COURSES_URL)
        val courses = parseCoursesPayload(coursePayload)
        val coursesArray = JSONArray()
        courses.forEach { coursesArray.put(it.toJson()) }

        // Keep only the latest semester entry per course ID when polling activities.
        val activeCourses = mutableListOf<MyStuCourse>()
        val seenCourseIds = linkedSetOf<String>()
        for (course in courses) {
            if (seenCourseIds.add(course.id)) {
                activeCourses.add(course)
                if (activeCourses.size >= courseLimit) break
            }
        }

        val tasks = mutableListOf<JSONObject>()
        val seenTaskIds = linkedSetOf<String>()
        val warnings = mutableListOf<String>()

        for (course in activeCourses) {
            if (tasks.size >= limit) break
            val moodleId = extractMoodleId(course.url)
            var found = emptyList<JSONObject>()

            if (moodleId.isNotEmpty()) {
                try {
                    val url = "$ACTIVITIES_URL?moodleCourseId=${LocalSchoolHttp.urlEncode(moodleId)}&category=undefined&lang=zh-CN"
                    val payload = session.getJson(url)
                    found = parseActivitiesPayload(payload, course, moodleId)
                } catch (err: CancellationException) {
                    throw err
                } catch (_: Exception) {
                    found = emptyList()
                }
            }

            // Fallback to ELC Moodle course page HTML when course lives under /courses/elc/.
            if (found.isEmpty() && course.url.contains(ELC_COURSE_PATH)) {
                try {
                    val upgraded = upgradeLegacyElcUrl(course.url)
                    val parsedUrl = upgraded.toHttpUrlOrNull()
                    if (parsedUrl != null && parsedUrl.isHttps && parsedUrl.host.equals(LocalSchoolHttp.MYSTU_HOST, true)) {
                        val html = session.getText(upgraded)
                        found = parseElcCourseHtml(html, upgraded, course.title)
                    }
                } catch (err: CancellationException) {
                    throw err
                } catch (_: Exception) {
                    // Best-effort per course; do not fail other courses.
                }
            }

            for (task in found) {
                val tid = task.optString("id")
                if (tid.isNotEmpty() && !seenTaskIds.add(tid)) continue
                tasks.add(task)
                if (tasks.size >= limit) break
            }
        }

        // Enrich up to `detailLimit` tasks missing a due timestamp from their Moodle detail page.
        if (fetchDetails && detailLimit > 0) {
            var enriched = 0
            for (task in tasks) {
                if (enriched >= detailLimit) break
                val hasDue = !task.isNull("dueMs") && task.optLong("dueMs", 0L) > 0L
                val rawUrl = upgradeLegacyElcUrl(task.optString("url"))
                if (hasDue || rawUrl.isBlank()) continue
                val parsed = rawUrl.toHttpUrlOrNull() ?: continue
                if (!parsed.isHttps || !parsed.host.equals(LocalSchoolHttp.MYSTU_HOST, ignoreCase = true)) continue
                enriched++
                try {
                    val html = session.getText(rawUrl)
                    val detail = parseAssignmentDetailHtml(html)
                    if (detail.dueMs != null && detail.dueMs > 0L) {
                        task.put("dueMs", detail.dueMs)
                        task.put("due_at", detail.dueAt ?: formatIsoOffset(detail.dueMs))
                    }
                    if (detail.schoolStatus.isNotEmpty()) {
                        task.put("school_status", detail.schoolStatus)
                    }
                    if (detail.description.isNotEmpty() && task.optString("body").isBlank()) {
                        task.put("body", detail.description)
                    }
                } catch (err: CancellationException) {
                    throw err
                } catch (_: Exception) {
                    // Detail page failures never discard the task itself.
                }
            }
        }

        return LocalMyStuSyncResult(
            courses = coursesArray,
            tasks = JSONArray(tasks),
            warnings = warnings,
            updatedSessionJson = session.exportUpdatedSessionJson(),
        )
    }

    /** Convenience wrapper returning the flat task JSON list directly. */
    suspend fun fetchTasks(sessionJson: JSONObject, limit: Int = 40): List<JSONObject> {
        val arr = fetchTasksAndCourses(sessionJson, limit = limit).tasks
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    companion object {
        const val VALIDATE_URL = "${LocalSchoolHttp.MYSTU_API}/user/validate?ver=1.1"
        const val COURSES_URL = "${LocalSchoolHttp.MYSTU_API}/course/query"
        const val ACTIVITIES_URL = "${LocalSchoolHttp.MYSTU_API}/courseactivity/query"
        const val ELC_COURSE_PATH = "/courses/elc/"

        val SCHOOL_OFFSET: ZoneOffset = ZoneOffset.ofHours(8)

        private val MOODLE_ID_RE = Regex("""[?&]id=(\d+)""")
        private val MOD_TYPE_RE = Regex("""/mod/([^/]+)/""")
        private val DIGITS_20_RE = Regex("""^[0-9]{1,20}$""")
        private val DUE_CN_EN_RE = Regex(
            """(?:截止日期|截止时间|到期时间|Due date|Due)[^0-9]{0,40}(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})日?[^0-9]{0,20}(\d{1,2}):(\d{2})""",
            RegexOption.IGNORE_CASE,
        )
        private val DATE_INLINE_RE = Regex("""(20\d{2})[年/-](\d{1,2})[月/-](\d{1,2})日?.*?(\d{1,2}):(\d{2})""")
        private val DUE_LABEL_RE = Regex(
            """^(?:due date|截止(?:日期|时间)?|到期(?:日期|时间)?|closes|关闭(?:日期|时间)?)[：:]?$""",
            RegexOption.IGNORE_CASE,
        )
        private val STATUS_LABEL_RE = Regex(
            """^(?:submission status|提交状态|completion status|完成状态)[：:]?$""",
            RegexOption.IGNORE_CASE,
        )
        private val LOCAL_TIME_PATTERNS = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT),
        )
        private val ENGLISH_MOODLE_PATTERNS = listOf(
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, H:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM yyyy, H:mm", Locale.ENGLISH),
        )

        /**
         * Parses MySTU `/course/query` JSON (both columnar `{fields, items:[[]]}` and
         * object-list `{items:[{}]}` variants) with `(id, year, term)` deduplication,
         * sorted newest `(year, term)` first.
         */
        fun parseCoursesPayload(payload: JSONObject): List<MyStuCourse> {
            val rows = payload.optJSONArray("items") ?: return emptyList()
            val columns = payload.optString("fields", "")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val records = mutableListOf<Map<String, Any?>>()
            for (i in 0 until rows.length()) {
                when (val entry = rows.opt(i)) {
                    is JSONObject -> {
                        val map = linkedMapOf<String, Any?>()
                        for (k in entry.keys()) {
                            map[k] = entry.opt(k).takeUnless { it === JSONObject.NULL }
                        }
                        records.add(map)
                    }
                    is JSONArray -> {
                        val map = linkedMapOf<String, Any?>()
                        val count = minOf(columns.size, entry.length())
                        for (c in 0 until count) {
                            map[columns[c]] = entry.opt(c).takeUnless { it === JSONObject.NULL }
                        }
                        records.add(map)
                    }
                }
            }

            val courses = mutableListOf<MyStuCourse>()
            val seen = linkedSetOf<Triple<String, Int?, Int?>>()
            for (raw in records) {
                val cid = strVal(raw["id"]).ifEmpty {
                    strVal(raw["groupId"]).ifEmpty { strVal(raw["url"]) }
                }
                val title = strVal(raw["name"]).ifEmpty { strVal(raw["groupName"]) }
                if (cid.isEmpty() || title.isEmpty()) continue
                val year = intVal(raw["attendanceYear"])
                val term = intVal(raw["attendanceSemester"])
                if (!seen.add(Triple(cid, year, term))) continue
                courses.add(
                    MyStuCourse(
                        id = cid,
                        title = title,
                        teacher = strVal(raw["teacher"]),
                        url = upgradeLegacyElcUrl(strVal(raw["url"])),
                        courseCode = strVal(raw["courseCode"]),
                        year = year,
                        term = term,
                    ),
                )
            }
            return courses.sortedWith(
                compareByDescending<MyStuCourse> { it.year ?: 0 }
                    .thenByDescending { it.term ?: 0 },
            )
        }

        /**
         * Parses MySTU `/courseactivity/query` JSON into flattened task JSONObjects compatible
         * with `AcademicRecords.task()`.
         */
        fun parseActivitiesPayload(
            payload: JSONObject,
            course: MyStuCourse,
            moodleId: String,
        ): List<JSONObject> {
            val rows = payload.optJSONArray("courseActivities") ?: return emptyList()
            val out = mutableListOf<JSONObject>()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val title = strVal(row.opt("activityTitle"))
                if (title.isEmpty()) continue
                val dueMs = parseTimestampMs(
                    firstPresent(row, "additionalDueDate", "dueDate", "due"),
                )
                val dueAt = dueMs?.let { formatIsoOffset(it) }
                val key = strVal(row.opt("activityId")).ifEmpty {
                    strVal(row.opt("id")).ifEmpty { "$moodleId-$title" }
                }
                val id = "task-$key"
                val url = upgradeLegacyElcUrl(strVal(row.opt("activityUrl")))
                out.add(
                    JSONObject()
                        .put("id", id)
                        .put("external_id", id)
                        .put("kind", "task")
                        .put("title", title.take(120))
                        .put("course", course.title)
                        .put("category", strVal(row.opt("activityCategory")))
                        .put("url", url)
                        .put("dueMs", dueMs ?: JSONObject.NULL)
                        .put("due_at", dueAt ?: JSONObject.NULL)
                        .put("school_status", "")
                        .put("body", strVal(row.opt("description")).take(6000))
                        .put("source", "mystu"),
                )
            }
            return out
        }

        /** Parses an ELC Moodle course page into flattened assignment/quiz task JSONObjects. */
        fun parseElcCourseHtml(html: String, baseUrl: String, courseTitle: String): List<JSONObject> {
            if (LocalSchoolHttp.isLoginHtml(html)) {
                throw LocalSchoolError("login_expired", "ELC 会话需要重新登录。", "mystu")
            }
            val doc = Jsoup.parse(html, baseUrl)
            val contentNodes = doc.select(".course-content")
            if (contentNodes.size != 1) {
                throw LocalSchoolError("schema_changed", "ELC 未返回唯一可识别的课程内容。", "mystu")
            }
            val out = mutableListOf<JSONObject>()
            val seen = linkedSetOf<String>()
            for (node in doc.select(".course-content li.activity, .course-content .activity[class*='modtype_']")) {
                if (node.hasClass("modtype_label")) continue
                val link = node.selectFirst(".activityinstance a[href], a[href]") ?: continue
                val href = link.attr("href")
                if (href.isBlank()) continue
                val fullUrl = upgradeLegacyElcUrl(LocalSchoolHttp.resolveUrl(baseUrl, href))
                val path = fullUrl.toHttpUrlOrNull()?.encodedPath.orEmpty()
                val modType = MOD_TYPE_RE.find(path)?.groupValues?.getOrNull(1) ?: "resource"
                val category = if (modType in setOf("assign", "quiz")) "assignment" else modType
                if (category !in setOf("assignment", "assign", "quiz")) continue
                val titleNode = node.selectFirst(".instancename") ?: link
                val title = LocalSchoolHttp.cleanText(titleNode.text())
                if (title.isEmpty() || !seen.add(fullUrl)) continue
                val id = LocalSchoolHttp.itemId("mystu", "task", courseTitle, fullUrl)
                out.add(
                    JSONObject()
                        .put("id", id)
                        .put("external_id", id)
                        .put("kind", "task")
                        .put("title", title.take(120))
                        .put("course", courseTitle)
                        .put("category", category)
                        .put("url", fullUrl)
                        .put("dueMs", JSONObject.NULL)
                        .put("due_at", JSONObject.NULL)
                        .put("school_status", "")
                        .put("body", LocalSchoolHttp.cleanText(node.text()).take(4000))
                        .put("source", "mystu"),
                )
            }
            return out
        }

        /**
         * Parses a Moodle/ELC assignment or quiz detail page for due timestamp (in Asia/Shanghai
         * `+08:00`), submission status, and description.
         */
        fun parseAssignmentDetailHtml(html: String): MyStuAssignmentDetail {
            if (LocalSchoolHttp.isLoginHtml(html)) {
                throw LocalSchoolError("login_expired", "作业详情需要重新登录。", "mystu")
            }
            val doc = Jsoup.parse(html)
            val intros = doc.select("#intro, .activity-description")
            val roots = intros.filter { node -> node.parents().none { it in intros } }
            val intro = if (roots.size == 1) roots[0] else null

            val pairs = mutableListOf<Pair<String, String>>()
            for (row in doc.select("tr")) {
                if (intro != null && intro in row.parents()) continue
                val cells = row.children().filter { it.tagName().lowercase(Locale.ROOT) in setOf("th", "td") }
                if (cells.size >= 2 && cells.none { it.selectFirst("table") != null }) {
                    pairs.add(
                        LocalSchoolHttp.cleanText(cells.first().text()).lowercase(Locale.ROOT) to
                            LocalSchoolHttp.cleanText(cells.last().text()),
                    )
                }
            }
            for (term in doc.select("dt")) {
                if (intro != null && intro in term.parents()) continue
                val dd = term.nextElementSibling()?.takeIf { it.tagName().equals("dd", true) } ?: continue
                pairs.add(
                    LocalSchoolHttp.cleanText(term.text()).lowercase(Locale.ROOT) to
                        LocalSchoolHttp.cleanText(dd.text()),
                )
            }

            val dueRaw = pairs.firstOrNull { DUE_LABEL_RE.matches(it.first) }?.second
            val statusRaw = pairs.firstOrNull { STATUS_LABEL_RE.matches(it.first) }?.second.orEmpty()

            var dueMs: Long? = dueRaw?.let { parseHumanDateMs(it) }
            if (dueMs == null) {
                val regexMatch = DUE_CN_EN_RE.find(html)
                if (regexMatch != null) {
                    dueMs = runCatching {
                        val y = regexMatch.groupValues[1].toInt()
                        val mo = regexMatch.groupValues[2].toInt()
                        val d = regexMatch.groupValues[3].toInt()
                        val h = regexMatch.groupValues[4].toInt()
                        val mi = regexMatch.groupValues[5].toInt()
                        LocalDateTime.of(y, mo, d, h, mi).toInstant(SCHOOL_OFFSET).toEpochMilli()
                    }.getOrNull()
                }
            }
            return MyStuAssignmentDetail(
                dueMs = dueMs,
                dueAt = dueMs?.let { formatIsoOffset(it) },
                schoolStatus = statusRaw,
                description = intro?.let { LocalSchoolHttp.cleanText(it.text()).take(6000) }.orEmpty(),
            )
        }

        /** Parses epoch seconds/millis or naive Asia/Shanghai date strings into epoch millis. */
        fun parseTimestampMs(raw: Any?): Long? {
            if (raw == null || raw === JSONObject.NULL || raw is Boolean) return null
            if (raw is Number) {
                val num = raw.toDouble()
                if (!num.isFinite() || num <= 0.0) return null
                return if (num < 10_000_000_000.0) (num * 1000.0).toLong() else num.toLong()
            }
            val text = raw.toString().trim()
            if (text.isEmpty() || text == "0") return null
            if (text.all { it.isDigit() }) {
                val num = text.toLongOrNull() ?: return null
                if (num <= 0L) return null
                return if (num < 10_000_000_000L) num * 1000L else num
            }
            val candidate = text.take(19)
            for (fmt in LOCAL_TIME_PATTERNS) {
                val ms = runCatching {
                    LocalDateTime.parse(candidate, fmt).toInstant(SCHOOL_OFFSET).toEpochMilli()
                }.getOrNull()
                if (ms != null) return ms
            }
            return parseHumanDateMs(text)
        }

        fun extractMoodleId(url: String): String =
            MOODLE_ID_RE.find(url)?.groupValues?.getOrNull(1).orEmpty()

        /** Upgrades observed legacy `http://my.stu.edu.cn/courses/elc/course/view.php?id=N` links to HTTPS. */
        fun upgradeLegacyElcUrl(url: String): String {
            val cleaned = LocalSchoolHttp.cleanText(url)
            val parsed = cleaned.toHttpUrlOrNull() ?: return cleaned
            if (!parsed.isHttps &&
                parsed.host.equals(LocalSchoolHttp.MYSTU_HOST, ignoreCase = true) &&
                parsed.port == 80 &&
                parsed.username.isEmpty() &&
                parsed.password.isEmpty() &&
                parsed.fragment == null &&
                parsed.encodedPath == "/courses/elc/course/view.php" &&
                parsed.queryParameterNames == setOf("id")
            ) {
                val ids = parsed.queryParameterValues("id")
                if (ids.size == 1 && ids[0] != null && DIGITS_20_RE.matches(ids[0]!!)) {
                    return "https://${LocalSchoolHttp.MYSTU_HOST}/courses/elc/course/view.php?id=${ids[0]}"
                }
            }
            return cleaned
        }

        private fun parseHumanDateMs(raw: String): Long? {
            val cleaned = LocalSchoolHttp.cleanText(raw)
            if (cleaned.isEmpty()) return null
            DATE_INLINE_RE.find(cleaned)?.let { m ->
                val ms = runCatching {
                    LocalDateTime.of(
                        m.groupValues[1].toInt(),
                        m.groupValues[2].toInt(),
                        m.groupValues[3].toInt(),
                        m.groupValues[4].toInt(),
                        m.groupValues[5].toInt(),
                    ).toInstant(SCHOOL_OFFSET).toEpochMilli()
                }.getOrNull()
                if (ms != null) return ms
            }
            for (fmt in ENGLISH_MOODLE_PATTERNS) {
                val ms = runCatching {
                    LocalDateTime.parse(cleaned, fmt).toInstant(SCHOOL_OFFSET).toEpochMilli()
                }.getOrNull()
                if (ms != null) return ms
            }
            return null
        }

        private fun formatIsoOffset(ms: Long): String =
            Instant.ofEpochMilli(ms).atOffset(SCHOOL_OFFSET).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        private fun firstPresent(obj: JSONObject, vararg keys: String): Any? {
            for (k in keys) {
                if (!obj.has(k) || obj.isNull(k)) continue
                val v = obj.opt(k)
                if (v == null || v === JSONObject.NULL) continue
                if (v is Number && v.toDouble() == 0.0) continue
                if (v is String && (v.isBlank() || v == "0")) continue
                return v
            }
            return null
        }

        private fun strVal(value: Any?): String =
            if (value == null || value === JSONObject.NULL || value is Boolean) "" else value.toString().trim()

        private fun intVal(value: Any?): Int? = when (value) {
            is Boolean, null -> null
            is Int -> value
            is Long -> value.toInt()
            is Number -> value.toInt()
            else -> value.toString().trim().toIntOrNull()
        }
    }
}
