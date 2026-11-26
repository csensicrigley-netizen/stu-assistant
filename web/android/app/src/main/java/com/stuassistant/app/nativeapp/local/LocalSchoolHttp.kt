package com.stuassistant.app.nativeapp.local

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Structured error raised by the local direct-to-school engine.
 *
 * Error codes intentionally match `stu_mcp` and `NativeAccountScreens` / `NativeMedicalScreen`
 * so the UI layer can present clear Chinese diagnostics without exposing raw stack traces.
 */
class LocalSchoolError(
    val code: String,
    val detail: String,
    val source: String = "",
    val httpStatus: Int = 0,
    cause: Throwable? = null,
) : Exception(detail, cause)

/** Interactive challenge encountered during CAS login (CAPTCHA / TOTP). */
sealed class SchoolChallenge {
    abstract val service: String
    abstract val prompt: String

    data class Captcha(
        override val service: String,
        val imageBytes: ByteArray,
        val imageDataUrl: String,
        val mediaType: String = "image/jpeg",
        val fieldName: String = "captcha",
        override val prompt: String = "请输入图中的图形验证码",
    ) : SchoolChallenge() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Captcha) return false
            return service == other.service &&
                imageBytes.contentEquals(other.imageBytes) &&
                imageDataUrl == other.imageDataUrl &&
                mediaType == other.mediaType &&
                fieldName == other.fieldName &&
                prompt == other.prompt
        }

        override fun hashCode(): Int {
            var result = service.hashCode()
            result = 31 * result + imageBytes.contentHashCode()
            result = 31 * result + imageDataUrl.hashCode()
            result = 31 * result + mediaType.hashCode()
            result = 31 * result + fieldName.hashCode()
            result = 31 * result + prompt.hashCode()
            return result
        }
    }

    data class Totp(
        override val service: String,
        val fieldName: String = "otp",
        override val prompt: String = "请输入动态口令（6位数字）",
    ) : SchoolChallenge()
}

/** Suspends to collect user input for a CAS challenge; returning null cancels login. */
fun interface SchoolChallengeHandler {
    suspend fun onChallenge(challenge: SchoolChallenge): String?
}

/** Result of a verified CAS login with persisted business-host-only cookies. */
data class CasLoginResult(
    val service: String,
    val sessionJson: JSONObject,
    val accountFingerprint: String,
    val proof: JSONObject,
)

/** Health status for probing a stored school session. */
enum class SessionProbeStatus {
    LIVE,
    EXPIRED,
    MAINTENANCE,
}

/** Serializable cookie record for domain-isolated persistence. */
data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val expiresAtMs: Long = Long.MAX_VALUE,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val hostOnly: Boolean = true,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtMs != Long.MAX_VALUE && expiresAtMs <= nowMs

    fun toOkHttpCookie(): Cookie {
        val builder = Cookie.Builder()
            .name(name)
            .value(value)
            .path(path.ifEmpty { "/" })
        if (hostOnly) {
            builder.hostOnlyDomain(domain)
        } else {
            builder.domain(domain)
        }
        if (expiresAtMs != Long.MAX_VALUE) {
            builder.expiresAt(expiresAtMs)
        }
        if (secure) builder.secure()
        if (httpOnly) builder.httpOnly()
        return builder.build()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("value", value)
        .put("domain", domain)
        .put("path", path.ifEmpty { "/" })
        .put("expires", if (expiresAtMs == Long.MAX_VALUE) -1L else expiresAtMs / 1000L)
        .put("secure", secure)
        .put("httpOnly", httpOnly)
        .put("hostOnly", hostOnly)

    companion object {
        fun fromOkHttp(cookie: Cookie, forceHost: String? = null): StoredCookie {
            val normalizedDomain = (forceHost ?: cookie.domain).trim().lowercase(Locale.ROOT).trimStart('.')
            val persistent = cookie.persistent
            return StoredCookie(
                name = cookie.name,
                value = cookie.value,
                domain = normalizedDomain,
                path = cookie.path.ifEmpty { "/" },
                expiresAtMs = if (persistent) cookie.expiresAt else Long.MAX_VALUE,
                secure = cookie.secure,
                httpOnly = cookie.httpOnly,
                hostOnly = forceHost != null || cookie.hostOnly,
            )
        }

        fun fromJson(obj: JSONObject): StoredCookie? {
            val name = obj.optString("name").trim()
            val domain = obj.optString("domain").trim().lowercase(Locale.ROOT).trimStart('.')
            if (name.isEmpty() || domain.isEmpty()) return null
            val rawExpires = obj.optDouble("expires", -1.0)
            val expiresAtMs = if (!rawExpires.isFinite() || rawExpires <= 0.0) {
                Long.MAX_VALUE
            } else if (rawExpires > 10_000_000_000.0) {
                rawExpires.toLong()
            } else {
                (rawExpires * 1000.0).toLong()
            }
            return StoredCookie(
                name = name,
                value = obj.optString("value", ""),
                domain = domain,
                path = obj.optString("path", "/").ifBlank { "/" },
                expiresAtMs = expiresAtMs,
                secure = obj.optBoolean("secure", false),
                httpOnly = obj.optBoolean("httpOnly", false),
                hostOnly = obj.optBoolean("hostOnly", true),
            )
        }
    }
}

/**
 * Domain-isolated CookieJar with hard guarantees:
 * 1. Cookies are strictly bound to exact allowed hosts; `sso.stu.edu.cn` cookies are NEVER sent to
 *    `http://jw.stu.edu.cn` or any business host.
 * 2. Central CAS SSO cookies (`sso.stu.edu.cn`, `sso.med.stu.edu.cn`, parent `stu.edu.cn`, `TGC`)
 *    are NEVER exported to persisted JSON and can be purged explicitly via [clearSsoCookies].
 * 3. `secure=true` cookies are NEVER sent over plaintext `http://`.
 */
