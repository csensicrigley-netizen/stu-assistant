package com.stuassistant.app.nativeapp

import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Ports of web/src/lib/{clock,calendar,sections,timetable,plan,records}. */
fun academicString(value: Any?): String = if (value == null || value === JSONObject.NULL) "" else value.toString()
fun academicObjects(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else
    (0 until array.length()).mapNotNull { array.optJSONObject(it) }
fun academicStrings(array: JSONArray?): List<String> = if (array == null) emptyList() else
    (0 until array.length()).map { academicString(array.opt(it)) }.filter { it.isNotEmpty() }
private fun academicNumber(value: Any?): Double? = academicString(value).takeIf { it.isNotBlank() }?.toDoubleOrNull()?.takeIf { it.isFinite() }

data class AcademicMoment(val ms: Long, val date: String, val weekday: Int, val minutes: Int)

object AcademicClock {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    @Volatile private var pinned: Long? = null
    @Volatile private var pinStarted = System.nanoTime()
    fun setPreviewTime(iso: String?) {
        pinStarted = System.nanoTime()
        pinned = iso?.let(::parseTime)
    }
    fun nowMillis(): Long = pinned?.let { it + (System.nanoTime() - pinStarted) / 1_000_000 } ?: System.currentTimeMillis()
    fun moment(ms: Long = nowMillis()): AcademicMoment {
        val time = Instant.ofEpochMilli(ms).atZone(zone)
        return AcademicMoment(ms, time.toLocalDate().toString(), time.dayOfWeek.value, time.hour * 60 + time.minute)
    }
    fun parseTime(raw: String): Long? = runCatching {
        var text = raw.trim().replace(Regex("^(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:)"), "$1T$2")
        text = text.replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")
        when {
            Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(text) -> LocalDate.parse(text).atStartOfDay(zone).toInstant().toEpochMilli()
            Regex("(?:[zZ]|[+-]\\d{2}:\\d{2})$").containsMatchIn(text) -> OffsetDateTime.parse(text.replace("z", "Z")).toInstant().toEpochMilli()
            else -> LocalDateTime.parse(text).atOffset(ZoneOffset.ofHours(8)).toInstant().toEpochMilli()
        }
    }.getOrNull()
    fun addDays(date: String, days: Int): String = LocalDate.parse(date).plusDays(days.toLong()).toString()
    fun dayStart(date: String): Long = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
    fun daysUntil(date: String, now: Long = nowMillis()): Int = ChronoUnit.DAYS.between(LocalDate.parse(moment(now).date), LocalDate.parse(date)).toInt()
    fun weekday(date: String): Int = LocalDate.parse(date).dayOfWeek.value
    fun weekdayText(weekday: Int): String = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").getOrElse(weekday - 1) { "周$weekday" }
    fun monthDay(date: String): String = runCatching { LocalDate.parse(date).let { "${it.monthValue}月${it.dayOfMonth}日" } }.getOrDefault(date)
    fun slash(date: String): String = runCatching { LocalDate.parse(date).let { "${it.monthValue}/${it.dayOfMonth}" } }.getOrDefault(date)
    fun clock(minutes: Int): String = String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)
    fun clockAt(ms: Long): String = clock(moment(ms).minutes)
    fun span(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        return when { m < 60 -> "$m 分"; m % 60 == 0 -> "${m / 60} 小时"; else -> "${m / 60} 小时 ${m % 60} 分" }
    }
    fun ago(ms: Long, now: Long = nowMillis()): String {
        val seconds = (now - ms) / 1000
        if (seconds < 60) return "刚刚"
        if (seconds < 3600) return "${seconds / 60} 分钟前"
        if (seconds < 86400) return "${seconds / 3600} 小时前"
        val days = -daysUntil(moment(ms).date, now)
        return when { days == 1 -> "昨天"; days < 7 -> "$days 天前"; else -> slash(moment(ms).date) }
    }
}

