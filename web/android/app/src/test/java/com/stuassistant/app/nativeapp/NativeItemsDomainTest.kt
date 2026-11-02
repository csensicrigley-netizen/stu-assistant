package com.stuassistant.app.nativeapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeItemsDomainTest {
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
}
