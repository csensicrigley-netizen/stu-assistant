package com.stuassistant.app.nativeapp

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/** Sorting may use a synthetic end-of-day instant. Detail must keep source precision. */
internal fun itemsTaskDeadline(task: AcademicTask): String = when {
    Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(task.dueRaw.trim()) -> task.dueRaw.trim()
    task.dueMs != null -> "${AcademicClock.moment(task.dueMs).date} ${AcademicClock.clockAt(task.dueMs)}"
    else -> task.dueRaw.ifBlank { "未提供" }
}

/** Atomically removes a single local calendar item by ID while preserving sibling entries. */
internal fun removeCalendarRecordFromDocument(document: JSONObject, id: String): Boolean {
    if (id.isBlank()) return false
    val items = academicObjects(document.optJSONArray("items"))
    val remaining = items.filterNot { it.optString("id") == id }
    if (remaining.size == items.size) return false
    document.put("items", JSONArray(remaining))
    return true
}

internal const val ITEMS_GRID_START_MIN = 8 * 60
internal const val ITEMS_GRID_END_MIN = 22 * 60
internal const val ITEMS_GRID_TOTAL_MIN = ITEMS_GRID_END_MIN - ITEMS_GRID_START_MIN
internal const val ITEMS_MIN_DURATION = 15

internal enum class ItemsPriority(val code: Int, val label: String) {
    NORMAL(0, "普通"),
    IMPORTANT(1, "重要"),
    URGENT(2, "很重要"),
    ;

    fun next(): ItemsPriority = when (this) {
        NORMAL -> IMPORTANT
        IMPORTANT -> URGENT
        URGENT -> NORMAL
    }

    companion object {
        fun fromCode(code: Int): ItemsPriority = when (code.coerceIn(0, 2)) {
            1 -> IMPORTANT
            2 -> URGENT
            else -> NORMAL
        }
    }
}

/** High-clarity OKLCH event tones derived from active campus palette × light/dark theme. */
internal data class ItemsEventTones(
    val accent: Color,
    val titleInk: Color,
    val timeInk: Color,
    val glassTop: Color,
    val glassBottom: Color,
    val border: Color,
    val dotFill: Color,
    val solidBg: Color,
    val solidFg: Color,
    val warnAccent: Color,
    val warnInk: Color,
    val urgentAccent: Color,
    val urgentInk: Color,
    val okAccent: Color,
)

private data class EventPalettePick(
    val accent: Color,
    val txtTone: Color,
    val solidBg: Color,
    val solidFg: Color,
)

internal fun itemsEventTones(
    dark: Boolean,
    palette: String,
    priority: ItemsPriority,
    done: Boolean,
    colors: NativeColors = nativeColors(dark, palette),
): ItemsEventTones {
    val normPal = normalizeNativePalette(palette)
    val (bL, bC, bH) = when (normPal) {
        "classic" -> if (dark) Triple(0.74, 0.13, 258.0) else Triple(0.48, 0.17, 258.0)
        "phoenix" -> if (dark) Triple(0.77, 0.135, 40.0) else Triple(0.54, 0.165, 36.0)
        "pine" -> if (dark) Triple(0.77, 0.115, 162.0) else Triple(0.48, 0.125, 164.0)
        "lilac" -> if (dark) Triple(0.78, 0.125, 286.0) else Triple(0.51, 0.165, 286.0)
        "latte" -> if (dark) Triple(0.78, 0.11, 68.0) else Triple(0.50, 0.12, 62.0)
        else -> if (dark) Triple(0.80, 0.10, 288.0) else Triple(0.52, 0.15, 288.0)
    }
    val okH = when (normPal) {
        "classic" -> 155.0
        "phoenix" -> 162.0
        "pine" -> 162.0
        "lilac" -> 166.0
        "latte" -> 160.0
        else -> 168.0
    }
    val warnH = if (normPal == "latte" || normPal == "phoenix") 84.0 else 80.0
    val urgH = if (bH in 25.0..55.0) 14.0 else 20.0

    val evBrand = if (dark) nativeOklch(minOf(0.80, maxOf(0.75, bL)), minOf(0.16, bC * 1.12), bH)
    else nativeOklch(minOf(0.55, maxOf(0.50, bL + 0.02)), bC, bH)
    val evBrandTxt = if (dark) nativeOklch(0.88, minOf(0.12, bC), bH)
    else nativeOklch(0.37, minOf(0.15, bC * 0.92), bH)

    val evWarn = if (dark) nativeOklch(0.825, 0.150, warnH) else nativeOklch(0.705, 0.168, warnH - 10.0)
    val evWarnTxt = if (dark) nativeOklch(0.885, 0.115, warnH) else nativeOklch(0.415, 0.132, warnH - 18.0)
    val evWarnSolid = if (dark) nativeOklch(0.835, 0.145, warnH) else nativeOklch(0.855, 0.148, warnH)
    val evWarnSolidFg = if (dark) colors.onBrand else nativeOklch(0.260, 0.068, warnH - 22.0)

    val evUrg = if (dark) nativeOklch(0.745, 0.172, urgH) else nativeOklch(0.575, 0.210, urgH)
    val evUrgTxt = if (dark) nativeOklch(0.865, 0.130, urgH) else nativeOklch(0.445, 0.180, urgH)

    val evOk = if (dark) nativeOklch(0.780, 0.138, okH) else nativeOklch(0.540, 0.150, okH)
    val evOkTxt = if (dark) nativeOklch(0.860, 0.105, okH) else nativeOklch(0.400, 0.125, okH)

    val (accent, txtTone, solidBg, solidFg) = when {
        done -> EventPalettePick(evOk, evOkTxt, evOk, colors.onBrand)
        priority == ItemsPriority.URGENT -> EventPalettePick(evUrg, evUrgTxt, evUrg, colors.onBrand)
        priority == ItemsPriority.IMPORTANT -> EventPalettePick(evWarn, evWarnTxt, evWarnSolid, evWarnSolidFg)
        else -> EventPalettePick(evBrand, evBrandTxt, evBrand, colors.onBrand)
    }

    val glassTop = lerp(colors.surface, accent, 0.24f)
    val glassBottom = lerp(colors.surface, accent, if (dark) 0.13f else 0.14f)
    val titleInk = if (dark) lerp(colors.ink, accent, 0.12f) else txtTone
    val timeInk = if (dark) lerp(colors.ink, accent, 0.76f) else lerp(colors.muted, txtTone, 0.78f)
    val border = accent.copy(alpha = if (dark) 0.48f else 0.46f)
    val dotFill = if (done) evOk else accent.copy(alpha = 0.22f)

    return ItemsEventTones(
        accent = accent,
        titleInk = titleInk,
        timeInk = timeInk,
        glassTop = glassTop,
        glassBottom = glassBottom,
        border = border,
        dotFill = dotFill,
        solidBg = solidBg,
        solidFg = solidFg,
        warnAccent = evWarn,
        warnInk = evWarnTxt,
        urgentAccent = evUrg,
        urgentInk = evUrgTxt,
        okAccent = evOk,
    )
}