data class AcademicPhase(val from: Int, val to: Int, val kind: String, val label: String)
data class AcademicClosure(val from: String, val to: String, val kind: String, val label: String)
data class AcademicMakeup(val date: String, val note: String)
data class AcademicTerm(
    val id: String, val label: String, val week1Monday: String, val totalWeeks: Int,
    val phases: List<AcademicPhase>, val closures: List<AcademicClosure>, val makeupDays: List<AcademicMakeup>,
    val after: AcademicClosure,
)
data class AcademicPosition(val term: AcademicTerm, val week: Int, val phase: AcademicPhase?)
data class AcademicDayStatus(val kind: String, val label: String = "", val note: String = "", val closure: String = "")
data class AcademicMilestone(val label: String, val from: String, val to: String, val days: Int, val kind: String)

object AcademicCalendar {
    val sourceUrl = "https://jwc.stu.edu.cn/info/1080/3307.htm"
    val terms = listOf(
        AcademicTerm("2026-2027-1", "2026–2027 学年 第一学期", "2026-09-07", 20,
            listOf(AcademicPhase(1, 2, "practice", "实践周"), AcademicPhase(3, 18, "teaching", "教学周"), AcademicPhase(19, 20, "exam", "考试周")),
            listOf(AcademicClosure("2026-09-25", "2026-09-27", "holiday", "中秋节"), AcademicClosure("2026-10-01", "2026-10-07", "holiday", "国庆节"), AcademicClosure("2026-12-03", "2026-12-04", "suspended", "校运会停课")),
            listOf(AcademicMakeup("2026-09-20", "国家调休上班日，学校按哪天课表上课尚未公布"), AcademicMakeup("2026-10-10", "国家调休上班日，学校按哪天课表上课尚未公布")),
            AcademicClosure("2027-01-23", "2027-02-27", "break", "寒假")),
        AcademicTerm("2026-2027-2", "2026–2027 学年 第二学期", "2027-03-01", 18,
            listOf(AcademicPhase(1, 16, "teaching", "教学周"), AcademicPhase(17, 18, "exam", "考试周")), emptyList(), emptyList(),
            AcademicClosure("2027-07-03", "2027-08-28", "break", "暑假")),
    )
    fun position(date: String = AcademicClock.moment().date): AcademicPosition? {
        val day = LocalDate.parse(date)
        for (term in terms) {
            val week = Math.floorDiv(ChronoUnit.DAYS.between(LocalDate.parse(term.week1Monday), day).toInt(), 7) + 1
            if (week in 1..term.totalWeeks) return AcademicPosition(term, week, phase(term, week))
        }
        return null
    }
    fun currentOrNext(date: String): AcademicTerm = position(date)?.term ?: terms.firstOrNull { it.week1Monday > date } ?: terms.last()
    fun monday(term: AcademicTerm, week: Int): String = AcademicClock.addDays(term.week1Monday, (week - 1) * 7)
    fun dateOf(term: AcademicTerm, week: Int, weekday: Int): String = AcademicClock.addDays(term.week1Monday, (week - 1) * 7 + weekday - 1)
    fun phase(term: AcademicTerm, week: Int): AcademicPhase? = term.phases.firstOrNull { week in it.from..it.to }
    fun status(date: String): AcademicDayStatus {
        val pos = position(date) ?: return AcademicDayStatus("outside")
        pos.term.closures.firstOrNull { date >= it.from && date <= it.to }?.let { return AcademicDayStatus("closed", it.label, closure = it.kind) }
        pos.term.makeupDays.firstOrNull { it.date == date }?.let { return AcademicDayStatus("makeup", note = it.note) }
        return AcademicDayStatus("normal")
    }
    fun milestones(date: String = AcademicClock.moment().date, limit: Int = 4): List<AcademicMilestone> = buildList {
        for (term in terms) {
            for (closure in term.closures) if (closure.to >= date) add(AcademicMilestone(closure.label, closure.from, closure.to, dayDistance(date, closure.from), closure.kind))
            for (phase in term.phases.filter { it.kind == "exam" }) {
                val from = monday(term, phase.from)
                val to = AcademicClock.addDays(monday(term, phase.to), 4)
                if (to >= date) add(AcademicMilestone("${if (term.id.endsWith("1")) "秋季" else "春季"}考试周", from, to, dayDistance(date, from), "exam"))
            }
            if (term.after.to >= date) add(AcademicMilestone(term.after.label, term.after.from, term.after.to, dayDistance(date, term.after.from), "break"))
        }
    }.sortedBy { it.days }.take(limit)
    private fun dayDistance(a: String, b: String) = ChronoUnit.DAYS.between(LocalDate.parse(a), LocalDate.parse(b)).toInt()
    fun progress(term: AcademicTerm, now: Long = AcademicClock.nowMillis()): Float {
        val start = AcademicClock.dayStart(term.week1Monday)
        val end = AcademicClock.dayStart(AcademicClock.addDays(term.week1Monday, term.totalWeeks * 7))
        return ((now - start).toDouble() / (end - start)).toFloat().coerceIn(0f, 1f)
    }
}

