package com.stuassistant.app.nativeapp

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import com.stuassistant.app.nativeapp.local.SchoolChallenge
import com.stuassistant.app.nativeapp.local.SchoolChallengeHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pure-local onboarding & direct-to-school campus binding. Zero assistant server accounts. */
@Composable
fun NativeWelcomeScreen(
    store: NativeStore,
    s: NativeState,
    start: String = "hello",
    onDone: () -> Unit,
    onCancel: (() -> Unit)? = null,
    onNavigate: ((String) -> Unit)? = null,
) {
    var step by remember(store, start) { mutableStateOf(if (start == "bind") "bind" else "hello") }
    val done by rememberUpdatedState(onDone)
    val cancel by rememberUpdatedState(onCancel)
    when (step) {
        "bind" -> NativeCampusBind(
            store = store,
            s = s,
            onDone = { done() },
            onCancel = {
                if (start == "bind" && cancel != null) cancel?.invoke()
                else step = "hello"
            },
        )
        else -> {
            BackHandler(enabled = cancel != null) { cancel?.invoke() }
            NativeAccountSurface {
                NativeLogo()
                NTitle("下一节课在哪，\n作业几点截止。", 32, 40.32f)
                NText("纯本地运行，免注册助手账号。教务课表、MySTU 作业、成绩与个人事项均经 Android Keystore 加密保存在这台手机上。", muted = true)
                NCard {
                    NRow("本机直连学校", "学号、密码与会话仅从手机直连学校系统，不经过开发者服务器", leadingIcon = "shield")
                    NDivider()
                    NRow("实时课程与教室", "现在该去哪间教室、还有几分钟，离线也能随时看", leadingIcon = "clock")
                    NDivider()
                    NRow("事项与考试倒数", "个人安排与作业截止统一管理，数据仅留在本机", leadingIcon = "items")
                }
                NButton(
                    label = "本地直连本部校园账号",
                    onClick = { step = "bind" },
                    primary = true,
                    modifier = Modifier.fillMaxWidth(),
                    haptic = NativeButtonHaptic.Navigation,
                )
                if (onNavigate != null) {
                    NButton(
                        label = "本地直连医学院教务",
                        onClick = { onNavigate("medical") },
                        modifier = Modifier.fillMaxWidth(),
                        haptic = NativeButtonHaptic.Navigation,
                    )
                    NButton(
                        label = "手动导入课表（离线）",
                        onClick = { onNavigate("import") },
                        modifier = Modifier.fillMaxWidth(),
                        haptic = NativeButtonHaptic.Navigation,
                    )
                }
                NButton(
                    label = "先看看示例",
                    onClick = { store.enterPreview(); done() },
                    modifier = Modifier.fillMaxWidth(),
                    haptic = NativeButtonHaptic.Navigation,
                )
                NButton(
                    label = "暂不连接，直接进入主页",
                    onClick = { (cancel ?: done)() },
                    modifier = Modifier.fillMaxWidth(),
                    haptic = NativeButtonHaptic.Utility,
                )
                NText("个人开源项目，与汕头大学官方无关。服务器仅提供公开通知、公交、充电桩、公告与版本检查。", 13, muted = true)
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

private data class ActiveLocalChallenge(
    val challenge: SchoolChallenge,
    val deferred: CompletableDeferred<String?>,
)

@Composable
private fun NativeCampusBind(
    store: NativeStore,
    s: NativeState,
    onDone: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val done by rememberUpdatedState(onDone)
    val cancel by rememberUpdatedState(onCancel)
    val colors = LocalNativeColors.current
    val tones = currentNativeSemanticTones()

    var account by remember { mutableStateOf(s.schoolId) }
    var password by remember { mutableStateOf("") }
    var rememberPassword by remember { mutableStateOf(true) }
    var agree by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<LocalCampusBindResult?>(null) }
    var activeChallenge by remember { mutableStateOf<ActiveLocalChallenge?>(null) }
    var challengeInput by remember { mutableStateOf("") }
    var bindJob by remember { mutableStateOf<Job?>(null) }

    fun cancelOngoing(leave: Boolean) {
        activeChallenge?.deferred?.complete(null)
        activeChallenge = null
        challengeInput = ""
        bindJob?.cancel()
        bindJob = null
        running = false
        password = ""
        if (leave) (cancel ?: done)() else problem = "已取消这次连接，你可以重新发起。"
    }

    BackHandler(enabled = onCancel != null || running) {
        cancelOngoing(leave = !running || onCancel != null)
    }

    NativeAccountSurface {
        if (onCancel != null && !running) {
            NButton("返回", { cancelOngoing(leave = true) }, haptic = NativeButtonHaptic.Navigation)
        }
        NText("本地直连校园账号", 28, true)

        NCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NIconBadge("shield", tint = tones.leaf, sizeDp = 36, iconDp = 18)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    NText("手机直连学校统一认证，不经过开发者服务器", 15, bold = true)
                    NText("认证过程在本机完成，中央 SSO Cookie 用后清除不落盘；业务会话与课表经 Android Keystore AES-256-GCM 加密保存在本机。", 13, muted = true)
                }
            }
        }

        NField("学号 / 校园账号", account, { if (!running) account = it.take(40) }, enabled = !running)
        NField("学校统一认证密码", password, { if (!running) password = it.take(200) }, password = true, enabled = !running)

        AccountCheckbox(
            checked = agree,
            onCheckedChange = { agree = it },
            enabled = !running,
            label = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("本人授权") }
                append("：我确认使用的是")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("我自己的") }
                append("学号与密码，仅在本机")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("只读") }
                append("取教务与 MySTU 数据；不代我签到、交作业、缴费或提交任何东西。")
            },
        )
        AccountCheckbox(
            checked = rememberPassword,
            onCheckedChange = { rememberPassword = it },
            enabled = !running,
            label = AnnotatedString("在本机加密记住校园密码，会话过期时由本机直连重连（不上传服务器，随时可在「我的」中清除）"),
        )

        activeChallenge?.let { pending ->
            val ch = pending.challenge
            NCard {
                NText(
                    when (ch) {
                        is SchoolChallenge.Captcha -> ch.prompt.ifBlank { "输入学校返回的图形验证码" }
                        is SchoolChallenge.Totp -> ch.prompt.ifBlank { "输入数盾 App 里当前的 6 位动态口令（输入后即丢弃，不保存）" }
                    },
                    16,
                    true,
                )
                if (ch is SchoolChallenge.Captcha) {
                    AccountCaptcha(ch.imageDataUrl)
                }
                NField(
                    label = if (ch is SchoolChallenge.Totp) "6 位动态码" else "验证码",
                    value = challengeInput,
                    onValueChange = { raw ->
                        challengeInput = if (ch is SchoolChallenge.Totp) raw.filter(Char::isDigit).take(6) else raw.take(12)
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    NButton(
                        label = "提交验证",
                        onClick = {
                            val value = challengeInput.trim()
                            if (value.isNotEmpty()) {
                                challengeInput = ""
                                activeChallenge = null
                                pending.deferred.complete(value)
                            }
                        },
                        enabled = challengeInput.trim().isNotEmpty(),
                        primary = true,
                        modifier = Modifier.weight(1f),
                        haptic = NativeButtonHaptic.Submit,
                    )
                    NButton(
                        label = "取消",
                        onClick = { cancelOngoing(leave = false) },
                        haptic = NativeButtonHaptic.Utility,
                    )
                }
            }
        }

        if (running && activeChallenge == null) {
            NCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NIconBadge("refresh", tint = colors.brand, sizeDp = 34, iconDp = 17)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        NText("正在本机直连学校系统…", 15, bold = true)
                        NText("正在登录教务与 MySTU 并读取课表、作业、考试和成绩，通常需要几秒到十几秒。", 13, muted = true)
                    }
                }
                NLoading()
            }
        }

        NError(problem)

        val finished = result
        if (finished != null) {
            NCard {
                NText("已在本机连接：${finished.bound.joinToString("、") { serviceLabel(it) }}", 16, bold = true)
                finished.errors.forEach { (svc, err) ->
                    NError("${serviceLabel(svc)}：${accountError(err)}")
                }
                NButton("继续使用", { done() }, primary = true, modifier = Modifier.fillMaxWidth(), haptic = NativeButtonHaptic.Navigation)
                if (finished.errors.isNotEmpty()) {
                    NButton("重试失败项", { result = null; problem = null }, modifier = Modifier.fillMaxWidth(), haptic = NativeButtonHaptic.Utility)
                }
            }
        } else if (!running) {
            NButton(
                label = "本机直连并同步",
                onClick = {
                    when {
                        !agree -> problem = "请先勾选确认本人只读授权。"
                        account.trim().length < 3 || password.isEmpty() -> problem = "请填写校园账号和学校统一认证密码。"
                        else -> {
                            running = true
                            problem = null
                            result = null
                            val cleanAccount = account.trim()
                            val secret = password
                            val saveLocal = rememberPassword
                            bindJob = scope.launch {
                                try {
                                    val handler = SchoolChallengeHandler { challenge ->
                                        val deferred = CompletableDeferred<String?>()
                                        withContext(Dispatchers.Main.immediate) {
                                            challengeInput = ""
                                            activeChallenge = ActiveLocalChallenge(challenge, deferred)
                                        }
                                        deferred.await()
                                    }
                                    val res = store.bindCampusLocal(
                                        studentId = cleanAccount,
                                        password = secret,
                                        remember = saveLocal,
                                        services = listOf("jw", "mystu"),
                                        onChallenge = handler,
                                    )
                                    password = ""
                                    activeChallenge = null
                                    result = res
                                    if (res.errors.isEmpty()) done()
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    activeChallenge = null
                                    problem = accountError(error)
                                } finally {
                                    running = false
                                }
                            }
                        }
                    }
                },
                primary = true,
                modifier = Modifier.fillMaxWidth(),
                haptic = NativeButtonHaptic.Submit,
            )
        }

        if (running) {
            NButton("取消这次连接", { cancelOngoing(leave = false) }, modifier = Modifier.fillMaxWidth(), haptic = NativeButtonHaptic.Utility)
        } else {
            NButton("稍后再连", { cancelOngoing(leave = true) }, modifier = Modifier.fillMaxWidth(), haptic = NativeButtonHaptic.Navigation)
        }
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