/** Normalized in-memory projection of a canonical `{ id, batch, done, created_at, draft }` record. */
internal data class ItemsRecordModel(
    val id: String,
    val batch: String,
    val done: Boolean,
    val createdAt: Long,
    val title: String,
    val note: String,
    val priority: ItemsPriority,
    val date: String?,
    val startMin: Int?,
    val endMin: Int?,
    val isAllDay: Boolean,
    val isDeadlinePoint: Boolean,
    val schoolTaskId: String?,
    val rawRecord: JSONObject,
    val rawDraft: JSONObject,
) {
    val isScheduled: Boolean get() = date != null
    val isTimed: Boolean get() = date != null && startMin != null
    val hasPlanWindow: Boolean
        get() = rawDraft.optJSONObject("plan") != null && rawDraft.optJSONObject("details")?.optBoolean("all_day") != true
    val hasIndependentDates: Boolean get() = hasPlanWindow || rawDraft.optJSONObject("deadline") != null
    val isDeadlineOnly: Boolean
        get() = isDeadlinePoint || (isAllDay && rawDraft.optJSONObject("deadline")?.optString("kind") == "date")
    val untimedLabel: String get() = when {
        isDeadlineOnly -> "截止"
        hasPlanWindow -> "计划"
        else -> "全天"
    }
    val startClock: String? get() = startMin?.let(AcademicClock::clock)
    val endClock: String? get() = endMin?.let(AcademicClock::clock)
}