data class AcademicPeriod(val index: Int, val code: String, val big: Int, val startMin: Int, val endMin: Int) {
    val start get() = AcademicClock.clock(startMin)
    val end get() = AcademicClock.clock(endMin)
    val inferred get() = index >= 10
}
data class AcademicMeeting(val id: String, val weekday: Int, val weeks: List<Int>, val periods: List<Int>, val teacher: String, val location: String, val rawTime: String, val notes: String, val onlineInfo: String, val title: String = "", val className: String = "")
data class AcademicCourse(val id: String, val title: String, val code: String, val className: String, val teacher: String, val meetings: List<AcademicMeeting>, val hue: Int)
data class AcademicUnarranged(val id: String, val title: String, val className: String, val teachers: List<String>)
data class AcademicTimetableDoc(val semester: String, val checkedAt: String?, val status: String, val courses: List<AcademicCourse>, val unarranged: List<AcademicUnarranged>, val pending: List<String>, val complete: Boolean)
data class AcademicBlock(val key: String, val course: AcademicCourse, val meetings: List<AcademicMeeting>, val weekday: Int, val periods: List<Int>, val startMin: Int, val endMin: Int, val location: String, val teacher: String, val inferred: Boolean, var conflict: Boolean = false) {
    val start get() = AcademicClock.clock(startMin)
    val end get() = AcademicClock.clock(endMin)
    val span get() = endMin - startMin
}
data class AcademicGap(val startMin: Int, val endMin: Int, val label: String) {
    val start get() = AcademicClock.clock(startMin)
    val end get() = AcademicClock.clock(endMin)
    val span get() = endMin - startMin
}
data class AcademicHop(val from: String, val to: String, val window: Int)
data class AcademicDayPlan(val date: String, val weekday: Int, val week: Int?, val status: AcademicDayStatus, val blocks: List<AcademicBlock>)
data class AcademicNextClass(val date: String, val offset: Int, val block: AcademicBlock, val plan: AcademicDayPlan)
data class AcademicSlot(val weekday: Int, val periods: List<Int>, val location: String, val teacher: String, val weeks: List<Int>, val rawTimes: List<String>, val notes: List<String>, val online: List<String>)

