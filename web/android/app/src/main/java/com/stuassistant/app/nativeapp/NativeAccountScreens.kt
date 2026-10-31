package com.stuassistant.app.nativeapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.RoundedRectangle
import java.net.URLEncoder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Authentication stays inside the existing store; entering the app never recreates its session. */
@Composable
fun NativeWelcomeScreen(
    store: NativeStore,
    s: NativeState,
    start: String = "hello",
    onDone: () -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    var step by remember(store, start) { mutableStateOf(start.takeIf { it in setOf("hello", "account", "bind") } ?: "account") }
    val done by rememberUpdatedState(onDone)
    val cancel by rememberUpdatedState(onCancel)
    val wide = LocalConfiguration.current.screenWidthDp >= 960
    when (step) {
        "bind" -> NativeCampusBind(store, s, onDone = { done() }, onCancel = cancel)
        "account" -> NativeAccountForm(store, s, onAuthenticated = { step = "bind" }, onBack = {
            if (cancel != null) cancel?.invoke() else step = "hello"
        }, onPreview = if (wide) ({ store.enterPreview(); done() }) else null)
        else -> {
            BackHandler(enabled = cancel != null) { cancel?.invoke() }
            NativeAccountSurface {
                NativeLogo()
                NTitle("下一节课在哪，\n作业几点截止。", 32, 40.32f)
                NText("教务课表、MySTU 作业和学校通知收在一处，按教务处官方作息算准每一节课。", muted = true)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NText("现在该去哪间教室，还有几分钟")
                    NText("作业按截止时间排好，考试倒数")
                    NText("只读不代办，账号密码由你本人输入、加密存在你自己的空间")
                }
                NButton("开始使用", { step = "account" }, primary = true, modifier = Modifier.fillMaxWidth())
                NButton("先看看示例", { store.enterPreview(); done() }, modifier = Modifier.fillMaxWidth())
                NText("个人项目，与汕头大学官方无关。", 14, muted = true)
            }
        }
    }
}

@Composable
private fun NativeAccountSurface(content: @Composable ColumnScope.() -> Unit) {
    NPage(title = "") {
        content()
        NText("联合创始人 小爱 · 大象 · FKL", 14, muted = true)
    }
}

