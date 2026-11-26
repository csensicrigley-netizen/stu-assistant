package com.stuassistant.app.nativeapp

import com.stuassistant.app.nativeapp.local.LocalSchoolError
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NativeLocalArchitectureTest {

    @Test
    fun publicRouteWhitelist_allowsOnlyApprovedAnonymousPublicEndpoints() {
        val allowed = listOf(
            "GET" to "/v1/public/notices",
            "GET" to "/v1/public/notices?limit=30",
            "GET" to "/v1/public/bus",
            "GET" to "/v1/public/charging",
            "GET" to "/v1/announcements",
            "GET" to "/v1/announcements?platform=android&version_code=68",
            "GET" to "/v1/meta/version",
            "GET" to "/v1/meta/version?name=1.2.0&code=68",
            "POST" to "/v1/public/export/today",
        )
        for ((method, path) in allowed) {
            val route = NativeApi.validatePublicRoute(method, path)
            assertTrue("Expected allowed route for $method $path", route.startsWith("/v1/"))
        }
    }

    @Test
    fun publicRouteWhitelist_blocksAllAccountPrivateSchoolAndExternalParseRoutes() = runBlocking {
        val api = NativeApi()
        val session = NativeSession("https://stu.oyishi.asia")
        val blocked = listOf(
            "POST" to "/v1/auth/login",
            "POST" to "/v1/auth/register",
            "POST" to "/v1/bind",
            "GET" to "/v1/bind/job-123",
            "POST" to "/v1/bind/job-123/input",
            "DELETE" to "/v1/bind/job-123",
            "POST" to "/v1/relogin",
            "GET" to "/v1/med/captcha",
            "GET" to "/v1/med/status",
            "POST" to "/v1/med/login",
            "POST" to "/v1/med/sync",
            "DELETE" to "/v1/med/session",
            "POST" to "/v1/webvpn/login/start",
            "POST" to "/v1/webvpn/auto",
            "POST" to "/v1/sync?sources=public,jw,mystu",
            "GET" to "/v1/status",
            "GET" to "/v1/me",
            "GET" to "/v1/timetable",
            "POST" to "/v1/timetable/import/parse",
            "POST" to "/v1/timetable/import/save",
            "GET" to "/v1/timetable/manual?semester=2026-2027-1",
            "GET" to "/v1/timetable/import/template",
            "GET" to "/v1/grades",
            "GET" to "/v1/exams",
            "GET" to "/v1/feed?kinds=task,homework",
            "GET" to "/v1/notices?limit=10",
            "GET" to "/v1/bus",
            "GET" to "/v1/charging",
            "GET" to "/v1/plan",
            "POST" to "/v1/plan",
            "POST" to "/v1/plan/abc/toggle",
            "DELETE" to "/v1/plan/abc",
            "POST" to "/v1/plan/reorder",
            "POST" to "/v1/account/logout",
            "DELETE" to "/v1/account",
            "POST" to "/v1/export/today",
            "GET" to "/v1/health",
            "POST" to "/api/calendar/v2/mobile/parse",
            // Wrong HTTP verbs on whitelisted routes must also be blocked.
            "POST" to "/v1/public/notices",
            "DELETE" to "/v1/public/notices",
            "GET" to "/v1/public/export/today",
            "POST" to "/v1/announcements",
            "POST" to "/v1/meta/version",
        )
        try {
            for ((method, path) in blocked) {
                try {
                    api.request(session, path, method, if (method == "POST") JSONObject() else null)
                    fail("Expected blocked_non_public_route for $method $path")
                } catch (error: NativeApiError) {
                    assertEquals("Route $method $path must be hard-blocked", "blocked_non_public_route", error.code)
                }
            }
        } finally {
            api.close()
        }
    }

    @Test
    fun publicRouteWhitelist_rejectsTraversalAndAbsoluteUriBypassAttempts() {
        val malformed = listOf(
            "/v1/public/notices/../me",
            "/v1/public/notices/%2e%2e/me",
            "https://evil.example/v1/public/notices",
            "//evil.example/v1/public/notices",
            "/v1/public/notices#frag",
            "/v1/public/notices\\..\\me",
        )
        for (path in malformed) {
            try {
                NativeApi.validatePublicRoute("GET", path)
                fail("Expected rejection for malformed path: $path")
            } catch (error: NativeApiError) {
                assertTrue(
                    "Unexpected code ${error.code} for $path",
                    error.code in setOf("invalid_path", "blocked_non_public_route"),
                )
            }
        }
    }

    @Test
    fun sanitizeTimetableForTodayExport_stripsAllPiiAndKeepsOnlyTimetableFields() {
        val raw = JSONObject()
            .put("semester", "2026-2027-1")
            .put("checked_at", "2026-11-26T10:00:00+08:00")
            .put("student_id", "2023100001")
            .put("email", "student@stu.edu.cn")
            .put("token", "secret-jwt-token")
            .put("cookie", "JSESSIONID=topsecret")
            .put("password", "my-secret-password")
            .put(
                "courses",
                JSONArray().put(
                    JSONObject()
                        .put("id", "c-1")
                        .put("title", "数据结构与算法")
                        .put("course_code", "CS201")
                        .put("class_name", "CS201-01")
                        .put("teacher", "周景行")
                        .put("student_id", "2023100001")
                        .put(
                            "meetings",
                            JSONArray().put(
                                JSONObject()
                                    .put("id", "m-1")
                                    .put("weekday", 2)
                                    .put("weeks", JSONArray(listOf(1, 2, 3, 4)))
                                    .put("periods", JSONArray(listOf(3, 4)))
                                    .put("location", "致知楼-C304")
                                    .put("teacher", "周景行")
                                    .put("raw_time", "1-4(周)[03-04节]")
                                    .put("email", "leak@stu.edu.cn")
                                    .put("token", "leak-token"),
                            ),
                        ),
                ),
            )

        val sanitized = sanitizeTimetableForTodayExport(raw, campus = "jw")
        assertNotNull(sanitized)
        sanitized!!
        val serialized = sanitized.toString()
        for (secret in listOf("2023100001", "student@stu.edu.cn", "secret-jwt-token", "JSESSIONID", "my-secret-password", "leak@stu.edu.cn", "leak-token")) {
            assertFalse("PII leaked into today export payload: $secret", serialized.contains(secret))
        }
        assertEquals("2026-2027-1", sanitized.getString("semester"))
        assertEquals("main", sanitized.getString("campus"))
        assertEquals(1, sanitized.getJSONArray("courses").length())
        val course = sanitized.getJSONArray("courses").getJSONObject(0)
        assertEquals("数据结构与算法", course.getString("title"))
        assertEquals(1, course.getJSONArray("meetings").length())
        assertNull(sanitizeTimetableForTodayExport(JSONObject().put("semester", "2026-2027-1").put("courses", JSONArray()), campus = "manual"))
    }

    @Test
    fun localPlanHelpers_normalizeAndComputeSummaryAccurately() {
        val today = "2026-11-26"
        val plans = listOf(
            normalizeLocalPlanRow(JSONObject().put("date", "2026-11-25").put("title", "逾期未做").put("done", false), 0),
            normalizeLocalPlanRow(JSONObject().put("date", "2026-11-25").put("title", "昨日已完成").put("done", true), 1),
            normalizeLocalPlanRow(JSONObject().put("date", today).put("title", "今日待办").put("done", false), 0),
            normalizeLocalPlanRow(JSONObject().put("date", today).put("title", "今日完成").put("done", true), 1),
            normalizeLocalPlanRow(JSONObject().put("date", "2026-11-28").put("title", "后天计划").put("done", false), 2),
            normalizeLocalPlanRow(JSONObject().put("date", "2026-11-27").put("title", "明天计划").put("done", false), 0),
        )
        assertTrue(plans.all { it.getString("id").startsWith("plan-") })
        val summary = computeLocalPlanSummary(plans, today = today)
        assertEquals(2, summary.getInt("today_total"))
        assertEquals(1, summary.getInt("today_done"))
        assertEquals(1, summary.getInt("today_open"))
        assertEquals(1, summary.getInt("overdue"))
        val upcoming = summary.getJSONArray("upcoming")
        assertEquals(2, upcoming.length())
        assertEquals("明天计划", upcoming.getJSONObject(0).getString("title"))
        assertEquals("后天计划", upcoming.getJSONObject(1).getString("title"))
    }

    @Test
    fun selectActiveTimetable_supportsPriorityFusionAndExplicitSourceOverride() {
        fun doc(title: String) = JSONObject()
            .put("semester", "2026-2027-1")
            .put("courses", JSONArray().put(JSONObject().put("id", "c-$title").put("title", title).put("meetings", JSONArray())))

        val vault = JSONObject()
            .put(
                "timetables",
                JSONObject()
                    .put("jw", doc("本部课"))
                    .put("med", doc("医学院课"))
                    .put("manual", doc("手动课")),
            )
            .put(
                "timetableUpdatedAt",
                JSONObject()
                    .put("jw", 1000L)
                    .put("med", 2000L)
                    .put("manual", 3000L),
            )

        val (autoSrc, autoDoc, available) = selectActiveTimetable(vault)
        assertEquals("manual", autoSrc)
        assertEquals("手动课", autoDoc!!.getJSONArray("courses").getJSONObject(0).getString("title"))
        assertEquals(listOf("jw", "med", "manual"), available)

        vault.put("preferredTimetableSource", "med")
        val (medSrc, medDoc, _) = selectActiveTimetable(vault)
        assertEquals("med", medSrc)
        assertEquals("医学院课", medDoc!!.getJSONArray("courses").getJSONObject(0).getString("title"))

        vault.put("preferredTimetableSource", "jw")
        val (jwSrc, jwDoc, _) = selectActiveTimetable(vault)
        assertEquals("jw", jwSrc)
        assertEquals("本部课", jwDoc!!.getJSONArray("courses").getJSONObject(0).getString("title"))
    }

    @Test
    fun formatLocalError_distinguishesScenariosAndNeverLeaksRawExceptions() {
        val badCred = formatLocalError(LocalSchoolError("invalid_credentials", "raw"))
        assertTrue(badCred.contains("密码不正确"))

        val medCred = formatLocalError(LocalSchoolError("bad_credentials", "raw"), scope = "med")
        assertTrue(medCred.contains("教务本地口令"))

        val captcha = formatLocalError(LocalSchoolError("captcha_required", "raw"))
        assertTrue(captcha.contains("图形验证码") && captcha.contains("重新连接"))

        val totp = formatLocalError(LocalSchoolError("totp_required", "raw"))
        assertTrue(totp.contains("动态口令") && totp.contains("重新连接"))

        val maintenance = formatLocalError(LocalSchoolError("source_unavailable", "raw"))
        assertTrue(maintenance.contains("维护") && maintenance.contains("离线查看"))

        val offCampus = formatLocalError(NativeApiError("network"))
        assertTrue(offCampus.contains("校外网络") || offCampus.contains("网络"))
        assertFalse(offCampus == "network")

        val expired = formatLocalError(LocalSchoolError("login_expired", "raw"))
        assertTrue(expired.contains("会话已过期") && expired.contains("重新连接"))

        val medExpired = formatLocalError(LocalSchoolError("med_expired", "raw"), scope = "med")
        assertTrue(medExpired.contains("医学院") && medExpired.contains("过期"))

        val rawJavaErr = formatLocalError(RuntimeException("javax.net.ssl.SSLHandshakeException: Chain validation failed"))
        assertFalse(rawJavaErr.contains("SSLHandshakeException"))
        assertFalse(rawJavaErr.contains("javax"))
        assertTrue(rawJavaErr.contains("网络") || rawJavaErr.contains("重试"))

        val rawApiErr = formatLocalError(NativeApiError("unknown_internal_500", 500, "java.io.IOException: broken pipe"))
        assertFalse(rawApiErr.contains("IOException"))
        assertFalse(rawApiErr.contains("broken pipe"))
    }
}