/** A consent checkbox keeps checked semantics; it is neither a push button nor a switch. */
@Composable
private fun AccountCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean, label: AnnotatedString) {
    val tick = nHapticFeedback("toggle")
    val colors = LocalNativeColors.current
    val alpha = if (enabled) 1f else .45f
    val shape = RoundedRectangle(4.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { next -> if (next != checked) { tick(); onCheckedChange(next) } },
            )
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .background(if (checked) colors.brand.copy(alpha = alpha) else colors.surface.copy(alpha = alpha), shape)
                .border(1.dp, if (checked) colors.brand.copy(alpha = alpha) else colors.border.copy(alpha = alpha), shape),
        ) {
            if (checked) Canvas(Modifier.size(20.dp).padding(3.dp)) {
                val check = Path().apply {
                    moveTo(size.width * .05f, size.height * .5f)
                    lineTo(size.width * .37f, size.height * .8f)
                    lineTo(size.width * .95f, size.height * .13f)
                }
                drawPath(check, Color.White.copy(alpha = alpha), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        BasicText(label, style = nativeTextStyle(color = colors.ink.copy(alpha = alpha), sizeSp = 14f, lineHeightSp = 22f))
    }
}

private fun accountError(error: Throwable): String = formatLocalError(error, scope = "campus")

private fun serviceLabel(value: String): String = when (value) {
    "jw" -> "教务"
    "mystu" -> "MySTU"
    "med" -> "医学院教务"
    else -> value
}