@Composable
private fun NativeAccountForm(
    store: NativeStore,
    s: NativeState,
    onAuthenticated: () -> Unit,
    onBack: () -> Unit,
    onPreview: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val authenticated by rememberUpdatedState(onAuthenticated)
    val confirmFeedback = nHapticFeedback("confirm")
    val rejectFeedback = nHapticFeedback("reject")
    val back by rememberUpdatedState(onBack)
    val expired = s.error?.let { it.contains("登录") && it.contains("过期") } == true
    var notice by remember { mutableStateOf<String?>(if (expired) "登录已过期，请重新登录。" else null) }
    var mode by remember { mutableStateOf(if (expired) "login" else "register") }
    LaunchedEffect(expired) {
        if (expired) { notice = "登录已过期，请重新登录。"; mode = "login" }
    }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(s.server) }
    var editServer by remember { mutableStateOf(s.server.isBlank()) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var requestJob by remember { mutableStateOf<Job?>(null) }

    val leave = {
        requestJob?.cancel()
        password = ""
        busy = false
        back()
    }
    BackHandler { leave() }
    NativeAccountSurface {
        NButton("返回", leave)
        NText(if (mode == "login") "登录助手账号" else "创建助手账号", 28, true)
        NText("这是助手自己的账号，换手机时用它找回设置。和学校账号分开。", muted = true)
        if (notice != null) Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { NText(notice!!, 14) }
        else if (s.error != null) NError(s.error)
        NChips(listOf("register" to "注册", "login" to "登录"), mode) { mode = it; problem = null }
        if (editServer || server.isBlank()) {
            NField("服务器地址", server, { server = it.take(2048) }, enabled = !busy)
            NText("由部署这个助手的人提供。", 14, muted = true)
        } else {
            NText("连接到 ${try { java.net.URI(server).host ?: server } catch (_: Exception) { server }}", 14, muted = true)
            NButton("更改", { editServer = true })
        }
        NField("邮箱", email, { email = it.take(200) }, enabled = !busy)
        NField("口令", password, { password = it.take(200) }, password = true, enabled = !busy)
        NText(if (mode == "register") "不要用学校密码。忘记口令可以换个邮箱重新注册，课表会重新同步。" else "注册时设置的口令。", 14, muted = true)
        NError(problem)
        NButton(if (busy) "正在连服务器…" else if (mode == "login") "登录" else "创建并继续", {
            if (!busy) {
                val address = email.trim()
                when {
                    address.length < 3 || !address.contains('@') || address.startsWith('@') || address.endsWith('@') -> { problem = "请填写完整的邮箱地址。"; rejectFeedback() }
                    password.length < 8 -> { problem = "填写的内容格式不对，检查一下邮箱和口令（至少 8 位）。"; rejectFeedback() }
                    else -> {
                        busy = true
                        problem = null
                        val secret = password
                        val register = mode == "register"
                        requestJob = scope.launch {
                            try {
                                val raw = server.trim().let { if (it.contains("://") || it.isEmpty()) it else "https://$it" }
                                val normalized = NativeApi.normalizeServer(raw)
                                if (normalized.isBlank()) throw NativeApiError("server_required")
                                // Probe the candidate without touching the saved server/session,
                                // and never attach the current account's Bearer to that address.
                                val probe = NativeApi()
                                val health = try { probe.request(NativeSession(normalized, null, 0L), "/v1/health", auth = false) }
                                    finally { probe.close() }
                                if (!health.optBoolean("ok")) throw NativeApiError("server_unavailable", detail = "这个地址没有返回可用的助手服务。")
                                currentCoroutineContext().ensureActive()
                                store.setServer(normalized)
                                if (store.state.value.server != normalized) throw NativeApiError("storage_failed", detail = "服务器地址未能保存。")
                                server = normalized
                                store.login(address, secret, register)
                                currentCoroutineContext().ensureActive()
                                password = ""
                                editServer = false
                                confirmFeedback()
                                authenticated()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                problem = accountError(error)
                                rejectFeedback()
                            } finally { busy = false }
                        }
                    }
                }
            }
        }, enabled = !busy, primary = true, modifier = Modifier.fillMaxWidth())
        if (onPreview != null) NButton("先看看示例", { if (busy) requestJob?.cancel(); onPreview() }, modifier = Modifier.fillMaxWidth())
    }
}

private data class PendingSchool(val account: String, val password: String, val remember: Boolean)
private val BIND_TERMINAL = setOf("ok", "failed", "cancelled")

