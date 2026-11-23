package com.stuassistant.app.nativeapp

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

internal data class NativeAnnouncement(
    val id: String, val revision: Int, val title: String, val body: String,
    val eyebrow: String, val imageUrl: String?, val imageAlt: String,
    val actionLabel: String, val actionUrl: String?, val display: String,
    val priority: Int,
    val effects: List<String> = emptyList(),
)

/** Treat remote text as plain text and allow only web destinations. */
internal object NativeAnnouncementRules {
    val effectIds = setOf("star-struck", "heart-on-fire", "folded-hands", "party-popper", "keycap-one-6")

    fun effects(row: JSONObject): List<String> {
        val array = row.optJSONArray("effects") ?: return emptyList()
        return (0 until minOf(array.length(), 5)).mapNotNull { array.opt(it) as? String }
            .filter { it in effectIds }.distinct()
    }
    fun httpsUrl(value: String): String? = try {
        val uri = URI(value)
        value.takeIf { it.length <= 2048 && it.none { char -> char <= ' ' || char == '\\' || char == '\u007f' } &&
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port in 1..65535) }
    } catch (_: Exception) { null }

    fun imageUrl(value: String, server: String): String? {
        httpsUrl(value)?.let { return it }
        if (!Regex("/v1/announcements/assets/[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(value)) return null
        return try { NativeApi.normalizeServer(server).takeIf { it.isNotBlank() }?.plus(value) } catch (_: Exception) { null }
    }

    fun parse(response: JSONObject, server: String): List<NativeAnnouncement> {
        val rows = response.optJSONArray("announcements") ?: return emptyList()
        return (0 until minOf(rows.length(), 100)).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            fun text(key: String, max: Int): String? = (row.opt(key) as? String)?.takeIf { it.length <= max }
            val id = text("id", 100)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val revision = (row.opt("revision") as? Number)?.toDouble()?.takeIf { it >= 1 && it <= Int.MAX_VALUE && it % 1 == 0.0 }?.toInt() ?: return@mapNotNull null
            val title = text("title", 160)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val body = text("body", 4000) ?: return@mapNotNull null
            val display = text("display", 10)?.takeIf { it in setOf("once", "daily", "always") } ?: return@mapNotNull null
            NativeAnnouncement(id, revision, title, body, text("eyebrow", 80) ?: "开发者来信",
                text("image_url", 2048)?.let { imageUrl(it, server) }, text("image_alt", 240) ?: title,
                text("action_label", 80) ?: "了解更多", text("action_url", 2048)?.let(::httpsUrl), display, row.optInt("priority", 0), effects(row))
        }.distinctBy { it.id }.sortedByDescending { it.priority }
    }

    fun receiptKey(server: String, account: String, item: NativeAnnouncement): String =
        MessageDigest.getInstance("SHA-256").digest("$server\n$account\n${item.id}\n${item.revision}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun eligible(display: String, lastShown: Long, now: Long, optedOut: Boolean = false): Boolean = !optedOut && when (display) {
        "always", "once" -> true // Legacy presentation receipts are not a user opt-out.
        "daily" -> lastShown <= 0 || day(lastShown) != day(now)
        else -> false
    }
    private fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()

    fun githubAppTarget(url: String): String? = httpsUrl(url)?.takeIf {
        URI(it).host.equals("github.com", ignoreCase = true)
    }?.let { "com.github.android" }
}

/** An explicit GitHub App attempt needs no package visibility or installed-app query. */
internal fun openAnnouncementLink(context: Context, url: String) {
    val safe = NativeAnnouncementRules.httpsUrl(url) ?: return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safe)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val github = NativeAnnouncementRules.githubAppTarget(safe)
    if (github != null && runCatching { context.startActivity(Intent(intent).setPackage(github)) }.isSuccess) return
    nativeOpenLink(context, safe)
}