internal object ItemsDrafts {
    fun parseRecord(record: JSONObject): ItemsRecordModel? {
        val draft = record.optJSONObject("draft") ?: return null
        val id = record.optString("id").ifBlank { UUID.randomUUID().toString() }
        val details = draft.optJSONObject("details")
        val title = draft.optString("title").ifBlank { "未命名事项" }
        val note = details?.optString("note").orEmpty()
        val prio = ItemsPriority.fromCode(details?.optInt("priority", 0) ?: 0)
        val schoolId = details?.optString("school_task_id")?.takeIf { it.isNotBlank() }

        val schedStartMs = CalendarDrafts.start(draft)
        val schedEndMs = CalendarDrafts.end(draft)
        val deadlineMs = CalendarDrafts.deadline(draft)
        val deadlineDateOnly = draft.optJSONObject("deadline")?.takeIf { it.optString("kind") == "date" }?.optString("value")?.takeIf { it.isNotBlank() }
        val planDate = draft.optJSONObject("plan")?.optString("start_date")?.takeIf { it.isNotBlank() }

        val date: String?
        val startMin: Int?
        val endMin: Int?
        val isAllDay: Boolean
        val isDeadlinePoint: Boolean

        when {
            schedStartMs != null -> {
                val sMom = AcademicClock.moment(schedStartMs)
                date = sMom.date
                val clampedStart = sMom.minutes.coerceIn(ITEMS_GRID_START_MIN, ITEMS_GRID_END_MIN - ITEMS_MIN_DURATION)
                val rawEnd = schedEndMs?.let { AcademicClock.moment(it).minutes }
                val dur = if (rawEnd != null && rawEnd > clampedStart) rawEnd - clampedStart else 60
                startMin = clampedStart
                endMin = (clampedStart + dur).coerceIn(clampedStart + ITEMS_MIN_DURATION, ITEMS_GRID_END_MIN)
                isAllDay = false
                isDeadlinePoint = false
            }
            deadlineMs != null -> {
                val dMom = AcademicClock.moment(deadlineMs)
                date = dMom.date
                val m = dMom.minutes.coerceIn(ITEMS_GRID_START_MIN, ITEMS_GRID_END_MIN - ITEMS_MIN_DURATION)
                startMin = m
                endMin = (m + 45).coerceAtMost(ITEMS_GRID_END_MIN)
                isAllDay = false
                isDeadlinePoint = true
            }
            planDate != null || deadlineDateOnly != null -> {
                date = deadlineDateOnly ?: planDate
                startMin = null
                endMin = null
                isAllDay = true
                isDeadlinePoint = false
            }
            else -> {
                date = null
                startMin = null
                endMin = null
                isAllDay = false
                isDeadlinePoint = false
            }
        }

        return ItemsRecordModel(
            id = id,
            batch = record.optString("batch"),
            done = record.optBoolean("done"),
            createdAt = record.optLong("created_at", 0L),
            title = title,
            note = note,
            priority = prio,
            date = date,
            startMin = startMin,
            endMin = endMin,
            isAllDay = isAllDay,
            isDeadlinePoint = isDeadlinePoint,
            schoolTaskId = schoolId,
            rawRecord = record,
            rawDraft = draft,
        )
    }

    private fun copyJson(src: JSONObject): JSONObject = JSONObject(src.toString())

    private fun ensureDetails(draft: JSONObject): JSONObject {
        val d = draft.optJSONObject("details")?.let(::copyJson) ?: JSONObject()
        draft.put("details", d)
        return d
    }

    fun newRecord(
        title: String,
        note: String = "",
        priority: ItemsPriority = ItemsPriority.NORMAL,
        date: String? = null,
        startMin: Int? = null,
        endMin: Int? = null,
        isAllDay: Boolean = false,
        schoolTaskId: String? = null,
        done: Boolean = false,
        id: String = UUID.randomUUID().toString(),
        batch: String = UUID.randomUUID().toString(),
    ): JSONObject {
        val details = JSONObject().put("priority", priority.code)
        if (note.isNotBlank()) details.put("note", note.trim())
        if (!schoolTaskId.isNullOrBlank()) details.put("school_task_id", schoolTaskId)
        val draft = JSONObject()
            .put("title", title.trim().ifEmpty { "未命名事项" })
            .put("timezone", "Asia/Shanghai")
            .put("reminders", JSONArray())
            .put("details", details)
        val populated = when {
            date != null && startMin != null && !isAllDay -> setTimed(draft, date, startMin, endMin ?: (startMin + 60))
            date != null -> setAllDay(draft, date)
            else -> draft
        }
        return JSONObject()
            .put("id", id)
            .put("batch", batch)
            .put("done", done)
            .put("created_at", System.currentTimeMillis())
            .put("draft", populated)
    }

    fun setTitle(draft: JSONObject, title: String): JSONObject =
        copyJson(draft).put("title", title.ifEmpty { "未命名事项" })

    fun setNote(draft: JSONObject, note: String): JSONObject {
        val next = copyJson(draft)
        val details = ensureDetails(next)
        if (note.isEmpty()) details.remove("note") else details.put("note", note)
        return next
    }

    fun setPriority(draft: JSONObject, priority: ItemsPriority): JSONObject {
        val next = copyJson(draft)
        ensureDetails(next).put("priority", priority.code)
        return next
    }

    fun setTimed(draft: JSONObject, date: String, startMin: Int, endMin: Int): JSONObject {
        val safeStart = startMin.coerceIn(ITEMS_GRID_START_MIN, ITEMS_GRID_END_MIN - ITEMS_MIN_DURATION)
        val safeEnd = endMin.coerceIn(safeStart + ITEMS_MIN_DURATION, ITEMS_GRID_END_MIN)
        val dayMs = AcademicClock.dayStart(date)
        val startMs = dayMs + safeStart * 60_000L
        val endMs = dayMs + safeEnd * 60_000L
        val next = copyJson(draft)
        val dl = next.optJSONObject("deadline")
        if (CalendarDrafts.start(draft) == null && dl != null &&
            (CalendarDrafts.deadline(draft) != null || dl.optString("kind") == "date")) {
            dl.put("kind", "instant").put("value", CalendarDrafts.iso(startMs))
            dl.remove("part_of_day")
            return next
        }
        val schedule = next.optJSONObject("schedule") ?: JSONObject()
        next.put(
            "schedule",
            schedule
                .put("start", CalendarDrafts.iso(startMs))
                .put("end", CalendarDrafts.iso(endMs)),
        )
        val details = next.optJSONObject("details")
        if (details?.optBoolean("all_day") == true) {
            next.remove("plan")
            ensureDetails(next).remove("all_day")
        }
        return next
    }