@Composable
private fun NativeCampusBind(store: NativeStore, s: NativeState, onDone: () -> Unit, onCancel: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    val done by rememberUpdatedState(onDone)
    val confirmFeedback = nHapticFeedback("confirm")
    val rejectFeedback = nHapticFeedback("reject")
    val cancel by rememberUpdatedState(onCancel)
    val serial = remember { Mutex() }
    var account by remember { mutableStateOf(s.schoolId) }
    var password by remember { mutableStateOf("") }
    var rememberPassword by remember { mutableStateOf(true) }
    var agree by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<JSONObject?>(null) }
    var jobId by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingSchool?>(null) }
    var starting by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    var pollingFinished by remember { mutableStateOf(false) }
    var startJob by remember { mutableStateOf<Job?>(null) }
    var inputJob by remember { mutableStateOf<Job?>(null) }
    var pollJob by remember { mutableStateOf<Job?>(null) }
    var cancelSent by remember { mutableStateOf<String?>(null) }
    val answers = remember { mutableStateMapOf<String, String>() }

    suspend fun cancelRemote(id: String) {
        if (cancelSent == id) return
        withContext(NonCancellable) {
            withTimeoutOrNull(5_000) {
                try {
                    serial.withLock { store.request("/v1/bind/${accountEncoded(id)}", "DELETE") }
                    cancelSent = id
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* A lost server job expires; no detached polling is retained. */ }
            }
        }
    }

    val stop: (Boolean) -> Unit = { leave ->
        if (!leaving) {
            leaving = true
            scope.launch {
                try {
                    // Let an in-flight creation return its ID so the server job can be cancelled.
                    startJob?.join()
                    inputJob?.cancelAndJoin()
                    pollJob?.cancelAndJoin()
                    val id = jobId
                    if (id != null && job?.optString("state") !in BIND_TERMINAL) cancelRemote(id)
                    jobId = null
                    job = null
                    pending = null
                    password = ""
                    answers.clear()
                    ready = false
                    if (leave) (cancel ?: done)() else problem = "已停止这次连接。你可以重新填写后再试。"
                } finally { leaving = false }
            }
        }
    }
    BackHandler(enabled = onCancel != null || starting || jobId != null) { stop(true) }

    LaunchedEffect(store, jobId) {
        val id = jobId ?: return@LaunchedEffect
        pollJob = currentCoroutineContext().job
        pollingFinished = false
        var terminal = false
        var misses = 0
        val began = System.nanoTime()
        try {
            while (true) {
                val snapshot = job ?: return@LaunchedEffect
                when (snapshot.optString("state")) {
                    "ok" -> {
                        terminal = true
                        problem = null
                        answers.clear()
                        pending?.let { credentials -> withContext(Dispatchers.IO) {
                            store.rememberSchool(credentials.account, credentials.password, credentials.remember)
                        } }
                        val rememberProblem = pending?.takeIf { it.remember && !store.state.value.rememberSchool }
                            ?.let { store.state.value.error ?: "校园账号已连接，但本机密码副本未能保存。" }
                        pending = null
                        password = ""
                        val errors = snapshot.optJSONObject("result")?.optJSONObject("errors")
                        store.reloadAfterMutation()
                        if (rememberProblem != null) problem = rememberProblem
                        ready = true
                        if ((errors?.length() ?: 0) == 0) confirmFeedback()
                        if ((errors?.length() ?: 0) == 0 && problem == null && !leaving) done()
                        return@LaunchedEffect
                    }
                    "failed" -> {
                        terminal = true
                        pending = null
                        answers.clear()
                        problem = bindFailure(snapshot)
                        rejectFeedback()
                        return@LaunchedEffect
                    }
                    "cancelled" -> {
                        terminal = true
                        pending = null
                        answers.clear()
                        problem = "这次连接已经取消，可重新发起。"
                        return@LaunchedEffect
                    }
                }
                if ((System.nanoTime() - began) / 1_000_000 > 300_000) {
                    problem = "这次连接已超过 5 分钟，已停止查询。请重新连接。"
                    return@LaunchedEffect
                }
                delay(1_500)
                if (leaving) continue
                try {
                    serial.withLock {
                        if (job?.optString("state") !in BIND_TERMINAL) {
                            job = checkedBindJob(store.request("/v1/bind/${accountEncoded(id)}"), id)
                        }
                    }
                    misses = 0
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    if (error is NativeApiError && error.status in setOf(404, 410)) {
                        problem = "这次连接已失效，服务器可能已经重启。请重新连接。"
                        return@LaunchedEffect
                    }
                    if (++misses >= 6) {
                        problem = "连续查不到连接状态，请检查网络后重新连接。"
                        return@LaunchedEffect
                    }
                }
            }
        } finally {
            if (!terminal) cancelRemote(id)
            if (!terminal) pending = null
            pollingFinished = true
        }
    }

    val state = job?.optString("state").orEmpty()
    val running = !ready && jobId != null && state !in BIND_TERMINAL && !pollingFinished
    val busy = starting || running || leaving || (state == "ok" && !ready)
    NativeAccountSurface {
        if (onCancel != null && !busy) NButton("返回", { stop(true) })
        NText("连接校园账号", 28, true)
        NText("本人授权使用：这里的账号密码由你本人输入，只用于替你读你自己的教务和 MySTU 数据。助手不代你提交任何东西，也不保存学校数据的副本到别人的空间。", 14, muted = true)
            NField("学号 / 校园账号", account, { if (!busy) account = it.take(40) }, enabled = !busy)
            NField("学校统一认证密码", password, { if (!busy) password = it.take(200) }, password = true, enabled = !busy)
            AccountCheckbox(agree, { agree = it }, enabled = !busy, label = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("本人授权") }
                append("：我确认用的是")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("我自己的") }
                append("学号与密码，授权助手")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("只读") }
                append("取我本人可见的教务与 MySTU 数据；不代我签到、交作业、缴费或提交任何东西。")
            })
            AccountCheckbox(rememberPassword, { rememberPassword = it }, enabled = !busy,
                label = AnnotatedString("记住学校密码，会话过期时自动重连（加密存在你自己那份空间，可随时清除）"))
            NText("本机副本会加密保存。服务器在绑定成功后也会加密保存凭据用于重连；本开关只控制本机副本，关闭它不会删除服务器凭据。", 14, muted = true)
        val challenges = job?.optJSONArray("challenges")
        if (!leaving && challenges != null) for (index in 0 until challenges.length()) {
            val challenge = challenges.optJSONObject(index) ?: continue
            val challengeId = challenge.optString("id")
            val kind = challenge.optString("kind")
            NCard {
                NText(when (kind) { "captcha" -> "输入图中的验证码"; "totp" -> "输入你数盾 App 里当前的 6 位动态口令（30 秒一变，只有你本人能看到；输入后即丢弃，不保存）"; else -> "用手机扫码确认" }, 16, true)
                when (kind) {
                    "captcha" -> AccountCaptcha(challenge.optString("image_b64"))
                    "qr" -> AccountQrContent(challenge.optString("content"), challenge.optString("image_b64"))
                }
                if (kind != "qr") {
                    NField(if (kind == "totp") "动态码" else "验证码", answers[challengeId].orEmpty(), {
                        answers[challengeId] = if (kind == "totp") it.filter(Char::isDigit).take(6) else it.take(12)
                    }, enabled = submitting == null && !leaving)
                    NButton(if (submitting == challengeId) "正在提交…" else "提交", {
                        if (submitting == null) {
                            submitting = challengeId
                            problem = null
                            val answer = answers[challengeId].orEmpty().trim()
                            inputJob = scope.launch {
                                try {
                                    serial.withLock {
                                        val id = jobId ?: return@withLock
                                        job = checkedBindJob(store.request("/v1/bind/${accountEncoded(id)}/input", "POST",
                                            JSONObject().put("challenge_id", challengeId).put("value", answer)), id)
                                        answers.remove(challengeId)
                                    }
                                } catch (error: CancellationException) { throw error }
                                catch (error: Exception) { problem = accountError(error); rejectFeedback() }
                                finally { submitting = null }
                            }
                        }
                    }, enabled = submitting == null && answers[challengeId].orEmpty().isNotBlank(), primary = true)
                }
            }
        }

        if (leaving) NText("正在取消连接…", 14, muted = true)
        else if (state == "running") NText("正在连接学校系统，通常要十几秒…", 14, muted = true)
        else if (state == "ok") NText("已连接，接下来读取你的课表", 14, muted = true)
        NError(problem)
        val result = job?.optJSONObject("result")
        val failed = result?.optJSONObject("errors")
        if (ready) {
            val bound = result?.optJSONArray("bound")
            NText("已连接：${jsonStrings(bound).joinToString("、") { serviceLabel(it) }}", bold = true)
            if (failed != null) for (key in failed.keys()) NError("${serviceLabel(key)}：${accountError(NativeApiError(failed.optString(key)))}")
            NButton("继续使用", { done() }, primary = true)
            if (failed != null && failed.length() > 0) NButton("重新连接", {
                    jobId = null; job = null; ready = false; problem = null
                })
        } else if (!running && state != "ok") {
            NButton(if (starting) "连接中…" else "连接", {
                when {
                    !agree -> { problem = "请先确认本人只读授权。"; rejectFeedback() }
                    account.trim().length < 3 || password.isEmpty() -> { problem = "请填写校园账号和学校统一认证密码。"; rejectFeedback() }
                    else -> {
                        starting = true; problem = null; ready = false; pollingFinished = false; answers.clear()
                        val credentials = PendingSchool(account.trim(), password, rememberPassword)
                        startJob = scope.launch {
                            try {
                                jobId = null; job = null; pending = credentials
                                val response = store.request("/v1/bind", "POST", JSONObject()
                                    .put("services", JSONArray(listOf("jw", "mystu"))).put("student_id", credentials.account).put("password", credentials.password))
                                val checked = checkedBindJob(response)
                                password = ""
                                if (!credentials.remember) pending = credentials.copy(password = "")
                                job = checked
                                jobId = checked.getString("job_id")
                            } catch (error: CancellationException) { pending = null; throw error }
                            catch (error: Exception) { pending = null; problem = accountError(error); rejectFeedback() }
                            finally { starting = false }
                        }
                    }
                }
            }, enabled = !busy, primary = true, modifier = Modifier.fillMaxWidth())
        }
        if ((starting || running) && !leaving) NButton("取消这次连接", { stop(false) })
        NButton(if (leaving) "正在取消…" else if (busy) "取消并返回" else "稍后再连", { stop(true) }, enabled = !leaving, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun AccountCaptcha(encoded: String, description: String = "学校提供的图形验证码") {
    val image by produceState<ImageBitmap?>(null, encoded) {
        value = withContext(Dispatchers.Default) {
            try {
                if (encoded.isBlank() || encoded.length > 2_800_000) null else {
                    val bytes = Base64.decode(encoded.substringAfter("base64,", encoded), Base64.DEFAULT)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096 || bounds.outWidth.toLong() * bounds.outHeight > 4_000_000) null
                    else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            } catch (_: Exception) { null }
        }
    }
    if (image == null) NText("正在读取验证码；图片无法显示时可取消后重新连接。", 14, muted = true)
    else Image(image!!, contentDescription = description, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 200.dp), contentScale = ContentScale.Fit)
}

