package com.stuassistant.app.nativeapp

import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.IDN
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.util.Locale
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

internal data class NativeSession(val server: String, val token: String?, val generation: Long)

/** HTTP only. The store owns account changes, authentication expiry and cache isolation. */
internal class NativeApi {
    private data class Response(val status: Int, val text: String)

    private val connectionLock = Any()
    private val connections = mutableSetOf<HttpURLConnection>()
    private var closed = false

    suspend fun request(
        session: NativeSession,
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        auth: Boolean = true,
    ): JSONObject {
        val response = exchange(session, path, method, body, auth, "application/json")
        if (response.status !in 200..299) throw httpError(response)
        return try {
            parseObject(response.text)
                ?: throw NativeApiError("invalid_json", response.status, "服务器没有返回完整的 JSON 对象。")
        } catch (error: NativeApiError) {
            throw error
        } catch (_: Exception) {
            throw NativeApiError("invalid_json", response.status, "服务器返回的 JSON 数据无效。")
        }
    }

    suspend fun requestText(session: NativeSession, path: String): String {
        val response = exchange(session, path, "GET", null, true, "text/csv")
        if (response.status !in 200..299) throw httpError(response)
        return response.text
    }

    fun close() {
        val pending = synchronized(connectionLock) {
            closed = true
            connections.toList().also { connections.clear() }
        }
        pending.forEach(::disconnectAsync)
    }

    private suspend fun exchange(
        session: NativeSession,
        path: String,
        method: String,
        body: JSONObject?,
        auth: Boolean,
        accept: String,
    ): Response {
        val base = normalizeServer(session.server)
        if (base.isEmpty()) throw NativeApiError("server_required")
        val route = validatePath(path)
        val verb = method.uppercase(Locale.ROOT)
        if (verb !in METHODS || (verb == "GET" && body != null)) {
            throw NativeApiError("invalid_method")
        }
        val encodedBody = body?.toString()?.toByteArray(Charsets.UTF_8)
        if (encodedBody != null && encodedBody.size > MAX_REQUEST_BYTES) {
            throw NativeApiError("request_too_large")
        }
        val token = session.token?.takeIf { auth && it.isNotBlank() }
        if (token != null && token.any { it <= '\u0020' || it == '\u007f' }) {
            throw NativeApiError("invalid_token")
        }
        val url = URL(base + path)
        val timeout = timeoutFor(route)
        // Retry transport failures for reads only. HTTP errors and mutations are single-attempt.
        for (attempt in 0..if (verb == "GET") 1 else 0) {
            currentCoroutineContext().ensureActive()
            try {
                return perform(url, verb, encodedBody, token, accept, timeout)
            } catch (error: CancellationException) {
                throw error
            } catch (error: NativeApiError) {
                if (attempt != 0 || verb != "GET" || error.code !in RETRYABLE) throw error
                delay(200)
            }
        }
        throw NativeApiError("network")
    }

    private suspend fun perform(
        url: URL,
        method: String,
        body: ByteArray?,
        token: String?,
        accept: String,
        timeoutMs: Long,
    ): Response {
        try {
            // This bounds the complete body, including a peer that keeps sending tiny chunks.
            return withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    val connection = url.openConnection() as HttpURLConnection
                    synchronized(connectionLock) {
                        if (closed) throw NativeApiError("closed")
                        connections.add(connection)
                    }
                    try {
                        suspendCancellableCoroutine { continuation ->
                            // Unlike a coroutine check between reads, disconnect also unblocks a pending read.
                            continuation.invokeOnCancellation { disconnectAsync(connection) }
                            try {
                                if (!continuation.isActive) return@suspendCancellableCoroutine
                                connection.requestMethod = method
                                connection.instanceFollowRedirects = false
                                connection.useCaches = false
                                connection.connectTimeout = timeoutMs.toInt()
                                connection.readTimeout = timeoutMs.toInt()
                                connection.setRequestProperty("Accept", accept)
                                connection.setRequestProperty("Accept-Encoding", "identity")
                                if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
                                if (method != "GET") {
                                    connection.doOutput = true
                                    if (body != null) connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                                    // Streaming mode also prevents replaying a buffered mutation after a connection failure.
                                    connection.setFixedLengthStreamingMode(body?.size ?: 0)
                                    connection.outputStream.use { if (body != null) it.write(body) }
                                }
                                if (!continuation.isActive) return@suspendCancellableCoroutine
                                val status = connection.responseCode
                                if (status < 100) throw NativeApiError("network")
                                if (connection.contentLengthLong > MAX_RESPONSE_BYTES) {
                                    throw NativeApiError("response_too_large", status)
                                }
                                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                                val text = if (stream == null) "" else stream.use { input ->
                                    val bytes = ByteArrayOutputStream()
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        if (!continuation.isActive) return@suspendCancellableCoroutine
                                        val read = input.read(buffer)
                                        if (read == -1) break
                                        if (bytes.size().toLong() + read > MAX_RESPONSE_BYTES) {
                                            throw NativeApiError("response_too_large", status)
                                        }
                                        bytes.write(buffer, 0, read)
                                    }
                                    bytes.toString(Charsets.UTF_8.name())
                                }
                                if (continuation.isActive) continuation.resume(Response(status, text))
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                if (continuation.isActive) {
                                    val failure = when (error) {
                                        is NativeApiError -> error
                                        is SocketTimeoutException -> NativeApiError("timeout")
                                        is IOException -> NativeApiError("network")
                                        else -> NativeApiError("network")
                                    }
                                    continuation.resumeWithException(failure)
                                }
                            }
                        }
                    } finally {
                        synchronized(connectionLock) { connections.remove(connection) }
                        disconnect(connection)
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            // An outer caller's cancellation must remain cancellation, not become a retryable timeout.
            currentCoroutineContext().ensureActive()
            throw NativeApiError("timeout")
        } catch (error: CancellationException) {
            throw error
        }
    }