class IsolatedCookieJar(
    allowedHosts: Set<String>,
    private val allowSsoBridgeCapture: Boolean = false,
) : CookieJar {
    private data class CookieKey(val name: String, val domain: String, val path: String)

    val allowedHosts: Set<String> = allowedHosts
        .map { it.trim().lowercase(Locale.ROOT).trimStart('.') }
        .filter { it.isNotEmpty() }
        .toSet()

    private val lock = Any()
    private val store = LinkedHashMap<CookieKey, StoredCookie>()
    private var bridgeUtCookies = mutableListOf<StoredCookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val reqHost = url.host.lowercase(Locale.ROOT)
        if (reqHost !in allowedHosts) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            for (cookie in cookies) {
                val domain = cookie.domain.trim().lowercase(Locale.ROOT).trimStart('.')
                val path = cookie.path.ifEmpty { "/" }
                // Capture the school's observed parent-domain `_UT_` Web SSO token in memory only
                // on the SSO jar so it can be narrowed to `my.stu.edu.cn` (HTTPS only, never JW).
                if (allowSsoBridgeCapture &&
                    reqHost == LocalSchoolHttp.SSO_HOST &&
                    cookie.name == "_UT_" &&
                    domain == "stu.edu.cn"
                ) {
                    val expired = cookie.persistent && cookie.expiresAt <= now
                    bridgeUtCookies.removeAll { it.path == path }
                    if (!expired) {
                        bridgeUtCookies.add(
                            StoredCookie(
                                name = cookie.name,
                                value = cookie.value,
                                domain = domain,
                                path = path,
                                expiresAtMs = if (cookie.persistent) cookie.expiresAt else Long.MAX_VALUE,
                                secure = cookie.secure,
                                httpOnly = cookie.httpOnly,
                                hostOnly = false,
                            ),
                        )
                    }
                    continue
                }
                // Reject foreign-domain and parent-domain cookies; keep business jars exact-host only.
                if (domain != reqHost || domain !in allowedHosts) continue
                val key = CookieKey(cookie.name, domain, path)
                if (cookie.persistent && cookie.expiresAt <= now) {
                    store.remove(key)
                } else {
                    store[key] = StoredCookie.fromOkHttp(cookie, forceHost = reqHost)
                }
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val reqHost = url.host.lowercase(Locale.ROOT)
        if (reqHost !in allowedHosts) return emptyList()
        val now = System.currentTimeMillis()
        val reqPath = url.encodedPath.ifEmpty { "/" }
        synchronized(lock) {
            val expiredKeys = store.entries.filter { it.value.isExpired(now) }.map { it.key }
            expiredKeys.forEach { store.remove(it) }
            val matched = mutableListOf<Cookie>()
            for (stored in store.values) {
                // Exact host equality prevents any cross-subdomain or SSO->JW leakage.
                if (stored.domain != reqHost) continue
                if (!pathMatches(reqPath, stored.path)) continue
                if (stored.secure && !url.isHttps) continue
                // Defense-in-depth: never leak any central SSO / TGC / _UT_ cookie over cleartext HTTP.
                if (!url.isHttps && isNonPersistableOrCentral(stored)) continue
                matched.add(stored.toOkHttpCookie())
            }
            return matched
        }
    }

    /** Manually inserts a strictly host-scoped cookie (used for narrow HTTPS `_UT_` bridge). */
    fun setScopedCookie(cookie: StoredCookie) {
        val normalized = cookie.domain.trim().lowercase(Locale.ROOT).trimStart('.')
        if (normalized !in allowedHosts) {
            throw LocalSchoolError(
                "unsafe_cookie_scope",
                "Cookie 作用域超出当前业务主机，已拦截。",
            )
        }
        synchronized(lock) {
            val key = CookieKey(cookie.name, normalized, cookie.path.ifEmpty { "/" })
            if (cookie.isExpired()) {
                store.remove(key)
            } else {
                store[key] = cookie.copy(domain = normalized, hostOnly = true)
            }
        }
    }

    /** Returns the single validated `_UT_` token captured on SSO, or null if absent/expired. */
    internal fun captureBridgeUtToken(source: String): StoredCookie? = synchronized(lock) {
        val now = System.currentTimeMillis()
        bridgeUtCookies.removeAll { it.isExpired(now) }
        if (bridgeUtCookies.isEmpty()) return null
        if (bridgeUtCookies.size != 1) {
            throw LocalSchoolError(
                "unsafe_cookie_scope",
                "学校服务令牌存在歧义；未混用中央认证会话。",
                source,
            )
        }
        val token = bridgeUtCookies[0]
        if (token.domain != "stu.edu.cn" ||
            token.path != "/" ||
            token.value.isEmpty() ||
            token.value.length > 8192 ||
            token.value.any { it.code < 32 || it.code == 127 }
        ) {
            throw LocalSchoolError(
                "unsafe_cookie_scope",
                "学校服务令牌范围发生变化；未扩大认证 Cookie 权限。",
                source,
            )
        }
        token
    }

    /**
     * Checks that no cookie in this jar targets a domain outside [expectedHost].
     * Called before allowing the legacy HTTP callback to `jw.stu.edu.cn`.
     */
    fun assertOnlyHost(expectedHost: String, source: String) {
        val normalized = expectedHost.trim().lowercase(Locale.ROOT).trimStart('.')
        synchronized(lock) {
            val foreign = store.values.any {
                it.domain != normalized || it.domain in NON_PERSISTABLE_HOSTS || it.name == "TGC"
            }
            if (foreign || bridgeUtCookies.isNotEmpty()) {
                throw LocalSchoolError(
                    "unsafe_cookie_scope",
                    "教务会话包含其他域 Cookie；已停止 HTTP 回调。",
                    source,
                )
            }
        }
    }

    /**
     * Exports ONLY business-host cookies suitable for encrypted persistence by `NativeStore`.
     * Central CAS SSO cookies (`sso.stu.edu.cn`, `sso.med.stu.edu.cn`, `stu.edu.cn`, `TGC`)
     * are unconditionally stripped and NEVER persisted.
     */
    fun exportPersistedCookies(targetHost: String): JSONArray {
        val normalized = targetHost.trim().lowercase(Locale.ROOT).trimStart('.')
        if (normalized in NON_PERSISTABLE_HOSTS) return JSONArray()
        val now = System.currentTimeMillis()
        val out = JSONArray()
        synchronized(lock) {
            for (stored in store.values) {
                if (stored.isExpired(now)) continue
                if (stored.domain != normalized) continue
                if (stored.domain in NON_PERSISTABLE_HOSTS) continue
                if (stored.name == "TGC") continue
                out.put(stored.toJson())
            }
        }
        return out
    }

    fun exportSessionJson(
        service: String,
        targetHost: String,
        accountFingerprint: String = "",
        jwHttpCompat: Boolean = false,
    ): JSONObject {
        val normalized = targetHost.trim().lowercase(Locale.ROOT).trimStart('.')
        return JSONObject()
            .put("version", SESSION_SCHEMA_VERSION)
            .put("service", service)
            .put("host", normalized)
            .put("jw_http_compat", jwHttpCompat)
            .put("school_account_fingerprint", accountFingerprint)
            .put("cookies", exportPersistedCookies(normalized))
    }

    /** Imports business cookies from persisted JSON, rejecting SSO and foreign domains. */
    fun importPersistedCookies(targetHost: String, array: JSONArray?) {
        val normalized = targetHost.trim().lowercase(Locale.ROOT).trimStart('.')
        if (normalized in NON_PERSISTABLE_HOSTS || normalized !in allowedHosts || array == null) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val parsed = StoredCookie.fromJson(obj) ?: continue
                if (parsed.isExpired(now)) continue
                if (parsed.domain != normalized) continue
                if (parsed.domain in NON_PERSISTABLE_HOSTS || parsed.name == "TGC") continue
                val scoped = parsed.copy(domain = normalized, hostOnly = true)
                store[CookieKey(scoped.name, scoped.domain, scoped.path)] = scoped
            }
        }
    }

    /** Explicitly purges any in-memory central SSO cookies and bridge tokens. */
    fun clearSsoCookies() {
        synchronized(lock) {
            bridgeUtCookies.clear()
            val toRemove = store.entries
                .filter { it.value.domain in NON_PERSISTABLE_HOSTS || it.value.name == "TGC" }
                .map { it.key }
            toRemove.forEach { store.remove(it) }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            store.clear()
            bridgeUtCookies.clear()
        }
    }

    fun snapshotCookies(): List<StoredCookie> = synchronized(lock) { store.values.toList() }

    private fun isNonPersistableOrCentral(cookie: StoredCookie): Boolean =
        cookie.domain in NON_PERSISTABLE_HOSTS || cookie.name == "TGC" || cookie.name == "_UT_"

    private fun pathMatches(requestPath: String, cookiePath: String): Boolean {
        val normalizedCookiePath = cookiePath.ifEmpty { "/" }
        if (requestPath == normalizedCookiePath) return true
        if (normalizedCookiePath == "/") return requestPath.startsWith("/")
        return requestPath.startsWith(
            if (normalizedCookiePath.endsWith("/")) normalizedCookiePath else "$normalizedCookiePath/",
        )
    }

    companion object {
        const val SESSION_SCHEMA_VERSION = 1
        val NON_PERSISTABLE_HOSTS: Set<String> = setOf(
            "sso.stu.edu.cn",
            "sso.med.stu.edu.cn",
            "stu.edu.cn",
        )
    }
}

/** Bounded HTTP response wrapper with decoded body text and final URL. */
data class LocalHttpResponse(
    val statusCode: Int,
    val url: String,
    val headers: Map<String, String>,
    val bodyBytes: ByteArray,
) {
    val bodyText: String by lazy { String(bodyBytes, Charsets.UTF_8) }

    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    val isRedirect: Boolean get() = statusCode in setOf(301, 302, 303, 307, 308)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LocalHttpResponse) return false
        return statusCode == other.statusCode &&
            url == other.url &&
            headers == other.headers &&
            bodyBytes.contentEquals(other.bodyBytes)
    }

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + url.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + bodyBytes.contentHashCode()
        return result
    }
}

/**
 * Authenticated, host-isolated HTTP session for a single campus service (`jw` or `mystu`).
 * Port of `stu_mcp.network.CampusHTTP` with strict URL/cookie/redirect/size boundaries and
 * standard system TLS verification (never TrustAll).
 */
