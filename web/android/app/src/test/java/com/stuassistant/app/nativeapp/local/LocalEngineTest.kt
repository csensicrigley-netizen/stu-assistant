package com.stuassistant.app.nativeapp.local

import com.stuassistant.app.nativeapp.AcademicClock
import com.stuassistant.app.nativeapp.AcademicRecords
import com.stuassistant.app.nativeapp.AcademicTimetable
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.crypto.Cipher
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEngineTest {

    // =========================================================================
    // 1. Cookie Isolation, Security Boundaries & CAS Handshake (LocalSchoolHttp)
    // =========================================================================

    @Test
    fun cookieJar_isolatesSsoFromHttpJwAndNeverPersistsCentralSsoCookies() {
        val ssoJar = IsolatedCookieJar(setOf(LocalSchoolHttp.SSO_HOST), allowSsoBridgeCapture = true)
        val jwJar = IsolatedCookieJar(setOf(LocalSchoolHttp.JW_HOST), allowSsoBridgeCapture = false)
        val mystuJar = IsolatedCookieJar(setOf(LocalSchoolHttp.MYSTU_HOST), allowSsoBridgeCapture = false)

        val ssoUrl = "https://sso.stu.edu.cn/login".toHttpUrl()
        val tgc = Cookie.Builder().name("TGC").value("TGT-secret-123").domain("sso.stu.edu.cn").path("/").secure().httpOnly().build()
        val ut = Cookie.Builder().name("_UT_").value("ut-token-xyz").domain("stu.edu.cn").path("/").build()
        ssoJar.saveFromResponse(ssoUrl, listOf(tgc, ut))

        // Central SSO cookies MUST NOT persist to disk even if requested directly.
        assertEquals(0, ssoJar.exportPersistedCookies(LocalSchoolHttp.SSO_HOST).length())
        assertEquals(0, ssoJar.exportPersistedCookies("stu.edu.cn").length())

        // `_UT_` MUST NEVER bridge to JW.
        LocalSchoolHttp.bridgeServiceToken(ssoJar, jwJar, "jw")
        assertTrue(jwJar.snapshotCookies().isEmpty())

        // `_UT_` bridges to MySTU narrowed to host-only `my.stu.edu.cn` with `secure=true`.
        LocalSchoolHttp.bridgeServiceToken(ssoJar, mystuJar, "mystu")
        val mystuHttps = "https://my.stu.edu.cn/discussion/my-courses".toHttpUrl()
        val mystuHttp = "http://my.stu.edu.cn/discussion/my-courses".toHttpUrl()
        assertEquals(listOf("_UT_"), mystuJar.loadForRequest(mystuHttps).map { it.name })
        assertTrue("Secure _UT_ must never be sent over http", mystuJar.loadForRequest(mystuHttp).isEmpty())

        // JW jar receives a JSESSIONID on jw.stu.edu.cn + a secure cookie + attempts to inject foreign/SSO cookies.
        val jwHttpUrl = "http://jw.stu.edu.cn/jwglxt/xtgl/login_slogin.html".toHttpUrl()
        val jwHttpsUrl = "https://jw.stu.edu.cn/jsxsd/xskb/xskb_list.do".toHttpUrl()
        jwJar.saveFromResponse(
            jwHttpsUrl,
            listOf(
                Cookie.Builder().name("JSESSIONID").value("jw-sess-1").domain("jw.stu.edu.cn").path("/").build(),
                Cookie.Builder().name("SEC_ONLY").value("sec-val").domain("jw.stu.edu.cn").path("/").secure().build(),
                Cookie.Builder().name("TGC").value("forged-tgc").domain("sso.stu.edu.cn").path("/").build(),
                Cookie.Builder().name("PARENT").value("parent-val").domain("stu.edu.cn").path("/").build(),
            ),
        )

        // Over http://jw.stu.edu.cn, ONLY non-secure jw.stu.edu.cn cookie is sent; never TGC/PARENT/SEC_ONLY.
        val sentOverHttp = jwJar.loadForRequest(jwHttpUrl).map { it.name }
        assertEquals(listOf("JSESSIONID"), sentOverHttp)
        assertEquals(setOf("JSESSIONID", "SEC_ONLY"), jwJar.loadForRequest(jwHttpsUrl).map { it.name }.toSet())

        // Tampered persisted JSON with SSO cookies must be stripped on import.
        val tampered = JSONArray()
            .put(JSONObject().put("name", "JSESSIONID").put("value", "ok").put("domain", "jw.stu.edu.cn").put("path", "/"))
            .put(JSONObject().put("name", "TGC").put("value", "bad").put("domain", "sso.stu.edu.cn").put("path", "/"))
            .put(JSONObject().put("name", "TGC").put("value", "bad2").put("domain", "jw.stu.edu.cn").put("path", "/"))
            .put(JSONObject().put("name", "_UT_").put("value", "bad3").put("domain", "stu.edu.cn").put("path", "/"))
        val importedJar = IsolatedCookieJar(setOf(LocalSchoolHttp.JW_HOST))
        importedJar.importPersistedCookies(LocalSchoolHttp.JW_HOST, tampered)
        assertEquals(listOf("JSESSIONID"), importedJar.snapshotCookies().map { it.name })

        // clearSsoCookies purges in-memory SSO cookies and bridge tokens.
        ssoJar.clearSsoCookies()
        assertTrue(ssoJar.snapshotCookies().isEmpty())
        assertNull(ssoJar.captureBridgeUtToken("mystu"))
    }

    @Test
    fun casLoginJw_completesHandshakeWithoutLeakingSsoCookieToHttpCallback() {
        val observedJwHttpCookies = mutableListOf<String>()
        withMockTransport(
            handler = { req ->
                val url = req.url.toString()
                when {
                    url == LocalSchoolHttp.JW_LOGIN_URL ->
                        htmlResp(req, 200, "<html>JW Login</html>", "Set-Cookie" to "JSESSIONID=jw-prime; Path=/")

                    req.method == "GET" && url.startsWith(LocalSchoolHttp.SSO_LOGIN_URL) ->
                        htmlResp(
                            req,
                            200,
                            """
                            <form method="post" action="${req.url}">
                                <input name="username" />
                                <input name="password" type="password" />
                                <input type="hidden" name="lt" value="LT-123" />
                                <input type="hidden" name="execution" value="e1s1" />
                                <input type="hidden" name="_eventId" value="submit" />
                            </form>
                            """.trimIndent(),
                            "Set-Cookie" to "JSESSIONID=sso-jsess; Path=/; Secure",
                        )

                    req.method == "POST" && url.startsWith(LocalSchoolHttp.SSO_LOGIN_URL) -> {
                        assertTrue("Credentials must only be posted over HTTPS", req.url.isHttps)
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(302)
                            .message("Found")
                            .header("Location", "${LocalSchoolHttp.JW_LOGIN_HTTP_URL}?ticket=ST-999-stu")
                            .addHeader("Set-Cookie", "TGC=TGT-central-secret; Path=/; Secure; HttpOnly")
                            .addHeader("Set-Cookie", "_UT_=parent-ut-token; Domain=.stu.edu.cn; Path=/")
                            .body("".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }

                    url.startsWith(LocalSchoolHttp.JW_LOGIN_HTTP_URL) -> {
                        observedJwHttpCookies.add(req.header("Cookie").orEmpty())
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(302)
                            .message("Found")
                            .header("Location", LocalSchoolHttp.JW_HOME)
                            .addHeader("Set-Cookie", "JSESSIONID=jw-authed-99; Path=/")
                            .body("".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }

                    url == LocalSchoolHttp.JW_HOME ->
                        htmlResp(
                            req,
                            200,
                            """<html><body><a id="NEW_XSD_XJCJ_WDCJ_KCCJCX">成绩</a><a id="NEW_XSD_KSBM_WDKS_KSAPCX">考试</a></body></html>""",
                        )

                    else -> htmlResp(req, 404, "not found")
                }
            },
        ) {
            val jwClient = LocalJwClient(paceDelayMs = 0L)
            val res = jwClient.login("20240001@stu.edu.cn", "secret-pass")

            assertTrue(res.verified)
            assertEquals(1, observedJwHttpCookies.size)
            assertFalse("TGC must never leak to HTTP JW", observedJwHttpCookies[0].contains("TGC"))
            assertFalse("_UT_ must never leak to HTTP JW", observedJwHttpCookies[0].contains("_UT_"))
            assertFalse("SSO JSESSIONID must never leak to HTTP JW", observedJwHttpCookies[0].contains("sso-jsess"))
            assertTrue(observedJwHttpCookies[0].contains("JSESSIONID=jw-prime"))

            val persisted = res.sessionJson.getJSONArray("cookies")
            assertEquals(1, persisted.length())
            assertEquals("jw.stu.edu.cn", persisted.getJSONObject(0).getString("domain"))
            assertEquals("jw-authed-99", persisted.getJSONObject(0).getString("value"))
            assertFalse(res.sessionJson.toString().contains("TGT-central-secret"))
            assertFalse(res.sessionJson.toString().contains("sso.stu.edu.cn"))
        }
    }

    @Test
    fun casLogin_supportsInteractiveCaptchaAndMapsWrongCredentials() {
        var captchaRequested = false
        withMockTransport(
            handler = { req ->
                val url = req.url.toString()
                when {
                    url == LocalSchoolHttp.MYSTU_ENTRY_URL ->
                        Response.Builder()
                            .request(req).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                            .header("Location", "${LocalSchoolHttp.SSO_LOGIN_URL}?service=${LocalSchoolHttp.urlEncode(LocalSchoolHttp.MYSTU_ENTRY_URL)}")
                            .body("".toResponseBody("text/plain".toMediaType()))
                            .build()

                    req.method == "GET" && url.startsWith(LocalSchoolHttp.SSO_LOGIN_URL) ->
                        htmlResp(
                            req,
                            200,
                            """
                            <form method="post" action="${req.url}">
                                <input name="username" />
                                <input name="password" type="password" />
                                <input name="captcha" />
                                <img src="/captcha.jpg" id="captchaImg" />
                                <input type="hidden" name="lt" value="LT-1" />
                                <input type="hidden" name="execution" value="e1s1" />
                                <input type="hidden" name="_eventId" value="submit" />
                            </form>
                            """.trimIndent(),
                        )

                    req.method == "GET" && url == "https://sso.stu.edu.cn/captcha.jpg" ->
                        Response.Builder()
                            .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()).toResponseBody("image/jpeg".toMediaType()))
                            .build()

                    req.method == "POST" && url.startsWith(LocalSchoolHttp.SSO_LOGIN_URL) ->
                        htmlResp(req, 200, """<html><body><div class="errors">用户名或密码错误</div></body></html>""")

                    else -> htmlResp(req, 404, "not found")
                }
            },
        ) {
            val mystu = LocalMyStuClient()
            // Without a challengeHandler, captcha form throws captcha_required immediately.
            val noHandlerErr = runCatching { mystu.login("20240001", "wrong") }.exceptionOrNull() as? LocalSchoolError
            assertNotNull(noHandlerErr)
            assertEquals("captcha_required", noHandlerErr!!.code)

            // With a challengeHandler, captcha is collected and bad password produces invalid_credentials.
            val wrongPassErr = runCatching {
                mystu.login("20240001", "wrong") { challenge ->
                    assertTrue(challenge is SchoolChallenge.Captcha)
                    captchaRequested = true
                    "ABCD"
                }
            }.exceptionOrNull() as? LocalSchoolError
            assertTrue(captchaRequested)
            assertNotNull(wrongPassErr)
            assertEquals("invalid_credentials", wrongPassErr!!.code)
        }
    }

    // =========================================================================
    // 2. JW Timetable, Grades & Exams HTML Parsing (LocalJwClient)
    // =========================================================================

    private fun jwBlock(title: String = "高等数学", time: String = "1-16(周)[01-02节]", omit: String? = null): String {
        val fields = linkedMapOf(
            "教师" to "林致远",
            "周次(节次)" to time,
            "班级" to "${title}01班",
            "通知单编号" to "N-$title",
            "教室" to "E305",
        )
        val inner = fields.filterKeys { it != omit }.entries.joinToString("") { (k, v) ->
            """<font title="$k">$v</font>"""
        }
        return "<font>$title</font>$inner"
    }

    private fun jwInventory(titles: List<String>): String = titles.joinToString("") { t ->
        "<tr><td>${t}01班</td><td>C-$t</td><td>$t</td><td>林致远</td></tr>"
    }

    private fun jwPage(
        blocks: String,
        titles: List<String> = listOf("高等数学", "坏课"),
        extraInventory: String = "",
        unarranged: List<String> = emptyList(),
        secondRow: String = "",
    ): String {
        val headings = "<th>上课班级</th><th>课程编号</th><th>课程名称</th><th>授课教师</th>"
        val dayHeaders = "一二三四五六日".map { "<th>星期$it</th>" }.joinToString("")
        return """
            <form method="post" action="${LocalJwClient.JW_TIMETABLE_URL}">
              <select name="xnxq01id"><option value="2026-2027-1" selected>2026-2027-1</option></select>
              <select name="zc"><option value="" selected>全部</option></select>
              <select name="kbjcmsid"><option value="default">默认</option></select>
              <input type="hidden" name="cj0701id" value="">
              <input type="hidden" name="demo" value="">
              <input type="checkbox" name="wkbkc" value="1" checked>
            </form>
            <table id="dataTables2"><tr>$headings</tr>${jwInventory(titles)}$extraInventory</table>
            <table id="dataTables"><tr>$headings</tr>${jwInventory(unarranged)}</table>
            <table id="timetable">
              <tr><th>节次</th>$dayHeaders</tr>
              <tr><td>第一大节（1,2小节）</td><td><div class="kbcontent">$blocks</div></td>${"<td></td>".repeat(6)}</tr>
              $secondRow
            </table>
        """.trimIndent()
    }

    @Test
    fun jwTimetable_spanningPeriodsAndDeduplicationAndDomainCompatibility() {
        val course = jwBlock(time = "1-16(周)[01-02-03-04节]")
        val secondRow = """<tr><td>第二大节（3,4小节）</td><td><div class="kbcontent">$course</div></td>${"<td></td>".repeat(6)}</tr>"""
        val doc = LocalJwClient.parseTimetableHtml(jwPage(course, titles = listOf("高等数学"), secondRow = secondRow), "2026-2027-1")

        assertTrue(doc.getJSONObject("coverage").getBoolean("complete"))
        assertEquals(0, doc.getJSONArray("pending").length())
        val courses = doc.getJSONArray("courses")
        assertEquals(1, courses.length())
        val c0 = courses.getJSONObject(0)
        assertEquals("高等数学", c0.getString("title"))
        assertEquals("林致远", c0.getString("teacher"))
        assertEquals(1, c0.getJSONArray("meetings").length())

        // Verify 100% compatibility with Android's AcademicTimetable.normalize()
        val normalized = AcademicTimetable.normalize(doc)
        assertNotNull(normalized)
        assertEquals("2026-2027-1", normalized!!.semester)
        assertEquals(1, normalized.courses.size)
        assertEquals(listOf(1, 2, 3, 4), normalized.courses[0].meetings[0].periods)
        assertEquals((1..16).toList(), normalized.courses[0].meetings[0].weeks)
        assertEquals("E305", normalized.courses[0].meetings[0].location)
    }

    @Test
    fun jwTimetable_reportsPartialFailuresAndRejectsAllBad() {
        val good = jwBlock("高等数学", "1-8(单周)[01-02节]")
        val badTime = jwBlock("坏课", "unrecognized")
        val partial = LocalJwClient.parseTimetableHtml(jwPage(badTime + good), "2026-2027-1")
        assertEquals(1, partial.getJSONArray("courses").length())
        assertEquals("高等数学", partial.getJSONArray("courses").getJSONObject(0).getString("title"))
        assertFalse(partial.getJSONObject("coverage").getBoolean("complete"))
        assertEquals(1, partial.getJSONObject("coverage").getInt("skipped_time_count"))
        assertEquals(1, partial.getJSONArray("unplaced_details").length())
        assertTrue(partial.getJSONArray("pending").length() > 0)
        assertEquals(listOf(1, 3, 5, 7), AcademicTimetable.normalize(partial)!!.courses[0].meetings[0].weeks)

        // All bad blocks must throw schema_changed rather than producing an empty timetable.
        val err = runCatching {
            LocalJwClient.parseTimetableHtml(jwPage(jwBlock("坏课", "unrecognized"), titles = listOf("坏课")), "2026-2027-1")
        }.exceptionOrNull() as? LocalSchoolError
        assertNotNull(err)
        assertEquals("schema_changed", err!!.code)

        // Only unarranged courses must succeed as partial.
        val unarrangedOnly = LocalJwClient.parseTimetableHtml(jwPage("", titles = emptyList(), unarranged = listOf("劳动实践")), "2026-2027-1")
        assertEquals(0, unarrangedOnly.getJSONArray("courses").length())
        assertEquals("劳动实践", unarrangedOnly.getJSONArray("unarranged").getJSONObject(0).getString("title"))
        assertEquals("partial", unarrangedOnly.getString("status"))
    }

    @Test
    fun jwGradesAndExams_parseHtmlTablesIntoAcademicDomainRecords() {
        val gradesHtml = """
            <table>
              <tr><th>开课学期</th><th>课程编号</th><th>课程名称</th><th>成绩</th><th>学分</th><th>绩点</th><th>考试性质</th><th>考核方式</th></tr>
              <tr><td>2025-2026-2</td><td>MATH101</td><td>微积分</td><td>92</td><td>4.0</td><td>4.2</td><td>正常考试</td><td>闭卷</td></tr>
              <tr><td>2025-2026-2</td><td>PE101</td><td>体育</td><td>良好</td><td>1.0</td><td>3.5</td><td>正常考试</td><td>考查</td></tr>
              <tr><td colspan="8">合计：2门</td></tr>
            </table>
        """.trimIndent()
        val grades = LocalJwClient.parseGradesHtml(gradesHtml)
        assertEquals(2, grades.size)
        val g0 = AcademicRecords.grade(grades[0])
        assertEquals("微积分", g0.title)
        assertEquals("92", g0.score)
        assertEquals(4.0, g0.credits!!, 0.001)
        assertEquals(4.2, g0.gpa!!, 0.001)

        val emptyGradesHtml = """
            <table>
              <tr><th>开课学期</th><th>课程编号</th><th>课程名称</th><th>成绩</th><th>学分</th><th>绩点</th></tr>
              <tr><td colspan="6">未查询到数据</td></tr>
            </table>
        """.trimIndent()
        assertTrue(LocalJwClient.parseGradesHtml(emptyGradesHtml).isEmpty())

        val examsHtml = """
            <table>
              <tr><th>校区</th><th>课程编号</th><th>课程名称</th><th>考试时间</th><th>考场</th><th>座位号</th><th>备注</th></tr>
              <tr><td>桑浦山</td><td>MATH101</td><td>微积分</td><td>2027-01-12 08:30~10:30</td><td>E305</td><td>18</td><td>带学生证</td></tr>
            </table>
        """.trimIndent()
        val exams = LocalJwClient.parseExamsHtml(examsHtml, "2026-2027-1")
        assertEquals(1, exams.size)
        val e0 = AcademicRecords.exam(exams[0])
        assertEquals("微积分", e0.title)
        assertEquals("2027-01-12", e0.date)
        assertEquals("E305", e0.location)
        assertEquals("18", e0.seat)
        assertNotNull(e0.startMs)

        val examQueryHtml = """<select name="xnxqid"><option value="2026-2027-1" selected>2026-2027-1</option></select>"""
        assertEquals("2026-2027-1", LocalJwClient.parseExamSemester(examQueryHtml))

        assertEquals(
            listOf("2026-2027-1", "2025-2026-2", "2025-2026-1", "2024-2025-2"),
            LocalJwClient.gradeTerms("2026-2027-1", 4),
        )
    }

    // =========================================================================
    // 3. MySTU Courses, Tasks & Moodle Detail Parsing (LocalMyStuClient)
    // =========================================================================

    @Test
    fun mystu_parsesColumnarMultiTermCoursesAndFlattenedTasksInShanghaiTz() {
        val coursePayload = JSONObject(
            """
            {
              "fields": "id,name,teacher,url,courseCode,attendanceYear,attendanceSemester",
              "items": [
                [101, "数据结构", "陈老师", "http://my.stu.edu.cn/courses/elc/course/view.php?id=555", "CS201", 2025, 2],
                [101, "数据结构", "陈老师", "https://my.stu.edu.cn/courses/campus/course/view.php?id=888", "CS201", 2026, 1]
              ]
            }
            """.trimIndent(),
        )
        val courses = LocalMyStuClient.parseCoursesPayload(coursePayload)
        assertEquals(2, courses.size)
        // Sorted newest (year, term) first; legacy http ELC URL upgraded to https.
        assertEquals(2026, courses[0].year)
        assertEquals(1, courses[0].term)
        assertEquals("https://my.stu.edu.cn/courses/elc/course/view.php?id=555", courses[1].url)

        val actPayload = JSONObject(
            """
            {
              "courseActivities": [
                {
                  "activityId": 9001,
                  "activityTitle": "第一次实验报告",
                  "activityCategory": "assignment",
                  "activityUrl": "https://my.stu.edu.cn/courses/campus/mod/assign/view.php?id=9001",
                  "additionalDueDate": "2026-10-29 23:59:00"
                }
              ]
            }
            """.trimIndent(),
        )
        val tasks = LocalMyStuClient.parseActivitiesPayload(actPayload, courses[0], "888")
        assertEquals(1, tasks.size)
        val taskDomain = AcademicRecords.task(tasks[0])
        assertEquals("task-9001", taskDomain.id)
        assertEquals("第一次实验报告", taskDomain.title)
        assertEquals("数据结构", taskDomain.course)
        assertEquals("mystu", taskDomain.source)

        val expectedMs = LocalDateTime.of(2026, 10, 29, 23, 59, 0).toInstant(ZoneOffset.ofHours(8)).toEpochMilli()
        assertEquals(expectedMs, taskDomain.dueMs)
        assertEquals("2026-10-29", AcademicClock.moment(taskDomain.dueMs!!).date)

        // Moodle assignment detail page fallback parsing
        val detailHtml = """
            <div id="page-mod-assign-view">
              <div id="intro">请按要求提交 PDF 报告。</div>
              <table class="submissionstatustable">
                <tr><th>Submission status</th><td>No attempt</td></tr>
                <tr><th>截止日期</th><td>2026年11月05日 23:55</td></tr>
              </table>
            </div>
        """.trimIndent()
        val detail = LocalMyStuClient.parseAssignmentDetailHtml(detailHtml)
        val expectedDetailMs = LocalDateTime.of(2026, 11, 5, 23, 55, 0).toInstant(ZoneOffset.ofHours(8)).toEpochMilli()
        assertEquals(expectedDetailMs, detail.dueMs)
        assertEquals("No attempt", detail.schoolStatus)
        assertTrue(detail.description.contains("PDF"))
    }

    // =========================================================================
    // 4. MedJW RSA-512, Read-Only Whitelist, CAS/Local Login & Timetable (LocalMedJwClient)
    // =========================================================================

    @Test
    fun medJw_rsa512EncryptsValidPkcs1v15AndRoundTripsWithJca() {
        assertEquals(512, LocalMedJwClient.PUBLIC_MODULUS.bitLength())
        assertEquals(65537L, LocalMedJwClient.PUBLIC_EXPONENT.toLong())

        val c1 = LocalMedJwClient.rsaPassword("med-pass-2026")
        val c2 = LocalMedJwClient.rsaPassword("med-pass-2026")
        assertEquals(LocalMedJwClient.RSA_B64_LENGTH, c1.length)
        assertEquals(64, LocalBase64.decode(c1).size)
        assertNotEquals("Random PKCS#1 v1.5 padding must differ across encryptions", c1, c2)
        assertEquals(88, LocalMedJwClient.rsaPassword("中文密码也行").length)
        assertEquals(88, LocalMedJwClient.rsaPassword("x".repeat(LocalMedJwClient.RSA_MAX_PLAIN)).length)

        // Over 53 bytes must throw Chinese LocalSchoolError.
        val tooLong = runCatching { LocalMedJwClient.rsaPassword("x".repeat(LocalMedJwClient.RSA_MAX_PLAIN + 1)) }
            .exceptionOrNull() as? LocalSchoolError
        assertNotNull(tooLong)
        assertTrue(tooLong!!.detail.contains("太长"))

        // Verify rsaEncryptRaw mathematically against standard JCA RSA/ECB/PKCS1Padding decryption.
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
        val pair = kpg.generateKeyPair()
        val pub = pair.public as RSAPublicKey
        val secret = "医学@2026".toByteArray(Charsets.UTF_8)
        val encrypted = LocalMedJwClient.rsaEncryptRaw(secret, pub.modulus, pub.publicExponent)
        assertEquals(128, encrypted.size)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, pair.private)
        assertEquals("医学@2026", String(cipher.doFinal(encrypted), Charsets.UTF_8))
    }

    @Test
    fun medJw_enforcesReadWhitelistAndParsesTimetableGradesExamsProfile() {
        assertTrue(LocalMedJwClient.isReadPath("/appapi/Studentkb/index"))
        assertTrue(LocalMedJwClient.isReadPath("/appapi/captcha/getCaptchaCode?yzmcode=123"))
        for (writePath in listOf("/appapi/Studentxk/addxk", "/appapi/Studentxk/cancel", "/appapi/UpPasswd", "/appapi/getsmsCode")) {
            assertFalse("Write path must be blocked: $writePath", LocalMedJwClient.isReadPath(writePath))
        }

        // Login error translations
        val badLogin = LocalMedJwClient.parseLoginPayload(JSONObject().put("code", 500).put("msg", "app_retrun_error_applogin"))
        assertFalse(badLogin.ok)
        assertEquals("bad_credentials", badLogin.errorCode)
        assertTrue(badLogin.error.contains("不正确"))

        val badCap = LocalMedJwClient.parseLoginPayload(JSONObject().put("code", 500).put("msg", "验证码不正确"))
        assertEquals("bad_captcha", badCap.errorCode)

        val weak = LocalMedJwClient.parseLoginPayload(
            JSONObject().put("code", 200).put("weakPass", 1).put("user", JSONObject().put("token", "t")),
        )
        assertFalse(weak.ok)
        assertEquals("weak_pass", weak.errorCode)

        // Timetable with room change across weeks, multiple teachers, glued jcdm="0102", and unarranged row
        val day0 = JSONObject().put(
            "03",
            JSONArray()
                .put(medRow(zc = 1, teaxms = "欧少闽", jxcdmc = "理433"))
                .put(medRow(zc = 2, teaxms = "罗舜庭", jxcdmc = "理433"))
                .put(medRow(zc = 3, teaxms = "欧少闽", jxcdmc = "理434")),
        ).put(
            "01",
            JSONArray().put(medRow(kcmc = "解剖学", kcbh = "MED101", jcdm = "0102", jcdm2 = "", zc = 5)),
        ).put(
            "00",
            JSONArray().put(JSONObject().put("kcmc", "临床见习").put("kcbh", "MED999").put("teaxms", "王医生")),
        )
        val kbArray = JSONArray().put(day0)
        repeat(6) { kbArray.put(JSONObject()) }
        val payload = JSONObject().put("code", 200).put("xnxqdm", "2026-2027-1").put("zc", 8).put("kbList", kbArray.toString())

        val doc = LocalMedJwClient.parseTimetablePayload(payload, "2026-2027-1")
        assertEquals("med", doc.getString("campus"))
        assertEquals("partial", doc.getString("status"))
        assertEquals(2, doc.getJSONArray("courses").length())
        assertEquals(1, doc.getJSONArray("unarranged").length())

        val normalized = AcademicTimetable.normalize(doc)
        assertNotNull(normalized)
        val bio = normalized!!.courses.first { it.title == "细胞生物学实验" }
        // Room change (理433 vs 理434) MUST stay two separate meetings with distinct IDs.
        assertEquals(2, bio.meetings.size)
        assertNotEquals(bio.meetings[0].id, bio.meetings[1].id)
        val m433 = bio.meetings.first { it.location.contains("理433") }
        assertEquals(listOf(1, 2), m433.weeks)
        assertTrue(m433.teacher.contains("欧少闽") && m433.teacher.contains("罗舜庭"))

        val anat = normalized.courses.first { it.title == "解剖学" }
        assertEquals(listOf(1, 2), anat.meetings[0].periods)

        // Profile must NEVER carry sfzh (national ID).
        val profile = LocalMedJwClient.parseProfilePayload(
            JSONObject().put("code", 200).put(
                "xjxx",
                JSONObject().put("xsxm", "林同学").put("xsbh", "2024999").put("yxmc", "医学院").put("sfzh", "440500200501011234"),
            ),
        )
        assertTrue(profile.getBoolean("ok"))
        assertFalse(profile.toString().contains("440500200501011234"))
        assertFalse(profile.toString().contains("sfzh"))
    }

    @Test
    fun medJw_casLoginAnd401ExpiryFlow() {
        withMockTransport(
            handler = { req ->
                val url = req.url.toString()
                when {
                    req.method == "GET" && url.startsWith(LocalMedJwClient.CAS_LOGIN_URL) ->
                        htmlResp(req, 200, """<form><input name="execution" value="exec-abc" /></form>""")

                    req.method == "POST" && url.startsWith(LocalMedJwClient.CAS_LOGIN_URL) ->
                        Response.Builder()
                            .request(req).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                            .header("Location", "${LocalMedJwClient.CAS_SERVICE}?ticket=ST-med-ticket-1")
                            .body("".toResponseBody("text/plain".toMediaType()))
                            .build()

                    url == "${LocalMedJwClient.API}/ssologin" ->
                        jsonResp(
                            req,
                            200,
                            """{"code":200,"user":{"token":"med-tok-777","userxm":"林同学","userbh":"2024888"}}""",
                        )

                    url == "${LocalMedJwClient.API}/Studentkb/index" -> {
                        assertEquals("med-tok-777", req.header("token"))
                        jsonResp(req, 401, """{"code":401,"msg":"请先登录！"}""")
                    }

                    else -> htmlResp(req, 404, "not found")
                }
            },
        ) {
            val med = LocalMedJwClient()
            val login = med.loginCas("2024888", "pw")
            assertTrue(login.ok)
            assertEquals("med-tok-777", login.token)
            assertEquals("林同学", login.user.name)

            val expErr = runCatching { med.fetchTimetable(login.token, "2026-2027-1") }.exceptionOrNull() as? LocalSchoolError
            assertNotNull(expErr)
            assertEquals("med_expired", expErr!!.code)
        }
    }

    // =========================================================================
    // 5. Offline Manual/CSV Timetable Importer (LocalTimetableImporter)
    // =========================================================================

    @Test
    fun importer_parsesPasteCsvTemplateAndValidatesMerges() {
        // Fictional CSV template must have BOM and parse cleanly with zero errors.
        val tpl = LocalTimetableImporter.templateCsv()
        assertTrue(tpl.startsWith("\uFEFF"))
        val tplParsed = LocalTimetableImporter.parseCsv(tpl, "2026-2027-1")
        assertTrue(tplParsed.getBoolean("ok"))
        assertEquals(0, tplParsed.getJSONArray("errors").length())
        assertEquals(3, tplParsed.getJSONObject("stats").getInt("courses"))

        // Paste & CSV produce identical deterministic IDs and hue values.
        val pasteText = """
            前端工程实践 | 周四 6-9节 | 1-16周 | 沈清扬 | 实验楼-508
            高等数学 | 周一 1-2节,周三 3-4节 | 1-16单周 | 周老师 | 305
            医学伦理学 | 王老师
        """.trimIndent()
        val parsed = LocalTimetableImporter.parsePaste(pasteText, "2026-2027-1")
        assertTrue(parsed.getBoolean("ok"))
        assertEquals(3, parsed.getJSONObject("stats").getInt("courses"))
        assertEquals(3, parsed.getJSONObject("stats").getInt("meetings"))
        assertEquals(1, parsed.getJSONArray("unarranged").length())

        val doc = LocalTimetableImporter.toTimetableDoc(parsed.getJSONArray("courses"), "2026-2027-1")
        assertEquals("partial", doc.getString("status"))
        val normalized = AcademicTimetable.normalize(doc)!!
        val math = normalized.courses.first { it.title == "高等数学" }
        assertEquals("周老师", math.teacher)
        assertEquals("305", math.meetings[0].location)
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15), math.meetings[0].weeks)
        assertEquals(2, math.meetings.size)

        // Error reporting with exact 1-based line numbers.
        val bad = LocalTimetableImporter.parsePaste("好课 | 周一 1-2节 | 1-16周\n坏课 | 周八 14-15节 | 31-40周", "2026-2027-1")
        assertFalse(bad.getBoolean("ok"))
        val errs = bad.getJSONArray("errors")
        assertTrue((0 until errs.length()).any { errs.getString(it).contains("第 2 行") && errs.getString(it).contains("周八") })

        // Single-course validation and merge into existing document.
        val extra = JSONObject()
            .put("title", "线性代数")
            .put("teacher", "许知行")
            .put(
                "meetings",
                JSONArray().put(
                    JSONObject()
                        .put("weekday", 5)
                        .put("periods", JSONArray(listOf(3, 4)))
                        .put("weeks", JSONArray(listOf(1, 2, 3, 4)))
                        .put("location", "明理楼-A102"),
                ),
            )
        val merged = LocalTimetableImporter.validateAndMergeCourse(doc, extra, "2026-2027-1")
        assertTrue(merged.getBoolean("ok"))
        assertEquals(3, merged.getJSONObject("doc").getJSONArray("courses").length())
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    private fun medRow(
        kcmc: String = "细胞生物学实验",
        kcbh: String = "BIO2101",
        teaxms: String = "何昭明",
        jxcdmc: String = "理433",
        szxqmc: String = "桑浦山校区",
        xq: Int = 1,
        zc: Int = 1,
        jcdm: String = "0304",
        jcdm2: String = "03,04",
    ): JSONObject = JSONObject()
        .put("kcmc", kcmc)
        .put("kcbh", kcbh)
        .put("jxbmc", "${kcmc}01班")
        .put("teaxms", teaxms)
        .put("jxcdmc", jxcdmc)
        .put("szxqmc", szxqmc)
        .put("xq", xq)
        .put("zc", zc)
        .put("jcdm", jcdm)
        .put("jcdm2", jcdm2)
        .put("qssj", "10:00:00")
        .put("jssj", "11:40:00")

    private fun withMockTransport(
        handler: (Request) -> Response,
        block: suspend () -> Unit,
    ) = runBlocking {
        LocalSchoolHttp.testTransport = { client, req ->
            val cookies = client.cookieJar.loadForRequest(req.url)
            val withCookie = if (cookies.isNotEmpty()) {
                val cookieHeader = cookies.joinToString("; ") { "${it.name}=${it.value}" }
                req.newBuilder().header("Cookie", cookieHeader).build()
            } else {
                req
            }
            val resp = handler(withCookie)
            val received = Cookie.parseAll(req.url, resp.headers)
            if (received.isNotEmpty()) {
                client.cookieJar.saveFromResponse(req.url, received)
            }
            resp
        }
        try {
            block()
        } finally {
            LocalSchoolHttp.testTransport = null
        }
    }

    private fun htmlResp(req: Request, code: Int, html: String, vararg headers: Pair<String, String>): Response {
        val builder = Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "ERR")
            .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        return builder.build()
    }

    private fun jsonResp(req: Request, code: Int, json: String): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "ERR")
            .body(json.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
}
