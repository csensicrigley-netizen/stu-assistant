package com.stuassistant.app.nativeapp.local

import java.math.BigInteger
import java.security.SecureRandom
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/** Stateless captcha challenge returned by `jwgl.med.stu.edu.cn`. */
data class MedCaptcha(
    val yzmCode: String,
    val imageBytes: ByteArray,
    val imageDataUrl: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MedCaptcha) return false
        return yzmCode == other.yzmCode &&
            imageBytes.contentEquals(other.imageBytes) &&
            imageDataUrl == other.imageDataUrl
    }

    override fun hashCode(): Int {
        var result = yzmCode.hashCode()
        result = 31 * result + imageBytes.contentHashCode()
        result = 31 * result + imageDataUrl.hashCode()
        return result
    }
}

/** Student profile summary extracted from MedJW login response. */
data class MedUser(
    val name: String = "",
    val number: String = "",
    val code: String = "",
    val account: String = "",
    val type: String = "",
    val phone: String = "",
    val college: String = "",
    val collegeEn: String = "",
)

/** Parsed result of a MedJW login payload (`applogin` or `ssologin`). */
data class MedLoginResult(
    val ok: Boolean,
    val token: String,
    val user: MedUser,
    val error: String,
    val errorCode: String,
    val weakPass: Boolean,
)

/**
 * Direct-to-school client & pure parsers for Shantou University Medical College (SUMC) academic
 * affairs (`jwgl.med.stu.edu.cn`, QiangZhi platform + `sso.med.stu.edu.cn` CAS).
 *
 * Faithfully ports `backend/app/med_jw.py` and `backend/app/med_parse.py`:
 * - Strict read-only path whitelist (`READ_PATHS`), zero write endpoints.
 * - Pure-JVM/Android RSA-512 PKCS#1 v1.5 encryption (`BigInteger.modPow` + `SecureRandom`),
 *   matching `med_jw.rsa_password()` without triggering JCA 512-bit key-size rejections.
 * - Standard system TLS certificate verification (never TrustAll).
 * - Output timetable JSON uses snake_case fields 100% compatible with `AcademicTimetable.normalize()`
 *   and includes `"campus": "med"`.
 */