class SchoolHttpSession(
    val service: String,
    sessionJson: JSONObject,
    baseClient: OkHttpClient = LocalSchoolHttp.defaultClient(),
    val allowLegacyHttp: Boolean = service == "jw" && sessionJson.optBoolean("jw_http_compat", true),
    private val deadlineMs: Long = 45_000L,
) {
    val host: String = when (service) {
        "jw" -> LocalSchoolHttp.JW_HOST
        "mystu" -> LocalSchoolHttp.MYSTU_HOST
        else -> throw LocalSchoolError("unknown_service", "不支持的校园服务：$service", service)
    }

    val cookieJar: IsolatedCookieJar = IsolatedCookieJar(setOf(host), allowSsoBridgeCapture = false)
    private val client: OkHttpClient
    private val accountFingerprint: String = sessionJson.optString("school_account_fingerprint", "")
    private var requests = 0
    private val deadlineAt = System.currentTimeMillis() + deadlineMs
    var usedLegacyHttp: Boolean = false
        private set

    init {
        if (allowLegacyHttp && service != "jw") {
            throw LocalSchoolError("unsafe_policy", "HTTP 兼容只允许用于学校教务来源。", service)
        }
        cookieJar.importPersistedCookies(host, sessionJson.optJSONArray("cookies"))
        client = baseClient.newBuilder()
            .cookieJar(cookieJar)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(18, TimeUnit.SECONDS)
            .build()
    }

    suspend fun get(
        url: String,
        extraHeaders: Map<String, String> = emptyMap(),
        maxBytes: Int = LocalSchoolHttp.MAX_RESPONSE_BYTES,
    ): LocalHttpResponse = request(url, formData = null, extraHeaders = extraHeaders, maxBytes = maxBytes)

    suspend fun postForm(
        url: String,
        formData: Map<String, String>,
        extraHeaders: Map<String, String> = emptyMap(),
        maxBytes: Int = LocalSchoolHttp.MAX_RESPONSE_BYTES,
    ): LocalHttpResponse = request(url, formData = formData, extraHeaders = extraHeaders, maxBytes = maxBytes)

    suspend fun getText(url: String, extraHeaders: Map<String, String> = emptyMap()): String =
        get(url, extraHeaders).bodyText

    suspend fun getJson(url: String, extraHeaders: Map<String, String> = emptyMap()): JSONObject {
        val response = get(url, extraHeaders)
        return try {
            val trimmed = response.bodyText.trimStart('\uFEFF').trim()
            if (!trimmed.startsWith("{")) {
                throw LocalSchoolError("schema_changed", "来源未返回预期的 JSON 对象。", service, response.statusCode)
            }
            JSONObject(trimmed)
        } catch (err: LocalSchoolError) {
            throw err
        } catch (_: Exception) {
            throw LocalSchoolError("schema_changed", "来源未返回预期的 JSON 数据。", service, response.statusCode)
        }
    }

    suspend fun request(
        url: String,
        formData: Map<String, String>? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        maxBytes: Int = LocalSchoolHttp.MAX_RESPONSE_BYTES,
    ): LocalHttpResponse = withContext(Dispatchers.IO) {
        var current = url
        var method = if (formData != null) "POST" else "GET"
        var bodyData = formData

        repeat(5) {
            currentCoroutineContext().ensureActive()
            LocalSchoolHttp.validateBusinessUrl(
                service = service,
                url = current,
                allowLegacyHttp = allowLegacyHttp,
            )
            val parsed = current.toHttpUrlOrNull()
                ?: throw LocalSchoolError("unsafe_url", "请求地址格式无效。", service)
            if (!parsed.isHttps && bodyData != null && bodyData.keys.any { it.lowercase(Locale.ROOT) in LocalSchoolHttp.PASSWORD_KEYS }) {
                throw LocalSchoolError("insecure_password_form", "账号密码只能提交到学校 HTTPS 登录页。", service)
            }
            if (!parsed.isHttps && service == "jw") {
                cookieJar.assertOnlyHost(LocalSchoolHttp.JW_HOST, service)
            }
            if (requests >= 100) {
                throw LocalSchoolError("request_limit", "此轮请求已达到上限；请稍后再刷新。", service)
            }
            val remaining = deadlineAt - System.currentTimeMillis()
            if (remaining <= 0L) {
                throw LocalSchoolError("request_deadline", "此轮读取已达到超时上限，请稍后再试。", service)
            }
            requests++
            if (!parsed.isHttps) usedLegacyHttp = true

            val reqBuilder = Request.Builder()
                .url(parsed)
                .header("User-Agent", LocalSchoolHttp.USER_AGENT)
                .header("Accept", "text/html,application/json,*/*")
            if (service == "mystu") {
                reqBuilder.header("X-Requested-With", "XMLHttpRequest")
                reqBuilder.header("Referer", LocalSchoolHttp.MYSTU_ENTRY_URL)
            }
            for ((k, v) in extraHeaders) {
                reqBuilder.header(k, v)
            }
            val postBody = bodyData
            if (method == "POST" && postBody != null) {
                val fb = FormBody.Builder(Charsets.UTF_8)
                for ((k, v) in postBody) {
                    fb.add(k, v)
                }
                reqBuilder.post(fb.build())
            } else {
                reqBuilder.get()
            }

            val resp = LocalSchoolHttp.executeBounded(
                client = client,
                request = reqBuilder.build(),
                source = service,
                maxBytes = maxBytes,
                deadlineAtMs = deadlineAt,
            )

            val status = resp.statusCode
            if (status == 401) {
                throw LocalSchoolError("login_expired", "登录会话已过期，请重新连接。", service, status)
            }
            if (status == 403) {
                throw LocalSchoolError("access_denied", "学校系统拒绝访问此资源，请稍后重试。", service, status)
            }
            if (status == 429) {
                throw LocalSchoolError("rate_limited", "请求过于频繁，请稍后再试。", service, status)
            }
            if (status == 404) {
                throw LocalSchoolError("not_found", "未找到对应的学校页面。", service, status)
            }
            if (status >= 500) {
                throw LocalSchoolError("source_unavailable", "学校系统暂时异常或正在维护，请稍后再试。", service, status)
            }
            if (resp.isRedirect) {
                val loc = resp.header("Location")?.takeIf { it.isNotBlank() }
                    ?: throw LocalSchoolError("network_error", "学校系统返回了无效跳转。", service, status)
                val nextUrl = LocalSchoolHttp.resolveUrl(current, loc)
                val dest = nextUrl.toHttpUrlOrNull()
                    ?: throw LocalSchoolError("unsafe_url", "跳转地址无效。", service, status)
                if (dest.host.equals(LocalSchoolHttp.SSO_HOST, ignoreCase = true) ||
                    dest.encodedPath.lowercase(Locale.ROOT).contains("/login")
                ) {
                    throw LocalSchoolError("login_expired", "登录会话已过期，请重新连接。", service, status)
                }
                if (status !in setOf(301, 302, 303) && !dest.host.equals(parsed.host, ignoreCase = true)) {
                    throw LocalSchoolError("unsafe_redirect", "已阻止跨站转发请求内容。", service, status)
                }
                current = nextUrl
                if (status in setOf(301, 302, 303)) {
                    method = "GET"
                    bodyData = null
                }
                return@repeat
            }
            if (status >= 400) {
                throw LocalSchoolError("source_rejected", "学校系统未接受此请求（HTTP $status）。", service, status)
            }
            val text = resp.bodyText
            if (text.trimStart().startsWith("<")) {
                if (service == "mystu" && parsed.encodedPath.contains("/mod/quiz/") &&
                    Jsoup.parse(text).selectFirst("""input[name="quizpassword"]""") != null
                ) {
                    throw LocalSchoolError("activity_password_required", "此测验要求活动密码，请在学校页面查看。", service, status)
                }
                if (LocalSchoolHttp.isLoginHtml(text)) {
                    throw LocalSchoolError("login_expired", "登录会话已过期，请重新连接。", service, status)
                }
            }
            return@withContext resp
        }
        throw LocalSchoolError("redirect_limit", "学校页面跳转次数超过上限。", service)
    }

    fun exportUpdatedSessionJson(): JSONObject =
        cookieJar.exportSessionJson(
            service = service,
            targetHost = host,
            accountFingerprint = accountFingerprint,
            jwHttpCompat = allowLegacyHttp,
        )
}

/**
 * Pure-Kotlin RFC 4648 Base64 codec so `local/` works identically on Android API 24+ and
 * plain JVM JUnit tests without `android.util.Base64` stubs or API 26 `java.util.Base64` lint issues.
 */
