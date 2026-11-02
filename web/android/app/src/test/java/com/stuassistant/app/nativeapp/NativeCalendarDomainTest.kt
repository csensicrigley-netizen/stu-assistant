package com.stuassistant.app.nativeapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeCalendarDomainTest {
    @Test fun dateDeadlineDoesNotBecomeMidnightAndPointDoesNotAcquireDuration() {
        val draft = JSONObject("""{"title":"报告","timezone":"Asia/Shanghai","deadline":{"kind":"date","value":"2026-11-02","boundary":"by"},"reminders":[]} """)
        assertNull(CalendarDrafts.deadline(draft))
        assertEquals("2026-11-02", CalendarDrafts.date(draft))
        val at = AcademicClock.parseTime("2026-11-02T14:00:00+08:00")!!
        val point = CalendarDrafts.edit(draft, "schedule", at)
        assertNull(CalendarDrafts.end(point))
        assertEquals(draft.getJSONObject("deadline").toString(), point.getJSONObject("deadline").toString())
        assertFalse(draft.has("schedule"))
    }
    @Test fun rangeCollapseAndDeadlineMovePreserveIndependentMeaning() {
        val draft = JSONObject("""{"title":"报告","timezone":"Asia/Shanghai","plan":{"start_date":"2026-11-01","end_date_exclusive":"2026-11-04"},"deadline":{"kind":"instant","value":"2026-11-02T17:00:00+08:00","boundary":"before"},"reminders":[{"kind":"relative","anchor":"deadline","offset_minutes":-15}],"details":{"note":"带资料"}}""")
        val at = AcademicClock.parseTime("2026-11-02T14:00:00+08:00")!!
        val range = CalendarDrafts.edit(draft, "schedule", at, at + 3600000)
        val point = CalendarDrafts.edit(range, "schedule", at, at)
        assertNull(CalendarDrafts.end(point))
        val moved = CalendarDrafts.edit(point, "deadline", at + 7200000)
        assertEquals("before", moved.getJSONObject("deadline").getString("boundary"))
        for (field in listOf("plan", "reminders", "details")) assertEquals(draft.get(field).toString(), moved.get(field).toString())
        assertEquals(at, CalendarDrafts.start(moved))
        assertEquals(2, calendarMarks(listOf(moved)).size)
    }
}
