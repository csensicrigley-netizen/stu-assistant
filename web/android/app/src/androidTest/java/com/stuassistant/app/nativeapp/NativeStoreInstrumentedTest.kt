package com.stuassistant.app.nativeapp

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real HTTPS public stub + Android Keystore tests for pure-local NativeStore. */
@RunWith(AndroidJUnit4::class)
class NativeStoreInstrumentedTest {
    private lateinit var context: IsolatedContext
    private lateinit var tls: SSLContext
    private lateinit var originalSocketFactory: SSLSocketFactory
    private val stores = mutableListOf<NativeStore>()
    private val servers = mutableListOf<LocalPublicApiStub>()

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = IsolatedContext(instrumentation.targetContext, UUID.randomUUID().toString())
        val password = "native-test-only".toCharArray()
        val keys = KeyStore.getInstance("PKCS12").apply {
            instrumentation.context.assets.open("native-api-test.p12").use { load(it, password) }
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("local-test-only", keys.getCertificate("native-api-test"))
        }
        val trustedManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, trustedManagers.trustManagers, null) }
        originalSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
    }

    @After
    fun tearDown() {
        stores.forEach { it.close() }
        servers.forEach { it.close() }
        if (::originalSocketFactory.isInitialized) HttpsURLConnection.setDefaultSSLSocketFactory(originalSocketFactory)
        if (::context.isInitialized) context.cleanup()
    }

    @Test
    fun publicWhitelistBlocksNonPublicRoutesAndNeverSendsAuthorizationHeader() = runBlocking {
        val server = server()
        val store = store(server)
        expectApiError("blocked_non_public_route") { store.request("/v1/auth/login", "POST", JSONObject()) }
        expectApiError("blocked_non_public_route") { store.request("/v1/bind", "POST", JSONObject()) }
        expectApiError("blocked_non_public_route") { store.request("/v1/relogin", "POST", JSONObject()) }
        expectApiError("blocked_non_public_route") { store.request("/v1/med/login", "POST", JSONObject()) }
        expectApiError("blocked_non_public_route") { store.request("/v1/plan", "GET") }
        expectApiError("blocked_non_public_route") { store.request("/v1/timetable", "GET") }
        expectApiError("blocked_non_public_route") { store.request("/v1/public/notices", auth = true) }

        store.refresh("read")
        assertTrue(store.state.value.notices.isNotEmpty())
        val bus = store.request("/v1/public/bus", auth = false)
        assertTrue(bus.optBoolean("ok"))
        val charging = store.request("/v1/public/charging", auth = false)
        assertNotNull(charging.optJSONArray("devices"))
        assertTrue("Public server must never receive Authorization headers", server.allAuthorizations().all { it == null })
        assertEquals(0, server.count("POST", "/v1/auth/login"))
        assertEquals(0, server.count("POST", "/v1/bind"))
        assertEquals(0, server.count("GET", "/v1/plan"))
    }

    @Test
    fun localCalendarAndPlansPersistEncryptedOfflineWithoutServerLogin() = runBlocking {
        val server = server()
        val store = store(server)
        assertNull(store.state.value.token)

        val account = store.calendarAccount()
        assertEquals(NativeStore.LOCAL_CALENDAR_KEY, account)
        val draft = JSONObject().put("title", "本地加密日程").put("timezone", "Asia/Shanghai").put("reminders", JSONArray())
        store.saveCalendar("batch-local-1", listOf(draft), account)
        store.saveCalendar("batch-local-1", listOf(draft), account)
        assertEquals(1, store.calendarItems().size)
        val itemId = store.calendarItems().single().getString("id")
        store.toggleCalendar(itemId, account)
        assertTrue(store.calendarItems().single().getBoolean("done"))

        val created = store.createPlan(planFields("本地计划一"))
        val second = store.createPlan(planFields("本地计划二"))
        assertEquals(2, store.state.value.plans.size)
        assertEquals(2, store.state.value.planSummary.getInt("today_total"))
        store.togglePlan(created.getString("id"), true)
        assertEquals(1, store.state.value.planSummary.getInt("today_done"))
        store.updatePlan(second.getString("id"), JSONObject().put("title", "本地计划二改"))
        store.reorderPlans(today(), listOf(second.getString("id"), created.getString("id")))
        store.removePlan(created.getString("id"))
        assertEquals(1, store.state.value.plans.size)
        assertEquals("本地计划二改", store.state.value.plans.single().getString("title"))

        store.close()
        val reopened = configuredStore(server.base)
        assertEquals(1, reopened.calendarItems().size)
        assertTrue(reopened.calendarItems().single().getBoolean("done"))
        assertEquals(1, reopened.state.value.plans.size)
        assertEquals("本地计划二改", reopened.state.value.plans.single().getString("title"))
        assertEquals(0, server.count("POST", "/v1/plan"))
    }

    @Test
    fun manualTimetableImportAndAnonymousTodayExportWorkWithoutLogin() = runBlocking {
        val server = server()
        val store = store(server)
        val parsed = store.importManualTextLocal(
            "数据结构 | 周一 1-2节 | 1-16周 | 周景行 | 致知楼-C304",
            format = "paste",
            semester = "2026-2027-1",
        )
        assertTrue(parsed.optBoolean("ok"))
        val doc = JSONObject()
            .put("semester", "2026-2027-1")
            .put("courses", parsed.getJSONArray("courses"))
            .put("unarranged", parsed.optJSONArray("unarranged") ?: JSONArray())
        val saved = store.saveManualTimetableLocal(doc, school = "med", note = "测试")
        assertTrue(saved.optBoolean("ok"))
        assertEquals("manual", store.state.value.activeTimetableSource)
        assertEquals("med", store.state.value.manualSchool)

        val ticket = store.exportTodayTimetable("2026-2027-1")
        assertEquals("2345ABCDJKLM", ticket.code)
        assertEquals("med", ticket.campus)
        assertEquals(1, server.count("POST", "/v1/public/export/today"))
        val sentBody = server.lastTodayExportBody.get()!!
        assertFalse(sentBody.has("student_id"))
        assertFalse(sentBody.has("email"))
        assertFalse(sentBody.has("token"))
        assertEquals("med", sentBody.getString("campus"))
        assertTrue(server.allAuthorizations().all { it == null })
    }

    @Test
    fun v1111UpgradeMigratesCalendarDoneTimetableAndSchoolPasswordAndPurgesServerToken() = runBlocking {
        val server = server()
        val uid = "v1111-user"
        val scope = "${server.base}\n$uid"
        val oldToken = server.tokenFor(uid)
        val secret = "school-secret-" + UUID.randomUUID()
        secure().write("session:${hash(server.base)}", JSONObject()
            .put("token", oldToken)
            .put("user", JSONObject().put("id", uid).put("email", "old@example.test").put("bound", JSONArray().put("jw"))))
        secure().writeCache(scope, JSONObject()
            .put("timetable", JSONObject().put("semester", "2026-2027-1")
                .put("courses", JSONArray().put(JSONObject().put("id", "c-old").put("title", "旧版课表").put("meetings", JSONArray()))))
            .put("done", JSONArray().put("old-done-1"))
            .put("plans", JSONArray().put(planFields("旧版计划").put("id", "old-plan-1")))
            .put("syncedAt", 1_700_000_000_000L))
        secure().writeCache("calendar-v1:$scope", JSONObject().put("items", JSONArray().put(
            JSONObject().put("id", "cal-old-1").put("batch", "b1").put("done", false)
                .put("draft", JSONObject().put("title", "旧版事项").put("timezone", "Asia/Shanghai")),
        )))
        secure().write("school:${hash(scope)}", JSONObject().put("id", "20230001").put("password", secret).put("remember", true))

        val upgraded = configuredStore(server.base)
        assertNull("Old server JWT must not be active", upgraded.state.value.token)
        assertNull("Old server session must be deleted", secure().read("session:${hash(server.base)}"))
        assertTrue(upgraded.state.value.onboarded)
        assertEquals("旧版课表", upgraded.state.value.timetable?.getJSONArray("courses")?.getJSONObject(0)?.getString("title"))
        assertEquals(setOf("old-done-1"), upgraded.state.value.done)
        assertEquals("20230001", upgraded.state.value.schoolId)
        assertTrue(upgraded.state.value.rememberSchool)
        assertEquals(secret, upgraded.savedSchoolPassword())
        assertEquals(1, upgraded.calendarItems().size)
        assertEquals("cal-old-1", upgraded.calendarItems().single().getString("id"))
        assertEquals(1, upgraded.state.value.plans.size)
    }

    @Test
    fun clearCampusCredentialsKeepsTimetableAndResetAllWipesEverything() = runBlocking {
        val server = server()
        val store = store(server)
        val secret = "campus-secret-" + UUID.randomUUID()
        store.rememberSchool("20239999", secret, true)
        assertEquals(secret, store.savedSchoolPassword())
        val parsed = store.importManualTextLocal("高数 | 周二 3-4节 | 1-16周 | 林老师 | A101", "paste", "2026-2027-1")
        store.saveManualTimetableLocal(JSONObject().put("semester", "2026-2027-1").put("courses", parsed.getJSONArray("courses")), "main")
        store.saveCalendar("b-keep", listOf(JSONObject().put("title", "保留事项").put("timezone", "Asia/Shanghai")), store.calendarAccount())

        store.clearCampusCredentials()
        assertFalse(store.state.value.rememberSchool)
        assertNull(store.savedSchoolPassword())
        assertEquals("", store.state.value.schoolId)
        assertNotNull("Timetable must stay after clearing credentials", store.state.value.timetable)
        assertEquals(1, store.calendarItems().size)

        store.resetAllLocalData()
        assertNull(store.state.value.timetable)
        assertFalse(store.state.value.onboarded)
        assertTrue(store.calendarItems().isEmpty())
    }

    @Test
    fun legacyWebViewImportMigratesSettingsDoneAndEncryptedSchoolWithoutToken() = runBlocking {
        val server = server()
        val store = store(server)
        val token = server.tokenFor("legacy")
        val password = "legacy-school-secret-" + UUID.randomUUID()
        val legacy = JSONObject().put("stu.api", server.base).put("stu.token", token)
            .put("stu.account", "legacy@example.test").put("stu.theme", "dark")
            .put("stu.palette", "classic").put("stu.haptics.strength", "37")
            .put("stu.done.v2:legacy", JSONArray().put("old-task").toString())
            .put("stu.school:${server.base}|legacy@example.test", JSONObject()
                .put("account", "legacy-student").put("password", password).toString())
            .put("unrelated.external.key", "must-not-be-imported")
        store.importLegacy(legacy)
        assertTrue(store.legacyMigrationDone())
        assertNull("Legacy token must not be retained", store.state.value.token)
        assertEquals("dark", store.state.value.theme)
        assertEquals("classic", store.state.value.palette)
        assertEquals(37, store.state.value.hapticStrength)
        assertEquals(setOf("old-task"), store.state.value.done)
        assertEquals("legacy-student", store.state.value.schoolId)
        assertEquals(password, store.savedSchoolPassword())
        val prefs = context.getSharedPreferences("stu.native.secure.v1", Context.MODE_PRIVATE)
        assertFalse(prefs.all.values.joinToString().contains(password))
        assertFalse(prefs.all.values.joinToString().contains(token))
    }

    @Test
    fun productStyleStoreRefusesRuntimeServerChanges() = runBlocking {
        val server = server()
        val foreign = server()
        val fixed = configuredStore(server.base, false)
        expectApiError("server_fixed") { fixed.setServer(foreign.base) }
        expectApiError("server_fixed") { fixed.setServer("") }
        assertEquals(server.base, fixed.state.value.server)
        assertEquals(0, foreign.totalRequests())
    }

    private fun server(): LocalPublicApiStub = LocalPublicApiStub(tls).also { servers += it }
    private fun store(server: LocalPublicApiStub): NativeStore = configuredStore(server.base, true)
    private fun configuredStore(server: String, allowServerChanges: Boolean = false): NativeStore =
        NativeStore(context, server, allowServerChanges).also { stores += it }
    private fun secure() = NativeSecureStorage(context)

    private suspend fun expectApiError(code: String? = null, action: suspend () -> Any?) {
        try {
            action()
            fail("Expected NativeApiError")
        } catch (error: NativeApiError) {
            if (code != null) assertEquals(code, error.code)
        }
    }

    private class IsolatedContext(base: Context, private val id: String) : ContextWrapper(base) {
        private val preferenceNames = mutableSetOf<String>()
        private val root = File(base.noBackupFilesDir, "native-instrumentation-$id")
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "native-test-$id-$name"
            synchronized(preferenceNames) { preferenceNames += isolated }
            return baseContext.getSharedPreferences(isolated, mode)
        }
        override fun getNoBackupFilesDir(): File = root.apply { check(isDirectory || mkdirs()) }
        fun cleanup() {
            synchronized(preferenceNames) {
                preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
            }
            check(root.parentFile!!.canonicalFile == baseContext.noBackupFilesDir.canonicalFile)
            root.deleteRecursively()
        }
    }

    private class LocalPublicApiStub(tls: SSLContext) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool { work ->
            Thread(work, "native-public-api-test").apply { isDaemon = true }
        }
        private val socket = (tls.serverSocketFactory.createServerSocket(0, 32,
            InetAddress.getByName("127.0.0.1")) as SSLServerSocket)
        private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
        private val counts = ConcurrentHashMap<String, AtomicInteger>()
        private val requests = ConcurrentLinkedQueue<RecordedRequest>()
        val lastTodayExportBody = AtomicReference<JSONObject?>(null)
        val base = "https://127.0.0.1:${socket.localPort}"

        init {
            executor.execute {
                while (!socket.isClosed) {
                    try {
                        val client = socket.accept() as SSLSocket
                        activeSockets += client
                        executor.execute { serve(client) }
                    } catch (_: Exception) {
                        if (!socket.isClosed) close()
                    }
                }
            }
        }

        fun tokenFor(uid: String): String {
            fun encoded(value: String) = Base64.encodeToString(value.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            return "${encoded("{\"alg\":\"none\"}")}.${encoded(JSONObject().put("sub", uid).put("iss", base).toString())}.test-only"
        }

        fun count(method: String, path: String) = counts["$method $path"]?.get() ?: 0
        fun totalRequests() = requests.size
        fun allAuthorizations(): List<String?> = requests.map { it.authorization }

        private data class Reply(val status: Int, val body: JSONObject)
        private data class RecordedRequest(val method: String, val path: String, val authorization: String?)

        private fun serve(client: SSLSocket) {
            try {
                client.soTimeout = 15_000
                client.startHandshake()
                val input = BufferedInputStream(client.inputStream)
                val start = readLine(input).split(' ')
                if (start.size < 2) return
                val method = start[0]
                val uri = URI(start[1])
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input)
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
                }
                val length = headers["content-length"]?.toInt() ?: 0
                val bytes = ByteArray(length)
                var received = 0
                while (received < length) {
                    val read = input.read(bytes, received, length - received)
                    check(read > 0)
                    received += read
                }
                val body = if (length == 0) JSONObject() else JSONObject(String(bytes, Charsets.UTF_8))
                counts.computeIfAbsent("$method ${uri.path}") { AtomicInteger() }.incrementAndGet()
                requests.add(RecordedRequest(method, uri.path, headers["authorization"]))
                val reply = dispatch(method, uri, body)
                val payload = reply.body.toString().toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 ${reply.status} ${if (reply.status == 200) "OK" else "Error"}\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                client.outputStream.write(header.toByteArray(Charsets.US_ASCII))
                client.outputStream.write(payload)
                client.outputStream.flush()
            } catch (_: Exception) {
                /* Best effort */
            } finally {
                activeSockets.remove(client)
                try { client.close() } catch (_: Exception) { }
            }
        }

        private fun dispatch(method: String, uri: URI, body: JSONObject): Reply {
            val path = uri.path
            return when {
                path == "/v1/public/notices" && method == "GET" -> Reply(
                    200,
                    JSONObject().put(
                        "items",
                        JSONArray().put(
                            JSONObject().put("id", "pub-1").put("source", "public").put("title", "公开校园通知")
                                .put("updated_at", System.currentTimeMillis() / 1000.0)
                                .put("detail", JSONObject().put("category", "通知").put("published_at", today())),
                        ),
                    ).put("count", 1),
                )
                path == "/v1/public/bus" && method == "GET" -> Reply(
                    200,
                    JSONObject().put("ok", true).put("stop", "汕头大学").put("lines", JSONArray()),
                )
                path == "/v1/public/charging" && method == "GET" -> Reply(
                    200,
                    JSONObject().put("devices", JSONArray()),
                )
                path == "/v1/public/export/today" && method == "POST" -> {
                    lastTodayExportBody.set(body)
                    Reply(
                        200,
                        JSONObject()
                            .put("code", "2345ABCDJKLM")
                            .put("expires_in", 300)
                            .put("deep_link", "today://sheet/stu-import?code=2345ABCDJKLM")
                            .put("semester", body.optString("semester"))
                            .put("campus", body.optString("campus"))
                            .put("course_count", body.optJSONArray("courses")?.length() ?: 0),
                    )
                }
                else -> Reply(404, JSONObject().put("detail", "not_found"))
            }
        }

        override fun close() {
            try { socket.close() } catch (_: Exception) { }
            activeSockets.forEach { try { it.close() } catch (_: Exception) { } }
            executor.shutdownNow()
        }

        private fun readLine(input: BufferedInputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                check(next != -1)
                if (next == '\n'.code) break
                if (next != '\r'.code) bytes.write(next)
                check(bytes.size() <= 16_384)
            }
            return bytes.toString(Charsets.US_ASCII.name())
        }
    }

    private companion object {
        fun today(): String = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
        fun planFields(title: String) = JSONObject().put("date", today()).put("title", title)
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