object LocalBase64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE = IntArray(128) { -1 }.apply {
        for (i in ALPHABET.indices) this[ALPHABET[i].code] = i
    }

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        val out = StringBuilder(((input.size + 2) / 3) * 4)
        var i = 0
        while (i < input.size) {
            val b0 = input[i++].toInt() and 0xFF
            val hasB1 = i < input.size
            val b1 = if (hasB1) input[i++].toInt() and 0xFF else 0
            val hasB2 = i < input.size
            val b2 = if (hasB2) input[i++].toInt() and 0xFF else 0
            out.append(ALPHABET[b0 ushr 2])
            out.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            out.append(if (hasB1) ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)] else '=')
            out.append(if (hasB2) ALPHABET[b2 and 0x3F] else '=')
        }
        return out.toString()
    }

    fun decode(input: String): ByteArray {
        val cleaned = input.filter { !it.isWhitespace() }
        if (cleaned.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream((cleaned.length * 3) / 4)
        var acc = 0
        var bits = 0
        for (ch in cleaned) {
            if (ch == '=') break
            val code = ch.code
            val value = if (code < 128) DECODE[code] else -1
            if (value < 0) throw IllegalArgumentException("Invalid Base64 character")
            acc = (acc shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}

/**
 * Core STU direct-connection HTTP & CAS SSO authentication engine.
 *
 * Strictly enforces:
 * - Standard Android/JVM TLS certificate verification (zero TrustAll / custom HostnameVerifier).
 * - Separate `ssoJar` and `schoolJar` cookie isolation during CAS login.
 * - Credentials posted ONLY to verified `https://sso.stu.edu.cn/login`.
 * - Central CAS SSO cookies discarded immediately after login and NEVER persisted.
 */
object LocalSchoolHttp {
    const val SSO_HOST = "sso.stu.edu.cn"
    const val SSO_LOGIN_URL = "https://sso.stu.edu.cn/login"
    const val MED_SSO_HOST = "sso.med.stu.edu.cn"

    const val JW_HOST = "jw.stu.edu.cn"
    const val JW_LOGIN_URL = "https://jw.stu.edu.cn/jwglxt/xtgl/login_slogin.html"
    const val JW_LOGIN_HTTP_URL = "http://jw.stu.edu.cn/jwglxt/xtgl/login_slogin.html"
    const val JW_BASE = "https://jw.stu.edu.cn/jsxsd"
    const val JW_HOME = "$JW_BASE/framework/xsMainV.htmlx"

    const val MYSTU_HOST = "my.stu.edu.cn"
    const val MYSTU_BASE = "https://my.stu.edu.cn"
    const val MYSTU_ENTRY_URL = "$MYSTU_BASE/discussion/my-courses"
    const val MYSTU_API = "$MYSTU_BASE/v3/services/api"
    const val MYSTU_VALIDATE_URL = "$MYSTU_API/user/validate?ver=1.1"
    const val MYSTU_COURSES_URL = "$MYSTU_API/course/query"

    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    const val AUTH_USER_AGENT = "STU-Assistant/LocalHTTP"
    const val MAX_AUTH_BYTES = 2 * 1024 * 1024
    const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024

    val PASSWORD_KEYS: Set<String> = setOf("password", "passwd", "pwd", "userpassword", "j_password")
    val AUTH_QUERY_KEYS: Set<String> = setOf(
        "token", "access_token", "refresh_token", "ticket", "sessionid", "authorization",
    ) + PASSWORD_KEYS

    val JW_ALLOWED_CALLBACK_PATHS: Set<String> = setOf(
        "", "/", "/jwglxt/xtgl/login_slogin.html", "/jsxsd", "/jsxsd/",
        "/jsxsd/xk/LoginToXk", "/jsxsd/framework/xsMainV.htmlx", "/jsxsd/framework/xsMainV_new.htmlx",
    )

    val MOODLE_SERVICES: Map<String, String> = mapOf(
        "elc" to "$MYSTU_BASE/courses/elc/login/index.php",
        "campus" to "$MYSTU_BASE/courses/campus/login/index.php",
    )

    val MOODLE_PATHS: Map<String, Set<String>> = MOODLE_SERVICES.keys.associateWith { name ->
        setOf("", "/", "/index.php", "/login/index.php", "/my/").map { "/courses/$name$it" }.toSet()
    }

    private val MOODLE_ALIASES: Set<String> = MOODLE_SERVICES.keys.map { "/courses/$it" }.toSet()
    private val MOODLE_TEST_PATHS: Set<String> = MOODLE_SERVICES.keys.flatMap { name ->
        listOf("/courses/$name/index.php", "/courses/$name/login/index.php")
    }.toSet()

    private val MYSTU_ALLOWED_PATHS: Set<String> =
        setOf("/discussion/my-courses") + MOODLE_PATHS.values.flatten().toSet()

    private val CAS_LOGIN_PATH_RE = Regex("""^/login(?:;jsessionid=[A-Za-z0-9.]+)?$""")
    private val LOGIN_TITLE_RE = Regex("""<title[^>]*>[^<]*(?:统一身份认证|用户登录|SSO Login)""", RegexOption.IGNORE_CASE)
    private val INLINE_SSO_REDIRECT_RE = Regex(
        """^\s*(?:(?:window|top|self)\.)?location(?:\.href)?\s*=\s*['"]([^'"]+)['"]\s*;?\s*$""",
    )
    private val DIGITS_20_RE = Regex("""^[0-9]{1,20}$""")

    @Volatile
    private var sharedClient: OkHttpClient? = null

    /**
     * Standard OkHttpClient using Android/JVM system TLS truststore and default HostnameVerifier.
     * NEVER overrides SSLSocketFactory, X509TrustManager, or HostnameVerifier.
     */
    fun defaultClient(): OkHttpClient = sharedClient ?: synchronized(this) {
        sharedClient ?: OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(18, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
            .also { sharedClient = it }
    }

    fun normalizeAccount(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).removeSuffix("@stu.edu.cn")

    fun accountFingerprint(username: String): String {
        val value = normalizeAccount(username)
        if (value.isEmpty() || value.length > 128 || value.any { it.isWhitespace() }) {
            throw LocalSchoolError("invalid_authentication_input", "账号为空或格式不正确。", "school")
        }
        return sha256Hex("stu-mcp/school-account/v1\u0000$value")
    }

    fun cleanText(value: Any?): String =
        (value?.toString() ?: "").replace(Regex("\\s+"), " ").trim()

    fun sha256Hex(raw: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun itemId(source: String, kind: String, vararg parts: Any?): String {
        val digest = sha256Hex(parts.joinToString("\u001f") { it?.toString() ?: "" }).take(24)
        return "$source:$kind:$digest"
    }

    fun resolveUrl(baseUrl: String, relativeOrAbsolute: String): String =
        URI(baseUrl).resolve(relativeOrAbsolute).toString()

    fun isLoginHtml(html: String): Boolean {
        if (LOGIN_TITLE_RE.containsMatchIn(html)) return true
        val doc = Jsoup.parse(html)
        if (doc.selectFirst("input[type=password]") != null) return true
        for (script in doc.select("script:not([src])")) {
            val match = INLINE_SSO_REDIRECT_RE.matchEntire(script.data()) ?: continue
            val target = match.groupValues[1].toHttpUrlOrNull()
            if (target != null && target.host.equals(SSO_HOST, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    fun validateBusinessUrl(service: String, url: String, allowLegacyHttp: Boolean) {
        val parsed = url.toHttpUrlOrNull()
            ?: throw LocalSchoolError("unsafe_url", "请求地址无效。", service)
        if (url.any { it.code < 32 } || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.fragment != null) {
            throw LocalSchoolError("unsafe_url", "请求地址包含非法片段或凭据。", service)
        }
        val expectedHost = when (service) {
            "jw" -> JW_HOST
            "mystu" -> MYSTU_HOST
            else -> throw LocalSchoolError("unknown_service", "不支持的校园来源。", service)
        }
        val host = parsed.host.lowercase(Locale.ROOT)
        if (host != expectedHost) {
            throw LocalSchoolError("unsafe_url", "请求地址不在此来源的允许范围内。", service)
        }
        val legacyJw = service == "jw" && allowLegacyHttp && host == JW_HOST
        val portOk = (parsed.isHttps && parsed.port == 443) || (!parsed.isHttps && parsed.port == 80 && legacyJw)
        if (!portOk) {
            if (!parsed.isHttps) {
                throw LocalSchoolError("insecure_transport", "已阻止通过非 HTTPS 连接发送登录态。", service)
            }
            throw LocalSchoolError("unsafe_url", "该来源端口不受支持。", service)
        }
        for (i in 0 until parsed.querySize) {
            val key = parsed.queryParameterName(i).lowercase(Locale.ROOT)
            if (key in AUTH_QUERY_KEYS) {
                throw LocalSchoolError("unsafe_url", "请求地址包含认证字段，已停止操作。", service)
            }
        }
    }

    /**
     * Bridges the observed parent-domain `_UT_` cookie from the ephemeral SSO jar into the
     * business jar, narrowed strictly to `my.stu.edu.cn` with `secure=true` and `hostOnly=true`.
     * NEVER bridges anything to `jw` (especially since `jw` uses an HTTP callback).
     */
    fun bridgeServiceToken(ssoJar: IsolatedCookieJar, schoolJar: IsolatedCookieJar, service: String) {
        if (service != "mystu") return
        val token = ssoJar.captureBridgeUtToken(service) ?: return
        schoolJar.setScopedCookie(
            StoredCookie(
                name = token.name,
                value = token.value,
                domain = MYSTU_HOST,
                path = "/",
                expiresAtMs = token.expiresAtMs,
                secure = true,
                httpOnly = token.httpOnly,
                hostOnly = true,
            ),
        )
    }

    internal data class ParsedCasForm(
        val action: String,
        val hiddenFields: Map<String, String>,
        val captchaFieldName: String? = null,
        val captchaImageUrl: String? = null,
        val totpFieldName: String? = null,
    )

    internal fun parseCasServiceUrl(url: String, source: String, allowedServices: Set<String>): String {
        val parsed = url.toHttpUrlOrNull() ?: throw casSchemaChanged(source)
        if (!parsed.isHttps ||
            !parsed.host.equals(SSO_HOST, ignoreCase = true) ||
            parsed.port != 443 ||
            parsed.username.isNotEmpty() ||
            parsed.password.isNotEmpty() ||
            parsed.fragment != null ||
            !CAS_LOGIN_PATH_RE.matches(parsed.encodedPath)
        ) {
            throw casSchemaChanged(source)
        }
        val names = parsed.queryParameterNames
        if ((names - setOf("service", "gateway")).isNotEmpty()) {
            throw casSchemaChanged(source)
        }
        val services = parsed.queryParameterValues("service")
        if (services.size != 1) throw casSchemaChanged(source)
        val svc = services[0] ?: throw casSchemaChanged(source)
        if (svc !in allowedServices) throw casSchemaChanged(source)
        if ("gateway" in names) {
            val gw = parsed.queryParameterValues("gateway")
            if (gw != listOf("true")) throw casSchemaChanged(source)
        }
        return svc
    }

    internal fun parseCasLoginForm(
        response: LocalHttpResponse,
        source: String,
        expectedService: String,
    ): ParsedCasForm {
        if (response.statusCode != 200) {
            throw LocalSchoolError("authentication_unconfirmed", "统一认证没有返回可验证的登录表单。", source, response.statusCode)
        }
        val doc = Jsoup.parse(response.bodyText, response.url)
        // Non-image widgets (geetest / slider / recaptcha / captcha iframe) cannot be completed in HTTP.
        val hasUnsupportedWidget = doc.select("iframe[src]").any {
            attrContainsIgnoreCase(it, "src", "captcha")
        } || doc.select("[class]").any {
            attrContainsIgnoreCase(it, "class", "slider", "geetest", "recaptcha")
        }
        if (hasUnsupportedWidget) {
            throw LocalSchoolError(
                "interaction_required",
                "统一认证要求滑块或人机验证，请先在浏览器登录一次后再试。",
                source,
            )
        }

        val forms = doc.select("form").filter { form ->
            form.select("""input[name="username"]""").isNotEmpty() &&
                form.select("""input[name="password"]""").isNotEmpty()
        }
        if (forms.size != 1) throw casSchemaChanged(source)
        val form = forms[0]
        if (!form.attr("method").equals("post", ignoreCase = true) ||
            form.select("""input[name="username"]""").size != 1 ||
            form.select("""input[name="password"]""").size != 1
        ) {
            throw casSchemaChanged(source)
        }
        val actionRaw = form.attr("action")
        val action = if (actionRaw.isBlank()) response.url else resolveUrl(response.url, actionRaw)
        if (parseCasServiceUrl(action, source, setOf(expectedService)) != expectedService) {
            throw casSchemaChanged(source)
        }

        val hiddenInputs = form.select("""input[type="hidden"][name]""")
        val fields = LinkedHashMap<String, String>()
        for (input in hiddenInputs) {
            val name = input.attr("name")
            if (name in fields) throw casSchemaChanged(source)
            val value = input.attr("value")
            if (value.isEmpty() || value.length > 4096) throw casSchemaChanged(source)
            fields[name] = value
        }
        val requiredHidden = setOf("lt", "execution", "_eventId")
        if (!fields.keys.containsAll(requiredHidden)) {
            throw casSchemaChanged(source)
        }

        val formInputs = form.select("input")
        val captchaInput = formInputs.firstOrNull {
            attrContainsIgnoreCase(it, "name", "captcha") || attrContainsIgnoreCase(it, "id", "captcha")
        }
        val captchaField = captchaInput?.attr("name")?.takeIf { it.isNotBlank() }
            ?: captchaInput?.attr("id")?.takeIf { it.isNotBlank() }
        val captchaImgNode = if (captchaField != null) {
            doc.select("img").firstOrNull {
                attrContainsIgnoreCase(it, "src", "captcha") ||
                    attrContainsIgnoreCase(it, "id", "captcha") ||
                    attrContainsIgnoreCase(it, "class", "captcha")
            }
        } else {
            null
        }
        val captchaImgUrl = captchaImgNode?.attr("src")?.takeIf { it.isNotBlank() }?.let {
            resolveUrl(response.url, it)
        }

        val totpInput = formInputs.firstOrNull {
            it.attr("autocomplete").equals("one-time-code", ignoreCase = true) ||
                attrContainsIgnoreCase(it, "name", "otp", "totp", "sms")
        }
        val totpField = totpInput?.attr("name")?.takeIf { it.isNotBlank() }

        // Without challenges, require exact standard CAS hidden field set just like stu_mcp.
        if (captchaField == null && totpField == null && fields.keys != requiredHidden) {
            throw casSchemaChanged(source)
        }

        return ParsedCasForm(
            action = action,
            hiddenFields = fields,
            captchaFieldName = captchaField,
            captchaImageUrl = captchaImgUrl,
            totpFieldName = totpField,
        )
    }

    private fun attrContainsIgnoreCase(el: org.jsoup.nodes.Element, attr: String, vararg needles: String): Boolean {
        val value = el.attr(attr).lowercase(Locale.ROOT)
        return value.isNotEmpty() && needles.any { it in value }
    }

    /**
     * Performs a complete, isolated STU CAS login for `"jw"` or `"mystu"`.
     *
     * Security invariants:
     * - Credentials are sent ONLY over HTTPS to `https://sso.stu.edu.cn/login`.
     * - `ssoJar` and `schoolJar` are strictly separate; `ssoJar` is wiped in `finally`.
     * - Persisted `sessionJson` contains ONLY cookies for the target business host.
     */
    suspend fun loginCas(
        service: String,
        studentId: String,
        password: String,
        challengeHandler: SchoolChallengeHandler? = null,
        baseClient: OkHttpClient = defaultClient(),
        timeoutMs: Long = 60_000L,
    ): CasLoginResult = withContext(Dispatchers.IO) {
        if (service !in setOf("jw", "mystu")) {
            throw LocalSchoolError("unknown_service", "请选择教务（jw）或 MySTU（mystu）。", service)
        }
        val normalizedUser = normalizeAccount(studentId)
        if (normalizedUser.isEmpty() || normalizedUser.length > 128 || normalizedUser.any { it.isWhitespace() } ||
            password.isEmpty() || password.length > 1024
        ) {
            throw LocalSchoolError("invalid_authentication_input", "学号或密码为空或格式不正确。", service)
        }

        val host = if (service == "jw") JW_HOST else MYSTU_HOST
        val actualService = if (service == "jw") JW_LOGIN_HTTP_URL else MYSTU_ENTRY_URL
        val casServices = mutableSetOf(actualService)
        if (service == "mystu") {
            casServices.addAll(MOODLE_SERVICES.values)
        }

        val ssoJar = IsolatedCookieJar(setOf(SSO_HOST), allowSsoBridgeCapture = true)
        val schoolJar = IsolatedCookieJar(setOf(host), allowSsoBridgeCapture = false)
        val ssoClient = baseClient.newBuilder()
            .cookieJar(ssoJar)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val schoolClient = baseClient.newBuilder()
            .cookieJar(schoolJar)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val deadlineAt = System.currentTimeMillis() + timeoutMs
        var totalRequests = 0

        fun guardBudget() {
            if (System.currentTimeMillis() >= deadlineAt || totalRequests >= 32) {
                throw LocalSchoolError("authentication_budget", "学校认证请求超过上限或超时；没有重发密码。", service)
            }
        }

        suspend fun rawGet(
            targetUrl: String,
            useSso: Boolean,
            extraHeaders: Map<String, String> = emptyMap(),
        ): LocalHttpResponse {
            currentCoroutineContext().ensureActive()
            guardBudget()
            val parsed = targetUrl.toHttpUrlOrNull()
                ?: throw LocalSchoolError("insecure_authentication_route", "认证跳转地址无效。", service)
            val expectedHost = if (useSso) SSO_HOST else host
            val allowHttpNow = !useSso && service == "jw" && parsed.host.equals(JW_HOST, true) &&
                parsed.encodedPath in JW_ALLOWED_CALLBACK_PATHS
            if (!parsed.host.equals(expectedHost, ignoreCase = true) ||
                parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.fragment != null
            ) {
                throw LocalSchoolError("insecure_authentication_route", "认证跳转超出已核验的学校域名。", service)
            }
            if (!(parsed.isHttps && parsed.port == 443) && !(!parsed.isHttps && parsed.port == 80 && allowHttpNow)) {
                throw LocalSchoolError("insecure_authentication_route", "认证跳转超出已核验的传输范围。", service)
            }
            if (!parsed.isHttps && service == "jw") {
                schoolJar.assertOnlyHost(JW_HOST, service)
            }
            totalRequests++
            val req = Request.Builder()
                .url(parsed)
                .header("User-Agent", AUTH_USER_AGENT)
                .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
                .get()
                .build()
            return executeBounded(
                client = if (useSso) ssoClient else schoolClient,
                request = req,
                source = service,
                maxBytes = MAX_AUTH_BYTES,
                deadlineAtMs = deadlineAt,
                credentialPost = false,
            )
        }

        suspend fun postSsoForm(actionUrl: String, fields: Map<String, String>): LocalHttpResponse {
            currentCoroutineContext().ensureActive()
            guardBudget()
            val parsed = actionUrl.toHttpUrlOrNull()
                ?: throw LocalSchoolError("insecure_authentication_route", "统一认证提交地址无效。", service)
            // Hard rule: credentials are NEVER sent over HTTP or to any host other than sso.stu.edu.cn.
            if (!parsed.isHttps || !parsed.host.equals(SSO_HOST, ignoreCase = true) || parsed.port != 443) {
                throw LocalSchoolError("unsafe_authentication_request", "密码只能发送到 HTTPS 统一认证。", service)
            }
            totalRequests++
            val formBuilder = FormBody.Builder(Charsets.UTF_8)
            for ((k, v) in fields) {
                formBuilder.add(k, v)
            }
            val req = Request.Builder()
                .url(parsed)
                .header("User-Agent", AUTH_USER_AGENT)
                .post(formBuilder.build())
                .build()
            return executeBounded(
                client = ssoClient,
                request = req,
                source = service,
                maxBytes = MAX_AUTH_BYTES,
                deadlineAtMs = deadlineAt,
                credentialPost = true,
            )
        }

        suspend fun follow(
            startUrl: String,
            allowCas: Boolean = false,
            maxHops: Int = 8,
            moodle: String? = null,
        ): LocalHttpResponse {
            var current = startUrl
            repeat(maxHops) {
                currentCoroutineContext().ensureActive()
                val parsed = current.toHttpUrlOrNull() ?: throw casSchemaChanged(service)
                val isSso = parsed.host.equals(SSO_HOST, ignoreCase = true)
                val page = if (isSso) {
                    val allowed = if (moodle != null) setOf(MOODLE_SERVICES.getValue(moodle)) else casServices
                    parseCasServiceUrl(current, service, allowed)
                    if (!allowCas) {
                        throw LocalSchoolError("login_rejected", "学校服务退回统一认证；本次会话未验证。", service)
                    }
                    rawGet(current, useSso = true)
                } else {
                    val allowedPaths = when {
                        moodle != null -> MOODLE_PATHS.getValue(moodle)
                        service == "jw" -> JW_ALLOWED_CALLBACK_PATHS
                        else -> MYSTU_ALLOWED_PATHS
                    }
                    if (!parsed.host.equals(host, ignoreCase = true) || parsed.encodedPath !in allowedPaths) {
                        throw casSchemaChanged(service)
                    }
                    validateCallbackQuery(service, parsed)
                    rawGet(current, useSso = false)
                }
                if (isSso) {
                    bridgeServiceToken(ssoJar, schoolJar, service)
                }
                val loc = page.header("Location")
                if (page.isRedirect && !loc.isNullOrBlank()) {
                    current = resolveUrl(page.url, loc)
                    return@repeat
                }
                return page
            }
            throw LocalSchoolError("authentication_budget", "学校认证跳转超过上限；没有重发密码。", service)
        }

        try {
            // Step 1: Prime business cookie and fetch the CAS login form.
            val loginPage: LocalHttpResponse = if (service == "jw") {
                val initial = rawGet(JW_LOGIN_URL, useSso = false)
                if (initial.isRedirect && !initial.header("Location").isNullOrBlank()) {
                    val redirected = resolveUrl(initial.url, initial.header("Location")!!)
                    val redParsed = redirected.toHttpUrlOrNull()
                    if (redParsed != null && redParsed.host.equals(SSO_HOST, ignoreCase = true)) {
                        rawGet("$SSO_LOGIN_URL?service=${urlEncode(actualService)}", useSso = true)
                    } else {
                        throw casSchemaChanged(service)
                    }
                } else if (initial.statusCode == 200) {
                    rawGet("$SSO_LOGIN_URL?service=${urlEncode(actualService)}", useSso = true)
                } else {
                    throw casSchemaChanged(service)
                }
            } else {
                val landed = follow(MYSTU_ENTRY_URL, allowCas = true)
                if (parseCasServiceUrl(landed.url, service, setOf(actualService)) != actualService) {
                    throw casSchemaChanged(service)
                }
                landed
            }

            // Step 2: Parse CAS form and satisfy interactive challenge (if any).
            var reply = submitCasWithChallenges(
                initialPage = loginPage,
                service = service,
                expectedService = actualService,
                username = normalizedUser,
                password = password,
                challengeHandler = challengeHandler,
                fetchSsoAsset = { assetUrl -> rawGet(assetUrl, useSso = true) },
                postForm = { actionUrl, formFields -> postSsoForm(actionUrl, formFields) },
            )

            // Step 3: Verify ticket redirect back to the exact expected service callback.
            val location = reply.header("Location")
            if (!reply.isRedirect || location.isNullOrBlank()) {
                throw classifyCasFailure(reply.bodyText, service, reply.statusCode)
            }
            val target = resolveUrl(reply.url, location)
            val dest = target.toHttpUrlOrNull() ?: throw casSchemaChanged(service)
            val expected = actualService.toHttpUrlOrNull() ?: throw casSchemaChanged(service)
            val ticketValues = dest.queryParameterValues("ticket")
            if (dest.scheme != expected.scheme ||
                !dest.host.equals(expected.host, ignoreCase = true) ||
                dest.port != expected.port ||
                dest.encodedPath != expected.encodedPath ||
                dest.fragment != null ||
                dest.queryParameterNames != setOf("ticket") ||
                ticketValues.size != 1 ||
                ticketValues[0].isNullOrEmpty()
            ) {
                throw casSchemaChanged(service)
            }

            // Bridge `_UT_` for MySTU only (never for JW!).
            bridgeServiceToken(ssoJar, schoolJar, service)

            // Follow ticket callback strictly on business host.
            follow(target, allowCas = false)

            val fingerprint = accountFingerprint(normalizedUser)
            val proof = JSONObject()

            // Step 4: Verify live authenticated business session before persisting.
            if (service == "jw") {
                val home = follow(JW_HOME, allowCas = false, maxHops = 5)
                if (home.statusCode != 200 || !LocalJwClient.jwAuthenticated(home.bodyText)) {
                    throw LocalSchoolError("authentication_unconfirmed", "教务没有返回可验证的学生主页。", "jw", home.statusCode)
                }
                proof.put("authenticated", true)
                    .put("student_home_verified", true)
                    .put("http_callback_used", true)
                val sessionJson = schoolJar.exportSessionJson(
                    service = "jw",
                    targetHost = JW_HOST,
                    accountFingerprint = fingerprint,
                    jwHttpCompat = true,
                )
                return@withContext CasLoginResult("jw", sessionJson, fingerprint, proof)
            } else {
                val apiHeaders = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to MYSTU_ENTRY_URL,
                )
                val identityResp = rawGet(MYSTU_VALIDATE_URL, useSso = false, extraHeaders = apiHeaders)
                if (identityResp.statusCode != 200) {
                    throw LocalSchoolError("authentication_unconfirmed", "MySTU 身份校验未通过。", "mystu", identityResp.statusCode)
                }
                val identityJson = try {
                    JSONObject(identityResp.bodyText.trimStart('\uFEFF'))
                } catch (_: Exception) {
                    throw casSchemaChanged("mystu")
                }
                validateMyStuIdentity(identityJson, normalizedUser)

                val coursesResp = rawGet(MYSTU_COURSES_URL, useSso = false, extraHeaders = apiHeaders)
                if (coursesResp.statusCode != 200) {
                    throw casSchemaChanged("mystu")
                }
                val coursesJson = try {
                    JSONObject(coursesResp.bodyText.trimStart('\uFEFF'))
                } catch (_: Exception) {
                    throw casSchemaChanged("mystu")
                }
                val courses = LocalMyStuClient.parseCoursesPayload(coursesJson)
                proof.put("authenticated", true)
                    .put("identity_verified", true)
                    .put("courses_verified", true)
                    .put("course_count", courses.size)

                // Bootstrap Moodle (elc / campus) while ephemeral SSO cookies exist in memory,
                // so persisted my.stu.edu.cn cookies can read assignment pages later without SSO.
                val moodles = detectMoodleInstances(courses)
                val bootstrapObj = JSONObject()
                for (name in moodles) {
                    val verified = try {
                        val page = follow("$MYSTU_BASE/courses/$name/", allowCas = true, maxHops = 12, moodle = name)
                        val finalUrl = page.url.toHttpUrlOrNull()
                        val doc = Jsoup.parse(page.bodyText)
                        page.statusCode == 200 &&
                            finalUrl != null &&
                            finalUrl.host.equals(MYSTU_HOST, ignoreCase = true) &&
                            finalUrl.encodedPath in MOODLE_PATHS.getValue(name) &&
                            !isLoginHtml(page.bodyText) &&
                            doc.selectFirst("""input[type="password"]""") == null &&
                            doc.selectFirst("""a[href*="/login/logout.php"]""") != null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                    bootstrapObj.put(name, verified)
                    proof.put("${name}_bootstrapped", verified)
                }
                proof.put("moodle_bootstrap", bootstrapObj)

                val sessionJson = schoolJar.exportSessionJson(
                    service = "mystu",
                    targetHost = MYSTU_HOST,
                    accountFingerprint = fingerprint,
                    jwHttpCompat = false,
                )
                return@withContext CasLoginResult("mystu", sessionJson, fingerprint, proof)
            }
        } finally {
            // Central CAS SSO cookies must never outlive the login handshake.
            ssoJar.clearAll()
        }
    }

    private suspend fun submitCasWithChallenges(
        initialPage: LocalHttpResponse,
        service: String,
        expectedService: String,
        username: String,
        password: String,
        challengeHandler: SchoolChallengeHandler?,
        fetchSsoAsset: suspend (String) -> LocalHttpResponse,
        postForm: suspend (String, Map<String, String>) -> LocalHttpResponse,
    ): LocalHttpResponse {
        var currentPage = initialPage
        for (round in 0 until 2) {
            val form = parseCasLoginForm(currentPage, service, expectedService)
            val fields = LinkedHashMap<String, String>(form.hiddenFields)
            fields["username"] = username
            fields["password"] = password

            if (form.captchaFieldName != null) {
                if (challengeHandler == null) {
                    throw LocalSchoolError(
                        "captcha_required",
                        "统一认证要求输入图形验证码，请手动重新连接。",
                        service,
                    )
                }
                val imgBytes = if (form.captchaImageUrl != null) {
                    val asset = fetchSsoAsset(form.captchaImageUrl)
                    if (asset.statusCode == 200) asset.bodyBytes else ByteArray(0)
                } else {
                    ByteArray(0)
                }
                val dataUrl = if (imgBytes.isNotEmpty()) {
                    "data:image/jpeg;base64,${LocalBase64.encode(imgBytes)}"
                } else {
                    ""
                }
                val answer = challengeHandler.onChallenge(
                    SchoolChallenge.Captcha(
                        service = service,
                        imageBytes = imgBytes,
                        imageDataUrl = dataUrl,
                        fieldName = form.captchaFieldName,
                    ),
                ) ?: throw LocalSchoolError("cancelled", "已取消登录。", service)
                fields[form.captchaFieldName] = answer.trim()
            }

            if (form.totpFieldName != null) {
                if (challengeHandler == null) {
                    throw LocalSchoolError(
                        "totp_required",
                        "统一认证要求输入动态口令，请手动重新连接。",
                        service,
                    )
                }
                val code = challengeHandler.onChallenge(
                    SchoolChallenge.Totp(
                        service = service,
                        fieldName = form.totpFieldName,
                    ),
                ) ?: throw LocalSchoolError("cancelled", "已取消登录。", service)
                fields[form.totpFieldName] = code.trim()
            }

            val reply = try {
                postForm(form.action, fields)
            } finally {
                fields.clear()
            }

            if (reply.isRedirect && !reply.header("Location").isNullOrBlank()) {
                return reply
            }

            // If CAS responded with a second-step OTP challenge form (without username/password),
            // handle it when an interactive challengeHandler is provided.
            val otpHandled = trySecondStepOtp(
                reply = reply,
                service = service,
                expectedService = expectedService,
                challengeHandler = challengeHandler,
                postForm = postForm,
            )
            if (otpHandled != null) return otpHandled

            // If a captcha appeared dynamically on retry (first round had no captcha, second has captcha),
            // let round 1 collect the captcha interactively instead of failing immediately.
            val doc = Jsoup.parse(reply.bodyText, reply.url)
            val nowHasCaptcha = doc.select("input").any {
                attrContainsIgnoreCase(it, "name", "captcha") || attrContainsIgnoreCase(it, "id", "captcha")
            }
            if (round == 0 && form.captchaFieldName == null && nowHasCaptcha && challengeHandler != null) {
                currentPage = reply
                continue
            }

            return reply
        }
        throw LocalSchoolError("login_rejected", "统一认证未确认登录；请检查学号和密码后重试。", service)
    }

    private suspend fun trySecondStepOtp(
        reply: LocalHttpResponse,
        service: String,
        expectedService: String,
        challengeHandler: SchoolChallengeHandler?,
        postForm: suspend (String, Map<String, String>) -> LocalHttpResponse,
    ): LocalHttpResponse? {
        if (reply.statusCode != 200) return null
        val doc = Jsoup.parse(reply.bodyText, reply.url)
        val otpInput = doc.select("form input").firstOrNull {
            it.attr("autocomplete").equals("one-time-code", ignoreCase = true) ||
                attrContainsIgnoreCase(it, "name", "otp", "totp", "token")
        } ?: return null
        val form = otpInput.parents().firstOrNull { it.tagName() == "form" } ?: return null
        // Skip if this is still the main username/password login form.
        if (form.selectFirst("""input[name="password"]""") != null) return null
        if (challengeHandler == null) {
            throw LocalSchoolError("totp_required", "统一认证要求动态口令，请手动重新连接。", service)
        }
        val actionRaw = form.attr("action")
        val action = if (actionRaw.isBlank()) reply.url else resolveUrl(reply.url, actionRaw)
        parseCasServiceUrl(action, service, setOf(expectedService))
        val fieldName = otpInput.attr("name").ifBlank { "otp" }
        val answer = challengeHandler.onChallenge(
            SchoolChallenge.Totp(service = service, fieldName = fieldName),
        ) ?: throw LocalSchoolError("cancelled", "已取消登录。", service)

        val fields = LinkedHashMap<String, String>()
        for (hidden in form.select("""input[type="hidden"][name]""")) {
            fields[hidden.attr("name")] = hidden.attr("value")
        }
        fields[fieldName] = answer.trim()
        val secondReply = postForm(action, fields)
        if (!secondReply.isRedirect || secondReply.header("Location").isNullOrBlank()) {
            throw LocalSchoolError("totp_rejected", "动态口令不对或已过期，请等下一个动态码后重试。", service, secondReply.statusCode)
        }
        return secondReply
    }

    internal fun classifyCasFailure(html: String, service: String, statusCode: Int): LocalSchoolError {
        val doc = Jsoup.parse(html)
        val errText = cleanText(
            doc.selectFirst(".errors, #msg, .alert-danger, .error-msg, .login-error")?.text()
                ?: doc.body().text(),
        )
        val lower = errText.lowercase(Locale.ROOT)
        return when {
            "验证码" in errText || "captcha" in lower ->
                LocalSchoolError("captcha_rejected", "图形验证码不对，请重试。", service, statusCode)
            "动态" in errText || "令牌" in errText || "otp" in lower || "totp" in lower ->
                LocalSchoolError("totp_rejected", "动态口令不对或已过期，请重试。", service, statusCode)
            "锁定" in errText || "频繁" in errText || "locked" in lower ->
                LocalSchoolError("too_many_attempts", "登录尝试过于频繁，账号可能暂时受限，请稍后再试。", service, statusCode)
            "密码" in errText || "凭证" in errText || "用户名" in errText ||
                "credentials" in lower || "incorrect" in lower || "badcredentials" in lower ||
                "authentication failed" in lower ->
                LocalSchoolError("invalid_credentials", "学校统一认证未通过：学号或密码不对。", service, statusCode)
            statusCode >= 500 ->
                LocalSchoolError("source_unavailable", "统一认证服务暂时不可用，请稍后再试。", service, statusCode)
            else ->
                LocalSchoolError("login_rejected", "统一认证未确认登录；请检查学号和密码后重试。", service, statusCode)
        }
    }

    internal fun validateMyStuIdentity(payload: JSONObject, expectedUsername: String?) {
        val rawUser = payload.opt("username")
        val rawId = payload.opt("id")
        val successOk = !payload.has("success") || payload.opt("success") == true
        val authOk = !payload.has("authenticated") || payload.opt("authenticated") == true
        if (rawUser !is String || rawUser.isBlank() ||
            rawId is Boolean || (rawId !is String && rawId !is Number) || rawId.toString().isBlank() ||
            !successOk || !authOk
        ) {
            throw LocalSchoolError("authentication_unconfirmed", "MySTU 没有返回已核验的身份资料。", "mystu")
        }
        if (expectedUsername != null && normalizeAccount(rawUser) != normalizeAccount(expectedUsername)) {
            throw LocalSchoolError(
                "authentication_identity_mismatch",
                "MySTU 返回身份与本次登录账号不一致；未保存会话。",
                "mystu",
            )
        }
    }

    private fun detectMoodleInstances(courses: List<MyStuCourse>): List<String> {
        val needed = linkedSetOf<String>()
        for (course in courses) {
            val parsed = course.url.toHttpUrlOrNull() ?: continue
            if (parsed.isHttps && parsed.host.equals(MYSTU_HOST, ignoreCase = true) && parsed.port == 443) {
                for (name in MOODLE_SERVICES.keys) {
                    if (parsed.encodedPath.startsWith("/courses/$name/")) {
                        needed.add(name)
                    }
                }
            }
        }
        return if (courses.isEmpty()) listOf("elc") else needed.sorted()
    }

    private fun validateCallbackQuery(service: String, parsed: HttpUrl) {
        val names = parsed.queryParameterNames
        if (service == "mystu" && parsed.encodedPath in MOODLE_ALIASES && names.isNotEmpty()) {
            throw casSchemaChanged(service)
        }
        val moodleTest = service == "mystu" && parsed.encodedPath in MOODLE_TEST_PATHS && names == setOf("testsession")
        if (moodleTest) {
            val values = parsed.queryParameterValues("testsession")
            if (values.size != 1 || values[0] == null || !DIGITS_20_RE.matches(values[0]!!)) {
                throw casSchemaChanged(service)
            }
            return
        }
        val allowedKeys = if (service == "jw" && parsed.encodedPath == "/jsxsd/xk/LoginToXk") {
            setOf("method", "ticket1")
        } else {
            setOf("ticket")
        }
        if ((names - allowedKeys).isNotEmpty()) throw casSchemaChanged(service)
        for (key in names) {
            val values = parsed.queryParameterValues(key)
            if (values.size != 1 || values[0].isNullOrEmpty() || values[0]!!.length > 8192) {
                throw casSchemaChanged(service)
            }
        }
    }

    @Volatile
    internal var testTransport: ((OkHttpClient, Request) -> Response)? = null

    internal fun executeBounded(
        client: OkHttpClient,
        request: Request,
        source: String,
        maxBytes: Int,
        deadlineAtMs: Long,
        credentialPost: Boolean = false,
    ): LocalHttpResponse {
        val response: Response = try {
            val mock = testTransport
            if (mock != null) {
                mock(client, request)
            } else {
                client.newCall(request).execute()
            }
        } catch (err: SocketTimeoutException) {
            throw LocalSchoolError(
                if (credentialPost) "authentication_outcome_unknown" else "request_timeout",
                if (credentialPost) "认证请求超时，无法确认登录结果，请稍后重试。" else "学校系统响应太慢，请稍后再试。",
                source,
                cause = err,
            )
        } catch (err: ConnectException) {
            throw LocalSchoolError(
                if (source == "jw" && !credentialPost) "source_unavailable" else "network_error",
                if (source == "jw" && !credentialPost) "学校教务系统暂时无法连接（可能正在夜间维护），请稍后再试。" else "连不上学校系统，请检查网络后再试。",
                source,
                cause = err,
            )
        } catch (err: UnknownHostException) {
            throw LocalSchoolError("network_error", "无法解析学校域名，请检查网络连接。", source, cause = err)
        } catch (err: SSLException) {
            throw LocalSchoolError("network_error", "学校站点 TLS 安全连接校验失败，请检查当前网络环境。", source, cause = err)
        } catch (err: IOException) {
            throw LocalSchoolError(
                if (credentialPost) "authentication_outcome_unknown" else "network_error",
                if (credentialPost) "认证请求结果无法确认；没有重发密码，请稍后重试。" else "连不上学校系统，请检查网络后再试。",
                source,
                cause = err,
            )
        }

        response.use { res ->
            val headers = LinkedHashMap<String, String>()
            for (i in 0 until res.headers.size) {
                headers[res.headers.name(i)] = res.headers.value(i)
            }
            val bodyStream = res.body?.byteStream()
            val out = ByteArrayOutputStream()
            if (bodyStream != null) {
                val buf = ByteArray(8192)
                var total = 0
                try {
                    while (true) {
                        if (System.currentTimeMillis() > deadlineAtMs) {
                            throw LocalSchoolError("request_timeout", "读取学校响应超时，已停止读取。", source, res.code)
                        }
                        val n = bodyStream.read(buf)
                        if (n == -1) break
                        total += n
                        if (total > maxBytes) {
                            throw LocalSchoolError(
                                if (credentialPost) "authentication_response_limit" else "response_too_large",
                                "学校响应超出大小上限，已停止读取。",
                                source,
                                res.code,
                            )
                        }
                        out.write(buf, 0, n)
                    }
                } catch (err: LocalSchoolError) {
                    throw err
                } catch (err: SocketTimeoutException) {
                    throw LocalSchoolError("request_timeout", "读取学校响应超时，请稍后再试。", source, res.code, err)
                } catch (err: IOException) {
                    throw LocalSchoolError("network_error", "读取学校响应中断，请检查网络后重试。", source, res.code, err)
                }
            }
            return LocalHttpResponse(
                statusCode = res.code,
                url = res.request.url.toString(),
                headers = headers,
                bodyBytes = out.toByteArray(),
            )
        }
    }

    internal fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    internal fun urlDecode(value: String): String = URLDecoder.decode(value, "UTF-8")

    private fun casSchemaChanged(source: String): LocalSchoolError = LocalSchoolError(
        "authentication_schema_changed",
        "学校认证页面或跳转结构发生变化；未猜测字段或重发密码。",
        source,
    )
}
