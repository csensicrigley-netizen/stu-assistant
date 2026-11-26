package com.stuassistant.app.nativeapp.local

import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure offline timetable importer & validator, faithfully ported from
 * `backend/app/timetable_import.py`.
 *
 * Zero network I/O. Produces `AcademicTimetable.normalize()`-compatible timetable JSON documents
 * with deterministic course/meeting IDs and hue values.
 */
object LocalTimetableImporter {
    const val MAX_PERIOD = 13
    const val MAX_WEEK = 30
    val DEFAULT_WEEKS: List<Int> = (1..16).toList()

    data class SchoolOption(val id: String, val name: String, val note: String)

    val SCHOOLS: List<SchoolOption> = listOf(
        SchoolOption("main", "主校区", "教务系统可自动同步"),
        SchoolOption("med", "医学院", "教务系统不同，可自动同步或手动录入"),
        SchoolOption("other", "其他", "自行录入"),
    )
    val SCHOOL_IDS: Set<String> = SCHOOLS.map { it.id }.toSet()

    private val CN_WEEKDAY = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "日" to 7, "天" to 7,
        "1" to 1, "2" to 2, "3" to 3, "4" to 4, "5" to 5, "6" to 6, "7" to 7,
    )

    private val EN_WEEKDAY = mapOf(
        "mon" to 1, "monday" to 1,
        "tue" to 2, "tues" to 2, "tuesday" to 2,
        "wed" to 3, "weds" to 3, "wednesday" to 3,
        "thu" to 4, "thur" to 4, "thurs" to 4, "thursday" to 4,
        "fri" to 5, "friday" to 5,
        "sat" to 6, "saturday" to 6,
        "sun" to 7, "sunday" to 7,
    )

    private val WD_RE = Regex("""(?:周|星期|礼拜)\s*([一二三四五六日天1-7])""")
    private val WD_BAD_RE = Regex("""(?:周|星期|礼拜)\s*([八九十]|\d+)""")
    private val EN_DAY_RE = Regex(
        """(?<![A-Za-z])(mon|monday|tues|tuesday|tue|weds|wednesday|wed|thurs|thursday|thur|thu|fri|friday|sat|saturday|sun|sunday)(?![A-Za-z])""",
        RegexOption.IGNORE_CASE,
    )
    private val BARE_WD_RE = Regex("""^\s*([一二三四五六日天])(?=\s|\d|第|$)""")
    private val RANGE_RE = Regex("""^(\d+)(?:-(\d+))?$""")
    private val WEEK_CHARS_RE = Regex("""^[\d\s,，、\-–~至到()（）第]+$""")
    private val DIGITS_RE = Regex("""\d+""")
    private val LOCATION_HINT_RE = Regex("""[楼室馆场区座层号栋厅苑]""")
    private val TIME_SPLIT_RE = Regex("""[;；+＋,，、]""")
    private val WD_TOKEN_SPLIT_RE = Regex("""[,，、/;；\s]+""")
    private val PERIOD_GROUP_SPLIT_RE = Regex("""[,，、]""")
    private val PIPE_SPLIT_RE = Regex("""[|｜]""")

    private val SEP_MAP = listOf(
        "至" to "-", "到" to "-", "~" to "-", "～" to "-", "–" to "-", "—" to "-", "－" to "-",
        "、" to ",", "，" to ",", "；" to ",", ";" to ",", "＋" to ",", "+" to ",",
    )

    private val HEADER_ALIASES: Map<String, List<String>> = linkedMapOf(
        "title" to listOf("课程名称", "课程名", "课程", "科目", "课名", "教学班", "课程名字", "course name", "course", "subject", "title", "class"),
        "weekday" to listOf("星期几", "上课星期", "星期", "周几", "周", "日", "weekday", "day of week", "day"),
        "periods" to listOf("上课节次", "节次", "上课时间", "时间", "课时", "课节", "节", "periods", "period", "class time", "time", "session"),
        "weeks" to listOf("上课周次", "起止周", "周次", "周数", "weeks", "week", "week range"),
        "teacher" to listOf("任课教师", "授课教师", "主讲教师", "教师", "老师", "主讲", "teacher", "instructor", "lecturer"),
        "location" to listOf("上课地点", "上课教室", "地点", "教室", "场地", "课室", "上课场地", "location", "room", "classroom", "place", "venue"),
    )

    private val HEADER_EXACT: Map<String, String> = linkedMapOf<String, String>().apply {
        for ((key, aliases) in HEADER_ALIASES) {
            for (alias in aliases) {
                putIfAbsent(alias, key)
            }
        }
    }

    private val HEADER_ORDERED: List<Pair<String, String>> =
        HEADER_EXACT.entries.sortedByDescending { it.key.length }.map { it.key to it.value }

    fun norm(value: Any?): String {
        if (value == null || value === JSONObject.NULL) return ""
        return value.toString().trim().replace(Regex("\\s+"), " ")
    }

    private fun sha1Hex(raw: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun courseId(title: String, teacher: String, location: String): String =
        "course_" + sha1Hex(listOf(norm(title), norm(teacher), norm(location)).joinToString("\u001f")).take(16)

    fun hueFor(courseId: String): Int =
        (sha1Hex(courseId).take(8).toLong(16) % 360L).toInt()

    fun meetingId(courseId: String, weekday: Int, periods: List<Int>): String =
        if (periods.isEmpty()) "$courseId-w$weekday" else "$courseId-w${weekday}p${periods.first()}-${periods.last()}"

    private fun cleanNumberText(text: String, drop: List<String>): String {
        var t = text
        for (ch in "第（）()【】[]{}") {
            t = t.replace(ch.toString(), "")
        }
        for (word in drop) {
            t = t.replace(word, "")
        }
        for ((a, b) in SEP_MAP) {
            t = t.replace(a, b)
        }
        return t.replace(Regex("\\s+"), "").trim(',')
    }

    private data class WeekdayMatch(val weekday: Int?, val rest: String, val badToken: String?)

    private fun takeWeekday(text: String): WeekdayMatch {
        WD_RE.find(text)?.let { m ->
            val wd = CN_WEEKDAY[m.groupValues[1]]
            if (wd != null) {
                return WeekdayMatch(wd, text.substring(0, m.range.first) + " " + text.substring(m.range.last + 1), null)
            }
        }
        EN_DAY_RE.find(text)?.let { m ->
            val wd = EN_WEEKDAY[m.value.lowercase(Locale.ROOT).trimEnd('.')]
            if (wd != null) {
                return WeekdayMatch(wd, text.substring(0, m.range.first) + " " + text.substring(m.range.last + 1), null)
            }
        }
        WD_BAD_RE.find(text)?.let { m ->
            return WeekdayMatch(null, text, m.value)
        }
        BARE_WD_RE.find(text)?.let { m ->
            return WeekdayMatch(CN_WEEKDAY[m.groupValues[1]], text.substring(m.range.last + 1), null)
        }
        return WeekdayMatch(null, text, null)
    }

    fun parseWeekday(text: String): Int? = takeWeekday(norm(text)).weekday

    fun parsePeriods(text: String): Pair<List<Int>, String?> {
        val body = cleanNumberText(text, listOf("节次", "节"))
        if (body.isEmpty()) return emptyList<Int>() to null
        val values = mutableListOf<Int>()
        for (rawPart in body.split(",")) {
            val part = rawPart.trim()
            if (part.isEmpty()) continue
            val m = RANGE_RE.matchEntire(part)
                ?: return emptyList<Int>() to "节次「$part」看不懂，写成 6-9 或 6,7,8,9"
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: a
            val lo = minOf(a, b)
            val hi = maxOf(a, b)
            if (lo < 1 || hi > MAX_PERIOD) {
                return emptyList<Int>() to "节次 $part 超出范围（1-$MAX_PERIOD）"
            }
            for (p in lo..hi) values.add(p)
        }
        return values.distinct().sorted() to null
    }

    fun parseWeeks(text: String): Pair<List<Int>, String?> {
        val odd = "单" in text
        val even = "双" in text
        val stripped = text.replace("单", "").replace("双", "")
        val body = cleanNumberText(stripped, listOf("周次", "周数", "周"))
        if (body.isEmpty()) return emptyList<Int>() to null
        var values = mutableListOf<Int>()
        for (rawPart in body.split(",")) {
            val part = rawPart.trim()
            if (part.isEmpty()) continue
            val m = RANGE_RE.matchEntire(part)
                ?: return emptyList<Int>() to "周次「$part」看不懂，写成 1-16 或 1,3,5"
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: a
            val lo = minOf(a, b)
            val hi = maxOf(a, b)
            if (lo < 1 || hi > MAX_WEEK) {
                return emptyList<Int>() to "周次 $part 超出范围（1-$MAX_WEEK）"
            }
            for (w in lo..hi) values.add(w)
        }
        if (odd) values = values.filter { it % 2 == 1 }.toMutableList()
        if (even) values = values.filter { it % 2 == 0 }.toMutableList()
        if (values.isEmpty()) return emptyList<Int>() to "周次是空的，写成 1-16 或 1,3,5"
        return values.distinct().sorted() to null
    }

    private fun looksLikeTime(text: String): Boolean {
        val t = norm(text)
        if (t.isEmpty() || "周次" in t || "周数" in t) return false
        if (WD_RE.containsMatchIn(t)) return true
        if (EN_DAY_RE.containsMatchIn(t)) return true
        if ("节" in t) return true
        if (BARE_WD_RE.containsMatchIn(t) && t.any { it.isDigit() }) return true
        if (Regex("""(?:周|星期|礼拜)\s*\S""").containsMatchIn(t) && t.any { it.isDigit() }) return true
        return false
    }

    private fun looksLikeWeeks(text: String): Boolean {
        val t = norm(text)
        if (t.isEmpty() || !t.any { it.isDigit() }) return false
        if ("周" in t || "单" in t || "双" in t) return true
        if (!WEEK_CHARS_RE.matches(t)) return false
        val nums = DIGITS_RE.findAll(t).mapNotNull { it.value.toIntOrNull() }.toList()
        return nums.isNotEmpty() && nums.all { it in 1..MAX_WEEK }
    }

    private fun looksLikeLocation(text: String): Boolean {
        val t = norm(text)
        if (t.isEmpty()) return false
        if (LOCATION_HINT_RE.containsMatchIn(t)) return true
        if (t.any { it.isDigit() }) return true
        return "-" in t
    }

    private fun parseTimeCell(lineno: Int, text: String, errs: MutableList<String>): List<Pair<Int, List<Int>>> {
        val acc = linkedMapOf<Int, MutableList<Int>>()
        var current: Int? = null

        for (rawPiece in text.split(TIME_SPLIT_RE)) {
            val piece = rawPiece.trim()
            if (piece.isEmpty()) continue
            val (wd, rest, bad) = takeWeekday(piece)
            if (bad != null) {
                errs.add("第 $lineno 行：「$bad」看不懂，星期只能是 一/二/三/四/五/六/日 或 1-7")
                return emptyList()
            }
            if (wd != null) current = wd
            val activeDay = current
            if (activeDay == null) {
                errs.add("第 $lineno 行：没写星期，星期只能是 一/二/三/四/五/六/日 或 1-7")
                return emptyList()
            }
            val (periods, perr) = parsePeriods(rest)
            if (perr != null) {
                errs.add("第 $lineno 行：$perr")
                return emptyList()
            }
            acc.getOrPut(activeDay) { mutableListOf() }.addAll(periods)
        }

        val filled = acc.filterValues { it.isNotEmpty() }
        if (filled.isEmpty()) {
            errs.add("第 $lineno 行：没写节次，节次是 1-$MAX_PERIOD（比如 6-9）")
            return emptyList()
        }
        if (filled.size != acc.size) {
            val union = filled.values.flatten().distinct().sorted()
            for (wd in acc.keys) {
                if (acc[wd].isNullOrEmpty()) acc[wd] = union.toMutableList()
            }
        }
        return acc.entries
            .filter { it.value.isNotEmpty() }
            .sortedBy { it.key }
            .map { it.key to it.value.distinct().sorted() }
    }

    private fun pairsFromColumns(
        lineno: Int,
        weekdayCell: String,
        periodCell: String,
        errs: MutableList<String>,
    ): List<Pair<Int, List<Int>>> {
        val wdTokens = norm(weekdayCell).split(WD_TOKEN_SPLIT_RE).filter { it.isNotEmpty() }
        if (wdTokens.isEmpty()) return emptyList()
        val weekdays = mutableListOf<Int>()
        for (token in wdTokens) {
            val (wd, _, bad) = takeWeekday(token)
            if (wd == null) {
                errs.add("第 $lineno 行：「${bad ?: token}」看不懂，星期只能是 一/二/三/四/五/六/日 或 1-7")
                return emptyList()
            }
            weekdays.add(wd)
        }
        val groups = norm(periodCell).split(PERIOD_GROUP_SPLIT_RE).map { it.trim() }.filter { it.isNotEmpty() }
        val rawList = if (weekdays.size > 1 && groups.size == weekdays.size) {
            groups
        } else {
            List(weekdays.size) { norm(periodCell) }
        }
        val out = mutableListOf<Pair<Int, List<Int>>>()
        for (i in weekdays.indices) {
            val (periods, perr) = parsePeriods(rawList[i])
            if (perr != null) {
                errs.add("第 $lineno 行：$perr")
                return emptyList()
            }
            if (periods.isEmpty()) {
                errs.add("第 $lineno 行：没写节次，节次是 1-$MAX_PERIOD（比如 6-9）")
                return emptyList()
            }
            out.add(weekdays[i] to periods)
        }
        return out
    }

    private fun splitTeacherPlace(rest: List<String>): Pair<String, String> {
        var teacher = ""
        val places = mutableListOf<String>()
        for (raw in rest) {
            val field = norm(raw)
            if (field.isEmpty()) continue
            if (looksLikeLocation(field)) {
                places.add(field)
            } else if (teacher.isEmpty()) {
                teacher = field
            } else {
                places.add(field)
            }
        }
        return teacher to places.joinToString(" / ")
    }

    private fun headerMap(cells: List<String>): Map<String, Int>? {
        val found = linkedMapOf<String, Int>()
        for ((index, cell) in cells.withIndex()) {
            val name = norm(cell)
            if (name.isEmpty()) continue
            val probe = name.lowercase(Locale.ROOT)
            var key = HEADER_EXACT[name] ?: HEADER_EXACT[probe]
            if (key == null) {
                for ((alias, mapped) in HEADER_ORDERED) {
                    if (alias in name || alias in probe) {
                        key = mapped
                        break
                    }
                }
            }
            if (key != null && key !in found) {
                found[key] = index
            }
        }
        return if ("title" in found && found.size >= 2) found else null
    }

    private data class ReadRowResult(
        val title: String,
        val teacher: String,
        val location: String,
        val weeksText: String,
        val pairs: List<Pair<Int, List<Int>>>,
        val rawTime: String,
        val errors: List<String>,
    )

    private fun readRow(lineno: Int, cells: List<String>, columns: Map<String, Int>?): ReadRowResult {
        val errs = mutableListOf<String>()
        if (columns != null) {
            fun cell(key: String): String {
                val idx = columns[key] ?: return ""
                return if (idx < cells.size) cells[idx] else ""
            }
            val title = cell("title")
            val wdCell = cell("weekday")
            val pCell = cell("periods")
            val weeksText = cell("weeks")
            val teacher = cell("teacher")
            val location = cell("location")
            val rawTime = norm("$wdCell $pCell")
            val pairs = if (wdCell.isNotEmpty() || pCell.isNotEmpty()) {
                pairsFromColumns(lineno, wdCell, pCell, errs)
            } else {
                emptyList()
            }
            return ReadRowResult(title, teacher, location, weeksText, pairs, rawTime, errs)
        }

        if (cells.size >= 2 && looksLikeTime(cells[1])) {
            val title = cells[0]
            val rest = cells.drop(1).toMutableList()
            val timeText = rest.removeAt(0)
            var weeksText = ""
            if (rest.isNotEmpty() && looksLikeWeeks(rest[0])) {
                weeksText = rest.removeAt(0)
            }
            val (teacher, location) = splitTeacherPlace(rest)
            val pairs = parseTimeCell(lineno, timeText, errs)
            return ReadRowResult(title, teacher, location, weeksText, pairs, norm(timeText), errs)
        }

        if (cells.size >= 3 && parseWeekday(cells[1]) != null) {
            val title = cells[0]
            val wdCell = cells[1]
            val pCell = cells[2]
            val weeksText = if (cells.size > 3) cells[3] else ""
            val teacher = if (cells.size > 4) cells[4] else ""
            val location = if (cells.size > 5) cells.drop(5).joinToString(" ") else ""
            val pairs = pairsFromColumns(lineno, wdCell, pCell, errs)
            return ReadRowResult(title, teacher, location, weeksText, pairs, norm("$wdCell $pCell"), errs)
        }

        val title = cells[0]
        val (teacher, location) = splitTeacherPlace(cells.drop(1))
        return ReadRowResult(title, teacher, location, "", emptyList(), "", errs)
    }

    private fun parseRows(rows: List<Pair<Int, List<String>>>, semester: String): JSONObject {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var columns: Map<String, Int>? = null
        val courses = linkedMapOf<String, JSONObject>()
        val unarranged = linkedMapOf<String, JSONObject>()

        for ((lineno, rawCells) in rows) {
            val cells = rawCells.map { norm(it) }
            if (cells.none { it.isNotEmpty() }) continue
            if (cells[0].startsWith("#")) continue

            val hdr = headerMap(cells)
            if (hdr != null) {
                columns = hdr
                continue
            }

            val row = readRow(lineno, cells, columns)
            val rowErrs = row.errors.toMutableList()
            if (row.title.isEmpty()) rowErrs.add("第 $lineno 行：没写课程名")
            if (rowErrs.isNotEmpty()) {
                errors.addAll(rowErrs)
                continue
            }

            var weeks: List<Int> = emptyList()
            if (row.weeksText.isNotEmpty()) {
                val (parsedWeeks, werr) = parseWeeks(row.weeksText)
                if (werr != null) {
                    errors.add("第 $lineno 行：$werr")
                    continue
                }
                weeks = parsedWeeks
            }
            if (weeks.isEmpty()) {
                weeks = DEFAULT_WEEKS
                warnings.add("第 $lineno 行：没写周次，按 ${DEFAULT_WEEKS.first()}-${DEFAULT_WEEKS.last()} 周记")
            }

            val cid = courseId(row.title, row.teacher, row.location)
            if (row.pairs.isEmpty()) {
                courses.getOrPut(cid) {
                    courseShell(cid, row.title, "", "", row.teacher)
                }
                unarranged[cid] = JSONObject()
                    .put("id", cid)
                    .put("title", row.title)
                    .put("class_name", "")
                    .put("teachers", JSONArray(if (row.teacher.isNotEmpty()) listOf(row.teacher) else emptyList<String>()))
                warnings.add("第 $lineno 行：没有时间段，先放进「没排时间」里")
                continue
            }

            val course = courses.getOrPut(cid) {
                courseShell(cid, row.title, "", "", row.teacher)
            }
            val meetings = course.getJSONArray("meetings")
            for ((weekday, periods) in row.pairs) {
                var merged = false
                for (i in 0 until meetings.length()) {
                    val existing = meetings.getJSONObject(i)
                    if (existing.optInt("weekday") == weekday && jsonIntList(existing.optJSONArray("periods")) == periods) {
                        val combined = (jsonIntList(existing.optJSONArray("weeks")) + weeks).distinct().sorted()
                        existing.put("weeks", JSONArray(combined))
                        merged = true
                        break
                    }
                }
                if (!merged) {
                    meetings.put(
                        JSONObject()
                            .put("id", meetingId(cid, weekday, periods))
                            .put("course_id", cid)
                            .put("title", row.title)
                            .put("class_name", "")
                            .put("weekday", weekday)
                            .put("weeks", JSONArray(weeks))
                            .put("periods", JSONArray(periods))
                            .put("teacher", row.teacher)
                            .put("location", row.location)
                            .put("raw_time", row.rawTime)
                            .put("notes", "")
                            .put("online_info", ""),
                    )
                }
            }
        }

        val orderedCourses = courses.values.sortedWith(courseComparator())
        if (orderedCourses.isEmpty() && unarranged.isEmpty() && errors.isEmpty()) {
            errors.add("没有解析出任何课程。一行的写法是「课程名 | 周一 1-2节 | 1-16周 | 教师 | 地点」，竖线或制表符分隔都行。")
        }

        val orderedUnarranged = unarranged.values.sortedBy { it.optString("title") }
        val allWeeks = orderedCourses.flatMap { c ->
            val ms = c.optJSONArray("meetings") ?: JSONArray()
            (0 until ms.length()).flatMap { jsonIntList(ms.getJSONObject(it).optJSONArray("weeks")) }
        }
        val meetingCount = orderedCourses.sumOf { it.optJSONArray("meetings")?.length() ?: 0 }

        return JSONObject()
            .put("ok", errors.isEmpty() && (orderedCourses.isNotEmpty() || orderedUnarranged.isNotEmpty()))
            .put("semester", semester)
            .put("courses", JSONArray(orderedCourses))
            .put("unarranged", JSONArray(orderedUnarranged))
            .put("errors", JSONArray(errors))
            .put("warnings", JSONArray(warnings))
            .put(
                "stats",
                JSONObject()
                    .put("courses", orderedCourses.size)
                    .put("meetings", meetingCount)
                    .put(
                        "weeks",
                        if (allWeeks.isNotEmpty()) JSONArray(listOf(allWeeks.min(), allWeeks.max()))
                        else JSONArray(listOf(0, 0)),
                    ),
            )
    }

    /** Parses pasted text (pipe/tab lines or Excel table copy) into an ImportPreview JSON. */
    fun parsePaste(text: String, semester: String): JSONObject {
        val body = text.removePrefix("\uFEFF")
        val rows = mutableListOf<Pair<Int, List<String>>>()
        val lines = splitLines(body)
        for ((idx, line) in lines.withIndex()) {
            val lineno = idx + 1
            if (line.isBlank()) {
                rows.add(lineno to emptyList())
                continue
            }
            val cells = when {
                '\t' in line -> line.split('\t')
                '|' in line || '｜' in line -> line.split(PIPE_SPLIT_RE)
                else -> listOf(line)
            }
            rows.add(lineno to cells)
        }
        return parseRows(rows, semester)
    }

    /** Parses CSV (or TSV) content with RFC 4180 quote handling into an ImportPreview JSON. */
    fun parseCsv(text: String, semester: String): JSONObject {
        val body = text.removePrefix("\uFEFF")
        val firstNonBlank = splitLines(body).firstOrNull { it.isNotBlank() } ?: ""
        val delimiter = if ('\t' in firstNonBlank && ',' !in firstNonBlank) '\t' else ','
        val parsed = try {
            readCsvRows(body, delimiter)
        } catch (_: Exception) {
            return emptyPreview(semester, "CSV 读不出来，另存成「CSV UTF-8」再试一次")
        }
        return parseRows(parsed, semester)
    }

    /** Unified parse entry matching backend `parse_body` (`format = "paste"` or `"csv"`). */
    fun parseText(text: String, format: String = "paste", semester: String): JSONObject {
        val sem = norm(semester)
        if (text.isBlank()) {
            return emptyPreview(sem, "没有内容，先把课表粘进来或者选一个文件")
        }
        return if (norm(format).lowercase(Locale.ROOT) == "csv") {
            parseCsv(text, sem)
        } else {
            parsePaste(text, sem)
        }
    }

    /**
     * Generates the standard UTF-8 BOM + CRLF CSV template using 100% fictional placeholder
     * courses (no real student/course data).
     */
    fun templateCsv(): String {
        val lines = listOf(
            "课程名称,星期,节次,周次,教师,地点",
            "前端工程实践,四,6-9,1-16,沈清扬,实验楼-数字媒体室508",
            "高等数学,一,1-2,1-16,林致远,明理楼-A201",
            "大学英语,二,3-4,1-16,陈思宁,博雅楼-B108",
        )
        return "\uFEFF" + lines.joinToString("\r\n") + "\r\n"
    }

    /** Converts a list/JSONArray of courses into a standard `AcademicTimetable`-compatible doc. */
    fun toTimetableDoc(
        courses: JSONArray,
        semester: String,
        campus: String = "manual",
        checkedAt: String? = Instant.now().toString(),
        extraUnarranged: JSONArray? = null,
        pending: JSONArray? = null,
    ): JSONObject {
        val placed = JSONArray()
        val unplaced = JSONArray()
        val seenUnplaced = linkedSetOf<String>()

        for (i in 0 until courses.length()) {
            val row = courses.optJSONObject(i) ?: continue
            val rawMeetings = row.optJSONArray("meetings")
            val validMeetings = JSONArray()
            if (rawMeetings != null) {
                for (j in 0 until rawMeetings.length()) {
                    val m = rawMeetings.optJSONObject(j) ?: continue
                    if (meetingOk(m)) validMeetings.put(JSONObject(m.toString()))
                }
            }
            if (validMeetings.length() == 0) {
                val title = norm(row.opt("title"))
                if (title.isEmpty()) continue
                val cid = norm(row.opt("id")).ifEmpty { courseId(title, "", "") }
                if (seenUnplaced.add(title)) {
                    unplaced.put(
                        JSONObject()
                            .put("id", cid)
                            .put("title", title)
                            .put("class_name", norm(row.opt("class_name")).ifEmpty { norm(row.opt("className")) })
                            .put("teachers", extractTeachersArray(row)),
                    )
                }
                continue
            }
            val copy = JSONObject(row.toString())
            copy.put("meetings", validMeetings)
            placed.put(copy)
        }

        if (extraUnarranged != null) {
            for (i in 0 until extraUnarranged.length()) {
                val u = extraUnarranged.optJSONObject(i) ?: continue
                val title = norm(u.opt("title"))
                if (title.isEmpty() || !seenUnplaced.add(title)) continue
                unplaced.put(
                    JSONObject()
                        .put("id", norm(u.opt("id")).ifEmpty { courseId(title, "", "") })
                        .put("title", title)
                        .put("class_name", norm(u.opt("class_name")).ifEmpty { norm(u.opt("className")) })
                        .put("teachers", extractTeachersArray(u)),
                )
            }
        }

        val pendingArr = pending ?: JSONArray()
        val isPartial = unplaced.length() > 0 || pendingArr.length() > 0
        return JSONObject()
            .put("semester", norm(semester))
            .put("campus", campus)
            .put("status", if (isPartial) "partial" else "ok")
            .put("checked_at", checkedAt ?: JSONObject.NULL)
            .put("courses", placed)
            .put("unarranged", unplaced)
            .put("unplaced_details", JSONArray())
            .put("pending", pendingArr)
            .put(
                "coverage",
                JSONObject()
                    .put("kind", "theory")
                    .put("weeks", "all")
                    .put("complete", true)
                    .put("source", if (campus == "med") "med" else "manual"),
            )
    }

    /**
     * Validates and canonicalizes a timetable JSON document into snake_case standard form.
     * Returns `{ ok, errors, warnings, doc, stats }`.
     */
    fun validateDoc(doc: JSONObject?, campus: String? = null): JSONObject {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (doc == null) {
            return JSONObject()
                .put("ok", false)
                .put("errors", JSONArray(listOf("课表格式不对，应该是一份 JSON 文档")))
                .put("warnings", JSONArray())
                .put("doc", JSONObject())
                .put("stats", emptyStats())
        }

        val semester = norm(doc.opt("semester"))
        if (semester.isEmpty()) {
            errors.add("缺学期（semester），比如 2026-2027-1")
        }

        val rawCourses = doc.optJSONArray("courses")
        val rawUnarranged = doc.optJSONArray("unarranged")
        if ((rawCourses == null || rawCourses.length() == 0) &&
            (rawUnarranged == null || rawUnarranged.length() == 0)
        ) {
            errors.add("这份课表里没有课程")
        }

        val canonicalCourses = JSONArray()
        if (rawCourses != null) {
            for (index in 0 until rawCourses.length()) {
                val row = rawCourses.optJSONObject(index) ?: JSONObject()
                val canonical = canonicalizeCourse(row, index + 1, errors, warnings)
                if (canonical != null) canonicalCourses.put(canonical)
            }
        }

        val resolvedCampus = campus ?: norm(doc.opt("campus")).ifEmpty { "manual" }
        val resultDoc = toTimetableDoc(
            courses = canonicalCourses,
            semester = semester,
            campus = resolvedCampus,
            checkedAt = norm(doc.opt("checked_at")).ifEmpty { Instant.now().toString() },
            extraUnarranged = rawUnarranged,
            pending = doc.optJSONArray("pending"),
        )

        return JSONObject()
            .put("ok", errors.isEmpty())
            .put("errors", JSONArray(errors))
            .put("warnings", JSONArray(warnings))
            .put("doc", resultDoc)
            .put("stats", computeStats(resultDoc))
    }

    /**
     * Validates a single course JSON object and merges it into an existing timetable document
     * (or creates a new document if [existingDoc] is null).
     * Returns `{ ok, errors, warnings, doc, stats }`.
     */
    fun validateAndMergeCourse(
        existingDoc: JSONObject?,
        course: JSONObject,
        semester: String,
        campus: String = "manual",
    ): JSONObject {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val sem = norm(semester).ifEmpty { norm(existingDoc?.opt("semester")) }
        if (sem.isEmpty()) {
            errors.add("缺学期（semester），比如 2026-2027-1")
        }
        val validated = canonicalizeCourse(course, 1, errors, warnings)
        if (errors.isNotEmpty() || validated == null) {
            return JSONObject()
                .put("ok", false)
                .put("errors", JSONArray(errors))
                .put("warnings", JSONArray(warnings))
                .put("doc", existingDoc ?: JSONObject())
                .put("stats", computeStats(existingDoc))
        }

        val mergedCourses = mutableListOf<JSONObject>()
        val existingArr = existingDoc?.optJSONArray("courses")
        if (existingArr != null) {
            for (i in 0 until existingArr.length()) {
                existingArr.optJSONObject(i)?.let { mergedCourses.add(JSONObject(it.toString())) }
            }
        }

        val newId = validated.optString("id")
        val existingMatch = mergedCourses.firstOrNull { it.optString("id") == newId }
        if (existingMatch != null) {
            val targetMeetings = existingMatch.optJSONArray("meetings") ?: JSONArray().also {
                existingMatch.put("meetings", it)
            }
            val incomingMeetings = validated.optJSONArray("meetings") ?: JSONArray()
            for (i in 0 until incomingMeetings.length()) {
                val inc = incomingMeetings.getJSONObject(i)
                val incWd = inc.optInt("weekday")
                val incPeriods = jsonIntList(inc.optJSONArray("periods"))
                val incLoc = norm(inc.opt("location"))
                val incWeeks = jsonIntList(inc.optJSONArray("weeks"))
                var combined = false
                for (j in 0 until targetMeetings.length()) {
                    val cur = targetMeetings.getJSONObject(j)
                    if (cur.optInt("weekday") == incWd &&
                        jsonIntList(cur.optJSONArray("periods")) == incPeriods &&
                        norm(cur.opt("location")) == incLoc
                    ) {
                        val mergedWeeks = (jsonIntList(cur.optJSONArray("weeks")) + incWeeks).distinct().sorted()
                        cur.put("weeks", JSONArray(mergedWeeks))
                        combined = true
                        break
                    }
                }
                if (!combined) targetMeetings.put(inc)
            }
        } else {
            mergedCourses.add(validated)
        }

        val ordered = mergedCourses.sortedWith(courseComparator())
        val resolvedCampus = norm(existingDoc?.opt("campus")).ifEmpty { campus }
        val doc = toTimetableDoc(
            courses = JSONArray(ordered),
            semester = sem,
            campus = resolvedCampus,
            checkedAt = Instant.now().toString(),
            extraUnarranged = existingDoc?.optJSONArray("unarranged"),
            pending = existingDoc?.optJSONArray("pending"),
        )
        return JSONObject()
            .put("ok", true)
            .put("errors", JSONArray())
            .put("warnings", JSONArray(warnings))
            .put("doc", doc)
            .put("stats", computeStats(doc))
    }

    private fun canonicalizeCourse(
        row: JSONObject,
        position: Int,
        errors: MutableList<String>,
        warnings: MutableList<String>,
    ): JSONObject? {
        val title = norm(row.opt("title"))
        if (title.isEmpty()) {
            errors.add("第 $position 门课：没写课程名")
            return null
        }
        val teacher = norm(row.opt("teacher"))
        val teachers = extractTeachersList(row)
        val rawMeetings = row.optJSONArray("meetings")
        var firstPlace = ""
        if (rawMeetings != null) {
            for (i in 0 until rawMeetings.length()) {
                val loc = norm(rawMeetings.optJSONObject(i)?.opt("location"))
                if (loc.isNotEmpty()) {
                    firstPlace = loc
                    break
                }
            }
        }
        val cid = norm(row.opt("id")).ifEmpty { courseId(title, teacher, firstPlace) }
        val courseCode = norm(row.opt("course_code")).ifEmpty { norm(row.opt("code")) }
        val className = norm(row.opt("class_name")).ifEmpty { norm(row.opt("className")) }
        val course = JSONObject()
            .put("id", cid)
            .put("title", title)
            .put("course_code", courseCode)
            .put("class_name", className)
            .put("teachers", JSONArray(teachers))
            .put("teacher", teacher.ifEmpty { teachers.joinToString("、") })
            .put("meetings", JSONArray())
            .put("hue", hueFor(cid))

        val label = "第 $position 门课「$title」"
        if (rawMeetings != null) {
            for (i in 0 until rawMeetings.length()) {
                val m = rawMeetings.optJSONObject(i) ?: JSONObject()
                val canonical = canonicalMeeting(m, course, i + 1, errors, label) ?: continue
                if (canonical.getJSONArray("weeks").length() == 0) {
                    canonical.put("weeks", JSONArray(DEFAULT_WEEKS))
                    warnings.add("${label}第 ${i + 1} 个时间段：没写周次，按 ${DEFAULT_WEEKS.first()}-${DEFAULT_WEEKS.last()} 周记")
                }
                course.getJSONArray("meetings").put(canonical)
            }
        }
        return course
    }

    private fun canonicalMeeting(
        raw: JSONObject,
        course: JSONObject,
        index: Int,
        errors: MutableList<String>,
        label: String,
    ): JSONObject? {
        val weekday = norm(raw.opt("weekday")).toIntOrNull() ?: 0
        if (weekday !in 1..7) {
            errors.add("${label}第 $index 个时间段：星期只能是 1-7（一=1 … 日=7）")
            return null
        }
        val periods = jsonIntList(raw.optJSONArray("periods")).filter { it in 1..MAX_PERIOD }.distinct().sorted()
        if (periods.isEmpty()) {
            errors.add("${label}第 $index 个时间段：节次要在 1-$MAX_PERIOD 之间")
            return null
        }
        val weeks = jsonIntList(raw.optJSONArray("weeks")).filter { it in 1..MAX_WEEK }.distinct().sorted()
        val cid = course.optString("id")
        return JSONObject()
            .put("id", norm(raw.opt("id")).ifEmpty { meetingId(cid, weekday, periods) })
            .put("course_id", cid)
            .put("title", norm(course.opt("title")))
            .put("class_name", norm(course.opt("class_name")))
            .put("weekday", weekday)
            .put("weeks", JSONArray(weeks))
            .put("periods", JSONArray(periods))
            .put("teacher", norm(raw.opt("teacher")).ifEmpty { norm(course.opt("teacher")) })
            .put("location", norm(raw.opt("location")))
            .put("raw_time", norm(raw.opt("raw_time")).ifEmpty { norm(raw.opt("rawTime")) })
            .put("notes", norm(raw.opt("notes")))
            .put("online_info", norm(raw.opt("online_info")).ifEmpty { norm(raw.opt("onlineInfo")) })
    }

    private fun courseShell(cid: String, title: String, code: String, className: String, teacher: String): JSONObject =
        JSONObject()
            .put("id", cid)
            .put("title", title)
            .put("course_code", code)
            .put("class_name", className)
            .put("teachers", JSONArray(if (teacher.isNotEmpty()) listOf(teacher) else emptyList<String>()))
            .put("teacher", teacher)
            .put("meetings", JSONArray())
            .put("hue", hueFor(cid))

    private fun meetingOk(m: JSONObject): Boolean {
        val wd = norm(m.opt("weekday")).toIntOrNull() ?: return false
        val periods = m.optJSONArray("periods") ?: return false
        return wd in 1..7 && periods.length() > 0
    }

    private fun extractTeachersList(row: JSONObject): List<String> {
        val arr = row.optJSONArray("teachers")
        if (arr != null) {
            return (0 until arr.length()).map { norm(arr.opt(it)) }.filter { it.isNotEmpty() }.distinct()
        }
        val single = norm(row.opt("teacher"))
        return if (single.isNotEmpty()) listOf(single) else emptyList()
    }

    private fun extractTeachersArray(row: JSONObject): JSONArray = JSONArray(extractTeachersList(row))

    private fun courseComparator(): Comparator<JSONObject> = Comparator { a, b ->
        val slotA = firstSlot(a)
        val slotB = firstSlot(b)
        val cmpDay = slotA.first.compareTo(slotB.first)
        if (cmpDay != 0) return@Comparator cmpDay
        val cmpPer = slotA.second.compareTo(slotB.second)
        if (cmpPer != 0) return@Comparator cmpPer
        a.optString("title").compareTo(b.optString("title"))
    }

    private fun firstSlot(course: JSONObject): Pair<Int, Int> {
        val meetings = course.optJSONArray("meetings") ?: return 9 to 99
        var bestDay = 9
        var bestPeriod = 99
        for (i in 0 until meetings.length()) {
            val m = meetings.optJSONObject(i) ?: continue
            val ps = jsonIntList(m.optJSONArray("periods"))
            if (ps.isEmpty()) continue
            val wd = m.optInt("weekday", 9)
            val p0 = ps.first()
            if (wd < bestDay || (wd == bestDay && p0 < bestPeriod)) {
                bestDay = wd
                bestPeriod = p0
            }
        }
        return bestDay to bestPeriod
    }

    private fun jsonIntList(arr: JSONArray?): List<Int> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Int>()
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            when (v) {
                is Number -> out.add(v.toInt())
                is String -> v.trim().toIntOrNull()?.let { out.add(it) }
            }
        }
        return out
    }

    private fun computeStats(doc: JSONObject?): JSONObject {
        if (doc == null) return emptyStats()
        val courses = doc.optJSONArray("courses") ?: JSONArray()
        var meetingCount = 0
        val weeks = mutableListOf<Int>()
        for (i in 0 until courses.length()) {
            val c = courses.optJSONObject(i) ?: continue
            val ms = c.optJSONArray("meetings") ?: continue
            meetingCount += ms.length()
            for (j in 0 until ms.length()) {
                val m = ms.optJSONObject(j) ?: continue
                weeks.addAll(jsonIntList(m.optJSONArray("weeks")))
            }
        }
        return JSONObject()
            .put("courses", courses.length())
            .put("meetings", meetingCount)
            .put(
                "weeks",
                if (weeks.isNotEmpty()) JSONArray(listOf(weeks.min(), weeks.max()))
                else JSONArray(listOf(0, 0)),
            )
    }

    private fun emptyStats(): JSONObject = JSONObject()
        .put("courses", 0)
        .put("meetings", 0)
        .put("weeks", JSONArray(listOf(0, 0)))

    private fun emptyPreview(semester: String, message: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("semester", semester)
        .put("courses", JSONArray())
        .put("unarranged", JSONArray())
        .put("errors", JSONArray(listOf(message)))
        .put("warnings", JSONArray())
        .put("stats", emptyStats())

    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        return if (lines.isNotEmpty() && lines.last().isEmpty() && normalized.endsWith('\n')) {
            lines.dropLast(1)
        } else {
            lines
        }
    }

    /** RFC 4180 CSV/TSV parser preserving 1-based record line numbers. */
    private fun readCsvRows(input: String, delimiter: Char): List<Pair<Int, List<String>>> {
        val result = mutableListOf<Pair<Int, List<String>>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var lineNo = 1
        var rowStartLine = 1
        var i = 0
        val len = input.length

        while (i < len) {
            val ch = input[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < len && input[i + 1] == '"') {
                        field.append('"')
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                        i++
                        continue
                    }
                } else {
                    if (ch == '\r') {
                        if (i + 1 < len && input[i + 1] == '\n') i++
                        field.append('\n')
                        lineNo++
                    } else {
                        if (ch == '\n') lineNo++
                        field.append(ch)
                    }
                    i++
                    continue
                }
            }

            when (ch) {
                '"' -> {
                    if (field.isEmpty()) {
                        inQuotes = true
                    } else {
                        field.append(ch)
                    }
                    i++
                }
                delimiter -> {
                    row.add(field.toString())
                    field.setLength(0)
                    i++
                }
                '\r', '\n' -> {
                    if (ch == '\r' && i + 1 < len && input[i + 1] == '\n') i++
                    row.add(field.toString())
                    field.setLength(0)
                    result.add(rowStartLine to row)
                    row = mutableListOf()
                    lineNo++
                    rowStartLine = lineNo
                    i++
                }
                else -> {
                    field.append(ch)
                    i++
                }
            }
        }
        if (inQuotes) {
            throw IllegalArgumentException("Unterminated quoted CSV field")
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            result.add(rowStartLine to row)
        }
        return result
    }
}
