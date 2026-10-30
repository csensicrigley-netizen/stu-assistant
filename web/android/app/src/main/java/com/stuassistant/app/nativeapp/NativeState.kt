package com.stuassistant.app.nativeapp

import org.json.JSONObject

/** Raw backend records stay intact; presentation/domain adapters live outside the store. */
data class NativeState(
    val server: String = "",
    val token: String? = null,
    val user: JSONObject? = null,
    val preview: Boolean = false,
    val sample: Boolean = false,
    val mode: String = "cached",
    val timetable: JSONObject? = null,
    val tasks: List<JSONObject> = emptyList(),
    val exams: List<JSONObject> = emptyList(),
    val grades: List<JSONObject> = emptyList(),
    val notices: List<JSONObject> = emptyList(),
    val plans: List<JSONObject> = emptyList(),
    val sources: List<JSONObject> = emptyList(),
    val planSummary: JSONObject = JSONObject(),
    val loading: Boolean = false,
    val syncing: Boolean = false,
    val error: String? = null,
    val done: Set<String> = emptySet(),
    val theme: String = "system",
    val palette: String = "sweet",
    val hapticStrength: Int = 100,
    val schoolId: String = "",
    val rememberSchool: Boolean = false,
    val syncedAt: Long? = null,
)

class NativeApiError(
    val code: String,
    val status: Int = 0,
    val detail: String = code,
) : Exception(detail)
