package com.stuassistant.app.nativeapp

import org.json.JSONArray
import org.json.JSONObject

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