@Composable
fun NativeHomeAnnouncement(store: NativeStore, homeReady: Boolean, modalFree: Boolean) {
    val state by store.state.collectAsState()
    val context = LocalContext.current
    // The JWT subject stays stable while /me is loading and across token refreshes.
    val account = remember(state.token, state.user?.optString("id"), state.preview) {
        runCatching { state.token?.split('.')?.getOrNull(1)?.let {
            JSONObject(String(java.util.Base64.getUrlDecoder().decode(it), Charsets.UTF_8)).optString("sub").takeIf(String::isNotBlank)
        } }.getOrNull() ?: state.user?.optString("id")?.takeIf(String::isNotBlank) ?: "preview"
    }
    val prefs = remember(context) { context.getSharedPreferences("stu.announcements.v1", Context.MODE_PRIVATE) }
    val foreground = LocalNativeForegroundEpoch.current
    var checked by remember(store, state.server, account, foreground) { mutableStateOf(false) }
    var shown by remember(store, state.server, account) { mutableStateOf<NativeAnnouncement?>(null) }
    val version = remember(context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }
    LaunchedEffect(homeReady, modalFree, state.server, account, foreground) {
        // Returning from the background checks hot configuration again, without
        // replacing an open card or consuming a second message in that opening.
        if (shown != null) { checked = true; return@LaunchedEffect }
        if (!homeReady || !modalFree || checked || state.server.isBlank()) return@LaunchedEffect
        delay(650)
        try {
            val result = withTimeout(6500) { store.request("/v1/announcements?platform=android&version_code=$version", auth = false) }
            currentCoroutineContext().ensureActive()
            val now = System.currentTimeMillis()
            val next = NativeAnnouncementRules.parse(result, state.server).firstOrNull {
                val key = NativeAnnouncementRules.receiptKey(state.server, account, it)
                NativeAnnouncementRules.eligible(it.display, prefs.getLong(key, 0), now, prefs.getBoolean("dismissed:$key", false))
            }
            checked = true
            if (next != null) {
                shown = next
            }
        } catch (error: TimeoutCancellationException) { checked = true }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { checked = true } // An optional message never interrupts the homepage.
    }
    val message = shown?.takeIf { homeReady } ?: return
    val afterExit = remember(message.id, message.revision) { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(state.server, account, message.id, message.revision) {
        // Wait for presentation: a tab change before the first frame must not consume this message.
        withFrameNanos { }
        prefs.edit().putLong(NativeAnnouncementRules.receiptKey(state.server, account, message), System.currentTimeMillis()).apply()
    }
    val dismissMessage: (Boolean) -> Unit = { dontRemind ->
        if (dontRemind) prefs.edit().putBoolean("dismissed:${NativeAnnouncementRules.receiptKey(state.server, account, message)}", true).apply()
        shown = null
    }
    NativeAnnouncementDialog(message, onDismiss = dismissMessage, onExited = {
        afterExit.value?.invoke()
        afterExit.value = null
    }, onAction = { dontRemind ->
        afterExit.value = { message.actionUrl?.let { openAnnouncementLink(context, it) } }
        dismissMessage(dontRemind)
    })
}

@Composable
internal fun NativeAnnouncementDialog(message: NativeAnnouncement, onDismiss: (Boolean) -> Unit,
    onAction: (Boolean) -> Unit, onExited: () -> Unit = {}) {
    var dontRemind by remember(message.id, message.revision) { mutableStateOf(false) }
    NDialog(message.title, onDismiss = { onDismiss(dontRemind) }, onExited = onExited) {
        NativeAnnouncementContent(message, { onDismiss(dontRemind) }, { onAction(dontRemind) },
            dontRemind, { dontRemind = !dontRemind })
    }
}

@Composable
internal fun NativeAnnouncementContent(message: NativeAnnouncement, onDismiss: () -> Unit, onAction: () -> Unit,
    dontRemind: Boolean, onToggleReminder: () -> Unit) {
    val colors = LocalNativeColors.current
    val effect = remember(message.id, message.revision, message.effects) { message.effects.randomOrNull() }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.weight(1f).padding(end = 8.dp)) { NText(message.eyebrow, 13, true, muted = true) }
        NButton("关闭", onDismiss, haptic = NativeButtonHaptic.Utility)
    }
    val bitmap by produceState<ImageBitmap?>(null, message.imageUrl) {
        val url = message.imageUrl ?: return@produceState
        value = loadAnnouncementImage(url)
    }
    Box(Modifier.fillMaxWidth().height(if (bitmap != null || message.imageUrl != null) 180.dp else 124.dp), contentAlignment = Alignment.Center) {
    if (bitmap != null) {
        Image(bitmap!!, message.imageAlt, Modifier.matchParentSize().clip(RoundedRectangle(16.dp)), contentScale = ContentScale.Fit)
    } else {
        Box(Modifier.matchParentSize().clip(RoundedRectangle(16.dp))
            .background(Brush.radialGradient(listOf(colors.brand.copy(alpha = .17f), colors.brand.copy(alpha = .025f)))), contentAlignment = Alignment.Center) {
            NativeLogo(64)
            Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
                fun star(x: Float, y: Float, radius: Float) {
                    val path = Path()
                    repeat(10) { i ->
                        val angle = -Math.PI / 2 + i * Math.PI / 5
                        val r = if (i % 2 == 0) radius else radius * .46f
                        val point = Offset(x + kotlin.math.cos(angle).toFloat() * r, y + kotlin.math.sin(angle).toFloat() * r)
                        if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                    }
                    path.close()
                    drawPath(path, colors.brand.copy(alpha = .65f), style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
                }
                star(size.width * .2f, size.height * .33f, 12.dp.toPx())
                star(size.width * .82f, size.height * .68f, 9.dp.toPx())
            }
        }
    }
    NativeAnnouncementEffect(effect, Modifier.requiredSize(280.dp))
    }
    Box(Modifier.semantics { heading() }) { NTitle(message.title, 27) }
    if (message.body.isNotBlank()) NText(message.body, 15, muted = true)
    if (message.actionUrl != null) {
        NativeAnnouncementButton(message.actionLabel + " ↗", onAction, primary = true, haptic = NativeButtonHaptic.Navigation)
        NativeAnnouncementButton(if (dontRemind) "关闭" else "下次再说", onDismiss, primary = false, haptic = NativeButtonHaptic.Utility)
    } else NativeAnnouncementButton(if (dontRemind) "关闭" else "知道了", onDismiss, primary = true, haptic = NativeButtonHaptic.Utility)
    NativeAnnouncementReminder(dontRemind, onToggleReminder)
}

