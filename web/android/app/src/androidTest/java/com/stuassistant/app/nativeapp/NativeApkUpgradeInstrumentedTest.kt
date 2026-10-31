package com.stuassistant.app.nativeapp

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run seed on the previous APK, install -r the new APK, then run verify with the same test APK. */
@RunWith(AndroidJUnit4::class)
class NativeApkUpgradeInstrumentedTest {
    @Test fun seedPreviousApk() {
        requirePhase("seed")
        val context = UpgradeContext(InstrumentationRegistry.getInstrumentation().targetContext)
        context.cleanup()
        val secure = NativeSecureStorage(context)
        // Only APIs present in both target APKs are used in this phase.
        secure.write("session:${hash(SERVER)}", JSONObject().put("token", TOKEN).put("user", user()))
        secure.writeCache(SCOPE, JSONObject().put("user", user()).put("timetable", timetable())
            .put("done", JSONArray().put(DONE)).put("syncedAt", 1_700_000_000_000L))
        secure.write("school:${hash(SCOPE)}", JSONObject().put("id", STUDENT)
            .put("password", SCHOOL_SECRET).put("remember", true))
        assertTrue(context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit()
            .putString("server", SERVER).putString("theme", "dark").putString("palette", "classic")
            .putInt("strength", 43).commit())
        assertEquals(TOKEN, secure.read("session:${hash(SERVER)}")!!.getString("token"))
        assertEquals(DONE, secure.readCache(SCOPE)!!.getJSONArray("done").getString(0))
        // Commit last: another instrumentation process must see a complete seed.
        assertTrue(context.getSharedPreferences(METADATA, Context.MODE_PRIVATE).edit()
            .putLong("seedVersionCode", installedVersion(context)).putBoolean("ready", true).commit())
    }

    @Test fun verifyAfterPackageUpgrade() {
        requirePhase("verify")
        val context = UpgradeContext(InstrumentationRegistry.getInstrumentation().targetContext)
        var store: NativeStore? = null
        try {
            val metadata = context.getSharedPreferences(METADATA, Context.MODE_PRIVATE)
            assertTrue("Run phase=seed on the previous APK before installing the upgrade", metadata.getBoolean("ready", false))
            assertTrue("Verification must run after a higher-version APK replaces the seed APK",
                installedVersion(context) > metadata.getLong("seedVersionCode", Long.MAX_VALUE))
            store = NativeStore(context, configuredServerForTests = SERVER)
            val restored = store.state.value
            val schoolSecret = store.savedSchoolPassword()
            // Restoration is synchronous. Cancel automatic loopback refresh immediately.
            store.close()
            store = null
            assertEquals(SERVER, restored.server)
            assertEquals(TOKEN, restored.token)
            assertEquals(user().toString(), restored.user?.toString())
            assertEquals(timetable().toString(), restored.timetable?.toString())
            assertEquals(setOf(DONE), restored.done)
            assertEquals(STUDENT, restored.schoolId)
            assertTrue(restored.rememberSchool)
            assertEquals(SCHOOL_SECRET, schoolSecret)
            assertEquals(43, restored.hapticStrength)
            assertEquals("dark", restored.theme)
            assertEquals("classic", restored.palette)
        } finally {
            store?.close()
            context.cleanup()
        }
    }

    private fun requirePhase(expected: String) = assumeTrue("Opt in with -e phase $expected",
        InstrumentationRegistry.getArguments().getString("phase") == expected)

    @Suppress("DEPRECATION")
    private fun installedVersion(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private fun user() = JSONObject().put("id", UID).put("email", "apk-upgrade@example.test")
        .put("bound", JSONArray().put("jw"))

    private fun timetable() = JSONObject().put("semester", "2026-2027-1").put("status", "ok")
        .put("courses", JSONArray().put(JSONObject().put("id", "apk-upgrade-course").put("title", "Upgrade fixture course")
            .put("meetings", JSONArray().put(JSONObject().put("weekday", 5).put("weeks", JSONArray().put(8))
                .put("periods", JSONArray().put(1)).put("location", "Fixture classroom")))))

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class UpgradeContext(base: Context) : ContextWrapper(base) {
        private val root = File(base.noBackupFilesDir, PREFIX)
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences("$PREFIX-$name", mode)
        override fun getNoBackupFilesDir(): File = root.apply { check(isDirectory || mkdirs()) }
        fun cleanup() {
            for (name in listOf(SETTINGS, SECURE, METADATA)) baseContext.deleteSharedPreferences("$PREFIX-$name")
            check(root.canonicalFile.parentFile == baseContext.noBackupFilesDir.canonicalFile && root.name == PREFIX)
            if (root.exists()) check(root.deleteRecursively())
            // The shared application Keystore alias is deliberately retained.
        }
    }

    private companion object {
        const val PREFIX = "apk-upgrade-fixture"
        const val SETTINGS = "stu.native.settings.v1"
        const val SECURE = "stu.native.secure.v1"
        const val METADATA = "upgrade-metadata"
        const val SERVER = "https://localhost:1"
        const val UID = "apk-upgrade-fixture-user"
        const val SCOPE = "$SERVER\n$UID"
        const val TOKEN = "apk-upgrade-fixture-token"
        const val DONE = "apk-upgrade-fixture-done"
        const val STUDENT = "apk-upgrade-fixture-student"
        const val SCHOOL_SECRET = "apk-upgrade-fixture-school-secret"
    }
}
