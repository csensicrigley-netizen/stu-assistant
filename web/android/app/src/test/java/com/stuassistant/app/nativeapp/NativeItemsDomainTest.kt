package com.stuassistant.app.nativeapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeItemsDomainTest {
    @Test fun listScrubUsesTheNearestPageAndAlwaysKeepsTheFractionInRange() {
        assertEquals(4 to 0f, ItemsCalendarMath.listPagePosition(4f, 10))
        assertEquals(4 to 0.25f, ItemsCalendarMath.listPagePosition(4.25f, 10))
        assertEquals(5 to -0.25f, ItemsCalendarMath.listPagePosition(4.75f, 10))
        assertEquals(5 to -0.5f, ItemsCalendarMath.listPagePosition(4.5f, 10))
        assertEquals(0 to 0f, ItemsCalendarMath.listPagePosition(-0.6f, 10))
        assertEquals(9 to 0f, ItemsCalendarMath.listPagePosition(9.6f, 10))
    }

    @Test fun changingScheduledTimePreservesIndependentPlanAndDeadline() {
        val plan = JSONObject().put("start_date", "2026-11-18").put("end_date_exclusive", "2026-11-24")
        val deadline = JSONObject().put("kind", "date").put("value", "2026-11-22")
        val draft = JSONObject().put("title", "课题").put("plan", plan).put("deadline", deadline)
        val moved = ItemsDrafts.setTimed(
            draft.put("schedule", JSONObject().put("start", "2026-11-19T14:00:00+08:00").put("source", "keep")),
            "2026-11-20", 21 * 60 + 45, 22 * 60,
        )
        assertEquals(plan.toString(), moved.getJSONObject("plan").toString())
        assertEquals(deadline.toString(), moved.getJSONObject("deadline").toString())
        assertEquals("keep", moved.getJSONObject("schedule").getString("source"))
        val item = checkNotNull(ItemsDrafts.parseRecord(JSONObject().put("id", "timed").put("draft", moved)))
        assertEquals("21:45", item.startClock)
        assertEquals("22:00", item.endClock)
        val unscheduled = ItemsDrafts.moveToInbox(moved)
        assertFalse(unscheduled.has("schedule"))
        assertEquals(plan.toString(), unscheduled.getJSONObject("plan").toString())
        assertEquals(deadline.toString(), unscheduled.getJSONObject("deadline").toString())
    }

    @Test fun movingADeadlineUpdatesThePointWithoutInventingASchedule() {
        val point = JSONObject().put("kind", "instant").put("value", "2026-11-23T10:30:00+08:00")
            .put("source", "mystu")
        val draft = JSONObject().put("title", "提交").put("deadline", point)
        val moved = ItemsDrafts.setTimed(draft, "2026-11-24", 690, 750)
        assertFalse(moved.has("schedule"))
        val result = moved.getJSONObject("deadline")
        assertEquals("mystu", result.getString("source"))
        assertEquals("2026-11-24T11:30+08:00", result.getString("value"))
    }

    @Test fun twoPersonalEventsStartingBeforeACourseKeepDistinctNonOverlappingLanes() {
        val lanes = itemsPackDayLanes(
            fixedIntervals = listOf(ItemsGridInterval("course", 600, 755, 0)),
            personalIntervals = listOf(ItemsGridInterval("first", 570, 660, 1), ItemsGridInterval("second", 585, 675, 1)),
        )
        for (density in listOf(1, 3, 5, 7)) {
            val columnWidth = 350f / density
            val geometry = lanes.values.map { computeBlockGeometry(columnWidth, it, density, 1f) }.sortedBy { it.leftPx }
            assertEquals(3, geometry.size)
            assertTrue(geometry.all { it.leftPx >= 0f && it.widthPx > 0f && it.leftPx + it.widthPx <= columnWidth })
            assertTrue(geometry.zipWithNext().all { (first, second) -> first.leftPx + first.widthPx <= second.leftPx })
        }
    }

    @Test fun dateOnlyDeadlineNeverDisplaysInvented2359() {
        val task = AcademicRecords.task(JSONObject().put("id", "activity").put("title", "报告").put("due_at", "2026-11-02"))
        assertNotNull("Existing sorting adapter supplies an internal instant", task.dueMs)
        assertEquals("2026-11-02", itemsTaskDeadline(task))
    }

    @Test fun actualTimeAndMissingTimeKeepTheirMeaning() {
        val exact = AcademicRecords.task(JSONObject().put("due_at", "2026-11-02T17:00:00+08:00"))
        assertEquals("2026-11-02 17:00", itemsTaskDeadline(exact))
        assertEquals("未提供", itemsTaskDeadline(AcademicRecords.task(JSONObject())))
    }

    @Test fun removeCalendarRecordDeletesMatchingEntryAndPreservesOthers() {
        val doc = JSONObject().put(
            "items",
            JSONArray(
                listOf(
                    JSONObject().put("id", "keep-1").put("done", false),
                    JSONObject().put("id", "drop-me").put("done", true),
                    JSONObject().put("id", "keep-2").put("done", false),
                ),
            ),
        )
        assertTrue(removeCalendarRecordFromDocument(doc, "drop-me"))
        val remaining = academicObjects(doc.optJSONArray("items")).map { it.optString("id") }
        assertEquals(listOf("keep-1", "keep-2"), remaining)
        assertFalse(removeCalendarRecordFromDocument(doc, "drop-me"))
        assertFalse(removeCalendarRecordFromDocument(doc, ""))
    }

    @Test fun paletteNormalizationAcceptsAllCampusThemesAndFallsBackCleanly() {
        for (key in listOf("sweet", "classic", "phoenix", "pine", "lilac", "latte")) {
            assertEquals(key, normalizeNativePalette(key))
        }
        assertEquals("sweet", normalizeNativePalette(null))
        assertEquals("sweet", normalizeNativePalette(""))
        assertEquals("sweet", normalizeNativePalette("unknown-theme"))
    }

    @Test fun itemsDraftsRoundTripsTimedAllDayInboxAndPreservesUnknownFields() {
        val rec = ItemsDrafts.newRecord(
            title = "导师课题组周会",
            note = "汇报数据清洗进度",
            priority = ItemsPriority.IMPORTANT,
            date = "2026-11-17",
            startMin = 14 * 60 + 30,
            endMin = 15 * 60 + 30,
            schoolTaskId = "task-hw-9",
            id = "rec-1",
            batch = "batch-1",
        )
        val rawDraft = rec.getJSONObject("draft")
        rawDraft.put("custom_extra", "keep-me")
        rawDraft.getJSONObject("details").put("source_tag", "preserve-tag")

        val timedModel = ItemsDrafts.parseRecord(rec)
        assertNotNull(timedModel)
        timedModel!!
        assertEquals("rec-1", timedModel.id)
        assertEquals("导师课题组周会", timedModel.title)
        assertEquals("汇报数据清洗进度", timedModel.note)
        assertEquals(ItemsPriority.IMPORTANT, timedModel.priority)
        assertEquals("2026-11-17", timedModel.date)
        assertEquals(14 * 60 + 30, timedModel.startMin)
        assertEquals(15 * 60 + 30, timedModel.endMin)
        assertFalse(timedModel.isAllDay)
        assertTrue(timedModel.isTimed)
        assertTrue(timedModel.isScheduled)
        assertEquals("task-hw-9", timedModel.schoolTaskId)

        val dUpdated = ItemsDrafts.setPriority(
            ItemsDrafts.setNote(
                ItemsDrafts.setTitle(rawDraft, "周会改名"),
                "带上打印初稿",
            ),
            ItemsPriority.URGENT,
        )
        val allDayDraft = ItemsDrafts.setAllDay(dUpdated, "2026-11-18")
        assertEquals("keep-me", allDayDraft.optString("custom_extra"))
        assertEquals("preserve-tag", allDayDraft.getJSONObject("details").optString("source_tag"))
        val allDayModel = ItemsDrafts.parseRecord(JSONObject().put("id", "rec-1").put("draft", allDayDraft))!!
        assertEquals("周会改名", allDayModel.title)
        assertEquals("带上打印初稿", allDayModel.note)
        assertEquals(ItemsPriority.URGENT, allDayModel.priority)
        assertEquals("2026-11-18", allDayModel.date)
        assertTrue(allDayModel.isAllDay)
        assertFalse(allDayModel.isTimed)

        val inboxDraft = ItemsDrafts.moveToInbox(allDayDraft)
        assertEquals("keep-me", inboxDraft.optString("custom_extra"))
        val inboxModel = ItemsDrafts.parseRecord(JSONObject().put("id", "rec-1").put("draft", inboxDraft))!!
        assertNull(inboxModel.date)
        assertNull(inboxModel.startMin)
        assertFalse(inboxModel.isScheduled)
        assertFalse(inboxModel.isAllDay)
    }

    @Test fun quickParserExtractsChineseDatesRangesDurationsAndClampsEarlyMorning() {
        val today = "2026-11-17" // Tuesday
        val tonight = ItemsQuickParser.parse("今晚复习生物统计半小时", today)
        assertEquals(today, tonight.hitDate)
        assertEquals(19 * 60 + 30, tonight.startMin)
        assertEquals(20 * 60, tonight.endMin)
        assertEquals("复习生物统计", tonight.cleanTitle)
        assertFalse(tonight.isAllDay)

        val tomMorning = ItemsQuickParser.parse("明早交实验报告", today)
        assertEquals("2026-11-18", tomMorning.hitDate)
        assertEquals(9 * 60, tomMorning.startMin)
        assertEquals(10 * 60, tomMorning.endMin)
        assertEquals("交实验报告", tomMorning.cleanTitle)

        val range = ItemsQuickParser.parse("明天下午2点到4点 讨论挑战杯", today)
        assertEquals("2026-11-18", range.hitDate)
        assertEquals(14 * 60, range.startMin)
        assertEquals(16 * 60, range.endMin)
        assertEquals("讨论挑战杯", range.cleanTitle)

        val friday = ItemsQuickParser.parse("周五 15:00 实验室组会 2个小时", today)
        assertEquals("2026-11-20", friday.hitDate)
        assertEquals(15 * 60, friday.startMin)
        assertEquals(17 * 60, friday.endMin)
        assertEquals("实验室组会", friday.cleanTitle)

        val earlyClamp = ItemsQuickParser.parse("今天 06:30 早读", today)
        assertEquals(today, earlyClamp.hitDate)
        assertEquals(480, earlyClamp.startMin)
        assertEquals(540, earlyClamp.endMin)
        assertEquals("早读", earlyClamp.cleanTitle)

        val allDayOnly = ItemsQuickParser.parse("明天交核酸实验", today)
        assertEquals("2026-11-18", allDayOnly.hitDate)
        assertNull(allDayOnly.startMin)
        assertTrue(allDayOnly.isAllDay)
    }

    @Test fun smartSchedulerAvoidsBusyIntervalsAndFindsClosestSlot() {
        val busy = listOf(
            600 to 720, // 10:00 - 12:00 course
            840 to 900, // 14:00 - 15:00 item
        )
        // Prefer 10:15 (615): closest conflict-free 60m slot is 09:00..10:00 (dist 75 vs 720..780 dist 105)
        val slot1 = ItemsSmartScheduler.findFreeSlot(durationMin = 60, preferredStartMin = 615, busy = busy)
        assertEquals(540 to 600, slot1)

        // Prefer 11:30 (690): closest conflict-free 60m slot is 12:00..13:00 (dist 30)
        val slot2 = ItemsSmartScheduler.findFreeSlot(durationMin = 60, preferredStartMin = 690, busy = busy)
        assertEquals(720 to 780, slot2)

        // Completely full day yields null
        val full = ItemsSmartScheduler.findFreeSlot(durationMin = 60, preferredStartMin = 600, busy = listOf(480 to 1320))
        assertNull(full)
        assertEquals(615 to 675, ItemsSmartScheduler.findFreeSlot(60, 600, emptyList(), dayStartMin = 601))
        assertNull(ItemsSmartScheduler.findFreeSlot(60, 600, emptyList(), dayStartMin = 601, dayEndMin = 645))
    }

    @Test fun dateWindowAlignsToMondayAndCalendarMathCurvesAreMonotonic() {
        val win = ItemsDateWindow(today = "2026-11-17")
        assertTrue(win.totalDays % 7 == 0)
        assertEquals(0, win.todayIndex / 7 * 7 - win.todayIndex / 7 * 7)
        assertEquals(java.time.DayOfWeek.MONDAY, java.time.LocalDate.parse(win.dates.first()).dayOfWeek)
        assertEquals(java.time.DayOfWeek.SUNDAY, java.time.LocalDate.parse(win.dates.last()).dayOfWeek)
        assertEquals("2026-11-17", win.dateAt(win.todayIndex))

        assertEquals(0f, ItemsCalendarMath.edgeAutoScrollFactor(0f), 1e-5f)
        assertEquals(1f, ItemsCalendarMath.edgeAutoScrollFactor(1f), 1e-5f)
        val fLow = ItemsCalendarMath.edgeAutoScrollFactor(0.25f)
        val fMid = ItemsCalendarMath.edgeAutoScrollFactor(0.5f)
        val fHigh = ItemsCalendarMath.edgeAutoScrollFactor(0.75f)
        assertTrue(fLow in 0f..fMid && fMid in fLow..fHigh && fHigh < 1f)
        // Nonlinear convexity: midpoint < 0.5
        assertTrue(fMid < 0.35f)

        val off3 = ItemsCalendarMath.offsetForDayIndex(win.todayIndex, 3, win.totalDays)
        assertEquals((win.todayIndex - 1).toFloat(), off3, 1e-5f)
        val snap7 = ItemsCalendarMath.snapOffsetForScale(7, win.todayIndex + 0.4f, 0.5f, win.totalDays)
        assertEquals(0f, snap7 % 7f, 1e-5f)
        val snapPan = ItemsCalendarMath.nearestGridStop(off3 + 0.8f, cols = 3, totalDays = win.totalDays)
        assertEquals(off3 + 1f, snapPan, 1e-5f)
    }

    @Test fun gridFlingKeepsMomentumAndBrakesAtEachAlignedDate() {
        val withoutCrossing = ItemsCalendarMath.stepGridFling(14.10f, 12f, 0.025f, 60f)
        val crossing = ItemsCalendarMath.stepGridFling(14.95f, 12f, 0.025f, 60f)
        assertTrue(crossing.offset > 15f)
        assertTrue(withoutCrossing.offset < 15f)
        assertEquals(withoutCrossing.velocity * 0.88f, crossing.velocity, 0.0001f)

        var frame = ItemsCalendarMath.GridFlingFrame(14.25f, 16f)
        repeat(120) {
            if (frame.velocity >= ItemsCalendarMath.gridFlingStopVelocity) {
                frame = ItemsCalendarMath.stepGridFling(frame.offset, frame.velocity, 1f / 60f, 60f)
            }
        }
        assertTrue("A flick must continue beyond the next date", frame.offset > 17f)
        assertTrue(frame.velocity < ItemsCalendarMath.gridFlingStopVelocity)
        assertEquals(18f, ItemsCalendarMath.nearestGridStop(frame.offset, 3, 63), 0f)

        assertEquals(0, ItemsCalendarMath.crossedGridDates(16f, 15.9f))
        assertEquals(2, ItemsCalendarMath.crossedGridDates(16.1f, 14.9f))
        assertEquals(2, ItemsCalendarMath.crossedGridDates(14.9f, 16.1f))
        assertEquals(ItemsCalendarMath.GridFlingFrame(60f, 0f),
            ItemsCalendarMath.stepGridFling(59.9f, 10f, 0.025f, 60f))
        assertEquals(21f, ItemsCalendarMath.nearestGridStop(19.4f, 7, 63), 0f)
    }

    @Test fun aLateGridCardUsesTheUncoveredViewportAndKeepsATallCardsTitleVisible() {
        assertEquals(466f, ItemsCalendarMath.scrollForVisibleGridCard(260f, 680f, 728f, 280f, 18f), 0f)
        assertEquals(162f, ItemsCalendarMath.scrollForVisibleGridCard(140f, 180f, 560f, 260f, 18f), 0f)
        assertEquals(100f, ItemsCalendarMath.scrollForVisibleGridCard(100f, 160f, 205f, 230f, 18f), 0f)
        assertEquals(100f, ItemsCalendarMath.scrollForVisibleGridCard(100f, 160f, 205f, 30f, 18f), 0f)
    }

    @Test fun lanePackingDetectsCourseAndPersonalOverlapsCleanly() {
        val fixed = listOf(ItemsGridInterval("course-1", 600, 700, pri = 0))
        val personal = listOf(
            ItemsGridInterval("item-clash", 630, 690, pri = 1),
            ItemsGridInterval("item-free", 840, 900, pri = 1),
        )
        val placed = itemsPackDayLanes(fixed, personal)
        val pCourse = placed["course-1"]!!
        val pClash = placed["item-clash"]!!
        val pFree = placed["item-free"]!!

        assertEquals(2, pCourse.lanes)
        assertEquals(0, pCourse.lane)
        assertTrue(pCourse.overlapsPersonal)
        assertFalse(pCourse.overlapsFixed)

        assertEquals(2, pClash.lanes)
        assertEquals(1, pClash.lane)
        assertTrue(pClash.overlapsFixed)
        assertFalse(pClash.overlapsPersonal)

        assertEquals(1, pFree.lanes)
        assertEquals(0, pFree.lane)
        assertFalse(pFree.overlapsFixed)
        assertFalse(pFree.overlapsPersonal)
    }

    @Test fun eventTonesProduceValidFiniteRgbAcrossAllPalettesModesAndPriorities() {
        val palettes = listOf("sweet", "classic", "phoenix", "pine", "lilac", "latte")
        for (pal in palettes) {
            for (dark in listOf(false, true)) {
                for (prio in ItemsPriority.entries) {
                    for (done in listOf(false, true)) {
                        val t = itemsEventTones(dark = dark, palette = pal, priority = prio, done = done)
                        val swatches = listOf(
                            t.accent, t.titleInk, t.timeInk, t.glassTop, t.glassBottom,
                            t.border, t.dotFill, t.solidBg, t.solidFg, t.warnAccent, t.urgentAccent, t.okAccent,
                        )
                        for (c in swatches) {
                            assertTrue(c.red in 0f..1f && c.green in 0f..1f && c.blue in 0f..1f && c.alpha in 0f..1f)
                        }
                    }
                }
            }
        }
    }
}
