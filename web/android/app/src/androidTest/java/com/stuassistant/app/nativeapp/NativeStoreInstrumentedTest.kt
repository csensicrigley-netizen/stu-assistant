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
import java.net.URLDecoder
import java.security.KeyStore
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

/** Real HTTPS transport and device Keystore; no production account or remote server is used. */
@RunWith(AndroidJUnit4::class)
class NativeStoreInstrumentedTest {
    private lateinit var context: IsolatedContext
    private lateinit var tls: SSLContext
    private lateinit var originalSocketFactory: SSLSocketFactory
    private val stores = mutableListOf<NativeStore>()
    private val servers = mutableListOf<LocalApiStub>()

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
        // Only this fixture's certificate is trusted; hostname verification still checks the SAN.
        HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
    }

    @After
    fun tearDown() {
        stores.forEach { it.close() }
        servers.forEach { it.close() }
        if (::originalSocketFactory.isInitialized) HttpsURLConnection.setDefaultSSLSocketFactory(originalSocketFactory)
        if (::context.isInitialized) context.cleanup()
        // Deliberately do not delete or replace the application's Keystore alias.
    }

    @Test
    fun schoolAuthenticationErrorKeepsSessionButAppAuthenticationErrorClearsIt() = runBlocking {
        val server = server()
        val store = signedIn(server, "alice")
        val token = store.state.value.token
        expectApiError("login_failed") { store.request("/v1/test/school401", "POST") }
        assertEquals(token, store.state.value.token)
        assertEquals("alice", store.state.value.user?.optString("id"))
        expectApiError("invalid_token") { store.request("/v1/test/session401") }
        assertNull(store.state.value.token)
        assertNull(secure().read("session:${hash(server.base)}"))
    }

    @Test
    fun serverAndAccountCachesKeepLocalTaskCompletionIsolated() = runBlocking {
        val first = server()
        val second = server()
        val store = signedIn(first, "alice")
        store.toggleTask("task-alice")
        assertTrue(store.state.value.done.contains("task-alice"))
        store.login("bob@example.test", "test-passphrase")
        awaitReady(store, "bob")
        assertFalse(store.state.value.done.contains("task-alice"))
        store.toggleTask("task-bob")
        store.setServer(second.base)
        assertNull(store.state.value.token)
        store.login("alice@example.test", "test-passphrase")
        awaitReady(store, "alice")
        assertTrue(store.state.value.done.isEmpty())
        store.toggleTask("second-server-only")
        store.setServer(first.base)
        assertEquals("bob", store.state.value.user?.optString("id"))
        assertEquals(setOf("task-bob"), store.state.value.done)
        store.login("alice@example.test", "test-passphrase")
        awaitReady(store, "alice")
        assertEquals(setOf("task-alice"), store.state.value.done)
        assertFalse(store.state.value.done.contains("second-server-only"))
        assertEquals(setOf("task-alice"), doneInCache(first, "alice"))
        assertEquals(setOf("task-bob"), doneInCache(first, "bob"))
        assertEquals(setOf("second-server-only"), doneInCache(second, "alice"))
    }

    @Test
    fun partialServiceFailureKeepsOnlyItsOldDataAndDoesNotAdvanceSyncedAt() = runBlocking {
        val server = server()
        val store = signedIn(server, "alice")
        val syncedAt = store.state.value.syncedAt
        assertEquals("grade-alice-1", store.state.value.grades.single().optString("title"))
        server.revision = 2
        server.failGrades = true
        store.refresh("read")
        assertEquals("grade-alice-1", store.state.value.grades.single().optString("title"))
        assertEquals("exam-alice-2", store.state.value.exams.single().optString("title"))
        assertEquals(syncedAt, store.state.value.syncedAt)
        assertNotNull(store.state.value.error)
        val cached = secure().readCache("${server.base}\nalice")!!
        assertEquals("grade-alice-1", cached.getJSONArray("grades").getJSONObject(0).getString("title"))
        assertEquals(syncedAt, cached.getLong("syncedAt"))
    }

    @Test
    fun rememberedSchoolSecretsAreEncryptedBoundToTheirKeyAndRemovedWhenDisabled() = runBlocking {
        val server = server()
        val store = signedIn(server, "alice")
        val secret = "school-secret-" + UUID.randomUUID()
        store.rememberSchool("student-24680", secret, true)
        assertTrue(store.state.value.rememberSchool)
        assertEquals(secret, store.savedSchoolPassword())
        val key = "school:${hash("${server.base}\nalice")}"
        val prefs = context.getSharedPreferences("stu.native.secure.v1", Context.MODE_PRIVATE)
        val ciphertext = prefs.getString(key, null)
        assertNotNull(ciphertext)
        assertFalse(ciphertext!!.contains(secret))
        assertFalse(ciphertext.contains("student-24680"))
        assertFalse(prefs.all.values.joinToString().contains(store.state.value.token!!))
        assertEquals(secret, secure().read(key)!!.getString("password"))
        assertTrue(prefs.edit().putString("school:wrong-scope", ciphertext).commit())
        expectApiError("secure_storage") { secure().read("school:wrong-scope") }
        secure().remove("school:wrong-scope")
        store.rememberSchool("student-24680", "", false)
        assertFalse(store.state.value.rememberSchool)
        assertNull(store.savedSchoolPassword())
        assertNull(secure().read(key))
        val cacheText = cacheFile(server, "alice").readText()
        assertFalse(cacheText.contains("grade-alice-1"))
        assertFalse(cacheText.contains(store.state.value.token!!))
    }

    @Test
    fun logoutAndDeleteAccountRemoveTheirEncryptedSessionCacheAndSchoolSecret() = runBlocking {
        val server = server()
        for (operation in listOf("logout", "delete")) {
            val store = signedIn(server, operation)
            store.rememberSchool("student-$operation", "test-school-password", true)
            store.toggleTask("task-$operation")
            val cache = cacheFile(server, operation)
            assertTrue(cache.exists())
            assertNotNull(secure().read("session:${hash(server.base)}"))
            if (operation == "logout") store.logout() else store.deleteAccount()
            assertNull(store.state.value.token)
            assertNull(secure().read("session:${hash(server.base)}"))
            assertNull(secure().read("school:${hash("${server.base}\n$operation")}"))
            assertNull(secure().readCache("${server.base}\n$operation"))
            assertFalse(cache.exists())
            assertFalse(File(cache.path + ".bak").exists())
            assertFalse(File(cache.path + ".new").exists())
            store.close()
        }
        assertEquals(1, server.count("POST", "/v1/account/logout"))
        assertEquals(1, server.count("DELETE", "/v1/account"))
    }

    @Test
    fun legacyImportMigratesAllowedSettingsDoneAndEncryptedSchoolCredentialOnce() = runBlocking {
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
        assertEquals(token, store.state.value.token)
        assertEquals("dark", store.state.value.theme)
        assertEquals("classic", store.state.value.palette)
        assertEquals(37, store.state.value.hapticStrength)
        assertEquals(setOf("old-task"), store.state.value.done)
        assertEquals(password, store.savedSchoolPassword())
        val prefs = context.getSharedPreferences("stu.native.secure.v1", Context.MODE_PRIVATE)
        assertFalse(prefs.all.containsKey("unrelated.external.key"))
        assertFalse(prefs.all.values.joinToString().contains(password))
        assertFalse(prefs.all.values.joinToString().contains(token))
        assertEquals(password, secure().read("school:${hash("${server.base}\nlegacy")}")!!.getString("password"))
        assertFalse(cacheFile(server, "legacy").readText().contains("old-task"))
        store.importLegacy(JSONObject().put("stu.api", server.base).put("stu.token", server.tokenFor("other"))
            .put("stu.theme", "light").put("stu.haptics.strength", "0"))
        assertEquals(token, store.state.value.token)
        assertEquals("dark", store.state.value.theme)
        assertEquals(37, store.state.value.hapticStrength)
        assertEquals(setOf("old-task"), store.state.value.done)
    }

    @Test
    fun acceptedPostWithLostResponseIsNotAutomaticallyReplayed() = runBlocking {
        val server = server()
        val store = signedIn(server, "alice")
        server.dropNextPlanPost.set(true)
        expectApiError { store.createPlan(planFields("exactly-once")) }
        assertEquals(1, server.count("POST", "/v1/plan"))
        assertEquals(1, server.planRows("alice").count { it.optString("title") == "exactly-once" })
        store.loadPlans()
        assertEquals(1, store.state.value.plans.count { it.optString("title") == "exactly-once" })
    }

    @Test
    fun stalePlanReadCannotOverwriteAcknowledgedMutations() = runBlocking {
        val server = server()
        val store = signedIn(server, "alice")
        server.seedPlan("alice", "existing")
        store.loadPlans()
        val gate = PlanGate()
        server.nextPlanReadGate.set(gate)
        coroutineScope {
            val stale = async(Dispatchers.IO) { store.loadPlans() }
            try {
                assertTrue(withContext(Dispatchers.IO) { gate.captured.await(10, TimeUnit.SECONDS) })
                store.createPlan(planFields("after-snapshot"))
            } finally {
                gate.release.countDown()
            }
            stale.await()
            val first = async(Dispatchers.IO) { store.createPlan(planFields("concurrent-a")) }
            val second = async(Dispatchers.IO) { store.createPlan(planFields("concurrent-b")) }
            first.await()
            second.await()
        }
        assertEquals(server.planRows("alice").map { it.getString("id") }.toSet(),
            store.state.value.plans.map { it.getString("id") }.toSet())
        assertEquals(setOf("existing", "after-snapshot", "concurrent-a", "concurrent-b"),
            store.state.value.plans.map { it.getString("title") }.toSet())
        assertEquals(3, server.count("POST", "/v1/plan"))
        val toggleId = store.state.value.plans.first().getString("id")
        store.togglePlan(toggleId, true)
        assertTrue(store.state.value.plans.single { it.getString("id") == toggleId }.getBoolean("done"))
        assertEquals(1, store.state.value.planSummary.getInt("today_done"))
    }

    @Test
    fun realLegacyWebViewStorageMigratesThroughTheProductionOriginReader() = runBlocking {
        val server = server()
        val store = store(server)
        val uid = "origin-unique-${UUID.randomUUID()}"
        val email = "$uid@example.test"
        val token = server.tokenFor(uid)
        val password = "origin-school-secret-${UUID.randomUUID()}"
        val taskId = "origin-task-${UUID.randomUUID()}"
        val unrelated = "native.instrumentation.unrelated.$uid"
        val entries = JSONObject().put("stu.api", server.base).put("stu.token", token)
            .put("stu.account", email).put("stu.theme", "dark").put("stu.palette", "classic")
            .put("stu.haptics.strength", "42").put("stu.haptics", "off")
            .put("stu.update.skip", "32")
            .put("stu.done.v2:$uid", JSONArray().put(taskId).toString())
            .put("stu.school:${server.base}|$email", JSONObject().put("account", "origin-student")
                .put("password", password).toString())
            .put(unrelated, "must-stay-only-in-web-storage")
        val keys = entries.keys().asSequence().toList()
        val oldValues = AtomicReference<JSONObject?>(null)
        try {
            val echo = withLegacyWebView { view, complete ->
                val backupScript = """(function(){var keys=${JSONArray(keys)},old={};
                    for(var i=0;i<keys.length;i++){old[keys[i]]=localStorage.getItem(keys[i]);}
                    return JSON.stringify({origin:location.origin,values:old});})()""".trimIndent()
                view.evaluateJavascript(backupScript) { rawBackup ->
                    try {
                        val backup = strictWebObject(rawBackup)
                        check(backup.getString("origin") == "https://localhost")
                        // Retain the backup before the first localStorage write, including on failure.
                        oldValues.set(backup.getJSONObject("values"))
                        val writeScript = """(function(){var entries=$entries,echo={};
                            Object.keys(entries).forEach(function(k){localStorage.setItem(k,entries[k]);echo[k]=localStorage.getItem(k);});
                            return JSON.stringify({origin:location.origin,values:echo});})()""".trimIndent()
                        view.evaluateJavascript(writeScript) { rawEcho ->
                            try { complete(strictWebObject(rawEcho), null) }
                            catch (error: Throwable) { complete(null, error) }
                        }
                    } catch (error: Throwable) { complete(null, error) }
                }
            }
            assertEquals("https://localhost", echo.getString("origin"))
            keys.forEach { assertEquals("Seed must be present in the real WebView origin", entries.getString(it),
                echo.getJSONObject("values").getString(it)) }

            // withLegacyWebView has destroyed the seed WebView before this real migration starts.
            val migrated = CountDownLatch(1)
            val migrationFailure = AtomicReference<Throwable?>(null)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                try { migrateLegacyStorage(context, store) { migrated.countDown() } }
                catch (error: Throwable) { migrationFailure.set(error); migrated.countDown() }
            }
            assertTrue("Legacy migration callback did not finish", migrated.await(15, TimeUnit.SECONDS))
            migrationFailure.get()?.let { throw it }
            assertTrue("The real helper must import, not just close its WebView", store.legacyMigrationDone())
            assertEquals(server.base, store.state.value.server)
            assertEquals(token, store.state.value.token)
            assertEquals(uid, store.state.value.user?.getString("id"))
            assertEquals("dark", store.state.value.theme)
            assertEquals("classic", store.state.value.palette)
            assertEquals(42, store.state.value.hapticStrength)
            assertEquals(32L, context.getSharedPreferences("native-updates", Context.MODE_PRIVATE).getLong("skip", -1))
            assertEquals(setOf(taskId), store.state.value.done)
            assertEquals("origin-student", store.state.value.schoolId)
            assertTrue(store.state.value.rememberSchool)
            assertEquals(password, store.savedSchoolPassword())
            val securePrefs = context.getSharedPreferences("stu.native.secure.v1", Context.MODE_PRIVATE)
            val settings = context.getSharedPreferences("stu.native.settings.v1", Context.MODE_PRIVATE)
            assertFalse(securePrefs.contains(unrelated))
            assertFalse(settings.contains(unrelated))
            assertFalse(securePrefs.all.values.joinToString().contains(password))
            assertFalse(securePrefs.all.values.joinToString().contains(token))
            assertEquals(password, secure().read("school:${hash("${server.base}\n$uid")}")!!.getString("password"))
            assertEquals(setOf(taskId), doneInCache(server, uid))
            assertFalse(cacheFile(server, uid).readText().contains(taskId))
        } finally {
            // This is the app's real profile. Restore only keys this test changed; never clear it.
            oldValues.get()?.let { previous ->
                val restored = withLegacyWebView { view, complete ->
                    val restoreScript = """(function(){var old=$previous,echo={};
                        Object.keys(old).forEach(function(k){if(old[k]===null){localStorage.removeItem(k);}else{localStorage.setItem(k,old[k]);}
                        echo[k]=localStorage.getItem(k);});
                        return JSON.stringify({origin:location.origin,values:echo});})()""".trimIndent()
                    view.evaluateJavascript(restoreScript) { raw ->
                        try { complete(strictWebObject(raw), null) }
                        catch (error: Throwable) { complete(null, error) }
                    }
                }
                assertEquals("https://localhost", restored.getString("origin"))
                val actual = restored.getJSONObject("values")
                keys.forEach { key ->
                    if (previous.isNull(key)) assertTrue("Previously absent key must be removed: $key", actual.isNull(key))
                    else assertTrue("Existing origin value must be restored: $key", previous.getString(key) == actual.getString(key))
                }
            }
        }
    }

    /** Called from the instrumentation worker; no latch ever blocks the WebView/UI thread. */
    @Suppress("SetJavaScriptEnabled")
    private fun withLegacyWebView(action: (WebView, (JSONObject?, Throwable?) -> Unit) -> Unit): JSONObject {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val finished = CountDownLatch(1)
        val result = AtomicReference<JSONObject?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val web = AtomicReference<WebView?>(null)
        val once = AtomicBoolean(false)
        val complete: (JSONObject?, Throwable?) -> Unit = { value, error ->
            result.set(value)
            failure.set(error)
            finished.countDown()
        }
        try {
            instrumentation.runOnMainSync {
                try {
                    val view = WebView(context)
                    web.set(view)
                    view.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        blockNetworkLoads = true
                        allowFileAccess = false
                        allowContentAccess = false
                    }
                    view.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            if (!once.compareAndSet(false, true)) return
                            try { action(view, complete) }
                            catch (error: Throwable) { complete(null, error) }
                        }
                    }
                    view.loadDataWithBaseURL("https://localhost/", "<html><body></body></html>", "text/html", "UTF-8", null)
                } catch (error: Throwable) { complete(null, error) }
            }
            assertTrue("Local origin WebView did not finish", finished.await(15, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            return result.get() ?: throw AssertionError("WebView callback did not return an object")
        } finally {
            instrumentation.runOnMainSync {
                web.getAndSet(null)?.let { it.stopLoading(); it.destroy() }
            }
        }
    }

    private fun strictWebObject(raw: String): JSONObject {
        val outer = JSONTokener(raw)
        val text = outer.nextValue()
        check(text is String && outer.nextClean() == '\u0000') { "evaluateJavascript must return one JSON string" }
        val inner = JSONTokener(text)
        val value = inner.nextValue()
        check(value is JSONObject && inner.nextClean() == '\u0000') { "JavaScript result must contain one object" }
        return value
    }

    private fun server(): LocalApiStub = LocalApiStub(tls).also { servers += it }
    private fun store(server: LocalApiStub): NativeStore = NativeStore(context).also {
        stores += it
        it.setServer(server.base)
    }
    private suspend fun signedIn(server: LocalApiStub, uid: String): NativeStore = store(server).also {
        it.login("$uid@example.test", "test-passphrase")
        awaitReady(it, uid)
    }
    private suspend fun awaitReady(store: NativeStore, uid: String) {
        withTimeout(25_000) {
            while (store.state.value.user?.optString("id") != uid || store.state.value.syncedAt == null ||
                store.state.value.loading || store.state.value.syncing) delay(20)
        }
        assertNull("Stub should provide all resources successfully", store.state.value.error)
    }
    private suspend fun expectApiError(code: String? = null, action: suspend () -> Any?) {
        try {
            action()
            fail("Expected NativeApiError")
        } catch (error: NativeApiError) {
            if (code != null) assertEquals(code, error.code)
        }
    }
    private fun secure() = NativeSecureStorage(context)
    private fun cacheFile(server: LocalApiStub, uid: String) = File(context.noBackupFilesDir,
        "native-encrypted-cache-v1/${hash("${server.base}\n$uid")}.json")
    private fun doneInCache(server: LocalApiStub, uid: String): Set<String> {
        val array = secure().readCache("${server.base}\n$uid")!!.getJSONArray("done")
        return (0 until array.length()).map { array.getString(it) }.toSet()
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

    private class PlanGate {
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    /** Tiny HTTP/1.1 HTTPS fixture; mutations use production NativeApi, not a mocked transport. */
    private class LocalApiStub(tls: SSLContext) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool { work ->
            Thread(work, "native-api-instrumentation").apply { isDaemon = true }
        }
        private val socket = (tls.serverSocketFactory.createServerSocket(0, 32,
            InetAddress.getByName("127.0.0.1")) as SSLServerSocket)
        private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
        private val counts = ConcurrentHashMap<String, AtomicInteger>()
        private val identities = ConcurrentHashMap<String, String>()
        private val planLock = Any()
        private val plans = mutableMapOf<String, MutableList<JSONObject>>()
        private val nextId = AtomicInteger()
        @Volatile var revision = 1
        @Volatile var failGrades = false
        val dropNextPlanPost = AtomicBoolean(false)
        val nextPlanReadGate = AtomicReference<PlanGate?>(null)
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
            val token = "${encoded("{\"alg\":\"none\"}")}.${encoded(JSONObject().put("sub", uid).toString())}.test-only"
            identities[token] = uid
            return token
        }
        fun count(method: String, path: String) = counts["$method $path"]?.get() ?: 0
        fun planRows(uid: String): List<JSONObject> = synchronized(planLock) {
            plans[uid].orEmpty().map { JSONObject(it.toString()) }
        }
        fun seedPlan(uid: String, title: String): JSONObject = synchronized(planLock) {
            newPlan(planFields(title)).also { plans.getOrPut(uid) { mutableListOf() }.add(it) }
        }

        private data class Reply(val status: Int, val body: JSONObject)
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
                require(length in 0..1_048_576)
                val bytes = ByteArray(length)
                var received = 0
                while (received < length) {
                    val read = input.read(bytes, received, length - received)
                    check(read > 0)
                    received += read
                }
                val body = if (length == 0) JSONObject() else JSONObject(String(bytes, Charsets.UTF_8))
                counts.computeIfAbsent("$method ${uri.path}") { AtomicInteger() }.incrementAndGet()
                val uid = headers["authorization"]?.let { identities[it.removePrefix("Bearer ")] }
                val reply = dispatch(method, uri, body, uid) ?: return
                val payload = reply.body.toString().toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 ${reply.status} ${if (reply.status == 200) "OK" else "Test Error"}\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                client.outputStream.write(header.toByteArray(Charsets.US_ASCII))
                client.outputStream.write(payload)
                client.outputStream.flush()
            } catch (_: Exception) {
                // A client cancelled or a deliberate lost-response fixture closed the connection.
            } finally {
                activeSockets.remove(client)
                try { client.close() } catch (_: Exception) { }
            }
        }

        private fun dispatch(method: String, uri: URI, body: JSONObject, uid: String?): Reply? {
            fun ok(value: JSONObject) = Reply(200, value)
            fun error(status: Int, code: String) = Reply(status, JSONObject().put("detail", code))
            val path = uri.path
            if (path in setOf("/v1/auth/login", "/v1/auth/register") && method == "POST") {
                val user = body.getString("email").substringBefore('@')
                return ok(JSONObject().put("token", tokenFor(user)).put("user", JSONObject()
                    .put("id", user).put("email", "$user@example.test")))
            }
            if (uid == null) return error(401, "missing_bearer")
            if (path == "/v1/test/school401") return error(401, "login_failed")
            if (path == "/v1/test/session401") return error(401, "invalid_token")
            if (path == "/v1/account" || path == "/v1/account/logout") return ok(JSONObject().put("ok", true))
            if (path == "/v1/me") return ok(JSONObject().put("id", uid).put("email", "$uid@example.test")
                .put("bound", JSONArray().put("jw").put("mystu")).put("demo", false))
            if (path == "/v1/status") return ok(JSONObject().put("sources", JSONArray().apply {
                for (source in listOf("public", "jw", "mystu")) put(JSONObject().put("source", source)
                    .put("code", 0).put("state", "ok").put("at", System.currentTimeMillis() / 1000.0))
            }).put("bound", JSONArray()).put("syncing", false).put("demo", false))
            if (path == "/v1/timetable") return ok(JSONObject().put("semester", "2026-2027-1")
                .put("courses", JSONArray().put(JSONObject().put("id", "course-$uid")
                    .put("title", "course-$uid").put("meetings", JSONArray()))).put("empty", false)
                .put("coverage", JSONObject().put("complete", true)))
            if (path == "/v1/feed") return ok(JSONObject().put("items", JSONArray().put(JSONObject()
                .put("id", "task-$uid").put("title", "task-$uid-$revision").put("source", "mystu")
                .put("updated_at", System.currentTimeMillis() / 1000.0).put("detail", JSONObject()
                    .put("dueMs", System.currentTimeMillis() + 86_400_000)))))
            if (path == "/v1/notices") return ok(JSONObject().put("items", JSONArray()))
            if (path == "/v1/grades") return if (failGrades) error(503, "grades_unavailable") else
                ok(JSONObject().put("rows", JSONArray().put(JSONObject().put("id", "grade-$uid")
                    .put("title", "grade-$uid-$revision").put("score", "90").put("credits", 2).put("gpa", 4))))
            if (path == "/v1/exams") return ok(JSONObject().put("rows", JSONArray().put(JSONObject()
                .put("id", "exam-$uid").put("title", "exam-$uid-$revision").put("exam_time", "2026-12-01 08:00"))))
            if (path == "/v1/plan" && method == "GET") {
                val query = uri.rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate {
                    val parts = it.split('=', limit = 2)
                    URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                }
                val all = planRows(uid)
                val selected = all.filter { row -> (query["start"] == null || row.getString("date") >= query.getValue("start")) &&
                    (query["end"] == null || row.getString("date") <= query.getValue("end")) }
                val reply = ok(JSONObject().put("items", JSONArray(selected)).put("summary", summary(all)))
                nextPlanReadGate.getAndSet(null)?.let { gate ->
                    gate.captured.countDown()
                    check(gate.release.await(15, TimeUnit.SECONDS))
                }
                return reply
            }
            if (path == "/v1/plan" && method == "POST") {
                val row = synchronized(planLock) {
                    newPlan(body).also { plans.getOrPut(uid) { mutableListOf() }.add(it) }
                }
                if (dropNextPlanPost.getAndSet(false)) return null
                return ok(JSONObject(row.toString()))
            }
            if (path.startsWith("/v1/plan/") && path.endsWith("/toggle") && method == "POST") {
                val id = path.removePrefix("/v1/plan/").removeSuffix("/toggle")
                val row = synchronized(planLock) {
                    plans[uid]?.firstOrNull { it.getString("id") == id }?.apply { put("done", body.getBoolean("done")) }
                        ?.let { JSONObject(it.toString()) }
                } ?: return error(404, "plan_not_found")
                return ok(row)
            }
            return error(404, "not_found")
        }

        private fun newPlan(fields: JSONObject) = JSONObject(fields.toString())
            .put("id", "plan-${nextId.incrementAndGet()}").put("done", fields.optBoolean("done"))
            .put("sort", nextId.get()).put("priority", 0).put("note", "")
            .put("start", JSONObject.NULL).put("end", JSONObject.NULL)
        private fun summary(rows: List<JSONObject>): JSONObject {
            val today = rows.filter { it.getString("date") == today() }
            val done = today.count { it.optBoolean("done") }
            return JSONObject().put("today_total", today.size).put("today_done", done)
                .put("today_open", today.size - done).put("overdue", 0).put("upcoming", JSONArray())
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