@Composable
private fun AccountQrContent(content: String, encoded: String) {
    val context = LocalContext.current
    var copied by remember(content) { mutableStateOf(false) }
    if (encoded.isNotBlank()) AccountCaptcha(encoded, "学校登录二维码") else {
        val generated by produceState<Pair<ImageBitmap?, Boolean>>(null to false, content) {
            value = withContext(Dispatchers.Default) {
                try {
                    if (content.isBlank() || content.length > 8192) null to true else {
                        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512,
                            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 2))
                        val pixels = IntArray(512 * 512) { index -> if (matrix[index % 512, index / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                        Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888).asImageBitmap() to true
                    }
                } catch (_: Exception) { null to true }
            }
        }
        if (generated.first != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(generated.first!!, contentDescription = "学校登录二维码", modifier = Modifier.size(256.dp), contentScale = ContentScale.Fit)
        } else if (generated.second) NError("二维码未能生成，请复制下面的验证内容或取消后重试。")
        else NText("正在生成二维码…", 14, muted = true)
    }
    NText("在手机上确认后，这里会自动继续。", 14, muted = true)
    if (content.isBlank()) NError("学校没有提供二维码内容，可取消后重试。") else {
        SelectionContainer { NText(content, 14) }
        NButton(if (copied) "已复制" else "复制验证内容", {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("学校验证", content))
            copied = clipboard != null
        })
        if (content.startsWith("https://", true) || content.startsWith("http://", true)) NOpenLink("打开验证链接", content)
    }
}