object AcademicTimetable {
    val periods = listOf(
        AcademicPeriod(1, "1", 1, 480, 525), AcademicPeriod(2, "2", 1, 535, 580),
        AcademicPeriod(3, "3", 2, 600, 645), AcademicPeriod(4, "4", 2, 655, 700), AcademicPeriod(5, "5", 2, 710, 755),
        AcademicPeriod(6, "6", 3, 840, 885), AcademicPeriod(7, "7", 3, 895, 940),
        AcademicPeriod(8, "8", 4, 960, 1005), AcademicPeriod(9, "9", 4, 1015, 1060), AcademicPeriod(10, "0", 4, 1070, 1115),
        AcademicPeriod(11, "A", 5, 1160, 1205), AcademicPeriod(12, "B", 5, 1215, 1260), AcademicPeriod(13, "C", 5, 1270, 1315),
    )
    val periodByIndex = periods.associateBy { it.index }
    const val gridStart = 480
    const val gridEnd = 1315
    private val hues = listOf(250, 145, 312, 215, 282, 180, 340)
    fun known(values: List<Int>): List<Int> = values.filter { periodByIndex.containsKey(it) }.distinct().sorted()
    fun parseWeeks(value: Any?): List<Int> {
        if (value is JSONArray) return (0 until value.length()).mapNotNull { academicNumber(value.opt(it))?.takeIf { n -> n % 1 == 0.0 && n > 0 }?.toInt() }.distinct().sorted()
        val clean = academicString(value).replace(Regex("[（(].*?[)）]"), "")
        return clean.split(Regex("[,，、]")).flatMap { part ->
            val match = Regex("^(\\d+)(?:\\s*[-–~]\\s*(\\d+))?$").matchEntire(part.trim()) ?: return@flatMap emptyList()
            val a = match.groupValues[1].toIntOrNull() ?: return@flatMap emptyList()
            val b = match.groupValues[2].toIntOrNull() ?: a
            (minOf(a, b)..maxOf(a, b)).asSequence().takeWhile { it.toLong() - a < 60 }.take(120).toList()
        }.distinct().sorted()
    }
    private fun huesFor(courses: List<JSONObject>): Map<String, Int> {
        val days = linkedMapOf<String, MutableSet<Int>>()
        val load = mutableMapOf<String, Int>()
        courses.forEach { course ->
            val title = academicString(course.opt("title"))
            val meetings = academicObjects(course.optJSONArray("meetings"))
            days.getOrPut(title) { linkedSetOf() }.addAll(meetings.map { it.optInt("weekday") }.filter { it in 1..7 })
            load[title] = (load[title] ?: 0) + meetings.size
        }
        val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
        val titles = days.keys.sortedWith(compareByDescending<String> { days[it]?.size ?: 0 }.thenByDescending { load[it] ?: 0 }.thenComparator { a, b -> collator.compare(a, b) })
        val chosen = linkedMapOf<String, Int>()
        val uses = mutableMapOf<Int, Int>()
        titles.forEach { title ->
            val neighbours = chosen.keys.filter { other -> days[other].orEmpty().intersect(days[title].orEmpty()).isNotEmpty() }
            val best = hues.withIndex().maxBy { (i, hue) ->
                val gap = neighbours.minOfOrNull { other -> val d = abs(hue - chosen.getValue(other)) % 360; minOf(d, 360 - d) } ?: 180
                gap * 100 - (uses[hue] ?: 0) * 10 - i
            }.value
            chosen[title] = best
            uses[best] = (uses[best] ?: 0) + 1
        }
        return chosen
    }
    fun normalize(raw: JSONObject?): AcademicTimetableDoc? {
        if (raw == null) return null
        val rows = academicObjects(raw.optJSONArray("courses"))
        val assigned = huesFor(rows)
        val courses = rows.mapIndexed { ci, course ->
            val id = academicString(course.opt("id")).ifEmpty { "course-$ci" }
            val title = academicString(course.opt("title")).ifEmpty { "未命名课程" }
            val teacher = academicString(course.opt("teacher")).ifEmpty { academicStrings(course.optJSONArray("teachers")).joinToString("、") }
            val meetings = academicObjects(course.optJSONArray("meetings")).mapIndexed { mi, row ->
                val rawPeriods = row.optJSONArray("periods")
                AcademicMeeting(academicString(row.opt("id")).ifEmpty { "$id-m$mi" }, row.optInt("weekday"), parseWeeks(row.opt("weeks")),
                    known(if (rawPeriods == null) emptyList() else (0 until rawPeriods.length()).mapNotNull { academicNumber(rawPeriods.opt(it))?.takeIf { n -> n % 1 == 0.0 }?.toInt() }),
                    academicString(row.opt("teacher")).ifEmpty { teacher }, academicString(row.opt("location")).ifEmpty { listOf(academicString(row.opt("building")), academicString(row.opt("room"))).filter { it.isNotEmpty() }.joinToString(" ") },
                    academicString(row.opt("raw_time")), academicString(row.opt("notes")), academicString(row.opt("online_info")),
                    academicString(row.opt("title")).ifEmpty { title }, academicString(row.opt("class_name")).ifEmpty { academicString(course.opt("class_name")) })
            }.filter { it.weekday in 1..7 && it.periods.isNotEmpty() }
            AcademicCourse(id, title, academicString(course.opt("course_code")), academicString(course.opt("class_name")), teacher, meetings, assigned[title] ?: hues[0])
        }
        val unarranged = academicObjects(raw.optJSONArray("unarranged")).mapIndexed { i, row -> AcademicUnarranged(academicString(row.opt("id")).ifEmpty { "un-$i" }, academicString(row.opt("title")), academicString(row.opt("class_name")), academicStrings(row.optJSONArray("teachers"))) }
        val pending = academicStrings(raw.optJSONArray("pending"))
        return AcademicTimetableDoc(academicString(raw.opt("semester")), academicString(raw.opt("checked_at")).ifEmpty { null }, if (raw.optString("status") == "partial" || pending.isNotEmpty() || unarranged.isNotEmpty()) "partial" else "ok", courses, unarranged, pending, raw.optJSONObject("coverage")?.optBoolean("complete", true) != false)
    }
    private fun joinable(a: Int, b: Int): Boolean = b == a + 1 && periodByIndex.getValue(b).startMin - periodByIndex.getValue(a).endMin <= 20
    fun blocksOn(doc: AcademicTimetableDoc?, week: Int, weekday: Int): List<AcademicBlock> {
        val blocks = mutableListOf<AcademicBlock>()
        doc?.courses?.forEach { course ->
            course.meetings.filter { it.weekday == weekday && week in it.weeks }.groupBy { it.location }.values.forEach { group ->
                val runs = mutableListOf<MutableList<Int>>()
                known(group.flatMap { it.periods }).forEach { p -> if (runs.isNotEmpty() && joinable(runs.last().last(), p)) runs.last().add(p) else runs.add(mutableListOf(p)) }
                runs.forEach { run ->
                    val owners = group.filter { m -> m.periods.any { it in run } }
                    blocks += AcademicBlock("${course.id}|$weekday|${run.joinToString(",")}", course, owners, weekday, run, periodByIndex.getValue(run.first()).startMin, periodByIndex.getValue(run.last()).endMin,
                        owners.map { it.location }.filter { it.isNotEmpty() }.distinct().joinToString(" / "), owners.map { it.teacher }.filter { it.isNotEmpty() }.distinct().joinToString("、"), run.any { it >= 10 })
                }
            }
        }
        blocks.sortWith(compareBy<AcademicBlock> { it.startMin }.thenBy { it.endMin })
        for (i in blocks.indices) for (j in i + 1 until blocks.size) if (blocks[j].startMin < blocks[i].endMin) { blocks[i].conflict = true; blocks[j].conflict = true }
        return blocks
    }
    fun planFor(doc: AcademicTimetableDoc?, date: String): AcademicDayPlan {
        val pos = AcademicCalendar.position(date)
        val status = AcademicCalendar.status(date)
        val blocks = if (pos == null || status.kind == "closed" || (doc != null && doc.semester.isNotEmpty() && doc.semester != pos.term.id)) emptyList() else blocksOn(doc, pos.week, AcademicClock.weekday(date))
        return AcademicDayPlan(date, AcademicClock.weekday(date), pos?.week, status, blocks)
    }
    fun nextClass(doc: AcademicTimetableDoc?, date: String, afterMin: Int, days: Int = 14): AcademicNextClass? {
        for (i in 0..days) {
            val at = AcademicClock.addDays(date, i)
            val plan = planFor(doc, at)
            val block = plan.blocks.firstOrNull { i > 0 || it.startMin > afterMin }
            if (block != null) return AcademicNextClass(at, i, block, plan)
        }
        return null
    }
    fun gaps(blocks: List<AcademicBlock>, minimum: Int = 40): List<AcademicGap> {
        if (blocks.isEmpty()) return emptyList()
        val out = mutableListOf<AcademicGap>()
        var cursor = gridStart
        fun add(start: Int, end: Int) {
            val middle = (start + end) / 2.0
            val label = when { middle < 720 -> "上午空闲"; middle < 840 -> "午间"; middle < 1080 -> "下午空闲"; middle < 1170 -> "傍晚"; else -> "晚上空闲" }
            if (end - start >= minimum) out += AcademicGap(start, end, label)
        }
        blocks.forEach { add(cursor, it.startMin); cursor = maxOf(cursor, it.endMin) }
        add(cursor, gridEnd)
        return out
    }
    fun minutes(blocks: List<AcademicBlock>) = blocks.sumOf { it.span }
    fun hops(blocks: List<AcademicBlock>): List<AcademicHop> = blocks.zipWithNext().filter { (a, b) -> a.location.isNotEmpty() && b.location.isNotEmpty() && a.location != b.location }.map { (a, b) -> AcademicHop(a.location, b.location, b.startMin - a.endMin) }
    fun slots(course: AcademicCourse): List<AcademicSlot> = course.meetings.groupBy { "${it.weekday}|${it.location}|${it.teacher}|${it.weeks.joinToString(",")}" }.values.map { group ->
        val first = group.first()
        AcademicSlot(first.weekday, known(group.flatMap { it.periods }), first.location, first.teacher, first.weeks, group.map { it.rawTime }.filter { it.isNotEmpty() }.distinct(), group.map { it.notes }.filter { it.isNotEmpty() }.distinct(), group.map { it.onlineInfo }.filter { it.isNotEmpty() }.distinct())
    }.sortedWith(compareBy<AcademicSlot> { it.weekday }.thenBy { it.periods.first() })
    fun progress(course: AcademicCourse, week: Int, weekday: Int, minute: Int): Pair<Int, Int> {
        val all = mutableSetOf<String>(); val done = mutableSetOf<String>()
        course.meetings.forEach { m -> m.weeks.forEach { w ->
            val key = "$w|${m.weekday}"; all += key
            if (w < week || (w == week && (m.weekday < weekday || (m.weekday == weekday && periodByIndex.getValue(m.periods.last()).endMin <= minute)))) done += key
        } }
        return done.size to all.size
    }
    fun weeksText(weeks: List<Int>): String {
        if (weeks.isEmpty()) return "周次未标注"
        if (weeks.size > 2 && weeks.zipWithNext().all { (a, b) -> b - a == 2 }) return "${weeks.first()}–${weeks.last()} ${if (weeks.first() % 2 != 0) "单" else "双"}周"
        val runs = mutableListOf<Pair<Int, Int>>()
        weeks.forEach { w -> if (runs.isNotEmpty() && w == runs.last().second + 1) runs[runs.lastIndex] = runs.last().first to w else runs += w to w }
        return runs.joinToString("、") { (a, b) -> if (a == b) "$a" else "$a–$b" } + " 周"
    }
    fun periodsText(values: List<Int>): String {
        val codes = values.map { periodByIndex[it]?.code ?: "$it" }
        return if (values.size >= 3 && values.last() < 10 && values.zipWithNext().all { (a, b) -> b == a + 1 }) "第 ${codes.first()}–${codes.last()} 节" else "第 ${codes.joinToString("、")} 节"
    }
}