    fun setAllDay(draft: JSONObject, date: String): JSONObject {
        val next = copyJson(draft)
        val deadline = next.optJSONObject("deadline")
        if (CalendarDrafts.start(draft) == null && deadline?.optString("kind") == "date") {
            deadline.put("value", date)
            return next
        }
        val plan = next.optJSONObject("plan")
        if (plan != null && next.optJSONObject("details")?.optBoolean("all_day") != true) {
            val shift = runCatching {
                ChronoUnit.DAYS.between(LocalDate.parse(plan.optString("start_date")), LocalDate.parse(date))
            }.getOrDefault(0L)
            if (shift != 0L) {
                plan.put("start_date", date)
                runCatching { LocalDate.parse(plan.optString("end_date_exclusive")).plusDays(shift) }
                    .getOrNull()?.let { plan.put("end_date_exclusive", it.toString()) }
            }
            return next
        }
        next.remove("schedule")
        next.put(
            "plan",
            JSONObject()
                .put("start_date", date)
                .put("end_date_exclusive", AcademicClock.addDays(date, 1)),
        )
        ensureDetails(next).put("all_day", true)
        return next
    }

    fun moveToInbox(draft: JSONObject): JSONObject {
        val next = copyJson(draft)
        next.remove("schedule")
        val details = next.optJSONObject("details")
        if (details?.optBoolean("all_day") == true) {
            next.remove("plan")
            details.remove("all_day")
        }
        return next
    }

    fun samplePreviewRecords(anchorDate: String): List<JSONObject> {
        val d0 = anchorDate
        val d1 = AcademicClock.addDays(d0, 1)
        val d2 = AcademicClock.addDays(d0, 2)
        val prev = AcademicClock.addDays(d0, -3)
        return listOf(
            newRecord("准备数据库实验验收", "携带笔记本与本地测试脚本", ItemsPriority.IMPORTANT, d0, 14 * 60 + 30, 16 * 60, id = "preview-cal-1"),
            newRecord("团队周会汇报进度", "汇总本周实验记录", ItemsPriority.URGENT, d0, 10 * 60 + 15, 11 * 60 + 15, id = "preview-cal-2"),
            newRecord("校运会方阵报名截止", "", ItemsPriority.NORMAL, d1, null, null, isAllDay = true, id = "preview-cal-3"),
            newRecord("图书馆期中模拟刷题", "二楼自习区", ItemsPriority.IMPORTANT, d2, 16 * 60, 17 * 60 + 30, id = "preview-cal-4"),
            newRecord("上周实验数据复盘", "", ItemsPriority.NORMAL, prev, 15 * 60, 16 * 60 + 30, done = true, id = "preview-cal-5"),
            newRecord("找导师签实习鉴定表", "", ItemsPriority.IMPORTANT, null, null, null, id = "preview-inbox-1"),
            newRecord("顺丰站取教材快递", "取件码 6-2-3018", ItemsPriority.NORMAL, null, null, null, id = "preview-inbox-2"),
        )
    }
}

/** Monday-aligned contiguous multi-week date model so `idx / 7` is always a calendar week. */
internal class ItemsDateWindow(
    val today: String,
    focusDate: String? = null,
    laterFocusDate: String? = null,
) {
    val dates: List<String>
    val totalDays: Int get() = dates.size
    val totalWeeks: Int get() = dates.size / 7
    val todayIndex: Int
    private val indexMap: Map<String, Int>

    init {
        val todayLocal = LocalDate.parse(today)
        val focusLocal = focusDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: todayLocal
        val laterLocal = laterFocusDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: todayLocal
        val minLocal = minOf(todayLocal, focusLocal, laterLocal)
        val maxLocal = maxOf(todayLocal, focusLocal, laterLocal)

        val startMonday = minLocal.minusDays(42).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val endSunday = maxLocal.plusDays(98).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val count = ChronoUnit.DAYS.between(startMonday, endSunday).toInt() + 1

        dates = List(count) { offset -> startMonday.plusDays(offset.toLong()).toString() }
        val map = HashMap<String, Int>(count)
        dates.forEachIndexed { idx, d -> map[d] = idx }
        indexMap = map
        todayIndex = map[today] ?: 0
    }

    fun indexOf(date: String): Int = indexMap[date] ?: -1

    fun dateAt(index: Int): String = dates[index.coerceIn(0, dates.lastIndex)]

    fun weekDates(weekIndex: Int): List<String> {
        val w = weekIndex.coerceIn(0, (totalWeeks - 1).coerceAtLeast(0))
        return dates.subList(w * 7, w * 7 + 7)
    }

    fun weekIndexOf(dayIndex: Float): Int =
        (floor(dayIndex.toDouble()).toInt() / 7).coerceIn(0, (totalWeeks - 1).coerceAtLeast(0))

    fun centerDate(offset: Float, colsF: Float): String {
        val centerIdx = floor((offset + colsF * 0.5f).toDouble()).toInt().coerceIn(0, dates.lastIndex)
        return dates[centerIdx]
    }
}

