package com.stuassistant.app.nativeapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** App-private ciphertext only. A missing/invalid key is an error, never a reset. */
internal class NativeSecureStorage(context: Context) {
    private val appContext = context.applicationContext
    private val preferences by lazy {
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    private val cacheDirectory by lazy {
        File(appContext.noBackupFilesDir, CACHE_DIRECTORY_NAME)
    }

    fun read(key: String): JSONObject? = guarded("secure_storage", "安全数据读取失败") {
        if (!preferences.contains(key)) return@guarded null
        val encoded = preferences.getString(key, null)
            ?: throw IllegalStateException("Missing stored ciphertext")
        decrypt(encoded, "preference", key)
    }

    fun write(key: String, value: JSONObject): Unit = guarded("secure_storage", "安全数据保存失败") {
        val encoded = encrypt(value, "preference", key)
        check(preferences.edit().putString(key, encoded).commit())
    }

    fun remove(key: String): Unit = guarded("secure_storage", "安全数据删除失败") {
        if (preferences.contains(key)) check(preferences.edit().remove(key).commit())
    }

    /** Deletes only matching preference names, without decrypting unrelated records. */
    fun clearPrefix(prefix: String): Unit = guarded("secure_storage", "安全数据清理失败") {
        val names = preferences.all.keys.filter { it.startsWith(prefix) }
        if (names.isNotEmpty()) {
            val editor = preferences.edit()
            names.forEach { editor.remove(it) }
            check(editor.commit())
        }
    }

    fun readCache(scope: String): JSONObject? = guarded("cache_error", "本地缓存读取失败") {
        val file = cacheFile(scope)
        val atomic = AtomicFile(file)
        val encoded = try {
            atomic.openRead().use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (error: FileNotFoundException) {
            // AtomicFile can recover a legacy .bak even if the base file is absent.
            if (!file.exists() && !File(file.path + ".bak").exists()) {
                return@guarded null
            }
            throw error
        }
        decrypt(encoded, "cache", scope)
    }

    fun writeCache(scope: String, value: JSONObject): Unit = guarded("cache_error", "本地缓存保存失败") {
        val bytes = encrypt(value, "cache", scope).toByteArray(Charsets.UTF_8)
        val atomic = AtomicFile(cacheFile(scope))
        var output: FileOutputStream? = null
        try {
            val stream = atomic.startWrite()
            output = stream
            stream.write(bytes)
            // AtomicFile.finishWrite logs some I/O failures instead of throwing.
            // Explicitly sync and verify the committed ciphertext before reporting success.
            stream.fd.sync()
            atomic.finishWrite(stream)
            output = null
            check(atomic.openRead().use { it.readBytes() }.contentEquals(bytes))
        } catch (error: Exception) {
            output?.let { atomic.failWrite(it) }
            throw error
        }
    }

    fun removeCache(scope: String): Unit = guarded("cache_error", "本地缓存删除失败") {
        val file = cacheFile(scope)
        AtomicFile(file).delete()
        check(listOf(file, File(file.path + ".bak"), File(file.path + ".new")).none { it.exists() })
    }

    private fun cacheFile(scope: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return File(cacheDirectory, "$name.json")
    }

    private fun encrypt(value: JSONObject, domain: String, identity: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The Keystore provider generates a fresh random IV; never reuse a caller IV.
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(create = true))
        cipher.updateAAD(aad(domain, identity))
        val encrypted = cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        check(iv.size == IV_BYTES)
        return JSONObject()
            .put("version", FORMAT_VERSION)
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .toString()
    }

    private fun decrypt(encoded: String, domain: String, identity: String): JSONObject {
        val envelope = JSONObject(encoded)
        check(envelope.getInt("version") == FORMAT_VERSION)
        val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
        val encrypted = Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP)
        check(iv.size == IV_BYTES && encrypted.size >= TAG_BITS / 8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(create = false), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad(domain, identity))
        return JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
    }

    private fun aad(domain: String, identity: String): ByteArray =
        "stu.native.storage.v$FORMAT_VERSION\u0000$domain\u0000$identity".toByteArray(Charsets.UTF_8)

    private fun encryptionKey(create: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                ?: throw IllegalStateException("Invalid storage key")
        }
        // Restored ciphertext without its device-bound Keystore key must not be
        // silently replaced. The caller can explicitly remove the affected data.
        check(create && !hasCiphertext()) { "Storage key unavailable" }
        val specification = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(specification)
            generateKey()
        }
    }

    private fun hasCiphertext(): Boolean {
        if (preferences.all.isNotEmpty()) return true
        if (!cacheDirectory.exists()) return false
        check(cacheDirectory.isDirectory)
        val files = cacheDirectory.listFiles() ?: throw IllegalStateException("Cache directory unreadable")
        return files.any { it.name.endsWith(".json") || it.name.endsWith(".json.bak") || it.name.endsWith(".json.new") }
    }

    private fun <T> guarded(code: String, detail: String, block: () -> T): T = synchronized(LOCK) {
        try {
            block()
        } catch (_: Exception) {
            // Never include provider errors, ciphertext or credentials in user-visible text/logs.
            throw NativeApiError(code, 0, detail)
        }
    }

    private companion object {
        val LOCK = Any()
        const val PREFERENCES_NAME = "stu.native.secure.v1"
        const val CACHE_DIRECTORY_NAME = "native-encrypted-cache-v1"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "com.stuassistant.app.native.storage.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