    private fun httpError(response: Response): NativeApiError {
        val payload = try { parseObject(response.text) } catch (_: Exception) { null }
        val rawDetail = payload?.opt("detail")?.takeUnless { it === JSONObject.NULL }
        val code = when (rawDetail) {
            is String -> rawDetail.takeIf { it.isNotBlank() } ?: "http_${response.status}"
            is JSONArray -> "invalid_input"
            else -> "http_${response.status}"
        }
        val detail = rawDetail?.toString()?.takeIf { it.isNotBlank() }
            ?: response.text.takeIf { it.isNotBlank() }
            ?: code
        return NativeApiError(code, response.status, detail)
    }

    private fun parseObject(text: String): JSONObject? {
        val source = text.removePrefix("\uFEFF")
        // Android's JSONObject parser accepts non-JSON syntax such as unquoted keys.
        // Validate strictly first, including the end of the response, before constructing the object.
        JsonReader(StringReader(source)).use { parser ->
            parser.isLenient = false
            val objectRoot = parser.peek() == JsonToken.BEGIN_OBJECT
            parser.skipValue()
            if (parser.peek() != JsonToken.END_DOCUMENT) throw IllegalArgumentException("Trailing JSON content")
            if (!objectRoot) return null
        }
        return JSONObject(source)
    }

    private fun validatePath(path: String): String {
        try {
            val uri = URI(path)
            val decoded = uri.path ?: throw NativeApiError("invalid_path")
            if (!path.startsWith("/v1/") || uri.isAbsolute || uri.rawAuthority != null ||
                uri.rawFragment != null || !decoded.startsWith("/v1/") ||
                path.any { it <= '\u0020' || it == '\u007f' || it == '\\' } ||
                decoded.any { it <= '\u001f' || it == '\u007f' || it == '\\' } ||
                decoded.split('/').any { it == "." || it == ".." }
            ) throw NativeApiError("invalid_path")
            return decoded
        } catch (error: NativeApiError) {
            throw error
        } catch (_: Exception) {
            throw NativeApiError("invalid_path")
        }
    }

    private fun timeoutFor(path: String): Long = when {
        path.startsWith("/v1/auth/") -> 45_000L
        path == "/v1/relogin" -> 60_000L
        path.startsWith("/v1/timetable") -> 40_000L
        path == "/v1/feed" || path == "/v1/grades" -> 30_000L
        else -> 15_000L
    }

    private fun disconnect(connection: HttpURLConnection) {
        try { connection.disconnect() } catch (_: Exception) { /* Best-effort cleanup. */ }
    }

    private fun disconnectAsync(connection: HttpURLConnection) {
        // Cancellation and ViewModel.close() can originate on the main thread.
        Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable { disconnect(connection) })
    }

    companion object {
        private const val MAX_REQUEST_BYTES = 1 * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
        private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
        private val RETRYABLE = setOf("network", "timeout")

        fun normalizeServer(raw: String): String {
            val value = raw.trim()
            if (value.isEmpty()) return ""
            try {
                val uri = URI(value)
                val scheme = uri.scheme?.lowercase(Locale.ROOT)
                if (scheme !in setOf("http", "https") || uri.rawAuthority.isNullOrEmpty() ||
                    uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                    value.any { it <= '\u0020' || it == '\u007f' || it == '\\' }
                ) throw NativeApiError("invalid_server")
                val url = URL(value)
                if (url.userInfo != null || url.host.isNullOrEmpty()) throw NativeApiError("invalid_server")
                val host = if (url.host.startsWith("[")) {
                    // URI/URL validate the IPv6 literal; retain its brackets for reconstruction.
                    if (uri.host == null || '%' in url.host) throw NativeApiError("invalid_server")
                    url.host.lowercase(Locale.ROOT)
                } else {
                    IDN.toASCII(url.host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
                }
                if (host.isEmpty() || url.port < -1 || url.port == 0 || url.port > 65535) {
                    throw NativeApiError("invalid_server")
                }
                val port = url.port.takeUnless {
                    it == -1 || (scheme == "http" && it == 80) || (scheme == "https" && it == 443)
                }?.let { ":$it" } ?: ""
                val path = uri.normalize().rawPath.orEmpty().trimEnd('/')
                if (uri.path.orEmpty().any { it <= '\u001f' || it == '\u007f' || it == '\\' }) {
                    throw NativeApiError("invalid_server")
                }
                return "$scheme://$host$port$path"
            } catch (error: NativeApiError) {
                throw error
            } catch (_: Exception) {
                throw NativeApiError("invalid_server")
            }
        }
    }
}
