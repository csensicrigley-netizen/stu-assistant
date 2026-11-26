package com.stuassistant.app.nativeapp.local

import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** Verified login result for STU main campus JW (`jw.stu.edu.cn`). */
data class LocalJwLoginResult(
    val sessionJson: JSONObject,
    val accountFingerprint: String,
    val verified: Boolean,
)

/** Combined local JW sync result (timetable is required; grades/exams are best-effort). */
data class LocalJwSyncResult(
    val timetable: JSONObject,
    val grades: JSONArray,
    val exams: JSONArray,
    val semester: String,
    val warnings: List<String>,
    val updatedSessionJson: JSONObject,
)

/** Parsed semester query form from `/jsxsd/xskb/xskb_list.do`. */
data class JwTimetableForm(
    val semester: String,
    val semesters: List<Pair<String, String>>,
    val data: Map<String, String>,
)

/**
 * Direct-to-school client & pure HTML parsers for STU main-campus academic affairs (`jw.stu.edu.cn`).
 *
 * Faithfully ports:
 * - `backend/app/jw_timetable.py` (patched spanning-大节 & resilient partial-coverage parser)
 * - `stu_mcp/features/jw/{adapter.py, parser.py, schema.py}`
 * - `backend/app/collectors.py` (`collect_jw` multi-term grade sweep)
 */
