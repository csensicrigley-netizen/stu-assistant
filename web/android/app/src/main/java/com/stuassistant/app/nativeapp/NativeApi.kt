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

/** Public-only session anchor. Carries zero auth credentials by design. */
internal data class NativeSession(val server: String, val generation: Long = 0L)

/**
 * Strictly public-only HTTP client for `stu.oyishi.asia`.
 *
 * Security boundary:
 * - Enforces a compile-time & runtime path + HTTP method allowlist limited exclusively to
 *   anonymous public campus data, announcements, version checks, and explicit anonymous
 *   timetable export tickets.
 * - Never attaches an `Authorization` header or forwards user credentials/tokens.
 */
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
    ): JSONObject {
        val response = exchange(session, path, method, body, "application/json")
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
        accept: String,
    ): Response {
        val base = normalizeServer(session.server)
        if (base.isEmpty()) throw NativeApiError("server_required")
        val verb = method.uppercase(Locale.ROOT)
        val route = validatePublicRoute(verb, path)
        if (verb == "GET" && body != null) {
            throw NativeApiError("invalid_method")
        }
        val encodedBody = body?.toString()?.toByteArray(Charsets.UTF_8)
        if (encodedBody != null && encodedBody.size > MAX_REQUEST_BYTES) {
            throw NativeApiError("request_too_large")
        }
        val url = URL(base + path)
        val timeout = timeoutFor(route)
        // Retry transport failures for idempotent public reads only.
        for (attempt in 0..if (verb == "GET") 1 else 0) {
            currentCoroutineContext().ensureActive()
            try {
                return perform(url, verb, encodedBody, accept, timeout)
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
        accept: String,
        timeoutMs: Long,
    ): Response {
        try {
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
                                if (method != "GET") {
                                    connection.doOutput = true
                                    if (body != null) connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
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
            currentCoroutineContext().ensureActive()
            throw NativeApiError("timeout")
        } catch (error: CancellationException) {
            throw error
        }
    }

    private fun httpError(response: Response): NativeApiError {
        val payload = try { parseObject(response.text) } catch (_: Exception) { null }
        payload?.optJSONObject("error")?.let { error ->
            return NativeApiError(
                error.optString("code", "http_${response.status}"),
                response.status,
                error.optString("message", "请求未完成"),
            )
        }
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
        JsonReader(StringReader(source)).use { parser ->
            parser.isLenient = false
            val objectRoot = parser.peek() == JsonToken.BEGIN_OBJECT
            parser.skipValue()
            if (parser.peek() != JsonToken.END_DOCUMENT) throw IllegalArgumentException("Trailing JSON content")
            if (!objectRoot) return null
        }
        return JSONObject(source)
    }

    private fun timeoutFor(path: String): Long = when (path) {
        "/v1/public/export/today" -> 30_000L
        else -> 15_000L
    }

    private fun disconnect(connection: HttpURLConnection) {
        try { connection.disconnect() } catch (_: Exception) { /* Best-effort cleanup. */ }
    }

    private fun disconnectAsync(connection: HttpURLConnection) {
        Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable { disconnect(connection) })
    }

    companion object {
        private const val MAX_REQUEST_BYTES = 128 * 1024
        private const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
        private val RETRYABLE = setOf("network", "timeout")

        internal val PUBLIC_GET_PATHS = setOf(
            "/v1/public/notices",
            "/v1/public/bus",
            "/v1/public/charging",
            "/v1/announcements",
            "/v1/meta/version",
        )
        internal val PUBLIC_POST_PATHS = setOf(
            "/v1/public/export/today",
        )

        /**
         * Hard allowlist enforced before any outbound request to the public API server.
         * Any private, account, bind, relogin, med, webvpn, academic, plan, or external parse
         * route fails immediately with `blocked_non_public_route`.
         */
        internal fun validatePublicRoute(method: String, path: String): String {
            val decoded = validatePath(path)
            val verb = method.uppercase(Locale.ROOT)
            val allowed = when (verb) {
                "GET" -> decoded in PUBLIC_GET_PATHS
                "POST" -> decoded in PUBLIC_POST_PATHS
                else -> false
            }
            if (!allowed) {
                throw NativeApiError("blocked_non_public_route", 403, "纯本地隐私模式已拦截非公开服务端请求")
            }
            return decoded
        }

        private fun validatePath(path: String): String {
            try {
                val uri = URI(path)
                val decoded = uri.path ?: throw NativeApiError("invalid_path")
                if (!path.startsWith("/") || uri.isAbsolute || uri.rawAuthority != null ||
                    uri.rawFragment != null || !decoded.startsWith("/") ||
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
