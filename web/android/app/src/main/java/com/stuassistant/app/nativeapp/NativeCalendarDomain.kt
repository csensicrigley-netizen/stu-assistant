package com.stuassistant.app.nativeapp

import org.json.JSONObject
import java.time.Instant
import kotlin.math.roundToInt

/** Canonical server fields are kept intact. Visual edits alter only their owned field. */
internal object CalendarDrafts {
    fun instant(text: String): Long? = text.takeIf { it.contains('T') }?.let(AcademicClock::parseTime)
    fun iso(ms: Long): String = Instant.ofEpochMilli(ms).atZone(AcademicClock.zone).toOffsetDateTime().toString()
    fun snap(ms: Long): Long = (ms / 300000.0).roundToInt().toLong() * 300000L
    fun start(d: JSONObject): Long? = instant(d.optJSONObject("schedule")?.optString("start").orEmpty())
    fun end(d: JSONObject): Long? = instant(d.optJSONObject("schedule")?.optString("end").orEmpty())
    fun deadline(d: JSONObject): Long? = d.optJSONObject("deadline")?.takeIf { it.optString("kind") == "instant" }
        ?.optString("value")?.let(::instant)
    fun date(d: JSONObject): String? = (start(d) ?: deadline(d))?.let { AcademicClock.moment(it).date }
        ?: d.optJSONObject("deadline")?.takeIf { it.optString("kind") == "date" }?.optString("value")
        ?: d.optJSONObject("plan")?.optString("start_date")
    fun label(d: JSONObject): String {
        val start = start(d)
        if (start != null) return AcademicClock.clockAt(start) + (end(d)?.let { " – ${AcademicClock.clockAt(it)}" } ?: "")
        deadline(d)?.let { return "${AcademicClock.clockAt(it)} 截止" }
        d.optJSONObject("deadline")?.let { return "${it.optString("value")} 截止" }
        d.optJSONObject("plan")?.let { return AcademicClock.monthDay(it.optString("start_date")) + " · 时间待定" }
        return "待安排"
    }
    fun edit(draft: JSONObject, field: String, start: Long, end: Long? = null): JSONObject {
        val d = JSONObject(draft.toString())
        when (field) {
            "deadline" -> d.put("deadline", JSONObject(d.optJSONObject("deadline")?.toString() ?: "{}")
                .put("kind", "instant").put("value", iso(start)).apply { remove("part_of_day") })
            else -> d.put("schedule", JSONObject().put("start", iso(start)).apply {
                if (end != null && end > start) put("end", iso(end))
            })
        }
        return d
    }
}

internal data class CalendarMark(val key: String, val title: String, val start: Long, val end: Long?,
    val field: String = "schedule", val item: Int = -1, val source: String = "local")

internal fun calendarMarks(drafts: List<JSONObject>, source: String = "draft"): List<CalendarMark> = buildList {
    drafts.forEachIndexed { index, draft ->
        CalendarDrafts.start(draft)?.let { add(CalendarMark("$source-$index-s", draft.optString("title"), it,
            CalendarDrafts.end(draft), item = if (source == "draft") index else -1, source = source)) }
        CalendarDrafts.deadline(draft)?.let { add(CalendarMark("$source-$index-d", draft.optString("title"), it,
            null, "deadline", if (source == "draft") index else -1, source)) }
    }
}