data class AcademicTask(val id: String, val title: String, val course: String, val source: String, val url: String?, val dueMs: Long?, val dueRaw: String, val schoolStatus: String)
data class AcademicExam(val id: String, val title: String, val timeRaw: String, val location: String, val seat: String, val campus: String, val notes: String, val date: String?, val startMs: Long?)
data class AcademicGrade(val id: String, val semester: String, val title: String, val score: String, val credits: Double?, val gpa: Double?, val examType: String, val marker: String, val method: String)
data class AcademicGradeSummary(val weightedScore: Double?, val weightedGpa: Double?, val credits: Double, val countedCredits: Double, val counted: Int, val total: Int)
data class AcademicNotice(val id: String, val title: String, val url: String?, val source: String, val section: String, val publishedRaw: String, val publishedMs: Long?)

object AcademicRecords {
    val sourceNames = mapOf("public" to "学校官网", "oa" to "OA", "jw" to "教务", "mystu" to "MySTU", "yuketang" to "雨课堂", "charging" to "充电平台", "smartcard" to "一卡通", "repair" to "宿舍报修")
    fun source(name: String) = sourceNames[name] ?: name
    fun task(raw: JSONObject): AcademicTask {
        val id = academicString(raw.opt("id"))
        val due = academicString(if (raw.has("due_at") && !raw.isNull("due_at")) raw.opt("due_at") else raw.opt("due"))
        val millis = academicNumber(raw.opt("dueMs"))?.takeIf { it > 0 }?.toLong() ?: due.takeIf { it.isNotEmpty() }?.let {
            AcademicClock.parseTime(if (Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(it.trim())) "${it.trim()}T23:59:00+08:00" else it)
        }
        return AcademicTask(id, academicString(raw.opt("title")).ifEmpty { "未命名任务" }, academicString(raw.opt("course")), academicString(raw.opt("source")).ifEmpty { if (id.startsWith("yuketang")) "yuketang" else "mystu" }, academicString(raw.opt("url")).ifEmpty { null }, millis, due, academicString(raw.opt("school_status")))
    }
    fun bucket(task: AcademicTask, now: Long = AcademicClock.nowMillis()): String {
        val due = task.dueMs ?: return "none"
        if (due < now - 14L * 86_400_000) return "expired"
        if (due < now) return "overdue"
        val today = AcademicClock.moment(now).date
        if (AcademicClock.moment(due).date == today) return "today"
        return if (due - AcademicClock.dayStart(today) < 4L * 86_400_000) "soon" else "later"
    }
    fun dueLine(task: AcademicTask, now: Long): String {
        val due = task.dueMs ?: return if (task.dueRaw.isNotEmpty()) "截止 ${task.dueRaw}" else "未写截止"
        val moment = AcademicClock.moment(due)
        val days = AcademicClock.daysUntil(moment.date, now)
        val day = when { days == 0 -> "今天"; days == 1 -> "明天"; days == -1 -> "昨天"; abs(days) < 7 -> AcademicClock.weekdayText(moment.weekday); else -> AcademicClock.slash(moment.date) }
        return "$day ${AcademicClock.clock(moment.minutes)}"
    }
    fun leftText(task: AcademicTask, now: Long): String? {
        val due = task.dueMs ?: return null
        val minutes = ((due - now) / 60000.0).roundToInt()
        return when { minutes < 0 -> "已过 ${AcademicClock.span(-minutes).replace(Regex(" \\d+ 分$"), "")}"; minutes < 1440 -> "剩 ${AcademicClock.span(minutes)}"; else -> "剩 ${minutes / 1440} 天" }
    }
    fun todayDue(task: AcademicTask, now: Long): String {
        val due = task.dueMs ?: return task.dueRaw.ifEmpty { "未写截止" }
        val moment = AcademicClock.moment(due); val days = AcademicClock.daysUntil(moment.date, now); val clock = AcademicClock.clock(moment.minutes)
        if (due < now) return if (days == 0) "今天 $clock 已过" else "已过 ${-days} 天"
        if (days == 0) { val minutes = ((due - now) / 60000.0).roundToInt(); return if (minutes < 180) "${AcademicClock.span(minutes)}后" else "今天 $clock" }
        return if (days == 1) "明天 $clock" else "$days 天后"
    }
    fun exam(raw: JSONObject): AcademicExam {
        val time = academicString(raw.opt("exam_time"))
        val match = Regex("(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?(?:\\D+(\\d{1,2}):(\\d{2}))?").find(time)
        val date = match?.let { runCatching { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()).toString() }.getOrNull() }
        val start = date?.let { if (match != null && match.groupValues[4].isNotEmpty()) AcademicClock.parseTime("${it}T${match.groupValues[4].padStart(2, '0')}:${match.groupValues[5]}:00+08:00") else AcademicClock.dayStart(it) }
        return AcademicExam(academicString(raw.opt("id")).ifEmpty { academicString(raw.opt("external_id")) }.ifEmpty { "exam|${academicString(raw.opt("course_code"))}|${academicString(raw.opt("title"))}|$time" }, academicString(raw.opt("title")), time, academicString(raw.opt("location")), academicString(raw.opt("seat_number")), academicString(raw.opt("exam_campus")).ifEmpty { academicString(raw.opt("campus")) }, academicString(raw.opt("notes")), date, start)
    }
    fun grade(raw: JSONObject): AcademicGrade = AcademicGrade(academicString(raw.opt("id")).ifEmpty { academicString(raw.opt("external_id")) }.ifEmpty { "grade|${academicString(raw.opt("semester"))}|${academicString(raw.opt("course_code"))}|${academicString(raw.opt("title"))}|${academicString(raw.opt("exam_type"))}" }, academicString(raw.opt("semester")), academicString(raw.opt("title")), academicString(raw.opt("score")), academicNumber(raw.opt("credits")), academicNumber(raw.opt("gpa")), academicString(raw.opt("exam_type")), academicString(raw.opt("score_marker")), academicString(raw.opt("exam_method")))
    fun summarize(grades: List<AcademicGrade>): AcademicGradeSummary {
        var sw = 0.0; var sc = 0.0; var gw = 0.0; var gc = 0.0; var count = 0
        grades.forEach { g ->
            val score = academicNumber(g.score)
            if (g.credits != null && g.credits != 0.0 && score != null) { sw += score * g.credits; sc += g.credits; count++ }
            if (g.credits != null && g.credits != 0.0 && g.gpa != null) { gw += g.gpa * g.credits; gc += g.credits }
        }
        return AcademicGradeSummary(if (sc != 0.0) sw / sc else null, if (gc != 0.0) gw / gc else null, grades.sumOf { it.credits ?: 0.0 }, sc, count, grades.size)
    }
    fun notice(raw: JSONObject): AcademicNotice {
        val detail = raw.optJSONObject("detail")
        fun value(key: String): String = academicString(if (raw.has(key) && !raw.isNull(key)) raw.opt(key) else detail?.opt(key))
        val published = value("published_at")
        val match = Regex("(\\d{4})\\D(\\d{1,2})\\D(\\d{1,2})").find(published)
        val millis = match?.let { AcademicClock.parseTime("${it.groupValues[1]}-${it.groupValues[2].padStart(2, '0')}-${it.groupValues[3].padStart(2, '0')}") }
        return AcademicNotice(academicString(raw.opt("id")), value("title"), value("url").ifEmpty { null }, academicString(raw.opt("source")).ifEmpty { "public" }, value("category").ifEmpty { value("department") }, published, millis)
    }
    fun semesterLabel(value: String): String {
        val match = Regex("^(\\d{4})-(\\d{4})-(\\d)$").matchEntire(value) ?: return value
        val season = when (match.groupValues[3]) { "0" -> ""; "1" -> "秋"; "2" -> "春"; "3" -> "夏"; else -> match.groupValues[3] }
        return "${match.groupValues[1].takeLast(2)}–${match.groupValues[2].takeLast(2)} $season"
    }
    fun creditText(value: Double): String = if (value % 1 == 0.0) value.toLong().toString() else String.format(Locale.ROOT, "%.1f", value)
}