class LocalJwClient(
    private val httpClient: OkHttpClient = LocalSchoolHttp.defaultClient(),
    private val paceDelayMs: Long = 500L,
) {
    /** Authenticates with STU CAS and returns an encrypted-ready business-only `jw.stu.edu.cn` session JSON. */
    suspend fun login(
        studentId: String,
        password: String,
        challengeHandler: SchoolChallengeHandler? = null,
    ): LocalJwLoginResult {
        val result = LocalSchoolHttp.loginCas(
            service = "jw",
            studentId = studentId,
            password = password,
            challengeHandler = challengeHandler,
            baseClient = httpClient,
        )
        return LocalJwLoginResult(
            sessionJson = result.sessionJson,
            accountFingerprint = result.accountFingerprint,
            verified = result.proof.optBoolean("authenticated", true),
        )
    }

    /** Probes whether a saved JW session is still accepted by `jw.stu.edu.cn`. */
    suspend fun probeSession(sessionJson: JSONObject): SessionProbeStatus {
        val cookies = sessionJson.optJSONArray("cookies")
        if (cookies == null || cookies.length() == 0) return SessionProbeStatus.EXPIRED
        return try {
            val session = SchoolHttpSession("jw", sessionJson, baseClient = httpClient, allowLegacyHttp = true)
            val html = session.getText(JW_TIMETABLE_URL)
            if (html.contains("""id="timetable"""") || jwAuthenticated(html)) {
                SessionProbeStatus.LIVE
            } else {
                SessionProbeStatus.EXPIRED
            }
        } catch (err: CancellationException) {
            throw err
        } catch (err: LocalSchoolError) {
            when (err.code) {
                "login_expired", "needs_login", "session_invalid" -> SessionProbeStatus.EXPIRED
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
     * Fetches and parses the theoretical timetable for [semester] (or school's default selected
     * semester when blank). Output is 100% compatible with `AcademicTimetable.normalize()`.
     */
    suspend fun fetchTimetable(sessionJson: JSONObject, semester: String = ""): JSONObject {
        val requested = semester.trim()
        if (requested.isNotEmpty() && !JW_TERM_PATTERN.matches(requested)) {
            throw LocalSchoolError("invalid_semester", "学期格式为 2026-2027-1；留空使用学校当前选定学期。", "jw")
        }
        val session = SchoolHttpSession("jw", sessionJson, baseClient = httpClient, allowLegacyHttp = true)
        return fetchTimetableWithSession(session, requested)
    }

    /**
     * Fetches grades across the current and previous terms (newest first, up to [depth] terms,
     * stopping after 2 consecutive empty terms). Output: `{ "rows": [...], "terms_read": N }`.
     */
    suspend fun fetchGrades(
        sessionJson: JSONObject,
        currentSemester: String = "",
        depth: Int = 8,
    ): JSONObject {
        val session = SchoolHttpSession("jw", sessionJson, baseClient = httpClient, allowLegacyHttp = true)
        return fetchGradesWithSession(session, currentSemester.trim(), depth)
    }

    /** Fetches grades for a single semester (or `""` for school default). */
    suspend fun fetchGradesForSemester(sessionJson: JSONObject, semester: String = ""): List<JSONObject> {
        val session = SchoolHttpSession("jw", sessionJson, baseClient = httpClient, allowLegacyHttp = true)
        val resp = session.postForm(JW_GRADE_URL, JW_GRADE_QUERY_DEFAULTS + ("kksj" to semester.trim()))
        return parseGradesHtml(resp.bodyText)
    }

    /**
     * Fetches exam schedule for [semester] (or the exam query page's default semester when blank).
     * Output: `{ "semester": "...", "rows": [...] }`.
     */
    suspend fun fetchExams(sessionJson: JSONObject, semester: String = ""): JSONObject {
        val session = SchoolHttpSession("jw", sessionJson, baseClient = httpClient, allowLegacyHttp = true)
        return fetchExamsWithSession(session, semester.trim())
    }

    /**
     * One-shot sync mirroring `backend/app/collectors.py:collect_jw`:
     * timetable first (required), then grades and exams (best-effort; failures become warnings).
     */
    suspend fun syncAll(sessionJson: JSONObject, semester: String = ""): LocalJwSyncResult {
        val requested = semester.trim()
        if (requested.isNotEmpty() && !JW_TERM_PATTERN.matches(requested)) {
            throw LocalSchoolError("invalid_semester", "学期格式为 2026-2027-1；留空使用学校当前选定学期。", "jw")
        }
        val session = SchoolHttpSession("jw", sessionJson, baseClient = httpClient, allowLegacyHttp = true)
        val timetable = fetchTimetableWithSession(session, requested)
        val termNow = timetable.optString("semester").ifBlank { requested }
        val warnings = mutableListOf<String>()

        var gradesArr = JSONArray()
        try {
            val gradeRes = fetchGradesWithSession(session, termNow, depth = 8)
            gradesArr = gradeRes.optJSONArray("rows") ?: JSONArray()
            val gradeWarn = gradeRes.optString("warning")
            if (gradeWarn.isNotBlank()) warnings.add(gradeWarn)
        } catch (err: CancellationException) {
            throw err
        } catch (err: LocalSchoolError) {
            warnings.add("成绩：${err.detail}")
        } catch (_: Exception) {
            warnings.add("成绩读取未完成")
        }

        var examsArr = JSONArray()
        if (paceDelayMs > 0) delay(paceDelayMs)
        try {
            val examRes = fetchExamsWithSession(session, termNow)
            examsArr = examRes.optJSONArray("rows") ?: JSONArray()
        } catch (err: CancellationException) {
            throw err
        } catch (err: LocalSchoolError) {
            warnings.add("考试：${err.detail}")
        } catch (_: Exception) {
            warnings.add("考试安排读取未完成")
        }

        return LocalJwSyncResult(
            timetable = timetable,
            grades = gradesArr,
            exams = examsArr,
            semester = termNow,
            warnings = warnings,
            updatedSessionJson = session.exportUpdatedSessionJson(),
        )
    }

    private suspend fun fetchTimetableWithSession(session: SchoolHttpSession, requestedSemester: String): JSONObject {
        val initialHtml = session.getText(JW_TIMETABLE_URL)
        val form = parseTimetableForm(initialHtml)
        val selected = requestedSemester.ifEmpty { form.semester }
        if (form.semesters.none { it.first == selected }) {
            throw LocalSchoolError("invalid_semester", "学校本人课表未提供该学期，请留空查询当前课表。", "jw")
        }
        val postData = form.data + ("xnxq01id" to selected)
        val tableHtml = session.postForm(JW_TIMETABLE_URL, postData).bodyText
        val parsed = parseTimetableHtml(tableHtml, selected)
        val hasPending = (parsed.optJSONArray("pending")?.length() ?: 0) > 0
        val hasUnarranged = (parsed.optJSONArray("unarranged")?.length() ?: 0) > 0
        return parsed
            .put("ok", true)
            .put("empty", false)
            .put("source", "jw")
            .put("campus", "main")
            .put("status", if (hasPending || hasUnarranged) "partial" else "ok")
            .put("checked_at", Instant.now().toString())
            .put("transport", if (session.usedLegacyHttp) "school_legacy_http" else "https")
    }

    private suspend fun fetchGradesWithSession(
        session: SchoolHttpSession,
        currentSemester: String,
        depth: Int,
    ): JSONObject {
        val terms = gradeTerms(currentSemester, depth)
        val seen = linkedSetOf<String>()
        val rows = JSONArray()
        var readTerms = 0
        var emptyStreak = 0
        var warning = ""

        for ((index, term) in terms.withIndex()) {
            if (index > 0 && paceDelayMs > 0) delay(paceDelayMs)
            val parsed = try {
                val resp = session.postForm(JW_GRADE_URL, JW_GRADE_QUERY_DEFAULTS + ("kksj" to term))
                parseGradesHtml(resp.bodyText)
            } catch (err: CancellationException) {
                throw err
            } catch (err: LocalSchoolError) {
                if (err.code == "login_expired" && rows.length() == 0) throw err
                warning = "成绩：${err.detail}"
                break
            }
            if (parsed.isEmpty()) {
                emptyStreak++
                if (emptyStreak >= 2) break
                continue
            }
            emptyStreak = 0
            readTerms++
            for (row in parsed) {
                val id = row.optString("id")
                if (id.isNotEmpty() && !seen.add(id)) continue
                rows.put(row)
            }
        }
        return JSONObject()
            .put("rows", rows)
            .put("terms_read", readTerms)
            .put("warning", warning)
    }

    private suspend fun fetchExamsWithSession(session: SchoolHttpSession, semester: String): JSONObject {
        val term = semester.ifEmpty {
            parseExamSemester(session.getText(JW_EXAM_QUERY_URL))
        }
        val resp = session.postForm(JW_EXAM_LIST_URL, JW_EXAM_QUERY_DEFAULTS + ("xnxqid" to term))
        val parsed = parseExamsHtml(resp.bodyText, term)
        return JSONObject()
            .put("semester", term)
            .put("rows", JSONArray(parsed))
    }

    companion object {
        const val JW_SCHEMA_VERSION = 2
        const val JW_GRADE_ROW_LIMIT = 200
        const val JW_GRADE_URL = "${LocalSchoolHttp.JW_BASE}/kscj/cjcx_list"
        const val JW_EXAM_QUERY_URL = "${LocalSchoolHttp.JW_BASE}/xsks/xsksap_query"
        const val JW_EXAM_LIST_URL = "${LocalSchoolHttp.JW_BASE}/xsks/xsksap_list"
        const val JW_TIMETABLE_URL = "${LocalSchoolHttp.JW_BASE}/xskb/xskb_list.do"

        val JW_GRADE_QUERY_DEFAULTS: Map<String, String> = mapOf(
            "kcmc" to "",
            "kcxz" to "",
            "kspage" to "1",
            "jspage" to "200",
        )
        val JW_EXAM_QUERY_DEFAULTS: Map<String, String> = mapOf(
            "xqlb" to "",
            "xqlbmc" to "",
        )

        val JW_TERM_PATTERN = Regex("""^20\d{2}-20\d{2}-[123]$""")
        private val GRADE_TERM_RE = Regex("""^(\d{4})-(\d{4})-([12])$""")

        val JW_TIMETABLE_FIELDS: Set<String> = setOf(
            "cj0701id", "zc", "demo", "xnxq01id", "kbjcmsid", "sfFD", "wkbkc", "xstzd", "xswk",
        )

        val JW_TIMETABLE_COLUMNS: Map<String, Set<String>> = linkedMapOf(
            "class_name" to setOf("上课班级", "教学班"),
            "course_code" to setOf("课程编号", "课程号", "课程代码"),
            "title" to setOf("课程名称", "课程名"),
            "teacher" to setOf("授课教师", "教师"),
        )

        private val JW_HOME_MARKERS = listOf("#NEW_XSD_XJCJ_WDCJ_KCCJCX", "#NEW_XSD_KSBM_WDKS_KSAPCX")
        private val JW_FONT_TITLES = setOf("教师", "周次(节次)", "教学楼", "教室", "通知单编号", "班级", "备注")

        private val JW_COLUMNS_GRADE: Map<String, Set<String>> = linkedMapOf(
            "semester" to setOf("开课学期", "学期"),
            "course_code" to setOf("课程编号", "课程号", "课程代码"),
            "title" to setOf("课程名称", "课程名"),
            "score" to setOf("成绩", "总评成绩"),
            "score_marker" to setOf("成绩标识"),
            "credits" to setOf("学分"),
            "hours" to setOf("总学时", "学时"),
            "gpa" to setOf("绩点"),
            "retake_semester" to setOf("补重学期"),
            "exam_method" to setOf("考核方式", "考试方式"),
            "exam_type" to setOf("考试性质", "考试类型"),
            "course_attribute" to setOf("课程属性", "属性"),
            "course_nature" to setOf("课程性质", "性质"),
            "category" to setOf("通选课类别", "类别"),
            "group" to setOf("分组名称"),
        )
        private val JW_REQUIRED_GRADE = setOf("semester", "course_code", "title", "score", "credits", "gpa")

        private val JW_COLUMNS_EXAM: Map<String, Set<String>> = linkedMapOf(
            "campus" to setOf("校区"),
            "exam_campus" to setOf("考试校区"),
            "exam_session" to setOf("考试场次"),
            "course_code" to setOf("课程编号", "课程号", "课程代码"),
            "title" to setOf("课程名称", "课程名"),
            "teacher" to setOf("授课教师", "教师"),
            "exam_time" to setOf("考试时间"),
            "location" to setOf("考场", "考试地点"),
            "seat_number" to setOf("座位号"),
            "exam_id" to setOf("准考证号", "考试编号"),
            "notes" to setOf("备注"),
        )
        private val JW_REQUIRED_EXAM = setOf("course_code", "title", "exam_time", "location")

        private val EMPTY_ROW_RE = Regex(
            """^(?:未查询到(?:任何|相关|符合条件的)?(?:记录|数据)?|无数据|暂无(?:相关)?(?:记录|数据)|没有(?:查询到)?(?:任何|相关|符合条件的)?(?:记录|数据))[!！。.]*$""",
        )
        private val SUMMARY_LABELS = setOf("合计", "总计", "平均", "平均成绩", "平均绩点", "总学分")
        private val SUMMARY_INLINE_RE = Regex("""^(?:合计|总计|平均|平均成绩|平均绩点|总学分)[：:][+-]?\d+(?:\.\d+)?(?:门|学分|人|项|分)?$""")
        private val SUMMARY_NUM_RE = Regex("""^[+-]?\d+(?:\.\d+)?(?:门|学分|人|项|分)?$""")
        private val POS_INT_RE = Regex("""^[1-9]\d*$""")
        private val MEETING_TIME_RE = Regex("""^([0-9,\-]+)\((单|双)?周\)(?:\((单|双)\))?\[([0-9,\-]+)节\]$""")
        private val WEEK_INTERVAL_RE = Regex("""^([0-9]{1,2})(?:-([0-9]{1,2}))?$""")
        private val TWO_DIGIT_RE = Regex("""^[0-9]{1,2}$""")
        private val ROW_PERIODS_RE = Regex("""[（(]([0-9,，]+)小节[）)]""")
        private val NOTE_LABEL_RE = Regex("""^备注[:：]?$""")
        private val DASHES_ONLY_RE = Regex("""^-+$""")
        private val WEEKDAY_HEADERS: Map<String, Int> =
            "一二三四五六日".mapIndexed { idx, ch -> "星期$ch" to (idx + 1) }.toMap()

        /** Generates newest-first semester strings for multi-term grade scanning. */
        fun gradeTerms(currentSemester: String, depth: Int = 8): List<String> {
            val match = GRADE_TERM_RE.matchEntire(currentSemester.trim()) ?: return listOf("")
            var year = match.groupValues[1].toInt()
            var term = match.groupValues[3].toInt()
            val out = mutableListOf<String>()
            repeat(depth.coerceAtLeast(1)) {
                out.add("$year-${year + 1}-$term")
                if (term == 2) {
                    term = 1
                } else {
                    year -= 1
                    term = 2
                }
            }
            return out
        }

        /** Verifies that an HTML page is the authenticated JW student homepage. */
        fun jwAuthenticated(html: String): Boolean {
            if (LocalSchoolHttp.isLoginHtml(html)) return false
            val doc = Jsoup.parse(html)
            if (JW_HOME_MARKERS.all { doc.selectFirst(it) != null }) return true
            throw schemaChanged("教务未返回可识别的学生主页；请检查学校页面，未保存未经验证的会话。")
        }

        /** Parses and validates the semester query form on `/jsxsd/xskb/xskb_list.do`. */
        fun parseTimetableForm(html: String): JwTimetableForm {
            val doc = parsePage(html)
            val forms = doc.select("form").filter { it.select("""select[name="xnxq01id"]""").isNotEmpty() }
            val form = one(forms, "未返回唯一的学期查询表单。")
            val actionRaw = form.attr("action")
            val resolvedAction = if (actionRaw.isBlank()) JW_TIMETABLE_URL else LocalSchoolHttp.resolveUrl(JW_TIMETABLE_URL, actionRaw)
            val parsedAction = resolvedAction.toHttpUrlOrNull()
            if (!form.attr("method").equals("post", ignoreCase = true) ||
                parsedAction == null ||
                !parsedAction.host.equals(LocalSchoolHttp.JW_HOST, ignoreCase = true) ||
                parsedAction.encodedPath != "/jsxsd/xskb/xskb_list.do"
            ) {
                throw ttChanged("查询路线改变，已停止发送表单。")
            }

            val data = linkedMapOf<String, String>()
            val selects = linkedMapOf<String, List<Element>>()
            val checkboxes = linkedMapOf<String, String>()

            for (control in form.select("input[name],select[name],textarea[name]")) {
                val name = control.attr("name")
                val tag = control.tagName().lowercase(Locale.ROOT)
                val type = control.attr("type").lowercase(Locale.ROOT)
                if (tag == "input" && type in setOf("button", "submit", "reset")) continue
                if (name !in JW_TIMETABLE_FIELDS || name in data || name in checkboxes || control.hasAttr("disabled")) {
                    throw ttChanged("查询字段重复或发生变化。")
                }
                when {
                    tag == "select" -> {
                        if (control.hasAttr("multiple")) throw ttChanged("查询选项无法识别。")
                        val options = control.select("option")
                        val values = options.map { controlValue(it) }
                        if (options.size !in 1..100 || values.toSet().size != options.size) {
                            throw ttChanged("查询选项重复或缺失。")
                        }
                        val selected = options.filter { it.hasAttr("selected") }
                        if (selected.size > 1) throw ttChanged("选定学期或周次有歧义。")
                        data[name] = controlValue(selected.firstOrNull() ?: options[0])
                        selects[name] = options
                    }
                    tag == "input" && type == "hidden" -> {
                        data[name] = controlValue(control)
                    }
                    tag == "input" && type == "checkbox" -> {
                        val v = controlValue(control)
                        checkboxes[name] = v
                        if (control.hasAttr("checked")) data[name] = v
                    }
                    else -> throw ttChanged("查询控件发生变化。")
                }
            }

            val zcOptions = selects["zc"]
            if (selects.keys != setOf("zc", "xnxq01id", "kbjcmsid") ||
                !data.keys.containsAll(setOf("cj0701id", "demo")) ||
                checkboxes["wkbkc"].isNullOrEmpty() ||
                zcOptions == null || zcOptions.none { controlValue(it) == "" } ||
                data["kbjcmsid"].isNullOrEmpty()
            ) {
                throw ttChanged("缺少全部周次或未排课查询控件。")
            }

            val semesters = selects.getValue("xnxq01id")
                .map { controlValue(it) to LocalSchoolHttp.cleanText(it.text()) }
                .filter { it.first.isNotEmpty() }
            if (semesters.isEmpty() ||
                semesters.any { !JW_TERM_PATTERN.matches(it.first) } ||
                semesters.none { it.first == data["xnxq01id"] }
            ) {
                throw ttChanged("学期选项无法确认。")
            }

            data["zc"] = ""
            data["wkbkc"] = checkboxes.getValue("wkbkc")
            return JwTimetableForm(data.getValue("xnxq01id"), semesters, data)
        }

        /** Expands JW week/period expressions like `1-16(单周)[03-04-05节]` into `(weeks, contiguousRuns)`. */
        fun parseMeetingTime(raw: String): Pair<List<Int>, List<List<Int>>> {
            val normalized = raw
                .replace(Regex("\\s+"), "")
                .replace('（', '(')
                .replace('）', ')')
                .replace('［', '[')
                .replace('］', ']')
                .replace('，', ',')
                .replace('、', ',')
            val m = MEETING_TIME_RE.matchEntire(normalized)
                ?: throw ttChanged("周次／节次表达无法无歧义解析。")
            val p1 = m.groupValues[2]
            val p2 = m.groupValues[3]
            if (p1.isNotEmpty() && p2.isNotEmpty() && p1 != p2) {
                throw ttChanged("周次／节次表达无法无歧义解析。")
            }
            val weeks = sortedSetOf<Int>()
            for (part in m.groupValues[1].split(",")) {
                val interval = WEEK_INTERVAL_RE.matchEntire(part)
                    ?: throw ttChanged("周次表达无法识别。")
                val first = interval.groupValues[1].toInt()
                val last = interval.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: first
                if (first !in 1..60 || last !in first..60) {
                    throw ttChanged("周次超出可识别范围。")
                }
                for (w in first..last) weeks.add(w)
            }
            val parity = p1.ifEmpty { p2 }
            val filteredWeeks = if (parity.isNotEmpty()) {
                val rem = if (parity == "单") 1 else 0
                weeks.filter { it % 2 == rem }
            } else {
                weeks.toList()
            }
            val parts = m.groupValues[4].split(Regex("[-,]"))
            if (parts.any { !TWO_DIGIT_RE.matches(it) }) {
                throw ttChanged("节次表达无法识别。")
            }
            val periods = parts.map { it.toInt() }
            if (filteredWeeks.isEmpty() ||
                periods != periods.distinct().sorted() ||
                periods.any { it !in 1..30 }
            ) {
                throw ttChanged("周次／节次重复或超出范围。")
            }
            val runs = mutableListOf<MutableList<Int>>()
            for (period in periods) {
                if (runs.isEmpty() || period != runs.last().last() + 1) {
                    runs.add(mutableListOf(period))
                } else {
                    runs.last().add(period)
                }
            }
            return filteredWeeks to runs
        }

        /**
         * Parses a full JW theoretical timetable HTML page (`jw_timetable.py:parse_timetable` port).
         * Supports classes spanning multiple 大节 rows and reports skipped rows/blocks in `pending` & `coverage`.
         */
        fun parseTimetableHtml(html: String, semester: String): JSONObject {
            val form = parseTimetableForm(html)
            if (form.semester != semester) {
                throw ttChanged("返回的学期与查询不符。")
            }
            val doc = parsePage(html)
            val weeksSelect = one(doc.select("""form select[name="zc"]"""), "周次查询字段有歧义。")
            val chosen = weeksSelect.select("option[selected]").ifEmpty { weeksSelect.select("option").take(1) }
            if (chosen.isEmpty() || controlValue(chosen[0]) != "") {
                throw ttChanged("未返回全部周次。")
            }
            val includeUnarranged = one(doc.select("""form input[name="wkbkc"]"""), "未排课查询字段有歧义。")
            if (!includeUnarranged.hasAttr("checked")) {
                throw ttChanged("未返回已启用的未排课查询，不能确认完整范围。")
            }

            val (arranged, skippedArranged) = parseInventory(doc, "dataTables2")
            val (unarrangedRows, skippedUnarranged) = parseInventory(doc, "dataTables")
            val skippedInventory = skippedArranged + skippedUnarranged

            data class MutableCourse(
                val id: String,
                val title: String,
                val courseCode: String,
                val className: String,
                val teachers: MutableList<String> = mutableListOf(),
                val meetings: MutableList<JSONObject> = mutableListOf(),
            )

            val courses = linkedMapOf<String, MutableCourse>()
            val index = linkedMapOf<Triple<String, String, String>, String>()
            for (row in arranged) {
                val cid = LocalSchoolHttp.itemId("jw", "timetable", semester, row.getValue("course_code"), row.getValue("class_name"))
                val rec = courses.getOrPut(cid) {
                    MutableCourse(cid, row.getValue("title"), row.getValue("course_code"), row.getValue("class_name"))
                }
                if (rec.title != row.getValue("title")) {
                    throw ttChanged("同一课程身份对应不同名称。")
                }
                val teacher = row.getValue("teacher")
                if (teacher !in rec.teachers) rec.teachers.add(teacher)
                val key = Triple(row.getValue("class_name"), row.getValue("title"), teacher)
                if (key in index) throw ttChanged("课程明细身份重复或有歧义。")
                index[key] = cid
            }

            val grid = one(doc.select("#timetable"), "星期节次网格缺失或重复。")
            val rows = directRows(grid)
            if (rows.isEmpty()) throw ttChanged("星期网格为空。")
            val headings = directCells(rows[0]).map { cellText(it) }
            if (headings.size != 8 || headings.drop(1).toSet() != WEEKDAY_HEADERS.keys) {
                throw ttChanged("星期表头缺失或有歧义。")
            }

            val allPeriods = mutableSetOf<Int>()
            for (row in rows.drop(1)) {
                val probe = directCells(row)
                if (probe.size != 8) continue
                val hit = ROW_PERIODS_RE.find(cellText(probe[0])) ?: continue
                hit.groupValues[1].split(Regex("[,，]")).mapNotNull { it.trim().toIntOrNull() }.forEach { allPeriods.add(it) }
            }

            val seenMeetings = mutableSetOf<String>()
            val matchedDetails = mutableSetOf<Triple<String, String, String>>()
            val gridPeriods = mutableSetOf<Int>()
            var blockCount = 0
            var skippedTime = 0
            var skippedBlocks = 0

            for (row in rows.drop(1)) {
                val cells = directCells(row)
                if (cells.isNotEmpty() && NOTE_LABEL_RE.matches(cellText(cells[0])) && row.select(".kbcontent").isEmpty()) {
                    continue
                }
                if (cells.size != 8 || cells.any { (it.attr("rowspan").ifEmpty { "1" }) != "1" || (it.attr("colspan").ifEmpty { "1" }) != "1" }) {
                    throw ttChanged("星期网格行结构改变。")
                }
                val periodMatch = ROW_PERIODS_RE.find(cellText(cells[0]))
                    ?: throw ttChanged("大节与实际节次的对应无法确认。")
                val periodValues = periodMatch.groupValues[1].split(Regex("[,，]")).map { it.trim().toInt() }
                val rowPeriodSet = periodValues.toSet()
                if (rowPeriodSet.isEmpty() ||
                    periodValues != rowPeriodSet.sorted() ||
                    rowPeriodSet.any { it !in 1..30 } ||
                    (gridPeriods intersect rowPeriodSet).isNotEmpty()
                ) {
                    throw ttChanged("节次网格重复或超出范围。")
                }
                gridPeriods.addAll(rowPeriodSet)

                for (dayIdx in 1 until 8) {
                    val heading = headings[dayIdx]
                    val weekday = WEEKDAY_HEADERS.getValue(heading)
                    val cell = cells[dayIdx]
                    val nodes = cell.select(".kbcontent")
                    if (nodes.isEmpty()) {
                        if (cellText(cell).isNotEmpty()) skippedBlocks++
                        continue
                    }
                    for (node in nodes) {
                        for (block in parseBlocks(node)) {
                            val required = setOf("title", "教师", "周次(节次)", "班级", "通知单编号", "教室")
                            if (!block.keys.containsAll(required) || block["通知单编号"].isNullOrEmpty()) {
                                skippedBlocks++
                                continue
                            }
                            val key = Triple(block.getValue("班级"), block.getValue("title"), block.getValue("教师"))
                            val cid = index[key]
                            if (cid == null) {
                                skippedBlocks++
                                continue
                            }
                            val (weeks, runs) = try {
                                parseMeetingTime(block.getValue("周次(节次)"))
                            } catch (_: LocalSchoolError) {
                                skippedTime++
                                continue
                            }
                            val flatPeriods = runs.flatten().toSet()
                            if (!allPeriods.containsAll(flatPeriods)) {
                                throw ttChanged("授课节次与网格对应不一致。")
                            }
                            matchedDetails.add(key)
                            val building = block["教学楼"].orEmpty()
                            val room = block.getValue("教室")
                            val location = if (building.isEmpty() || room.startsWith(building)) {
                                room
                            } else {
                                LocalSchoolHttp.cleanText("$building $room")
                            }
                            val weeksStr = weeks.joinToString(", ", "[", "]")
                            for (periods in runs) {
                                val periodsStr = periods.joinToString(", ", "[", "]")
                                val mid = LocalSchoolHttp.itemId(
                                    "jw", "meeting", cid, block.getValue("通知单编号"), weekday, periodsStr, weeksStr,
                                )
                                if (!seenMeetings.add(mid)) continue
                                courses.getValue(cid).meetings.add(
                                    JSONObject()
                                        .put("id", mid)
                                        .put("course_id", cid)
                                        .put("title", block.getValue("title"))
                                        .put("class_name", block.getValue("班级"))
                                        .put("weekday", weekday)
                                        .put("weeks", JSONArray(weeks))
                                        .put("periods", JSONArray(periods))
                                        .put("teacher", block.getValue("教师"))
                                        .put("location", location)
                                        .put("building", building)
                                        .put("room", room)
                                        .put("raw_time", block.getValue("周次(节次)"))
                                        .put("notes", block["备注"].orEmpty())
                                        .put("online_info", block["online_info"].orEmpty()),
                                )
                            }
                            blockCount++
                        }
                    }
                }
            }

            if (gridPeriods.isEmpty() && courses.isEmpty()) {
                throw ttChanged("缺少节次网格，不能认定为空课表。")
            }
            val activeCourses = courses.filterValues { it.meetings.isNotEmpty() }
            if (activeCourses.isEmpty() && unarrangedRows.isEmpty()) {
                throw ttChanged("课表里没有任何可用的课程安排。")
            }
            if (activeCourses.size > 500 || seenMeetings.size > 2000) {
                throw ttChanged("课程明细与授课安排不完整或超过上限。")
            }

            val pending = mutableListOf<String>()
            if (unarrangedRows.isNotEmpty()) {
                pending.add("学校列有未排课课程，导出前须确认这些课程的安排。")
            }
            val unplacedDetails = (index.keys - matchedDetails)
                .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
                .map { key ->
                    JSONObject()
                        .put("course_id", index.getValue(key))
                        .put("class_name", key.first)
                        .put("title", key.second)
                        .put("teacher", key.third)
                }
            if (unplacedDetails.isNotEmpty()) {
                pending.add("部分已排课教师明细没有对应的网格安排，须在学校核对，不能认定这些安排为空。")
            }
            if (skippedTime > 0) {
                pending.add("有 $skippedTime 条安排的周次写法没认出来，已跳过；这份课表不完整，别当成全部课。")
            }
            if (skippedInventory > 0) {
                pending.add("有 $skippedInventory 条课程明细无法识别，须在学校核对。")
            }
            if (skippedBlocks > 0) {
                pending.add("有 $skippedBlocks 条网格安排缺少字段或无法匹配明细，须在学校核对。")
            }

            data class UnscheduledCourse(
                val id: String,
                val title: String,
                val courseCode: String,
                val className: String,
                val teachers: MutableList<String> = mutableListOf(),
            )

            val unscheduled = linkedMapOf<String, UnscheduledCourse>()
            for (row in unarrangedRows) {
                val cid = LocalSchoolHttp.itemId("jw", "timetable", semester, row.getValue("course_code"), row.getValue("class_name"))
                if (cid in activeCourses) {
                    throw ttChanged("同一课程同时出现在已排课和未排课明细。")
                }
                val entry = unscheduled.getOrPut(cid) {
                    UnscheduledCourse(cid, row.getValue("title"), row.getValue("course_code"), row.getValue("class_name"))
                }
                val teacher = row.getValue("teacher")
                if (entry.title != row.getValue("title") || teacher in entry.teachers) {
                    throw ttChanged("未排课课程身份重复或有歧义。")
                }
                entry.teachers.add(teacher)
            }

            val coursesArray = JSONArray()
            for (c in activeCourses.values.sortedBy { it.id }) {
                val sortedTeachers = c.teachers.sorted()
                val sortedMeetings = c.meetings.sortedWith(
                    compareBy<JSONObject>({ it.optInt("weekday") })
                        .thenBy { intArrayKey(it.optJSONArray("periods")) }
                        .thenBy { intArrayKey(it.optJSONArray("weeks")) }
                        .thenBy { it.optString("id") },
                )
                coursesArray.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("title", c.title)
                        .put("course_code", c.courseCode)
                        .put("class_name", c.className)
                        .put("teachers", JSONArray(sortedTeachers))
                        .put("teacher", sortedTeachers.filter { it.isNotEmpty() }.joinToString("、"))
                        .put("hue", LocalTimetableImporter.hueFor(c.id))
                        .put("meetings", JSONArray(sortedMeetings)),
                )
            }

            val unarrangedArray = JSONArray()
            for (u in unscheduled.values.sortedBy { it.id }) {
                unarrangedArray.put(
                    JSONObject()
                        .put("id", u.id)
                        .put("title", u.title)
                        .put("course_code", u.courseCode)
                        .put("class_name", u.className)
                        .put("teachers", JSONArray(u.teachers.sorted())),
                )
            }

            val semestersArray = JSONArray()
            for ((sem, label) in form.semesters) {
                semestersArray.put(JSONObject().put("semester", sem).put("label", label))
            }

            val complete = unplacedDetails.isEmpty() && skippedTime == 0 && skippedInventory == 0 && skippedBlocks == 0
            return JSONObject()
                .put("semester", semester)
                .put("available_semesters", semestersArray)
                .put("courses", coursesArray)
                .put("unarranged", unarrangedArray)
                .put("unplaced_details", JSONArray(unplacedDetails))
                .put("pending", JSONArray(pending))
                .put("status", if (pending.isNotEmpty() || unarrangedArray.length() > 0) "partial" else "ok")
                .put(
                    "coverage",
                    JSONObject()
                        .put("kind", "theory")
                        .put("weeks", "all")
                        .put("complete", complete)
                        .put("course_count", activeCourses.size)
                        .put("meeting_count", seenMeetings.size)
                        .put("grid_arrangements", blockCount)
                        .put("unarranged_count", unscheduled.size)
                        .put("unplaced_detail_count", unplacedDetails.size)
                        .put("skipped_time_count", skippedTime)
                        .put("skipped_inventory_count", skippedInventory)
                        .put("skipped_block_count", skippedBlocks)
                        .put("calendar_verified", false)
                        .put("period_times_verified", false)
                        .put("experimental_timetable_included", false),
                )
        }

        /** Parses the default exam semester from `/jsxsd/xsks/xsksap_query`. */
        fun parseExamSemester(html: String): String {
            val doc = parsePage(html)
            val fields = doc.select("select[name=xnxqid],select#xnxqid")
            if (fields.size != 1 || fields[0].hasAttr("multiple")) {
                throw schemaChanged("教务未返回唯一的考试学期控件；请明确指定学期后重试。")
            }
            val allOptions = fields[0].select("option")
            val valuedOptions = allOptions.filter { it.hasAttr("value") }
            val values = valuedOptions.map { it.attr("value") }
            if (values.toSet().size != values.size) {
                throw schemaChanged("教务考试学期选项重复；请明确指定学期后重试。")
            }
            val selected = allOptions.filter { it.hasAttr("selected") }
            if (selected.size > 1) {
                throw schemaChanged("教务同时选中了多个考试学期；请明确指定学期后重试。")
            }
            if (selected.isEmpty() && valuedOptions.isEmpty()) {
                throw schemaChanged("教务未返回可识别的考试学期；请明确指定学期后重试。")
            }
            val chosen = (selected.firstOrNull() ?: valuedOptions.first()).attr("value")
            if (!JW_TERM_PATTERN.matches(chosen)) {
                throw schemaChanged("教务未选中可识别的考试学期；请明确指定学期后重试。")
            }
            return chosen
        }

        /** Parses JW grades HTML table (`stu_mcp/features/jw/parser.py:parse_jw(html, "grade")`). */
        fun parseGradesHtml(html: String): List<JSONObject> = parseAcademicTable(html, "grade", "")

        /** Parses JW exams HTML table (`stu_mcp/features/jw/parser.py:parse_jw(html, "exam", semester)`). */
        fun parseExamsHtml(html: String, semester: String): List<JSONObject> = parseAcademicTable(html, "exam", semester)

        private fun parseAcademicTable(html: String, kind: String, semester: String): List<JSONObject> {
            val doc = parsePage(html)
            val columnSpec = if (kind == "grade") JW_COLUMNS_GRADE else JW_COLUMNS_EXAM
            val required = if (kind == "grade") JW_REQUIRED_GRADE else JW_REQUIRED_EXAM
            val (rows, columns, width, headerRow) = findAcademicTable(doc, columnSpec, required)

            val records = mutableListOf<JSONObject>()
            val seen = mutableSetOf<String>()
            var beforeHeader = true

            for (row in rows) {
                val cells = directCells(row)
                if (row === headerRow) beforeHeader = false
                if (beforeHeader) {
                    if (cells.any { parseSpan(it, "rowspan") != 1 }) {
                        throw schemaChanged("教务表格存在跨行单元格，无法唯一对应记录，保留原缓存。")
                    }
                    if (cells.size == 1 && cells[0].tagName().equals("th", true) && parseSpan(cells[0], "colspan") > 1) {
                        continue
                    }
                }
                if (cells.isEmpty()) continue
                val (mapping, duplicate) = matchColumns(cells, columnSpec)
                if (mapping.keys.containsAll(required)) {
                    if (duplicate || mapping != columns || cells.size != width || !isPlainCells(cells)) {
                        throw schemaChanged("教务重复表头存在冲突，保留原缓存。")
                    }
                    continue
                }
                if (cells.any { it.selectFirst("table") != null }) {
                    throw schemaChanged("教务记录包含嵌套表格，无法唯一对应字段，保留原缓存。")
                }
                val values = cells.map { LocalSchoolHttp.cleanText(it.text()) }
                if (cells.any { parseSpan(it, "rowspan") != 1 }) {
                    throw schemaChanged("教务记录存在跨行单元格，无法唯一对应字段，保留原缓存。")
                }
                if (isEmptyRow(cells, values, columns, width) || isSummaryRow(cells, values, columns, width)) {
                    continue
                }
                if (cells.all { it.tagName().equals("th", true) } ||
                    (mapping.keys.containsAll(setOf("course_code", "title")) && mapping.size >= 3)
                ) {
                    throw schemaChanged("教务记录中出现无法识别的表头，保留原缓存。")
                }
                if (cells.size != width || !isPlainCells(cells)) {
                    throw schemaChanged("教务记录与表头不匹配，保留原缓存。")
                }
                if (values.none { it.isNotEmpty() }) continue

                val rawMap = linkedMapOf<String, String>()
                for ((field, idx) in columns) {
                    rawMap[field] = values[idx]
                }
                if (rawMap["course_code"].isNullOrEmpty() || rawMap["title"].isNullOrEmpty()) {
                    throw schemaChanged("教务记录缺少课程编号或名称，保留原缓存。")
                }

                val obj = JSONObject()
                for ((k, v) in rawMap) {
                    obj.put(k, v)
                }
                obj.put("jw_schema_version", JW_SCHEMA_VERSION)
                obj.put("kind", kind)
                if (kind == "grade") {
                    for (numKey in listOf("credits", "gpa")) {
                        val parsedNum = rawMap[numKey]?.toDoubleOrNull()?.takeIf { it.isFinite() }
                        if (parsedNum != null) obj.put(numKey, parsedNum) else obj.put(numKey, JSONObject.NULL)
                    }
                    val id = LocalSchoolHttp.itemId(
                        "jw",
                        kind,
                        rawMap["semester"].orEmpty(),
                        rawMap["course_code"].orEmpty(),
                        rawMap["exam_type"].orEmpty(),
                        rawMap["group"].orEmpty(),
                    )
                    obj.put("id", id)
                    obj.put("external_id", id)
                } else {
                    val id = LocalSchoolHttp.itemId(
                        "jw",
                        kind,
                        semester,
                        rawMap["course_code"].orEmpty(),
                        rawMap["exam_time"].orEmpty(),
                    )
                    obj.put("id", id)
                    obj.put("external_id", id)
                    obj.put("semester", semester)
                }
                val id = obj.getString("id")
                if (!seen.add(id)) {
                    throw schemaChanged("教务记录身份重复，无法区分记录，保留原缓存。")
                }
                records.add(obj)
            }
            return records
        }

        private data class TableMatch(
            val rows: List<Element>,
            val columns: Map<String, Int>,
            val width: Int,
            val headerRow: Element,
        )

        private fun findAcademicTable(
            doc: org.jsoup.nodes.Document,
            columnSpec: Map<String, Set<String>>,
            required: Set<String>,
        ): TableMatch {
            val candidates = mutableListOf<TableMatch>()
            for (table in doc.select("table")) {
                val rows = directRows(table)
                val headers = mutableListOf<Triple<Element, Map<String, Int>, Int>>()
                for (row in rows) {
                    val cells = directCells(row)
                    if (cells.any { it.selectFirst("table") != null }) continue
                    val (cols, duplicate) = matchColumns(cells, columnSpec)
                    if (cols.keys.containsAll(required)) {
                        if (duplicate) throw schemaChanged("教务表头存在重复或同义字段，无法唯一确定列，保留原缓存。")
                        if (!isPlainCells(cells)) throw schemaChanged("教务表头存在合并单元格，无法唯一确定列，保留原缓存。")
                        headers.add(Triple(row, cols, cells.size))
                    }
                }
                if (headers.isNotEmpty()) {
                    val (firstRow, cols, width) = headers[0]
                    if (headers.drop(1).any { it.second != cols || it.third != width }) {
                        throw schemaChanged("教务同一表格存在互相冲突的表头，保留原缓存。")
                    }
                    candidates.add(TableMatch(rows, cols, width, firstRow))
                }
            }
            if (candidates.isEmpty()) throw schemaChanged("教务表头缺少预期字段；未把此页面当作空结果。")
            if (candidates.size != 1) throw schemaChanged("教务返回多个符合字段的表格，无法唯一确定结果，保留原缓存。")
            return candidates[0]
        }

        private fun matchColumns(
            cells: List<Element>,
            spec: Map<String, Set<String>>,
        ): Pair<Map<String, Int>, Boolean> {
            val labels = cells.map { LocalSchoolHttp.cleanText(it.text()).replace(Regex("\\s+"), "") }
            val cols = linkedMapOf<String, Int>()
            var duplicate = false
            for ((field, aliases) in spec) {
                val hits = labels.indices.filter { labels[it] in aliases }
                if (hits.size > 1) duplicate = true
                if (hits.isNotEmpty()) cols[field] = hits[0]
            }
            return cols to duplicate
        }

        private fun parseSpan(cell: Element, attr: String): Int {
            val raw = cell.attr(attr).ifEmpty { "1" }
            if (raw.length > 6 || !POS_INT_RE.matches(raw)) {
                throw schemaChanged("教务表格含有无法识别的合并单元格，保留原缓存。")
            }
            return raw.toInt()
        }

        private fun isPlainCells(cells: List<Element>): Boolean =
            cells.all { parseSpan(it, "rowspan") == 1 && parseSpan(it, "colspan") == 1 }

        private fun isEmptyRow(
            cells: List<Element>,
            values: List<String>,
            columns: Map<String, Int>,
            width: Int,
        ): Boolean {
            if (values.isEmpty() || values[0].isEmpty() || values.drop(1).any { it.isNotEmpty() }) return false
            if (!EMPTY_ROW_RE.matches(values[0].replace(Regex("\\s+"), ""))) return false
            return cells.size == 1 ||
                parseSpan(cells[0], "colspan") > 1 ||
                (cells.size == width && 0 !in columns.values)
        }

        private fun isSummaryRow(
            cells: List<Element>,
            values: List<String>,
            columns: Map<String, Int>,
            width: Int,
        ): Boolean {
            if (values.isEmpty()) return false
            val label = values[0].replace(Regex("\\s+"), "")
            if (values.drop(1).none { it.isNotEmpty() } &&
                (cells.size == 1 || parseSpan(cells[0], "colspan") > 1) &&
                SUMMARY_INLINE_RE.matches(label)
            ) {
                return true
            }
            if (label.trimEnd('：', ':') !in SUMMARY_LABELS) return false
            if (cells.size == width) {
                if (listOf("course_code", "title").any { f -> columns[f]?.let { values[it].isNotEmpty() } == true }) {
                    return false
                }
            } else if (parseSpan(cells[0], "colspan") == 1) {
                return false
            }
            return values.drop(1).all { it.isEmpty() || SUMMARY_NUM_RE.matches(it) }
        }

        private fun parseInventory(doc: org.jsoup.nodes.Document, tableId: String): Pair<List<Map<String, String>>, Int> {
            val table = one(doc.select("#$tableId"), "课程明细表缺失或重复。")
            val rows = directRows(table)
            val candidates = mutableListOf<Triple<Int, Map<String, Int>, Int>>()
            for ((index, row) in rows.withIndex()) {
                val cells = directCells(row)
                val labels = cells.map { cellText(it).replace(Regex("\\s+"), "") }
                val matches = JW_TIMETABLE_COLUMNS.mapValues { (_, aliases) ->
                    labels.indices.filter { labels[it] in aliases }
                }
                if (matches.values.all { it.isNotEmpty() }) {
                    if (matches.values.any { it.size != 1 }) throw ttChanged("课程表头存在重复含义。")
                    candidates.add(Triple(index, matches.mapValues { it.value[0] }, labels.size))
                }
            }
            val (headIdx, columns, width) = one(candidates, "课程表头缺失或有歧义。")
            val result = mutableListOf<Map<String, String>>()
            var skipped = 0
            for ((pos, row) in rows.withIndex()) {
                if (pos == headIdx) continue
                val cells = directCells(row)
                if (cells.size == 1 &&
                    cellText(cells[0]) in setOf("无课表课程", "有课表课程") &&
                    cells[0].tagName().equals("th", true) &&
                    cells[0].selectFirst("table") == null &&
                    POS_INT_RE.matches(cells[0].attr("colspan"))
                ) {
                    continue
                }
                if (cells.size != width || cells.any {
                        (it.attr("rowspan").ifEmpty { "1" }) != "1" ||
                            (it.attr("colspan").ifEmpty { "1" }) != "1" ||
                            it.selectFirst("table") != null
                    }
                ) {
                    skipped++
                    continue
                }
                val record = columns.mapValues { (_, idx) -> cellText(cells[idx]) }
                if (listOf("class_name", "course_code", "title").any { record[it].isNullOrEmpty() }) {
                    skipped++
                    continue
                }
                result.add(record)
            }
            if (result.size > 1000) throw ttChanged("课程明细超过读取上限。")
            return result to skipped
        }

        private fun parseBlocks(node: Element): List<Map<String, String>> {
            for (child in node.childNodes()) {
                when (child) {
                    is Element -> {
                        val tag = child.tagName().lowercase(Locale.ROOT)
                        val isItemBox = tag == "div" && child.hasClass("item-box")
                        if (tag !in setOf("font", "br") && !isItemBox) {
                            throw ttChanged("授课安排包含未识别内容。")
                        }
                        if (tag == "div" && (cellText(child).isNotEmpty() || child.select("table,font").isNotEmpty())) {
                            throw ttChanged("授课提示框含未识别业务内容。")
                        }
                    }
                    is TextNode -> {
                        val txt = LocalSchoolHttp.cleanText(child.wholeText)
                        if (txt.isNotEmpty() && !DASHES_ONLY_RE.matches(txt)) {
                            throw ttChanged("授课安排包含未识别文本。")
                        }
                    }
                    is Comment -> { /* Ignore HTML comments. */ }
                }
            }
            val records = mutableListOf<MutableMap<String, String>>()
            for (font in node.children().filter { it.tagName().equals("font", true) }) {
                val title = font.attr("title")
                val name = font.attr("name")
                if (font.selectFirst("table") != null || (title.isNotEmpty() && title !in JW_FONT_TITLES)) {
                    throw ttChanged("授课安排出现未识别业务字段。")
                }
                when {
                    title.isEmpty() && name.isEmpty() -> {
                        records.add(linkedMapOf("title" to cellText(font)))
                    }
                    title.isNotEmpty() -> {
                        if (records.isEmpty() || title in records.last()) {
                            throw ttChanged("授课安排字段顺序或含义有歧义。")
                        }
                        records.last()[title] = stripLabelPrefix(cellText(font), title)
                    }
                    name !in setOf("wkxx", "xsks") -> {
                        throw ttChanged("授课安排出现未知字段。")
                    }
                    name == "wkxx" -> {
                        if (records.isEmpty() || "online_info" in records.last()) {
                            throw ttChanged("网络授课信息缺少所属课程。")
                        }
                        records.last()["online_info"] = cellText(font)
                    }
                }
            }
            if (cellText(node).isNotEmpty() && records.isEmpty()) {
                throw ttChanged("授课安排缺少可识别字段。")
            }
            return records
        }

        private fun stripLabelPrefix(text: String, label: String): String =
            text.replaceFirst(Regex("^" + Regex.escape(label) + """\s*[:：]\s*"""), "")

        private fun parsePage(html: String): org.jsoup.nodes.Document {
            if (LocalSchoolHttp.isLoginHtml(html)) {
                throw LocalSchoolError("login_expired", "教务登录已过期，请重新连接校园账号。", "jw")
            }
            return Jsoup.parse(html)
        }

        private fun directRows(table: Element): List<Element> =
            table.select("tr").filter { row ->
                row.parents().firstOrNull { it.tagName().equals("table", true) } === table
            }

        private fun directCells(row: Element): List<Element> =
            row.children().filter { it.tagName().lowercase(Locale.ROOT) in setOf("th", "td") }

        private fun cellText(el: Element): String {
            val result = LocalSchoolHttp.cleanText(el.text())
            if (result.length > 2000) throw ttChanged("字段超过可识别范围。")
            return result
        }

        private fun controlValue(el: Element): String {
            val value = el.attr("value")
            if (value.length > 1024) throw ttChanged("查询字段无法识别。")
            return value
        }

        private fun <T> one(items: List<T>, message: String): T {
            if (items.size != 1) throw ttChanged(message)
            return items[0]
        }

        private fun intArrayKey(arr: JSONArray?): String {
            if (arr == null) return ""
            return (0 until arr.length()).joinToString(",") { arr.optInt(it).toString().padStart(3, '0') }
        }

        private fun ttChanged(message: String): LocalSchoolError =
            LocalSchoolError("schema_changed", "教务课表$message", "jw")

        private fun schemaChanged(message: String): LocalSchoolError =
            LocalSchoolError("schema_changed", message, "jw")
    }
}