class LocalMedJwClient(
    private val httpClient: OkHttpClient = LocalSchoolHttp.defaultClient(),
) {
    private val apiClient: OkHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Fetches a new JPEG captcha image bound to [yzmCode] (millisecond timestamp). */
    suspend fun fetchCaptcha(yzmCode: String = newYzmCode()): MedCaptcha = withContext(Dispatchers.IO) {
        val code = yzmCode.trim().ifEmpty { newYzmCode() }
        val path = "/appapi/captcha/getCaptchaCode?yzmcode=${LocalSchoolHttp.urlEncode(code)}"
        val resp = rawRequest("GET", path, timeoutMs = CAPTCHA_TIMEOUT_MS)
        if (resp.statusCode >= 400) {
            throw LocalSchoolError(
                "med_unreachable",
                "教务这次没给图片（错误码 ${resp.statusCode}），稍后再试一次",
                SOURCE,
                resp.statusCode,
            )
        }
        MedCaptcha(
            yzmCode = code,
            imageBytes = resp.bodyBytes,
            imageDataUrl = imageDataUrl(resp.bodyBytes),
        )
    }

    /**
     * Authenticates via SUMC CAS (`https://sso.med.stu.edu.cn/cas/login`) and exchanges the CAS
     * service ticket for a `jwgl.med.stu.edu.cn` API token via `/appapi/ssologin`.
     *
     * CAS cookies live strictly in an ephemeral in-memory jar and are wiped in `finally`.
     */
    suspend fun loginCas(
        username: String,
        password: String,
        service: String = CAS_SERVICE,
    ): MedLoginResult = withContext(Dispatchers.IO) {
        val cleanUser = norm(username)
        if (cleanUser.isEmpty() || password.isEmpty()) {
            throw LocalSchoolError("bad_credentials", "账号和密码都要填。", SOURCE)
        }

        val casJar = IsolatedCookieJar(setOf(MED_SSO_HOST), allowSsoBridgeCapture = false)
        val casClient = httpClient.newBuilder()
            .cookieJar(casJar)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()

        val casUrl = "$CAS_LOGIN_URL?service=${LocalSchoolHttp.urlEncode(service)}"
        val deadlineAt = System.currentTimeMillis() + 25_000L

        try {
            currentCoroutineContext().ensureActive()
            val getReq = Request.Builder()
                .url(casUrl)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build()
            val getResp = try {
                LocalSchoolHttp.executeBounded(casClient, getReq, SOURCE, LocalSchoolHttp.MAX_AUTH_BYTES, deadlineAt)
            } catch (err: LocalSchoolError) {
                throw LocalSchoolError(
                    if (err.code == "request_timeout") "timeout" else "med_unreachable",
                    "连不上学校统一认证，换个网络再试。",
                    SOURCE,
                    cause = err,
                )
            }

            val html = getResp.bodyText
            val execution = extractCasExecution(html)
                ?: throw LocalSchoolError("med_failed", "统一认证的登录页变了，暂时连不上，先用手动导入。", SOURCE)

            currentCoroutineContext().ensureActive()
            val formBody = FormBody.Builder(Charsets.UTF_8)
                .add("username", cleanUser)
                .add("password", password)
                .add("execution", execution)
                .add("_eventId", "submit")
                .add("geolocation", "")
                .build()
            val postReq = Request.Builder()
                .url(casUrl)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "text/html,application/xhtml+xml")
                .post(formBody)
                .build()
            var reply = try {
                LocalSchoolHttp.executeBounded(
                    casClient, postReq, SOURCE, LocalSchoolHttp.MAX_AUTH_BYTES, deadlineAt, credentialPost = true,
                )
            } catch (err: LocalSchoolError) {
                throw LocalSchoolError(
                    if (err.code == "request_timeout" || err.code == "authentication_outcome_unknown") "timeout" else "med_unreachable",
                    "统一认证没有响应，稍后再试。",
                    SOURCE,
                    cause = err,
                )
            }

            // Follow CAS redirects strictly until ticket appears in Location/URL (max 5 hops).
            var ticket: String? = null
            var lastUrl = reply.url
            var lastBody = reply.bodyText
            for (hop in 0 until 5) {
                val loc = reply.header("Location")
                if (reply.isRedirect && !loc.isNullOrBlank()) {
                    val nextUrl = LocalSchoolHttp.resolveUrl(reply.url, loc)
                    lastUrl = nextUrl
                    val foundTicket = TICKET_RE.find(nextUrl)?.groupValues?.getOrNull(1)
                    if (!foundTicket.isNullOrEmpty()) {
                        ticket = foundTicket
                        break
                    }
                    val parsedNext = nextUrl.toHttpUrlOrNull()
                    if (parsedNext == null || !parsedNext.isHttps || !parsedNext.host.equals(MED_SSO_HOST, true)) {
                        break
                    }
                    val hopReq = Request.Builder()
                        .url(parsedNext)
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Accept", "text/html,application/xhtml+xml")
                        .get()
                        .build()
                    reply = LocalSchoolHttp.executeBounded(casClient, hopReq, SOURCE, LocalSchoolHttp.MAX_AUTH_BYTES, deadlineAt)
                    lastBody = reply.bodyText
                } else {
                    ticket = TICKET_RE.find(reply.url)?.groupValues?.getOrNull(1)
                    break
                }
            }

            if (ticket.isNullOrEmpty()) {
                val combined = "$lastUrl\n$lastBody"
                if (combined.contains("captcha", ignoreCase = true) || combined.contains("验证码")) {
                    throw LocalSchoolError(
                        "cas_captcha",
                        "这次统一认证要验证码，切到「教务本地口令」那条路再试。",
                        SOURCE,
                    )
                }
                throw LocalSchoolError(
                    "bad_credentials",
                    "统一认证说学号或密码不对，检查一下再试。",
                    SOURCE,
                )
            }

            val ssoBody = JSONObject()
                .put("ticket", ticket)
                .put("service", service)
                .put("wxCode", JSONObject.NULL)
                .put("appId", JSONObject.NULL)
            val payload = postApi("/appapi/ssologin", body = ssoBody)
            val parsed = parseLoginPayload(payload)
            if (!parsed.ok) {
                throw LocalSchoolError(parsed.errorCode, parsed.error, SOURCE)
            }
            parsed
        } finally {
            casJar.clearAll()
        }
    }

    /**
     * Authenticates with SUMC local academic password + stateless captcha using RSA-512 encryption.
     */
    suspend fun loginLocal(
        username: String,
        password: String,
        verifyCode: String,
        yzmCode: String,
    ): MedLoginResult = withContext(Dispatchers.IO) {
        val cleanUser = norm(username)
        if (cleanUser.isEmpty() || password.isEmpty()) {
            throw LocalSchoolError("bad_credentials", "账号和密码都要填。", SOURCE)
        }
        val body = loginBody(cleanUser, password, verifyCode, yzmCode)
        val payload = postApi("/appapi/applogin", body = body)
        val parsed = parseLoginPayload(payload)
        if (!parsed.ok) {
            throw LocalSchoolError(parsed.errorCode, parsed.error, SOURCE)
        }
        parsed
    }

    /**
     * Fetches the full timetable for [semester] using the MedJW API [token].
     * Returns an `AcademicTimetable.normalize()`-compatible JSONObject with `"campus": "med"`.
     */
    suspend fun fetchTimetable(
        token: String,
        semester: String,
        zc: String = "all",
    ): JSONObject = withContext(Dispatchers.IO) {
        val payload = postApi(
            "/appapi/Studentkb/index",
            token = token,
            body = timetableBody(zc = zc),
        )
        val codeStr = payload.opt("code")?.takeUnless { it === JSONObject.NULL }?.toString()?.trim()
        if (codeStr != null && codeStr != "200") {
            if (codeStr in setOf("401", "501")) {
                throw LocalSchoolError("med_expired", "医学院的登录过期了，需要重新连一次。", SOURCE, 401)
            }
            throw LocalSchoolError(
                "med_failed",
                errorText(payload.opt("code"), payload.opt("msg")).ifEmpty { "医学院这次没有返回课表。" },
                SOURCE,
            )
        }
        parseTimetablePayload(payload, semester)
    }

    /** Fetches grades for [semesterCode] (or server default semester when blank). */
    suspend fun fetchGrades(token: String, semesterCode: String = ""): JSONObject = withContext(Dispatchers.IO) {
        val first = postApi("/appapi/Studentcj/index", token = token)
        checkTokenAlive(first)
        val code = norm(semesterCode).ifEmpty { norm(first.opt("xnxqdm")) }
        val payload = postApi("/appapi/Studentcj/data", token = token, body = gradesBody(code))
        checkTokenAlive(payload)
        val merged = JSONObject(payload.toString())
        if (!merged.has("xnxqdm") || norm(merged.opt("xnxqdm")).isEmpty()) merged.put("xnxqdm", code)
        if (!merged.has("xnxqmc") || norm(merged.opt("xnxqmc")).isEmpty()) merged.put("xnxqmc", norm(first.opt("xnxqmc")))
        val rows = parseGradesPayload(merged)
        JSONObject()
            .put("semester_code", code)
            .put("semesters", first.optJSONArray("xnxqList") ?: JSONArray())
            .put("rows", JSONArray(rows))
    }

    /** Fetches exams for [semesterCode] (or server default semester when blank). */
    suspend fun fetchExams(token: String, semesterCode: String = ""): JSONObject = withContext(Dispatchers.IO) {
        val first = postApi("/appapi/xsksxx/index", token = token)
        checkTokenAlive(first)
        val code = norm(semesterCode).ifEmpty { norm(first.opt("xnxqdm")) }
        val payload = postApi("/appapi/xsksxx/datas", token = token, body = examsBody(code))
        checkTokenAlive(payload)
        val merged = JSONObject(payload.toString())
        if (!merged.has("xnxqdm") || norm(merged.opt("xnxqdm")).isEmpty()) merged.put("xnxqdm", code)
        if (!merged.has("xnxqmc") || norm(merged.opt("xnxqmc")).isEmpty()) merged.put("xnxqmc", norm(first.opt("xnxqmc")))
        val rows = parseExamsPayload(merged)
        JSONObject()
            .put("semester_code", code)
            .put("rows", JSONArray(rows))
    }

    /** Fetches student profile card (excluding national ID `sfzh`). */
    suspend fun fetchProfile(token: String): JSONObject = withContext(Dispatchers.IO) {
        val payload = postApi("/appapi/Studentxjkp/index", token = token, body = profileBody())
        checkTokenAlive(payload)
        parseProfilePayload(payload)
    }

    private fun checkTokenAlive(payload: JSONObject) {
        val codeStr = payload.opt("code")?.takeUnless { it === JSONObject.NULL }?.toString()?.trim()
        if (codeStr in setOf("401", "501")) {
            throw LocalSchoolError("med_expired", "医学院的登录过期了，需要重新连一次。", SOURCE, 401)
        }
    }

    private suspend fun postApi(path: String, token: String = "", body: JSONObject? = null): JSONObject {
        val resp = rawRequest("POST", path, token = token, body = body, timeoutMs = TIMEOUT_MS)
        return decodeJsonResponse(resp.statusCode, resp.bodyBytes)
    }

    private suspend fun rawRequest(
        method: String,
        path: String,
        token: String = "",
        body: JSONObject? = null,
        timeoutMs: Long = TIMEOUT_MS,
    ): LocalHttpResponse {
        if (!isReadPath(path)) {
            throw LocalSchoolError("blocked_write_path", "这个接口不在只读名单里，已经拦下，没有任何请求发出去", SOURCE)
        }
        currentCoroutineContext().ensureActive()
        val fullUrl = urlOf(path)
        val parsed = fullUrl.toHttpUrlOrNull()
            ?: throw LocalSchoolError("unsafe_url", "医学院接口地址无效。", SOURCE)
        if (!parsed.isHttps || !parsed.host.equals(MED_HOST, ignoreCase = true)) {
            throw LocalSchoolError("unsafe_url", "请求地址不在医学院教务安全域名内。", SOURCE)
        }

        val reqBuilder = Request.Builder()
            .url(parsed)
            .header("Accept", "application/json")
            .header("User-Agent", "stu-app-med/1")
            .header("Origin", BASE)
            .header("Referer", CAS_SERVICE)
        if (token.isNotBlank()) {
            reqBuilder.header(TOKEN_HEADER, token.trim())
        }
        if (method.equals("POST", ignoreCase = true)) {
            val bytes = (body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)
            reqBuilder.post(bytes.toRequestBody(JSON_MEDIA))
        } else {
            reqBuilder.get()
        }

        val deadlineAt = System.currentTimeMillis() + timeoutMs
        return try {
            LocalSchoolHttp.executeBounded(
                client = apiClient,
                request = reqBuilder.build(),
                source = SOURCE,
                maxBytes = LocalSchoolHttp.MAX_RESPONSE_BYTES,
                deadlineAtMs = deadlineAt,
            )
        } catch (err: LocalSchoolError) {
            if (err.code == "request_timeout") {
                throw LocalSchoolError("timeout", "医学院教务系统响应太慢，稍后再试一次", SOURCE, cause = err)
            }
            throw LocalSchoolError("med_unreachable", "连不上医学院教务系统，检查一下网络再试一次", SOURCE, cause = err)
        }
    }

    companion object {
        const val MED_HOST = "jwgl.med.stu.edu.cn"
        const val MED_SSO_HOST = "sso.med.stu.edu.cn"
        const val BASE = "https://$MED_HOST"
        const val PREFIX = "/dev-api"
        const val API = "$BASE$PREFIX/appapi"
        const val CAS_SERVICE = "$BASE/app/index.html"
        const val CAS_LOGIN_URL = "https://$MED_SSO_HOST/cas/login"
        const val TOKEN_HEADER = "token"
        const val SOURCE = "med"

        const val RSA_KEY_SIZE = 64
        const val RSA_MAX_PLAIN = RSA_KEY_SIZE - 11
        const val RSA_B64_LENGTH = 88

        /** Hardcoded public RSA-512 SubjectPublicKeyInfo (DER Base64) from QiangZhi frontend. */
        const val PUBLIC_KEY_B64 =
            "MFwwDQYJKoZIhvcNAQEBBQADSwAwSAJBAKoR8mX0rGKLqzcWmOzbfj64K8ZIgOdH" +
                "nzkXSOVOZbFu/TJhZ7rFAN+eaGkl3C4buccQd/EjEsj9ir7ijT7h96MCAwEAAQ=="

        private const val TIMEOUT_MS = 20_000L
        private const val CAPTCHA_TIMEOUT_MS = 15_000L
        private const val MAX_WEEK = 30
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val SECURE_RANDOM = SecureRandom()
        private val TICKET_RE = Regex("""ticket=(ST-[^&\s"]+)""")
        private val EXECUTION_RE = Regex("""name="execution"\s+value="([^"]+)"""")
        private val CN_DAYS = "一二三四五六日"

        /** Strict read-only endpoint whitelist (identical to `med_jw.py:READ_PATHS`). */
        val READ_PATHS: Set<String> = setOf(
            "/appapi/getIstoken",
            "/appapi/getInfo",
            "/appapi/applogin",
            "/appapi/captcha/getCaptchaCode",
            "/appapi/getimage",
            "/appapi/getmainimage",
            "/appapi/Studentkb/index",
            "/appapi/Studentkb/data",
            "/appapi/Studentkb/jxrl",
            "/appapi/Studentcj/index",
            "/appapi/Studentcj/data",
            "/appapi/Studentcj/newDetail",
            "/appapi/xsksxx/index",
            "/appapi/xsksxx/datas",
            "/appapi/Studentxjkp/index",
            "/appapi/appxl/index",
            "/appapi/appxl/data",
            "/appapi/ssologin",
            "/appapi/getCasData",
        )

        private val I18N_ERRORS: Map<String, String> = mapOf(
            "app_retrun_error_applogin" to "您的帐号或身份证号或密码不正确",
            "app_retrun_error_applogina" to "您的帐号初始密码已过期，请联系教务管理员设置有效期或重新初始化密码",
            "app_retrun_error_apploginb" to "您的帐号已过期，请联系教务管理员",
            "app_retrun_error_apploginc" to "您输入的身份证号或学生姓名不正确",
            "app_retrun_error_apploginmndl" to "您的帐号或身份证号不正确",
            "app_retrun_error_apploginmndlfh" to "您的帐号或身份证号不正确",
        )

        private val DER_NUMBERS: Pair<BigInteger, BigInteger> by lazy {
            parseDerPublicNumbers(LocalBase64.decode(PUBLIC_KEY_B64))
        }

        val PUBLIC_MODULUS: BigInteger get() = DER_NUMBERS.first
        val PUBLIC_EXPONENT: BigInteger get() = DER_NUMBERS.second

        fun isReadPath(path: String): Boolean {
            val clean = norm(path).substringBefore('?')
            return clean in READ_PATHS
        }

        fun urlOf(path: String): String {
            val body = norm(path)
            return when {
                body.startsWith("http://") || body.startsWith("https://") -> body
                body.startsWith("/appapi/") -> API + body.removePrefix("/appapi")
                body.startsWith("$PREFIX/") -> BASE + body
                else -> "$API/${body.trimStart('/')}"
            }
        }

        fun newYzmCode(): String = System.currentTimeMillis().toString()

        fun captchaUrl(yzmCode: String): String =
            "$API/captcha/getCaptchaCode?yzmcode=${LocalSchoolHttp.urlEncode(yzmCode)}"

        fun imageDataUrl(raw: ByteArray, media: String = "image/jpeg"): String =
            "data:${media.ifBlank { "image/jpeg" }};base64,${LocalBase64.encode(raw)}"

        /** Parses SubjectPublicKeyInfo DER bytes into `(modulus, exponent)`. */
        internal fun parseDerPublicNumbers(der: ByteArray): Pair<BigInteger, BigInteger> {
            val (t0, body, _) = derRead(der, 0)
            if (t0 != 0x30) throw LocalSchoolError("rsa_error", "公钥格式不对，无法加密密码", SOURCE)
            val (_, _, pos1) = derRead(body, 0) // AlgorithmIdentifier
            val (tBits, bits, _) = derRead(body, pos1)
            if (tBits != 0x03 || bits.isEmpty() || bits[0].toInt() != 0) {
                throw LocalSchoolError("rsa_error", "公钥格式不对，无法加密密码", SOURCE)
            }
            val (tInner, inner, _) = derRead(bits, 1)
            if (tInner != 0x30) throw LocalSchoolError("rsa_error", "公钥格式不对，无法加密密码", SOURCE)
            val (tN, rawN, posE) = derRead(inner, 0)
            val (tE, rawE, _) = derRead(inner, posE)
            if (tN != 0x02 || tE != 0x02) throw LocalSchoolError("rsa_error", "公钥格式不对，无法加密密码", SOURCE)
            return BigInteger(1, rawN) to BigInteger(1, rawE)
        }

        private fun derRead(data: ByteArray, start: Int): Triple<Int, ByteArray, Int> {
            var pos = start
            val tag = data[pos++].toInt() and 0xFF
            var length = data[pos++].toInt() and 0xFF
            if ((length and 0x80) != 0) {
                val count = length and 0x7F
                length = 0
                repeat(count) {
                    length = (length shl 8) or (data[pos++].toInt() and 0xFF)
                }
            }
            val slice = data.copyOfRange(pos, pos + length)
            return Triple(tag, slice, pos + length)
        }

        internal fun pkcs1Pad(message: ByteArray, keySizeBytes: Int = RSA_KEY_SIZE): ByteArray {
            if (message.size > keySizeBytes - 11) {
                throw LocalSchoolError("invalid_input", "密码太长了，超过了这个系统能加密的长度", SOURCE)
            }
            val padLen = keySizeBytes - 3 - message.size
            val out = ByteArray(keySizeBytes)
            out[0] = 0x00
            out[1] = 0x02
            var written = 0
            val tmp = ByteArray(padLen + 8)
            while (written < padLen) {
                SECURE_RANDOM.nextBytes(tmp)
                for (b in tmp) {
                    if (b.toInt() != 0) {
                        out[2 + written++] = b
                        if (written == padLen) break
                    }
                }
            }
            out[2 + padLen] = 0x00
            System.arraycopy(message, 0, out, 3 + padLen, message.size)
            return out
        }

        /** Pure PKCS#1 v1.5 RSA encryption supporting any modulus bit-length (including 512-bit). */
        fun rsaEncryptRaw(message: ByteArray, modulus: BigInteger, exponent: BigInteger): ByteArray {
            val size = (modulus.bitLength() + 7) / 8
            val block = pkcs1Pad(message, size)
            val m = BigInteger(1, block)
            if (m >= modulus) {
                throw LocalSchoolError("invalid_input", "密码太长，加密块装不下", SOURCE)
            }
            val c = m.modPow(exponent, modulus)
            return toFixedLengthUnsignedBytes(c, size)
        }

        private fun toFixedLengthUnsignedBytes(value: BigInteger, size: Int): ByteArray {
            val raw = value.toByteArray()
            val out = ByteArray(size)
            val srcPos = if (raw.size > size && raw[0].toInt() == 0) 1 else 0
            val len = minOf(size, raw.size - srcPos)
            System.arraycopy(raw, srcPos, out, size - len, len)
            return out
        }

        /** Encrypts plaintext password using QiangZhi RSA-512 / PKCS#1 v1.5 → 88-char Base64. */
        fun rsaPassword(password: String): String {
            val raw = password.toByteArray(Charsets.UTF_8)
            if (raw.isEmpty()) throw LocalSchoolError("invalid_input", "密码是空的", SOURCE)
            if (raw.size > RSA_MAX_PLAIN) {
                throw LocalSchoolError("invalid_input", "密码太长了，超过了这个系统能加密的长度", SOURCE)
            }
            val cipher = rsaEncryptRaw(raw, PUBLIC_MODULUS, PUBLIC_EXPONENT)
            return LocalBase64.encode(cipher)
        }

        fun loginBody(username: String, password: String, verifyCode: String, yzmCode: String): JSONObject {
            val stampText = yzmCode.trim()
            val stampVal: Any = stampText.toLongOrNull() ?: stampText
            return JSONObject()
                .put("username", norm(username))
                .put("password", rsaPassword(password))
                .put("code", "")
                .put("appid", JSONObject.NULL)
                .put("yzmCode", stampVal)
                .put("verifyCode", norm(verifyCode))
        }

        fun timetableBody(zc: Any = "all", jc: String = "", teadm: String = ""): JSONObject {
            val weekStr = norm(zc).ifEmpty { "all" }
            val weekVal: Any = weekStr.toIntOrNull() ?: weekStr
            return JSONObject()
                .put("zc", weekVal)
                .put("jc", norm(jc))
                .put("teadm", norm(teadm))
        }

        fun gradesBody(semesterCode: String = ""): JSONObject =
            JSONObject().put("xnxqdm", norm(semesterCode))

        fun examsBody(semesterCode: String = "", kind: String = ""): JSONObject =
            JSONObject().put("xnxqdm", norm(semesterCode)).put("ksaplxdm", norm(kind))

        fun profileBody(): JSONObject =
            JSONObject().put("yhtype", "").put("xsdm", "")

        internal fun extractCasExecution(html: String): String? {
            val doc = Jsoup.parse(html)
            val fromInput = doc.selectFirst("""input[name="execution"]""")?.attr("value")?.trim()
            if (!fromInput.isNullOrEmpty()) return fromInput
            return EXECUTION_RE.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        }

        /** Parses `applogin` / `ssologin` JSON response into a typed [MedLoginResult]. */
        fun parseLoginPayload(payload: JSONObject?): MedLoginResult {
            if (payload == null) {
                return MedLoginResult(false, "", MedUser(), "教务返回的内容看不懂，稍后再试一次", "med_failed", false)
            }
            val rawUser = payload.optJSONObject("user") ?: JSONObject()
            val user = MedUser(
                name = norm(rawUser.opt("userxm")),
                number = norm(rawUser.opt("userbh")),
                code = norm(rawUser.opt("userdm")),
                account = norm(rawUser.opt("userAccount")),
                type = norm(rawUser.opt("usertype")),
                phone = norm(rawUser.opt("userdh")),
                college = norm(rawUser.opt("userdwmc")),
                collegeEn = norm(rawUser.opt("userdwywmc")),
            )
            val weak = optNum(payload.opt("weakPass")) == 1.0 || optNum(payload.opt("initPw")) == 1.0
            if (weak) {
                return MedLoginResult(
                    ok = false,
                    token = "",
                    user = user,
                    error = "教务要求先改密码才能继续，请到教务系统改完密码再回来登录",
                    errorCode = "weak_pass",
                    weakPass = true,
                )
            }
            val token = norm(rawUser.opt("token"))
            if (token.isNotEmpty()) {
                return MedLoginResult(ok = true, token = token, user = user, error = "", errorCode = "", weakPass = false)
            }
            val code = payload.opt("code")
            val msg = payload.opt("msg")
            val msgText = norm(msg)
            val codeNum = codeNumber(code)
            val errCode = when {
                codeNum in setOf(401, 501) || "请先登录" in msgText || ("登录" in msgText && "过期" in msgText) -> "med_expired"
                "验证码" in msgText -> "bad_captcha"
                msgText in I18N_ERRORS -> "bad_credentials"
                else -> "med_failed"
            }
            val errMsg = errorText(code, msg).ifEmpty { "登录没有拿到凭证，稍后再试一次" }
            return MedLoginResult(false, "", user, errMsg, errCode, false)
        }

        fun errorText(code: Any?, msg: Any?): String {
            val text = norm(msg)
            val num = codeNumber(code)
            I18N_ERRORS[text]?.let { return it }
            if (num == 200) return ""
            if (num in setOf(401, 501) || "请先登录" in text || ("登录" in text && "过期" in text)) {
                return "教务登录已过期，请重新登录"
            }
            if ("验证码" in text) {
                return if ("过期" in text) {
                    "验证码已过期，点一下图片换一张，然后马上登录"
                } else {
                    "验证码不正确，点一下图片换一张再试一次"
                }
            }
            if (text.isNotEmpty() && hasCjk(text)) return text
            if (num == null) return "连不上医学院教务系统，检查一下网络再试一次"
            return "医学院教务系统这次没办成（错误码 $num），稍后再试一次"
        }

        /**
         * Parses MedJW `Studentkb/index` response into a standard `AcademicTimetable.normalize()`-
         * compatible JSONObject (snake_case fields + `"campus": "med"`).
         *
         * Faithfully ports `backend/app/med_parse.py` including:
         * - `kbList` string / double-encoded string / JSONArray decoding
         * - Both `{periodCode: [records]}` (real production) and `[records]` (legacy doc) day shapes
         * - Merging per-week records by `(weekday, periods, location)` so room changes remain distinct
         * - Collecting multi-week teacher names and routing unscheduled courses to `unarranged`
         */
        fun parseTimetablePayload(payload: JSONObject?, semester: String): JSONObject {
            val body = payload ?: JSONObject()
            val days = decodeKbList(body.opt("kbList"))

            data class MeetingBucket(
                val id: String,
                val courseId: String,
                val title: String,
                val className: String,
                val weekday: Int,
                val periods: List<Int>,
                val location: String,
                val rawTime: String,
                val notes: String,
                val onlineInfo: String,
                val weeks: MutableSet<Int> = sortedSetOf(),
                val teachers: MutableSet<String> = sortedSetOf(),
            )

            data class CourseBucket(
                val id: String,
                val title: String,
                val code: String,
                val className: String,
                val initialTeacher: String,
                val hue: Int,
                val meetingsByKey: LinkedHashMap<Triple<Int, List<Int>, String>, MeetingBucket> = linkedMapOf(),
                val allTeachers: MutableSet<String> = sortedSetOf(),
            )

            val courses = linkedMapOf<String, CourseBucket>()
            val unplaced = linkedMapOf<String, JSONObject>()
            var entryCount = 0

            for (dayIndex in 0 until days.length()) {
                val dayEntry = days.opt(dayIndex)
                for ((bucket, row) in extractDayRows(dayEntry)) {
                    entryCount++
                    val title = norm(row.opt("kcmc"))
                    if (title.isEmpty()) continue
                    val parsedXq = optInt(row.opt("xq"))
                    val weekday = when {
                        parsedXq != null && parsedXq in 1..7 -> parsedXq
                        dayIndex + 1 in 1..7 -> dayIndex + 1
                        else -> 1
                    }
                    val periods = extractPeriods(row, bucket)
                    val weeks = extractWeeks(row)
                    val code = norm(row.opt("kcbh"))
                    val className = norm(row.opt("jxbmc"))
                    val teacher = norm(row.opt("teaxms"))
                    val cid = LocalTimetableImporter.courseId(title, "", code)

                    if (periods.isEmpty() || weeks.isEmpty()) {
                        val uidKey = "$code|$title"
                        if (uidKey !in unplaced) {
                            unplaced[uidKey] = JSONObject()
                                .put("id", cid)
                                .put("title", title)
                                .put("class_name", className)
                                .put("teachers", JSONArray(if (teacher.isNotEmpty()) listOf(teacher) else emptyList<String>()))
                        }
                        continue
                    }

                    val room = norm(row.opt("jxcdmc")).ifEmpty { norm(row.opt("jzwmc")) }
                    val campus = norm(row.opt("szxqmc"))
                    val location = when {
                        campus.isNotEmpty() && room.isNotEmpty() && !room.startsWith(campus) -> "$campus $room"
                        room.isNotEmpty() -> room
                        else -> campus
                    }

                    val course = courses.getOrPut(cid) {
                        CourseBucket(
                            id = cid,
                            title = title,
                            code = code,
                            className = className,
                            initialTeacher = teacher,
                            hue = LocalTimetableImporter.hueFor(cid),
                        )
                    }
                    if (teacher.isNotEmpty()) course.allTeachers.add(teacher)

                    val mergeKey = Triple(weekday, periods, location)
                    val meeting = course.meetingsByKey.getOrPut(mergeKey) {
                        val identity = JSONArray()
                            .put(weekday)
                            .put(JSONArray(periods))
                            .put(location)
                            .toString()
                        val mid = "${LocalTimetableImporter.meetingId(cid, weekday, periods)}-${LocalSchoolHttp.sha256Hex(identity)}"
                        val online = row.optJSONObject("meetingData")?.let { norm(it.opt("shareUrl")) }.orEmpty()
                        val notes = if (norm(row.opt("tkbz")) == "2") "停课" else ""
                        MeetingBucket(
                            id = mid,
                            courseId = cid,
                            title = title,
                            className = course.className,
                            weekday = weekday,
                            periods = periods,
                            location = location,
                            rawTime = formatRawTime(row, weekday, periods),
                            notes = notes,
                            onlineInfo = online,
                        )
                    }
                    meeting.weeks.addAll(weeks)
                    if (teacher.isNotEmpty()) meeting.teachers.add(teacher)
                }
            }

            val coursesArr = JSONArray()
            for (course in courses.values) {
                val sortedMeetings = course.meetingsByKey.values.sortedWith(
                    compareBy<MeetingBucket>({ it.weekday }).thenBy { it.periods.firstOrNull() ?: 0 },
                )
                val meetingsArr = JSONArray()
                for (m in sortedMeetings) {
                    val mTeacher = m.teachers.joinToString("、")
                    meetingsArr.put(
                        JSONObject()
                            .put("id", m.id)
                            .put("course_id", m.courseId)
                            .put("title", m.title)
                            .put("class_name", m.className)
                            .put("weekday", m.weekday)
                            .put("weeks", JSONArray(m.weeks.toList()))
                            .put("periods", JSONArray(m.periods))
                            .put("teacher", mTeacher)
                            .put("location", m.location)
                            .put("raw_time", m.rawTime)
                            .put("notes", m.notes)
                            .put("online_info", m.onlineInfo),
                    )
                }
                val teachersList = course.allTeachers.toList()
                val teacherStr = teachersList.joinToString("、").ifEmpty { course.initialTeacher }
                coursesArr.put(
                    JSONObject()
                        .put("id", course.id)
                        .put("title", course.title)
                        .put("course_code", course.code)
                        .put("class_name", course.className)
                        .put("teachers", JSONArray(teachersList))
                        .put("teacher", teacherStr)
                        .put("hue", course.hue)
                        .put("meetings", meetingsArr),
                )
            }

            val pending = JSONArray()
            val unarrangedArr = JSONArray(unplaced.values.toList())
            if (unplaced.isNotEmpty()) {
                pending.put("有 ${unplaced.size} 门课学校还没排时间，已列在「需要留意」里。")
            }

            val doc = LocalTimetableImporter.toTimetableDoc(
                courses = coursesArr,
                semester = semester,
                campus = "med",
                checkedAt = Instant.now().toString(),
                extraUnarranged = unarrangedArr,
                pending = pending,
            )
            doc.put("source", SOURCE)
            doc.put("meta", buildMeta(body, entryCount))
            return doc
        }

        /** Parses `Studentcj/data` grades payload into grade JSON rows. */
        fun parseGradesPayload(payload: JSONObject?): List<JSONObject> {
            val rows = payload?.optJSONArray("kccjList") ?: return emptyList()
            val semCode = norm(payload.opt("xnxqdm"))
            val semName = norm(payload.opt("xnxqmc"))
            val decimals = codeNumber(payload.opt("zcjblxsdws"))

            val out = mutableListOf<JSONObject>()
            for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: continue
                val title = norm(item.opt("kcmc")).ifEmpty { norm(item.opt("kcywmc")) }
                if (title.isEmpty()) continue
                val code = norm(item.opt("kcbh"))
                val semester = norm(item.opt("xnxqmc")).ifEmpty { semName }.ifEmpty { semCode }
                val detailId = norm(item.opt("cjdm"))
                val score = norm(item.opt("zcj"))
                val mode = norm(item.opt("cjfsdm"))
                val id = "med:grade:" + digest24(semester, code, title, detailId)
                val credits = optNum(item.opt("xf"))
                val failed = mode == "01" && (score.toDoubleOrNull()?.let { it < 60.0 } == true)
                out.add(
                    JSONObject()
                        .put("id", id)
                        .put("external_id", id)
                        .put("kind", "grade")
                        .put("title", title)
                        .put("semester", semester)
                        .put("semester_code", norm(item.opt("xnxqdm")).ifEmpty { semCode })
                        .put("course_code", code)
                        .put("score", score)
                        .put("credits", credits ?: JSONObject.NULL)
                        .put("gpa", JSONObject.NULL)
                        .put("exam_type", "")
                        .put("score_mode", if (mode == "01") "百分制" else mode)
                        .put("score_decimals", decimals ?: JSONObject.NULL)
                        .put("course_type", norm(item.opt("kcdlmc")))
                        .put("detail_id", detailId)
                        .put("failed", failed),
                )
            }
            return out
        }

        /** Parses `xsksxx/datas` exams payload into exam JSON rows. */
        fun parseExamsPayload(payload: JSONObject?): List<JSONObject> {
            val rows = payload?.optJSONArray("ksapList") ?: return emptyList()
            val semCode = norm(payload.opt("xnxqdm"))
            val semName = norm(payload.opt("xnxqmc"))

            val out = mutableListOf<JSONObject>()
            for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: continue
                val title = norm(item.opt("kcmc")).ifEmpty { norm(item.opt("kcywmc")) }
                if (title.isEmpty()) continue
                val code = norm(item.opt("kcbh"))
                val date = norm(item.opt("ksrq"))
                val clock = norm(item.opt("kssj"))
                val semester = norm(item.opt("xnxqdm")).ifEmpty { semCode }.ifEmpty { semName }
                val id = "med:exam:" + digest24(semester, code, title, date, clock)
                val examTime = listOf(date, clock).filter { it.isNotEmpty() }.joinToString(" ")
                out.add(
                    JSONObject()
                        .put("id", id)
                        .put("external_id", id)
                        .put("kind", "exam")
                        .put("title", title)
                        .put("semester", semester)
                        .put("semester_name", semName)
                        .put("course_code", code)
                        .put("exam_time", examTime)
                        .put("exam_date", date)
                        .put("exam_clock", clock)
                        .put("location", norm(item.opt("kscdmc")))
                        .put("seat_number", norm(item.opt("zwh")))
                        .put("campus", norm(item.opt("xqmc")))
                        .put("week", codeNumber(item.opt("zc")) ?: JSONObject.NULL)
                        .put("weekday", codeNumber(item.opt("xq")) ?: JSONObject.NULL)
                        .put("periods_text", norm(item.opt("jcdm2")))
                        .put("form", norm(item.opt("khxsmc")))
                        .put("open_book", norm(item.opt("ksxs")) == "1")
                        .put("batch", norm(item.opt("ksaplxmc")))
                        .put("exam_id", norm(item.opt("ksrcdm")).ifEmpty { norm(item.opt("sjdm")) }),
                )
            }
            return out
        }

        /** Parses `Studentxjkp/index` or `getInfo` payload (intentionally omitting `sfzh`). */
        fun parseProfilePayload(payload: JSONObject?): JSONObject {
            val out = JSONObject()
                .put("ok", false)
                .put("source", "")
                .put("error", "")
            for (k in listOf(
                "name", "number", "account", "college", "major", "major_direction", "grade",
                "class_name", "campus", "enroll_year", "study_years", "gender", "nation",
                "status", "student_status", "phone", "email", "dorm", "school_key",
            )) {
                out.put(k, "")
            }
            if (payload == null) return out.put("error", "教务返回的内容看不懂，稍后再试一次")
            val num = codeNumber(payload.opt("code"))
            if (num != null && num != 200) {
                return out.put("error", errorText(payload.opt("code"), payload.opt("msg")))
            }
            val info = payload.optJSONObject("xjxx")
            if (info != null && info.length() > 0) {
                return out
                    .put("ok", true)
                    .put("source", "xjkp")
                    .put("name", norm(info.opt("xsxm")))
                    .put("number", norm(info.opt("xsbh")))
                    .put("college", norm(info.opt("yxmc")))
                    .put("major", norm(info.opt("zymc")))
                    .put("major_direction", norm(info.opt("zyfxmc")).ifEmpty { norm(info.opt("fxzymc")) })
                    .put("grade", norm(info.opt("dqszj")).ifEmpty { norm(info.opt("rxnf")) })
                    .put("class_name", norm(info.opt("bjmc")))
                    .put("campus", norm(info.opt("xqmc")))
                    .put("enroll_year", norm(info.opt("rxnf")))
                    .put("study_years", norm(info.opt("xz")))
                    .put("gender", norm(info.opt("xbmc")))
                    .put("nation", norm(info.opt("mzmc")))
                    .put("status", norm(info.opt("xjztmc")))
                    .put("student_status", norm(info.opt("xsztmc")))
                    .put("phone", norm(info.opt("dh")))
                    .put("email", norm(info.opt("email")))
                    .put("dorm", norm(info.opt("dorm")))
            }
            val user = payload.optJSONObject("user")
            if (user != null && user.length() > 0) {
                return out
                    .put("ok", true)
                    .put("source", "info")
                    .put("name", norm(user.opt("userxm")))
                    .put("number", norm(user.opt("userbh")))
                    .put("account", norm(user.opt("userAccount")))
                    .put("college", norm(user.opt("userdwmc")))
                    .put("phone", norm(user.opt("userdh")))
            }
            return out.put("error", "教务没有返回学籍信息，稍后再试一次")
        }

        /** Summarizes a parsed MedJW timetable doc into the 6 UI fields (`ok`, `semester`, `courses`, `meetings`, `status`, `message`). */
        fun syncSummary(doc: JSONObject?): JSONObject {
            val courses = doc?.optJSONArray("courses") ?: JSONArray()
            var meetingCount = 0
            for (i in 0 until courses.length()) {
                meetingCount += courses.optJSONObject(i)?.optJSONArray("meetings")?.length() ?: 0
            }
            val hasCourses = courses.length() > 0
            val status = if (hasCourses && doc?.optString("status") == "ok") "ok" else "partial"
            val notes = mutableListOf<String>()
            for (field in listOf("errors", "warnings")) {
                val arr = doc?.optJSONArray(field) ?: continue
                for (i in 0 until arr.length()) {
                    val text = norm(arr.opt(i))
                    if (text.isNotEmpty()) notes.add(text)
                }
            }
            val message = if (hasCourses) {
                notes.joinToString("；")
            } else {
                "这次没有拿到任何课程，可能是新学期课表还没发布。"
            }
            return JSONObject()
                .put("ok", hasCourses)
                .put("semester", norm(doc?.opt("semester")))
                .put("courses", courses.length())
                .put("meetings", meetingCount)
                .put("status", status)
                .put("message", message)
        }

        internal fun decodeJsonResponse(status: Int, raw: ByteArray): JSONObject {
            if (status == 401) {
                return JSONObject().put("code", 401).put("msg", "医学院的登录过期了，需要重新连一次。")
            }
            if (status >= 400) {
                return JSONObject()
                    .put("code", -1)
                    .put("http_status", status)
                    .put("msg", "医学院教务请求失败（HTTP $status），稍后再试。")
            }
            val text = String(raw, Charsets.UTF_8).trimStart('\uFEFF').trim()
            if (text.isEmpty()) {
                return JSONObject().put("code", -1).put("msg", "教务这次没有返回内容，稍后再试一次")
            }
            return try {
                if (!text.startsWith("{")) {
                    JSONObject().put("code", -1).put("msg", "教务返回的内容看不懂，可能是页面改了，稍后再试一次")
                } else {
                    JSONObject(text)
                }
            } catch (_: Exception) {
                JSONObject().put("code", -1).put("msg", "教务返回的内容看不懂，可能是页面改了，稍后再试一次")
            }
        }

        private fun decodeKbList(raw: Any?): JSONArray {
            if (raw == null || raw === JSONObject.NULL) return JSONArray()
            if (raw is JSONArray) return raw
            if (raw is JSONObject) return decodeKbList(raw.opt("kbList"))
            if (raw is String) {
                var current = raw.trim()
                repeat(3) {
                    if (current.isEmpty()) return JSONArray()
                    if (current.startsWith("[")) {
                        return runCatching { JSONArray(current) }.getOrDefault(JSONArray())
                    }
                    if (current.startsWith("\"")) {
                        current = runCatching { JSONObject("{\"v\":$current}").getString("v").trim() }
                            .getOrElse { return JSONArray() }
                    } else {
                        return JSONArray()
                    }
                }
            }
            return JSONArray()
        }

        private fun extractDayRows(dayEntry: Any?): List<Pair<String, JSONObject>> {
            val out = mutableListOf<Pair<String, JSONObject>>()
            when (dayEntry) {
                is JSONObject -> {
                    for (bucket in dayEntry.keys()) {
                        val rows = dayEntry.optJSONArray(bucket) ?: continue
                        for (i in 0 until rows.length()) {
                            rows.optJSONObject(i)?.let { out.add(bucket to it) }
                        }
                    }
                }
                is JSONArray -> {
                    for (i in 0 until dayEntry.length()) {
                        dayEntry.optJSONObject(i)?.let { out.add("" to it) }
                    }
                }
            }
            return out
        }

        private fun extractPeriods(row: JSONObject, bucket: String): List<Int> {
            val raw = norm(row.opt("jcdm2"))
            val nums = mutableListOf<Int>()
            if (raw.isNotEmpty()) {
                for (part in raw.replace('，', ',').split(',')) {
                    optInt(part)?.takeIf { it > 0 }?.let { nums.add(it) }
                }
            }
            if (nums.isEmpty()) {
                val joined = norm(row.opt("jcdm"))
                if (joined.length >= 2 && joined.length % 2 == 0 && joined.all { it.isDigit() }) {
                    var i = 0
                    while (i < joined.length) {
                        val n = joined.substring(i, i + 2).toIntOrNull() ?: 0
                        if (n > 0) nums.add(n)
                        i += 2
                    }
                } else if (joined.isNotEmpty()) {
                    val (parsed, _) = LocalTimetableImporter.parsePeriods(joined)
                    nums.addAll(parsed)
                }
            }
            if (nums.isEmpty()) {
                optInt(bucket)?.takeIf { it > 0 }?.let { nums.add(it) }
            }
            return nums.filter { it in 1..LocalTimetableImporter.MAX_PERIOD }.distinct().sorted()
        }

        private fun extractWeeks(row: JSONObject): List<Int> {
            val single = optInt(row.opt("zc"))
            if (single != null && single in 1..MAX_WEEK) return listOf(single)
            val text = norm(row.opt("zc")).ifEmpty { norm(row.opt("zcmc")) }
            if (text.isEmpty()) return emptyList()
            val (parsed, _) = LocalTimetableImporter.parseWeeks(text)
            return parsed.filter { it in 1..MAX_WEEK }
        }

        private fun formatRawTime(row: JSONObject, weekday: Int, periods: List<Int>): String {
            val start = norm(row.opt("qssj"))
            val end = norm(row.opt("jssj"))
            val head = if (periods.isNotEmpty() && weekday in 1..7) {
                "周${CN_DAYS[weekday - 1]} 第${periods.first()}-${periods.last()}节"
            } else {
                ""
            }
            val span = if (start.isNotEmpty() && end.isNotEmpty()) {
                "${start.take(5)}-${end.take(5)}"
            } else {
                ""
            }
            return listOf(head, span).filter { it.isNotEmpty() }.joinToString(" ")
        }

        private fun buildMeta(payload: JSONObject, entries: Int): JSONObject {
            val meta = JSONObject().put("entries", entries)
            norm(payload.opt("xnxqdm")).takeIf { it.isNotEmpty() }?.let { meta.put("semester_code", it) }
            codeNumber(payload.opt("zc"))?.let { meta.put("current_week", it) }
            norm(payload.opt("firstDayOfWeek")).takeIf { it.isNotEmpty() }?.let { meta.put("first_day_of_week", it) }
            codeNumber(payload.opt("maxzc"))?.let { meta.put("max_week", it) }
            norm(payload.opt("rq")).takeIf { it.isNotEmpty() }?.let { meta.put("today", it) }
            return meta
        }

        private fun norm(value: Any?): String = LocalTimetableImporter.norm(value)

        private fun digest24(vararg parts: String): String =
            LocalSchoolHttp.sha256Hex(parts.joinToString("|") { norm(it) }).take(24)

        private fun optInt(value: Any?): Int? {
            if (value == null || value === JSONObject.NULL || value is Boolean) return null
            if (value is Int) return value
            if (value is Long) return value.toInt()
            val text = norm(value)
            return if (text.isNotEmpty() && text.removePrefix("-").all { it.isDigit() }) text.toIntOrNull() else null
        }

        private fun codeNumber(value: Any?): Int? {
            if (value == null || value === JSONObject.NULL || value is Boolean) return null
            if (value is Int) return value
            if (value is Long) return value.toInt()
            if (value is Double) return if (value.isFinite() && value % 1.0 == 0.0) value.toInt() else null
            return optInt(value)
        }

        private fun optNum(value: Any?): Double? {
            if (value == null || value === JSONObject.NULL || value is Boolean) return null
            if (value is Number) return value.toDouble().takeIf { it.isFinite() }
            return norm(value).toDoubleOrNull()?.takeIf { it.isFinite() }
        }

        private fun hasCjk(text: String): Boolean = text.any { it in '\u4e00'..'\u9fff' }
    }
}