/** A consent checkbox keeps checked semantics; it is neither a push button nor a switch. */
@Composable
private fun AccountCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean, label: AnnotatedString) {
    val tick = nHapticFeedback("toggle")
    val colors = LocalNativeColors.current
    val alpha = if (enabled) 1f else .45f
    val shape = RoundedRectangle(4.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
        value = checked, enabled = enabled, role = Role.Checkbox,
        onValueChange = { next -> if (next != checked) { tick(); onCheckedChange(next) } },
    ).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top) {
        Box(Modifier.size(20.dp).background(if (checked) colors.brand.copy(alpha = alpha) else colors.surface.copy(alpha = alpha), shape)
            .border(1.dp, if (checked) colors.brand.copy(alpha = alpha) else colors.border.copy(alpha = alpha), shape)) {
            if (checked) Canvas(Modifier.size(20.dp).padding(3.dp)) {
                val check = Path().apply {
                    moveTo(size.width * .05f, size.height * .5f)
                    lineTo(size.width * .37f, size.height * .8f)
                    lineTo(size.width * .95f, size.height * .13f)
                }
                drawPath(check, Color.White.copy(alpha = alpha), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        BasicText(label, style = TextStyle(color = colors.ink.copy(alpha = alpha), fontFamily = NativeFontFamily,
            fontSize = 14.sp, lineHeight = 22.sp))
    }
}

private fun checkedBindJob(value: JSONObject, expectedId: String? = null): JSONObject {
    val id = value.optString("job_id")
    if (id.isBlank() || (expectedId != null && id != expectedId) ||
        value.optString("state") !in setOf("running", "waiting_input", "ok", "failed", "cancelled") || value.optJSONArray("challenges") == null
    ) throw NativeApiError("invalid_response", detail = "连接状态响应不完整，请重试。")
    return value
}

private fun bindFailure(job: JSONObject): String {
    val error = job.optJSONObject("error")
    val details = error?.optJSONObject("detail")
    if (details != null && details.length() > 0) return details.keys().asSequence().joinToString("\n") { key ->
        "${serviceLabel(key)}：${accountError(NativeApiError(details.optString(key)))}"
    }
    return accountError(NativeApiError(error?.optString("code")?.takeIf { it.isNotBlank() } ?: "login_failed"))
}

private fun accountError(error: Throwable): String {
    if (error !is NativeApiError) return "连接没有完成，请检查网络后重试。"
    return when (error.code) {
        "bad_credentials" -> "邮箱或口令不对。"
        "email_taken" -> "这个邮箱已经注册过，换成登录试试。"
        "invalid_input" -> "填写的内容格式不对，检查一下邮箱和口令（至少 8 位）。"
        "invalid_authentication_input" -> "学号或密码格式不对，检查一下有没有多余空格。"
        "too_many_attempts" -> "尝试太频繁了，过几分钟再试。"
        "login_throttled" -> "这一小时里连接次数太多了，稍后再试，避免学校锁定账号。"
        "authentication_budget" -> "这次登录尝试次数太多，学校暂时不接受，稍后再试。"
        "invalid_token", "missing_bearer", "user_gone", "not_signed_in" -> "登录状态过期了，请重新登录。"
        "account_disabled" -> "这个账号已停用。"
        "invalid_credentials" -> "学校统一认证没有通过：学号或密码不对。"
        "login_rejected" -> "学校拒绝了这次登录。可以先在浏览器登录一次学校系统，确认账号正常。"
        "captcha_rejected" -> "验证码不对，再试一次。"
        "invalid_totp", "totp_rejected" -> "动态码不对或已过期，等下一个码再试。"
        "source_unavailable" -> "学校系统暂时不可用，可能在夜间维护，白天再试。"
        "network" -> "连不上助手服务器，检查一下网络或服务器地址。"
        "network_error" -> "服务器连不上学校系统，可能是网络问题，或学校系统正在维护。"
        "timeout" -> "服务器没有及时响应，稍后再试。"
        "request_timeout" -> "学校系统响应太慢，稍后再试。"
        "server_required" -> "请填写助手服务器地址。"
        "invalid_server" -> "服务器地址应为 HTTP 或 HTTPS 地址，不能包含账号、查询参数或片段。"
        "invalid_json", "invalid_response" -> "服务器返回的数据不完整，请稍后重试。"
        "authentication_schema_changed" -> "学校登录页面改版了，助手需要更新后才能连接。"
        "authentication_identity_mismatch" -> "登录后的身份和填写的学号不一致，请检查学号。"
        "authentication_outcome_unknown" -> "没能确认登录结果，请重试一次。"
        "authentication_unconfirmed" -> "学校没有确认这次登录，请重试一次。"
        "login_failed" -> "连接没有成功（login_failed）"
        "interaction_required", "captcha_required", "totp_required" -> "学校要求额外验证，但这次没能完成，请重试。"
        "insecure_transport" -> "学校教务的登录回调走了不加密的连接，服务器拒绝了，请联系部署者。"
        "insecure_authentication_route" -> "学校登录走了不安全的路线，服务器拒绝了，请联系部署者。"
        "secure_storage_unavailable", "keystore_unavailable" -> "服务器的加密存储暂时不可用，请联系部署者。"
        "session_changed" -> "账号或服务器已经切换，请重新操作。"
        else -> if (error.detail != error.code && error.detail.isNotBlank()) error.detail else "没有成功（${error.code}）"
    }
}

private fun accountEncoded(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
private fun serviceLabel(value: String): String = when (value) { "jw" -> "教务"; "mystu" -> "MySTU"; else -> value }
private fun jsonStrings(value: JSONArray?): List<String> = if (value == null) emptyList() else (0 until value.length()).map { value.optString(it) }