internal object ItemsCalendarMath {
    fun listPagePosition(offset: Float, totalDays: Int): Pair<Int, Float> {
        val bounded = offset.coerceIn(0f, (totalDays - 1).toFloat())
        val page = bounded.roundToInt()
        return page to (bounded - page)
    }

    val scaleStops = listOf(1, 3, 5, 7)

    fun nearestScale(colsF: Float): Int = when {
        colsF < 2f -> 1
        colsF < 4f -> 3
        colsF < 6f -> 5
        else -> 7
    }

    fun scaleShortName(cols: Int): String = when (cols) {
        1 -> "1日"
        3 -> "3日"
        5 -> "5日"
        else -> "周"
    }

    fun columnGapDp(colsF: Float): Float {
        val c = colsF.coerceIn(1f, 7f)
        return when {
            c <= 1f -> 0f
            c <= 3f -> ((c - 1f) / 2f) * 5f
            c <= 5f -> 5f - ((c - 3f) / 2f) * 2f
            else -> maxOf(1.5f, 3f - ((c - 5f) / 2f) * 1f)
        }
    }

    fun offsetForDayIndex(index: Int, cols: Int, totalDays: Int): Float {
        val safeIdx = index.coerceIn(0, (totalDays - 1).coerceAtLeast(0))
        val maxOff = (totalDays - cols).coerceAtLeast(0)
        return when (cols) {
            7 -> (safeIdx / 7) * 7f
            5 -> {
                val w0 = (safeIdx / 7) * 7
                (if (safeIdx - w0 <= 4) w0 else w0 + 2).coerceIn(0, maxOff).toFloat()
            }
            1 -> safeIdx.toFloat()
            else -> (safeIdx - 1).coerceIn(0, maxOff).toFloat()
        }
    }

    fun nearestGridStop(offset: Float, cols: Int, totalDays: Int): Float {
        val stride = if (cols == 7) 7f else 1f
        val maxOffset = (totalDays - cols).coerceAtLeast(0).toFloat()
        return ((offset / stride).roundToInt() * stride).coerceIn(0f, maxOffset)
    }

    /** Count aligned date boundaries without re-ticking on the first motion away from a stop. */
    fun crossedGridDates(from: Float, to: Float): Int {
        return if (to >= from) (floor(to) - floor(from)).toInt()
        else (ceil(from) - ceil(to)).toInt()
    }

    data class GridFlingFrame(val offset: Float, val velocity: Float)

    const val gridFlingStopVelocity = 0.48f
    const val gridFlingMaxVelocity = 22f

    fun stepGridFling(offset: Float, velocity: Float, seconds: Float, maxOffset: Float): GridFlingFrame {
        val friction = 3.3f
        val decay = exp(-friction * seconds)
        val next = (offset + velocity * (1f - decay) / friction).coerceIn(0f, maxOffset)
        if ((velocity < 0f && next == 0f) || (velocity > 0f && next == maxOffset)) {
            return GridFlingFrame(next, 0f)
        }
        val dateBrake = 0.88f.pow(crossedGridDates(offset, next))
        return GridFlingFrame(next, velocity * decay * dateBrake)
    }

    fun scrollForVisibleGridCard(
        scroll: Float,
        cardTop: Float,
        cardBottom: Float,
        visibleHeight: Float,
        margin: Float,
    ): Float {
        if (visibleHeight <= margin * 2f) return scroll
        val topAligned = cardTop - margin
        val bottomAligned = cardBottom - visibleHeight + margin
        return when {
            scroll > topAligned -> topAligned
            scroll < bottomAligned -> minOf(topAligned, bottomAligned)
            else -> scroll
        }.coerceAtLeast(0f)
    }

    fun snapOffsetForScale(targetCols: Int, anchorDay: Float, anchorFrac: Float, totalDays: Int): Float {
        val clampedDay = anchorDay.coerceIn(0f, (totalDays - 0.01f).coerceAtLeast(0f))
        val dayInt = floor(clampedDay.toDouble()).toInt()
        val maxOff = (totalDays - targetCols).coerceAtLeast(0)
        if (targetCols == 7) return ((dayInt / 7) * 7).coerceIn(0, maxOff).toFloat()
        if (targetCols == 5) {
            val wkStart = (dayInt / 7) * 7
            val off = if (dayInt - wkStart < 5) wkStart else wkStart + 2
            return off.coerceIn(0, maxOff).toFloat()
        }
        return (anchorDay - anchorFrac * targetCols).roundToInt().coerceIn(0, maxOff).toFloat()
    }

    fun isTodayVisible(
        todayIndex: Int,
        isGrid: Boolean,
        listOffset: Float,
        calOffset: Float,
        calColsF: Float,
    ): Boolean = if (isGrid) {
        todayIndex >= calOffset - 0.15f && todayIndex <= calOffset + calColsF - 0.85f
    } else {
        abs(listOffset - todayIndex) < 0.25f
    }