/** The whole row is the accessible target; the small glass box is decorative. */
@Composable
private fun NativeAnnouncementReminder(checked: Boolean, onToggle: () -> Unit) {
    val colors = LocalNativeColors.current
    val reduced = LocalLiquidGlassReducedMotion.current
    val tick = nHapticFeedback("toggle")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val selection by animateFloatAsState(if (checked) 1f else 0f,
        tween(if (reduced) 0 else 220, easing = CubicBezierEasing(.2f, 0f, 0f, 1f)), label = "reminder-selection")
    val pressure by animateFloatAsState(if (pressed) 1f else 0f,
        if (reduced) snap() else spring(dampingRatio = .82f, stiffness = 620f), label = "reminder-pressure")
    val changing = kotlin.math.abs(selection - if (checked) 1f else 0f) > .001f ||
        kotlin.math.abs(pressure - if (pressed) 1f else 0f) > .001f
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .preferredFrameRate(if (changing) FrameRateCategory.High else FrameRateCategory.Default)
        .graphicsLayer { scaleX = if (reduced) 1f else 1f - .015f * pressure; scaleY = scaleX }
        .clip(RoundedRectangle(12.dp))
        .background(colors.brand.copy(alpha = .045f * pressure.coerceIn(0f, 1f)))
        .then(if (focused) Modifier.border(1.5.dp, colors.brand, RoundedRectangle(12.dp)) else Modifier)
        .toggleable(checked, interactionSource = interaction, indication = null, role = Role.Checkbox,
            // Toggle the owner's live state; the drawn value can lag a frame
            // behind rapid input while the retained dialog content recomposes.
            onValueChange = { tick(); onToggle() })
        .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        val shape = RoundedRectangle(6.dp)
        Box(Modifier.size(22.dp).graphicsLayer {
            scaleX = if (reduced) 1f else 1f - .085f * pressure; scaleY = scaleX
            this.shape = shape; shadowElevation = 2.dp.toPx() * selection
        }.clip(shape)
            .background(lerp(colors.surface.copy(alpha = .9f), colors.brand, selection))
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .18f), Color.Transparent)))
            .border(1.dp, lerp(colors.muted.copy(alpha = .8f), colors.brand, selection), shape)) {
            Canvas(Modifier.fillMaxSize().padding(4.dp).clearAndSetSemantics {}) {
                val check = Path().apply {
                    moveTo(size.width * .06f, size.height * .5f)
                    lineTo(size.width * .38f, size.height * .8f)
                    lineTo(size.width * .94f, size.height * .16f)
                }
                val measure = PathMeasure().apply { setPath(check, false) }
                val revealed = Path()
                measure.getSegment(0f, measure.length * selection, revealed, true)
                drawPath(revealed, colors.onBrand.copy(alpha = selection),
                    style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        NText("不再提醒这条消息", 13, muted = true)
    }
}

@Composable
private fun NativeAnnouncementButton(label: String, onClick: () -> Unit, primary: Boolean, haptic: NativeButtonHaptic) {
    val colors = LocalNativeColors.current
    val reduced = LocalLiquidGlassReducedMotion.current
    NativeGlassButton(onClick, modifier = Modifier.fillMaxWidth(), haptic = haptic,
        surfaceColor = if(primary) colors.brand else colors.surface.copy(alpha = .94f), shape = RoundedRectangle(16.dp)) {
        AnimatedContent(label, transitionSpec = {
            fadeIn(tween(if (reduced) 0 else 160)) togetherWith fadeOut(tween(if (reduced) 0 else 100))
        }, label = "announcement-button-label") { text ->
            androidx.compose.foundation.text.BasicText(text,
                style = androidx.compose.ui.text.TextStyle(color = if(primary) colors.onBrand else colors.ink, fontSize = 16.sp, lineHeight = 22.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontFamily = NativeFontFamily))
        }
    }
}

/** No auth headers, redirects, disk cache or unbounded image decoding. */
private suspend fun loadAnnouncementImage(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 3500
        connection.readTimeout = 3500
        connection.useCaches = false
        connection.setRequestProperty("Accept", "image/*")
        if (connection.responseCode != 200 || connection.contentLengthLong > 5 * 1024 * 1024) return@withContext null
        val bytes = connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (true) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() > deadline) return@withContext null
                val count = input.read(buffer)
                if (count == -1) break
                if (output.size() + count > 5 * 1024 * 1024) return@withContext null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 40_000_000) return@withContext null
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2
        currentCoroutineContext().ensureActive()
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { null }
    finally { connection?.disconnect() }
}
