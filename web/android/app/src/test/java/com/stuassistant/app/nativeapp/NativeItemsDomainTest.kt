package com.stuassistant.app.nativeapp

import org.json.JSONArray
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
}