    fun rubberBandClamp(value: Float, min: Float, max: Float, factor: Float = 0.26f): Float = when {
        value < min -> min + (value - min) * factor
        value > max -> max + (value - max) * factor
        else -> value
    }

    /** Nonlinear edge auto-scroll easing curve: gentle precision near boundary, capped brisk ramp at edge. */
    fun edgeAutoScrollFactor(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return if (p <= 0f) 0f else 0.12f * p + 0.88f * p.pow(2.6f)
    }

    fun maxAutoScrollColsPerSec(cols: Int): Float = when (cols) {
        1 -> 1.65f
        3 -> 2.35f
        5 -> 3.05f
        else -> 3.7f
    }

    fun snapListTarget(off: Float, velPagesPerSec: Float, startIdx: Int, totalDays: Int): Int {
        val clamped = off.coerceIn(0f, (totalDays - 1).coerceAtLeast(0).toFloat())
        val lo = floor(clamped.toDouble()).toInt()
        val hi = kotlin.math.ceil(clamped.toDouble()).toInt().coerceAtMost((totalDays - 1).coerceAtLeast(0))
        if (lo == hi) return lo
        if (abs(velPagesPerSec) > 1.35f) return if (velPagesPerSec > 0f) hi else lo
        val moved = clamped - startIdx
        if (abs(moved) >= 1f) return clamped.roundToInt().coerceIn(0, (totalDays - 1).coerceAtLeast(0))
        if (abs(moved) > 0.30f) return if (moved > 0f) hi else lo
        return startIdx.coerceIn(lo, hi)
    }

    fun headerMonthWeekLabel(date: String): String {
        val month = runCatching { LocalDate.parse(date).monthValue }.getOrNull()
        val pos = runCatching { AcademicCalendar.position(date) }.getOrNull()
        val mText = month?.let { "${it}月" } ?: date.take(7)
        return if (pos != null) "$mText · ${pos.week}周" else "$mText · ${AcademicClock.slash(date)}"
    }

    fun formatDuration(minutes: Int): String = when {
        minutes < 60 -> "${minutes}分"
        minutes % 60 == 0 -> "${minutes / 60}小时"
        else -> String.format(java.util.Locale.ROOT, "%.1f时", minutes / 60f)
    }
}

/** Course/exam title without parenthetical noise or boilerplate phrases; never truncated. */
internal fun itemsCleanTitle(title: String): String {
    val clean = title
        .replace(Regex("[（(].*?[)）]"), "")
        .replace("中国特色社会主义", "")
        .replace("基本原理", "原理")
        .replace("导论", "")
        .trim()
    return clean.ifEmpty { title.trim() }
}

internal fun itemsShortTitle(title: String, maxChars: Int = 4): String {
    val src = itemsCleanTitle(title)
    return if (src.length <= maxChars) src else src.take(maxChars)
}

/** Compact room label for pills: drops a short campus prefix (`桑浦山-D座206` -> `D座206`) and never cuts the room. */
internal fun itemsRoomLabel(location: String): String {
    val trimmed = location.trim()
    val cut = trimmed.indexOfFirst { it == '-' || it == '－' || it == '—' }
    return if (cut in 1..4 && cut < trimmed.lastIndex) trimmed.substring(cut + 1).trim() else trimmed
}

internal data class ItemsGridInterval(
    val key: String,
    val startMin: Int,
    val endMin: Int,
    val pri: Int, // 0 = fixed course/exam, 1 = personal item
)

internal data class ItemsPlacedLane(
    val lane: Int,
    val lanes: Int,
    val overlapsFixed: Boolean,
    val overlapsPersonal: Boolean,
    val hasFixedRail: Boolean,
)

/** Interval-graph lane packing + course×event `ov-rail` metadata. */
internal fun itemsPackDayLanes(
    fixedIntervals: List<ItemsGridInterval>,
    personalIntervals: List<ItemsGridInterval>,
): Map<String, ItemsPlacedLane> {
    val all = (fixedIntervals + personalIntervals)
        .sortedWith(compareBy<ItemsGridInterval> { it.startMin }.thenBy { it.pri }.thenByDescending { it.endMin - it.startMin }.thenBy { it.key })
    val result = LinkedHashMap<String, ItemsPlacedLane>(all.size)
    val cluster = mutableListOf<Pair<ItemsGridInterval, Int>>()
    val laneEnds = mutableListOf<Int>()
    var clusterEnd = Int.MIN_VALUE

    fun flush() {
        val n = laneEnds.size.coerceAtLeast(1)
        // A narrow rail is safe only when lane zero is reserved for fixed blocks
        // throughout the cluster. Otherwise every block uses its packed lane.
        val hasFixedRail = n > 1 &&
            cluster.any { (iv, _) -> iv.pri == 0 } &&
            cluster.all { (iv, ln) -> (iv.pri == 0) == (ln == 0) }
        cluster.forEach { (iv, ln) ->
            val ovFixed = iv.pri == 1 && fixedIntervals.any { f -> f.startMin < iv.endMin && iv.startMin < f.endMin }
            val ovPersonal = iv.pri == 0 && personalIntervals.any { p -> p.startMin < iv.endMin && iv.startMin < p.endMin }
            result[iv.key] = ItemsPlacedLane(ln, n, ovFixed, ovPersonal, hasFixedRail)
        }
        cluster.clear()
        laneEnds.clear()
    }

    for (iv in all) {
        if (cluster.isNotEmpty() && iv.startMin >= clusterEnd) flush()
        var ln = laneEnds.indexOfFirst { it <= iv.startMin }
        if (ln < 0) {
            ln = laneEnds.size
            laneEnds.add(iv.endMin)
        } else {
            laneEnds[ln] = iv.endMin
        }
        cluster.add(iv to ln)
        clusterEnd = maxOf(clusterEnd, iv.endMin)
    }
    if (cluster.isNotEmpty()) flush()
    return result
}

