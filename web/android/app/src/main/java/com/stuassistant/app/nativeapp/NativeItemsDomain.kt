package com.stuassistant.app.nativeapp

/** Sorting may use a synthetic end-of-day instant. Detail must keep source precision. */
internal fun itemsTaskDeadline(task: AcademicTask): String = when {
    Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(task.dueRaw.trim()) -> task.dueRaw.trim()
    task.dueMs != null -> "${AcademicClock.moment(task.dueMs).date} ${AcademicClock.clockAt(task.dueMs)}"
    else -> task.dueRaw.ifBlank { "未提供" }
}