internal object ItemsSmartScheduler {
    fun upcomingStart(date: String, now: AcademicMoment): Int = when {
        date < now.date -> ITEMS_GRID_END_MIN
        date == now.date -> maxOf(ITEMS_GRID_START_MIN, now.minutes + 1)
        else -> ITEMS_GRID_START_MIN
    }

    fun busyIntervalsOn(
        date: String,
        doc: AcademicTimetableDoc?,
        exams: List<AcademicExam>,
        items: List<ItemsRecordModel>,
        ignoreItemId: String? = null,
    ): List<Pair<Int, Int>> = buildList {
        AcademicTimetable.planFor(doc, date).blocks.forEach { b ->
            add(b.startMin to b.endMin)
        }
        exams.filter { it.date == date && it.startMs != null }.forEach { ex ->
            val sm = AcademicClock.moment(ex.startMs!!).minutes
            if (sm in 1 until 1440) add(sm to (sm + 120).coerceAtMost(ITEMS_GRID_END_MIN))
        }
        items.filter { it.date == date && it.isTimed && !it.isDeadlinePoint && !it.done && it.id != ignoreItemId }.forEach { item ->
            add(item.startMin!! to item.endMin!!)
        }
    }

    fun findFreeSlot(
        durationMin: Int,
        preferredStartMin: Int,
        busy: List<Pair<Int, Int>>,
        dayStartMin: Int = ITEMS_GRID_START_MIN,
        dayEndMin: Int = ITEMS_GRID_END_MIN,
    ): Pair<Int, Int>? {
        val dur = durationMin.coerceAtLeast(ITEMS_MIN_DURATION)
        val first = maxOf(ITEMS_GRID_START_MIN, dayStartMin)
        val last = minOf(ITEMS_GRID_END_MIN, dayEndMin) - dur
        if (first > last) return null
        var bestStart: Int? = null
        var bestDist = Int.MAX_VALUE
        var m = ((first + 14) / 15) * 15
        while (m <= last) {
            val end = m + dur
            if (busy.all { (bs, be) -> end <= bs || m >= be }) {
                val dist = abs(m - preferredStartMin)
                if (dist < bestDist) {
                    bestDist = dist
                    bestStart = m
                }
            }
            m += 15
        }
        return bestStart?.let { it to (it + dur) }
    }
}

internal data class ItemsQuickParseResult(
    val cleanTitle: String,
    val hitDate: String?,
    val startMin: Int?,
    val endMin: Int?,
    val isAllDay: Boolean,
)

/** On-device natural-language Chinese date/time extractor for instant Quick-Add feedback. */
internal object ItemsQuickParser {
    private val dowMap = mapOf(
        "周一" to 1, "周二" to 2, "周三" to 3, "周四" to 4, "周五" to 5, "周六" to 6, "周日" to 7,
    )

    private fun clampTimePair(startMin: Int, durMin: Int = 60): Pair<Int, Int> {
        val rounded = ((startMin / 15.0).roundToInt() * 15)
        val s = rounded.coerceIn(ITEMS_GRID_START_MIN, ITEMS_GRID_END_MIN - 30)
        val e = (s + durMin).coerceIn(s + 30, ITEMS_GRID_END_MIN)
        return s to e
    }

    private fun to24Minutes(ampm: String?, hourStr: String, minStr: String?): Int {
        var h = hourStr.toIntOrNull() ?: 12
        val m = minStr?.toIntOrNull() ?: 0
        if ((ampm == "下午" || ampm == "晚上" || ampm == "晚") && h < 12) h += 12
        return h * 60 + m
    }

    fun parse(
        raw: String,
        todayDate: String,
        focusDate: String = todayDate,
    ): ItemsQuickParseResult {
        val cleanOriginal = raw.trim()
        var title = raw
        var date: String? = null
        var rawStartMin: Int? = null
        var durMin = 60

        val focusLocal = runCatching { LocalDate.parse(focusDate) }.getOrNull() ?: LocalDate.parse(todayDate)
        val focusMonday = focusLocal.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        when {
            title.contains("今晚") -> {
                date = todayDate
                rawStartMin = 19 * 60 + 30
                title = title.replaceFirst("今晚", "")
            }
            title.contains("明早") -> {
                date = AcademicClock.addDays(todayDate, 1)
                rawStartMin = 9 * 60
                title = title.replaceFirst("明早", "")
            }
            title.contains("今天") -> {
                date = todayDate
                title = title.replaceFirst("今天", "")
            }
            title.contains("明天") -> {
                date = AcademicClock.addDays(todayDate, 1)
                title = title.replaceFirst("明天", "")
            }
            title.contains("后天") -> {
                date = AcademicClock.addDays(todayDate, 2)
                title = title.replaceFirst("后天", "")
            }
            else -> {
                val nextWeekMatch = Regex("下(周[一二三四五六日])").find(title)
                val mdMatch = Regex("(1[0-2]|0?\\d)月(\\d{1,2})[日号]?").find(title)
                    ?: Regex("(1[0-2]|0?\\d)/(\\d{1,2})").find(title)
                if (nextWeekMatch != null) {
                    val wd = dowMap[nextWeekMatch.groupValues[1]] ?: 1
                    date = focusMonday.plusWeeks(1).plusDays((wd - 1).toLong()).toString()
                    title = title.replaceFirst(nextWeekMatch.value, "")
                } else if (mdMatch != null) {
                    val m = mdMatch.groupValues[1].toIntOrNull()
                    val d = mdMatch.groupValues[2].toIntOrNull()
                    if (m != null && d != null && m in 1..12 && d in 1..31) {
                        val baseYear = focusLocal.year
                        val cand = runCatching { LocalDate.of(baseYear, m, d) }.getOrNull()
                        if (cand != null) {
                            date = if (cand.isBefore(LocalDate.parse(todayDate).minusDays(90))) {
                                cand.plusYears(1).toString()
                            } else {
                                cand.toString()
                            }
                            title = title.replaceFirst(mdMatch.value, "")
                        }
                    }
                } else {
                    for ((token, wd) in dowMap) {
                        if (title.contains(token)) {
                            date = focusMonday.plusDays((wd - 1).toLong()).toString()
                            title = title.replaceFirst(token, "")
                            break
                        }
                    }
                }
            }
        }

        if (title.contains("半小时")) {
            durMin = 30
            title = title.replaceFirst("半小时", "")
        } else {
            val durMatch = Regex("(\\d+(?:\\.\\d+)?)\\s*个?小时").find(title)
            if (durMatch != null) {
                val hours = durMatch.groupValues[1].toDoubleOrNull()
                if (hours != null && hours.isFinite()) {
                    durMin = ((hours * 60.0 / 15.0).roundToInt() * 15).coerceIn(30, 240)
                    title = title.replaceFirst(durMatch.value, "")
                }
            }
        }

        val rangeRegex = Regex("(上午|下午|晚上|晚)?\\s*(\\d{1,2})[:：点](\\d{2})?\\s*[-–~至到]\\s*(上午|下午|晚上|晚)?\\s*(\\d{1,2})[:：点](\\d{2})?")
        val singleRegex = Regex("(上午|下午|晚上|晚)?\\s*(\\d{1,2})[:：点](\\d{2})?")
        val rm = rangeRegex.find(title)
        if (rm != null) {
            val s = to24Minutes(rm.groupValues[1].takeIf { it.isNotEmpty() }, rm.groupValues[2], rm.groupValues[3].takeIf { it.isNotEmpty() })
            var e = to24Minutes(
                rm.groupValues[4].takeIf { it.isNotEmpty() } ?: rm.groupValues[1].takeIf { it.isNotEmpty() },
                rm.groupValues[5],
                rm.groupValues[6].takeIf { it.isNotEmpty() },
            )
            if (e <= s) e = s + 60
            rawStartMin = s
            durMin = (e - s).coerceAtLeast(30)
            title = title.replaceFirst(rm.value, "")
        } else {
            val tm = singleRegex.find(title)
            if (tm != null) {
                rawStartMin = to24Minutes(
                    tm.groupValues[1].takeIf { it.isNotEmpty() },
                    tm.groupValues[2],
                    tm.groupValues[3].takeIf { it.isNotEmpty() },
                )
                title = title.replaceFirst(tm.value, "")
            }
        }

        var startMin: Int? = null
        var endMin: Int? = null
        if (rawStartMin != null) {
            val (s, e) = clampTimePair(rawStartMin, durMin)
            startMin = s
            endMin = e
            if (date == null) date = focusDate
        }

        val cleaned = title.replace(Regex("\\s+"), " ").trim().ifEmpty { cleanOriginal }
        return ItemsQuickParseResult(
            cleanTitle = cleaned,
            hitDate = date,
            startMin = startMin,
            endMin = endMin,
            isAllDay = date != null && startMin == null,
        )
    }
}
