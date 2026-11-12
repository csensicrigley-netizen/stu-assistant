package com.stuassistant.app.nativeapp

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.stuassistant.app.NativeFeedbackSink
import com.stuassistant.app.R
import com.stuassistant.app.glass.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class NativeColors(
    val bg: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val brand: Color,
    val danger: Color,
    val border: Color,
    val onBrand: Color = Color.White,
)

val LocalNativeColors = staticCompositionLocalOf {
    NativeColors(
        Color(0xFFFFF0F1),
        Color(0xFFFFFCFC),
        Color(0xFF35212D),
        Color(0xFF76636D),
        Color(0xFF6756B9),
        Color(0xFFAB3A47),
        Color(0xFFE8DCE0),
    )
}
val LocalNativeBackdrop = staticCompositionLocalOf<Backdrop> { error("Native backdrop owner missing") }
val LocalNativeFeedback = staticCompositionLocalOf<NativeFeedbackSink?> { null }
val LocalNativeStrength = staticCompositionLocalOf { 100 }
val LocalNativeConnect = staticCompositionLocalOf<() -> Unit> { {} }
val LocalNativePalette = staticCompositionLocalOf { "sweet" }
val LocalNativeForegroundEpoch = staticCompositionLocalOf { 0 }

val NativeFontFamily = FontFamily(
    Font(R.font.stu_regular, FontWeight.Normal),
    Font(R.font.stu_semibold, FontWeight.SemiBold),
    Font(R.font.stu_bold, FontWeight.Bold),
)

/**
 * Unified CJK + Latin typography builder.
 * Disables Android's uneven legacy font padding and centers glyphs within their line box so
 * Schibsted Grotesk digits/Latin letters and system CJK characters share an exact optical center.
 */
internal fun nativeTextStyle(
    color: Color,
    sizeSp: Float,
    lineHeightSp: Float = sizeSp * 1.48f,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign = TextAlign.Start,
    decoration: TextDecoration = TextDecoration.None,
    tabularNums: Boolean = false,
): TextStyle = TextStyle(
    color = color,
    fontSize = sizeSp.sp,
    lineHeight = lineHeightSp.sp,
    fontWeight = weight,
    fontFamily = NativeFontFamily,
    textAlign = align,
    textDecoration = decoration,
    fontFeatureSettings = if (tabularNums) "tnum" else null,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

@Composable
fun NTitle(
    text: String,
    size: Int = 28,
    lineHeight: Float = size * 1.22f,
    maxLines: Int = 2,
) {
    BasicText(
        text = text,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = nativeTextStyle(
            color = LocalNativeColors.current.ink,
            sizeSp = size.toFloat(),
            lineHeightSp = lineHeight,
            weight = FontWeight.Bold,
        ),
    )
}

/** Opt-in semantic feedback; visual press interactions do not imply vibration. */
@Composable
fun nHapticFeedback(kind: String): () -> Unit {
    val engine = LocalNativeFeedback.current
    val strength by rememberUpdatedState(LocalNativeStrength.current)
    val active = remember(engine, kind) { booleanArrayOf(false) }
    DisposableEffect(active) { active[0] = true; onDispose { active[0] = false } }
    return remember(engine, kind, active) { { if (active[0] && strength > 0) engine?.play(kind, strength) } }
}

@Composable
fun nSelectionFeedback(): () -> Unit = nHapticFeedback("selection")

@Composable
fun NText(
    text: String,
    size: Int = 15,
    bold: Boolean = false,
    muted: Boolean = false,
    color: Color? = null,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    align: TextAlign = TextAlign.Start,
    tabularNums: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val c = LocalNativeColors.current
    val resolvedColor = color ?: if (muted) c.muted else c.ink
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        softWrap = softWrap,
        overflow = TextOverflow.Ellipsis,
        style = nativeTextStyle(
            color = resolvedColor,
            sizeSp = size.toFloat(),
            lineHeightSp = size * if (bold) 1.38f else 1.54f,
            weight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            align = align,
            tabularNums = tabularNums,
        ),
    )
}

@Composable
fun NTag(
    text: String,
    color: Color = LocalNativeColors.current.brand,
    filled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val bg = if (filled) color else color.copy(alpha = 0.12f)
    val fg = if (filled) Color.White else color
    Box(
        modifier = modifier
            .clip(Capsule())
            .background(bg)
            .then(
                if (!filled) Modifier.border(0.75.dp, color.copy(alpha = 0.20f), Capsule())
                else Modifier,
            )
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = nativeTextStyle(
                color = fg,
                sizeSp = 11.5f,
                lineHeightSp = 14f,
                weight = FontWeight.SemiBold,
                tabularNums = true,
            ),
        )
    }
}

@Composable
fun NIconBadge(
    icon: String,
    tint: Color = LocalNativeColors.current.brand,
    sizeDp: Int = 36,
    iconDp: Int = 18,
    filled: Boolean = false,
) {
    val shape = RoundedRectangle((sizeDp * 0.34f).dp)
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(shape)
            .background(if (filled) tint else tint.copy(alpha = 0.12f))
            .then(
                if (!filled) Modifier.border(0.75.dp, tint.copy(alpha = 0.18f), shape)
                else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        NativeIcon(
            name = icon,
            color = if (filled) LocalNativeColors.current.onBrand else tint,
            size = iconDp.dp,
            strokeWidth = 1.9f,
        )
    }
}

@Composable
fun NPage(
    title: String,
    eyebrow: String = "",
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val c = LocalNativeColors.current
    val topEdgeDepth = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 40.dp

    Box(
        Modifier
            .fillMaxSize()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .nativeTopSoftEdge(scroll, topEdgeDepth)
                .verticalScroll(scroll)
                .statusBarsPadding()
                .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 124.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (title.isNotBlank() || eyebrow.isNotBlank() || actions != null) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (eyebrow.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(Capsule())
                                    .background(c.brand.copy(alpha = 0.78f)),
                            )
                            NText(eyebrow, 13, bold = true, muted = true, maxLines = 1, softWrap = false)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.weight(1f)) {
                            NTitle(title, size = 28)
                        }
                        if (actions != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                content = actions,
                            )
                        }
                    }
                }
            }
            content()
        }
    }
}

/** Source path and colour recipes from Logo.tsx, unified across all campus palettes. */
@Composable
fun NativeLogo(size: Int = 64) {
    val spec = nativeLogoPalette(LocalNativePalette.current)
    val path = remember {
        androidx.compose.ui.graphics.vector.PathParser()
            .parsePathString("M662 264H406A124 124 0 0 0 406 512H618A124 124 0 0 1 618 760H362")
            .toPath()
    }
    val cut = remember {
        androidx.compose.ui.graphics.Path().apply {
            addRect(androidx.compose.ui.geometry.Rect(0f, 0f, 1024f, 1024f))
            addOval(androidx.compose.ui.geometry.Rect(433f, 433f, 591f, 591f))
            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
        }
    }
    val logoShape = RoundedRectangle((size * 232f / 1024f).dp)
    Canvas(
        Modifier
            .size(size.dp)
            .shadow(6.dp, logoShape, ambientColor = spec.lo.copy(alpha = 0.25f), spotColor = spec.lo.copy(alpha = 0.28f))
            .clip(logoShape)
            .clearAndSetSemantics {},
    ) {
        if (this.size.width <= 1f) return@Canvas
        val s = this.size.width / 1024f
        scale(s, s, pivot = Offset.Zero) {
            drawRect(Brush.linearGradient(listOf(spec.hi, spec.lo), end = Offset(1024f, 1024f)), size = androidx.compose.ui.geometry.Size(1024f, 1024f))
            drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .24f), Color.Transparent), center = Offset(205f, 102f), radius = 819f), size = androidx.compose.ui.geometry.Size(1024f, 1024f))
            clipPath(cut) {
                drawPath(path, spec.paper, style = Stroke(100f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            drawCircle(spec.bead, 62f, Offset(512f, 512f))
        }
    }
}

enum class NativeButtonHaptic(val press: String, val release: String) {
    Navigation("navigate_press", "navigate_release"),
    Submit("press", "release"),
    Utility("utility_press", "utility_release"),
    Destructive("destructive_press", "destructive_release"),
}

/**
 * Authentic Kyant Liquid Glass capsule button (`vibrancy() + blur(2.dp) + lens(12.dp, 24.dp)`
 * + `InteractiveHighlight` + elastic drag stretch) paired with unified optical CJK/Latin typography.
 */
@Composable
fun NButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    haptic: NativeButtonHaptic = if (primary) NativeButtonHaptic.Submit else NativeButtonHaptic.Utility,
) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val surfaceColor = if (primary) {
        c.brand.copy(alpha = if (enabled) 0.86f else 0.35f)
    } else {
        c.surface.copy(alpha = if (enabled) (if (dark) 0.38f else 0.48f) else 0.20f)
    }
    val textColor = if (primary) {
        c.onBrand.copy(alpha = if (enabled) 1f else 0.65f)
    } else {
        c.ink.copy(alpha = if (enabled) 1f else 0.45f)
    }
    NativeGlassButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        haptic = haptic,
        surfaceColor = surfaceColor,
    ) {
        BasicText(
            text = label,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = nativeTextStyle(
                color = textColor,
                sizeSp = 15f,
                lineHeightSp = 20f,
                weight = FontWeight.SemiBold,
                align = TextAlign.Center,
            ),
        )
    }
}

/** All application LiquidButtons share one two-phase gesture owner, including step buttons and slot cards. */
@Composable
internal fun NativeGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    haptic: NativeButtonHaptic,
    surfaceColor: Color,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val commitRelease = NativePressFeedback(source, enabled, haptic)
    LiquidButton(
        { commitRelease(); onClick() },
        LocalNativeBackdrop.current,
        modifier = modifier,
        enabled = enabled,
        interactionSource = source,
        surfaceColor = surfaceColor,
        content = content,
    )
}

/** Opt in only interactive cards; keep their existing size, content and surface recipe. */
@Composable
internal fun Modifier.nativeCardAction(
    onClick: () -> Unit,
    enabled: Boolean = true,
    haptic: NativeButtonHaptic = NativeButtonHaptic.Navigation,
    shape: Shape = RoundedRectangle(20.dp),
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scope = rememberCoroutineScope()
    var tapPulse by remember { mutableStateOf(false) }
    val pulseJob = remember { arrayOfNulls<Job>(1) }
    val reduced = LocalLiquidGlassReducedMotion.current
    val tint = LocalNativeColors.current.ink
    val commitRelease = NativePressFeedback(source, enabled, haptic)
    val scale by animateFloatAsState(
        if ((pressed || tapPulse) && !reduced) .982f else 1f,
        animationSpec = if (reduced) snap() else spring(dampingRatio = .76f, stiffness = 760f),
        label = "card press",
    )
    val highlight by animateFloatAsState(
        if (pressed || tapPulse) 1f else 0f,
        animationSpec = if (reduced) snap() else tween(85),
        label = "card highlight",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(shape)
        .drawWithContent {
            drawContent()
            if (highlight > 0f) drawRect(tint.copy(alpha = .05f * highlight))
        }
        .clickable(enabled = enabled, role = Role.Button, interactionSource = source, indication = null) {
            commitRelease()
            pulseJob[0]?.cancel()
            tapPulse = true
            pulseJob[0] = scope.launch { delay(80); tapPulse = false }
            onClick()
        }
}

private data class NativeAcceptedPress(
    val interaction: PressInteraction.Press,
    val profile: NativeButtonHaptic,
    val strength: Int,
)

/** A gesture locks its profile and strength; commit pairs release before a callback can dispose it. */
@Composable
internal fun NativePressFeedback(
    source: MutableInteractionSource,
    enabled: Boolean = true,
    haptic: NativeButtonHaptic = NativeButtonHaptic.Submit,
): () -> Unit {
    val engine = LocalNativeFeedback.current
    val strength by rememberUpdatedState(LocalNativeStrength.current)
    val latestEnabled by rememberUpdatedState(enabled)
    val profile by rememberUpdatedState(haptic)
    val pressed = remember(source, engine) { arrayOfNulls<NativeAcceptedPress>(1) }
    val release = remember(source, engine) {
        {
            val accepted = pressed[0]
            pressed[0] = null
            if (accepted != null && strength > 0) engine?.play(accepted.profile.release, accepted.strength)
        }
    }
    DisposableEffect(pressed) { onDispose { pressed[0] = null } }
    LaunchedEffect(source, engine) {
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> if (latestEnabled && strength > 0 && pressed[0] == null) {
                    pressed[0] = NativeAcceptedPress(interaction, profile, strength)
                    engine?.play(profile.press, strength)
                }
                is PressInteraction.Release -> if (pressed[0]?.interaction === interaction.press) release()
                is PressInteraction.Cancel -> if (pressed[0]?.interaction === interaction.press) pressed[0] = null
                else -> Unit
            }
        }
    }
    return release
}

/**
 * Translucent iOS Liquid Glass card surface for 120Hz scrolling:
 * allows warm campus ambient light orbs on `base` to softly glow through frosted glass fills,
 * framed by diagonal specular rim catchlights without per-card RenderEffect blur overhead.
 */
@Composable
fun Modifier.nativeGlassCard(
    radiusDp: Int = 22,
    tint: Color? = null,
    elevated: Boolean = true,
): Modifier {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val shape = RoundedRectangle(radiusDp.dp)
    val baseSurface = tint ?: c.surface
    val hasCustomTint = tint != null
    val fillBrush = remember(baseSurface, c.brand, hasCustomTint, dark) {
        if (hasCustomTint) {
            val topTone = lerp(baseSurface, Color.White, if (dark) 0.10f else 0.28f)
                .copy(alpha = if (dark) 0.80f else 0.85f)
            val bottomTone = baseSurface.copy(alpha = if (dark) 0.72f else 0.76f)
            Brush.verticalGradient(listOf(topTone, bottomTone))
        } else if (dark) {
            Brush.linearGradient(
                colors = listOf(
                    lerp(baseSurface, Color.White, 0.13f).copy(alpha = 0.70f),
                    baseSurface.copy(alpha = 0.58f),
                    lerp(baseSurface, c.brand, 0.06f).copy(alpha = 0.65f),
                ),
                start = Offset.Zero,
                end = Offset(620f, 540f),
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.82f),
                    baseSurface.copy(alpha = 0.64f),
                    Color.White.copy(alpha = 0.74f),
                ),
                start = Offset.Zero,
                end = Offset(620f, 540f),
            )
        }
    }
    val rimBrush = remember(c.border, c.brand, dark) {
        if (dark) {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.28f),
                    c.border.copy(alpha = 0.46f),
                    c.brand.copy(alpha = 0.22f),
                ),
                start = Offset.Zero,
                end = Offset(520f, 520f),
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.96f),
                    c.border.copy(alpha = 0.52f),
                    Color.White.copy(alpha = 0.84f),
                ),
                start = Offset.Zero,
                end = Offset(520f, 520f),
            )
        }
    }
    val shadowTint = c.brand.copy(alpha = if (dark) 0.16f else 0.055f)
    return this
        .then(
            if (elevated) {
                Modifier.shadow(
                    elevation = if (dark) 3.dp else 2.dp,
                    shape = shape,
                    ambientColor = shadowTint,
                    spotColor = shadowTint,
                )
            } else {
                Modifier
            },
        )
        .clip(shape)
        .background(fillBrush)
        .border(1.dp, rimBrush, shape)
}

@Composable
fun NCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .nativeGlassCard(22)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun NRow(
    title: String,
    subtitle: String = "",
    trailing: String = "",
    trailingIcon: String = "chevron-right",
    leadingIcon: String? = null,
    leadingTint: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduced = LocalLiquidGlassReducedMotion.current
    val pressScale by animateFloatAsState(
        if (onClick != null && pressed && !reduced) 0.985f else 1f,
        if (reduced) snap() else spring(dampingRatio = 0.74f, stiffness = 660f),
        label = "nrow-scale",
    )
    val c = LocalNativeColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, interactionSource = source, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leadingIcon != null) {
            NIconBadge(icon = leadingIcon, tint = leadingTint ?: c.brand, sizeDp = 36, iconDp = 18)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            NText(title, 15, bold = true)
            if (subtitle.isNotBlank()) NText(subtitle, 13, muted = true)
        }
        if (trailing.isNotEmpty()) {
            NText(
                trailing,
                13,
                muted = true,
                maxLines = 1,
                softWrap = false,
                tabularNums = true,
                modifier = Modifier.widthIn(max = 150.dp),
            )
        }
        if (onClick != null) NativeIcon(trailingIcon, c.muted, size = 17.dp)
    }
}

@Composable
fun NIconButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: String = "trash",
    tint: Color? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduced = LocalLiquidGlassReducedMotion.current
    val scale by animateFloatAsState(
        if (enabled && pressed && !reduced) 0.88f else 1f,
        if (reduced) snap() else spring(dampingRatio = 0.62f, stiffness = 580f),
        label = "icon-btn-scale",
    )
    val ink = (tint ?: LocalNativeColors.current.ink).copy(alpha = if (enabled) 1f else .4f)
    Box(
        Modifier
            .size(44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, role = Role.Button, interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NativeIcon(icon, ink, size = 18.dp)
    }
}

/**
 * Authentic Kyant Liquid Glass segmented control (`LiquidBottomTabs` + `LiquidBottomTab`):
 * preserves refractive `vibrancy() + blur(8.dp) + lens(24.dp, 24.dp)` container and the
 * press-expand chromatic-aberration moving glass lens (`lens(10.dp, 14.dp, chromaticAberration = true)`).
 */
@Composable
fun NChips(
    options: List<Pair<String, String>>,
    value: String,
    onSelect: (String) -> Unit,
) = NChips(options, value, onSelect, haptic = null)

@Composable
fun NChips(
    options: List<Pair<String, String>>,
    value: String,
    onSelect: (String) -> Unit,
    haptic: String?,
) {
    if (options.isEmpty()) return
    val current by rememberUpdatedState(value)
    val callback by rememberUpdatedState(onSelect)
    val tick = nHapticFeedback(haptic ?: "selection")
    val c = LocalNativeColors.current
    LiquidBottomTabs(
        selectedTabIndex = { options.indexOfFirst { it.first == current }.coerceAtLeast(0) },
        onTabSelected = { i ->
            options.getOrNull(i)?.first?.let {
                if (it != current) {
                    if (haptic != null) tick()
                    callback(it)
                }
            }
        },
        backdrop = LocalNativeBackdrop.current,
        tabsCount = options.size,
        modifier = Modifier.fillMaxWidth().semantics { isTraversalGroup = true },
    ) {
        options.forEach { (key, label) ->
            val isSelected = key == current
            LiquidBottomTab(
                onClick = {
                    if (key != current) {
                        if (haptic != null) tick()
                        callback(key)
                    }
                },
                modifier = Modifier.semantics {
                    selected = isSelected
                    contentDescription = label
                },
            ) {
                BasicText(
                    text = label,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    style = nativeTextStyle(
                        color = if (isSelected) c.brand else c.ink,
                        sizeSp = 15f,
                        lineHeightSp = 20f,
                        weight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        align = TextAlign.Center,
                    ),
                )
            }
        }
    }
}

@Composable
fun NField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    password: Boolean = false,
    multiline: Boolean = false,
    enabled: Boolean = true,
    placeholder: String = "",
    minHeight: Int = 48,
    autoFocus: Boolean = false,
) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(autoFocus) {
        if (autoFocus && enabled) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    val fieldShape = RoundedRectangle(14.dp)
    val borderColor = if (focused) c.brand.copy(alpha = 0.72f) else c.border.copy(alpha = if (dark) 0.65f else 0.80f)
    val borderWidth = if (focused) 1.5.dp else 1.dp
    val fieldBg = c.surface.copy(alpha = if (dark) 0.56f else 0.76f)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label.isNotEmpty()) NText(label, 13, bold = true, muted = true)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight.dp)
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .clip(fieldShape)
                .background(fieldBg)
                .border(borderWidth, borderColor, fieldShape)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .semantics { contentDescription = label },
            textStyle = nativeTextStyle(
                color = c.ink,
                sizeSp = 15.5f,
                lineHeightSp = 23f,
            ),
            singleLine = !multiline,
            keyboardOptions = KeyboardOptions(
                keyboardType = when {
                    password -> KeyboardType.Password
                    label.contains("邮箱") -> KeyboardType.Email
                    label.contains("服务器") -> KeyboardType.Uri
                    else -> KeyboardType.Text
                },
                autoCorrectEnabled = false,
            ),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = SolidColor(c.brand),
            decorationBox = { input ->
                Box(contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        BasicText(
                            placeholder,
                            style = nativeTextStyle(
                                color = c.muted.copy(alpha = 0.72f),
                                sizeSp = 15f,
                                lineHeightSp = 22f,
                            ),
                        )
                    }
                    input()
                }
            },
        )
    }
}

@Composable
fun NError(text: String?, retry: (() -> Unit)? = null) {
    if (text.isNullOrEmpty()) return
    val c = LocalNativeColors.current
    val shape = RoundedRectangle(14.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.danger.copy(alpha = 0.09f))
            .border(1.dp, c.danger.copy(alpha = 0.24f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        BasicText(
            text,
            style = nativeTextStyle(color = c.danger, sizeSp = 13.5f, lineHeightSp = 20f, weight = FontWeight.Medium),
        )
        if (retry != null) NButton("重试", retry, haptic = NativeButtonHaptic.Submit)
    }
}

@Composable
fun NLoading() {
    NText("正在读取…", 14, muted = true)
}

@Composable
fun NDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.75.dp)
            .background(LocalNativeColors.current.border.copy(alpha = 0.72f)),
    )
}

/**
 * Unified Liquid Glass date / week step switcher shared by Items (事项) and Timetable (课表).
 * Keeps identical 20dp glass container geometry, 48dp refractive step buttons, 17sp tabular
 * number-pop title, and an inline brand capsule pill to jump back to Today / This Week without
 * vertical layout shifts.
 */
@Composable
internal fun NativeStepSwitcherBar(
    title: String,
    order: Long,
    previousLabel: String,
    nextLabel: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    canPrevious: Boolean = true,
    canNext: Boolean = true,
    idleSubtitle: String,
    resetLabel: String? = null,
    resetPrefix: String? = null,
    onReset: (() -> Unit)? = null,
) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    Row(
        Modifier
            .fillMaxWidth()
            .nativeGlassCard(20)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NativeStepIconButton(previousLabel, "chevron-left", enabled = canPrevious, dark = dark, onClick = onPrevious)
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            CalendarCounter(
                title,
                order,
                nativeTextStyle(
                    color = c.ink,
                    sizeSp = 17f,
                    lineHeightSp = 22f,
                    weight = FontWeight.Bold,
                    tabularNums = true,
                ),
                duration = 240,
            )
            Box(Modifier.heightIn(min = 20.dp), contentAlignment = Alignment.Center) {
                if (resetLabel != null && onReset != null) {
                    val backTick = nSelectionFeedback()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (!resetPrefix.isNullOrBlank()) {
                            NText(
                                resetPrefix,
                                11,
                                muted = true,
                                maxLines = 1,
                                softWrap = false,
                                tabularNums = true,
                            )
                        }
                        Box(
                            Modifier
                                .clip(Capsule())
                                .background(c.brand.copy(alpha = 0.11f))
                                .clickable(role = Role.Button) {
                                    backTick()
                                    onReset()
                                }
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        ) {
                            BasicText(
                                resetLabel,
                                maxLines = 1,
                                softWrap = false,
                                style = nativeTextStyle(
                                    color = c.brand,
                                    sizeSp = 11.5f,
                                    lineHeightSp = 14f,
                                    weight = FontWeight.SemiBold,
                                ),
                            )
                        }
                    }
                } else {
                    NText(idleSubtitle, 11, muted = true, maxLines = 1, softWrap = false, tabularNums = true)
                }
            }
        }
        NativeStepIconButton(nextLabel, "chevron-right", enabled = canNext, dark = dark, onClick = onNext)
    }
}

@Composable
private fun NativeStepIconButton(
    label: String,
    icon: String,
    enabled: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalNativeColors.current
    NativeGlassButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).semantics { contentDescription = label },
        enabled = enabled,
        haptic = NativeButtonHaptic.Navigation,
        surfaceColor = c.surface.copy(alpha = if (enabled) (if (dark) 0.38f else 0.48f) else 0.18f),
    ) {
        NativeIcon(icon, c.ink.copy(alpha = if (enabled) 1f else 0.35f), size = 16.dp, strokeWidth = 2.1f)
    }
}

fun nativeOpenLink(context: android.content.Context, url: String) {
    runCatching {
        val uri = Uri.parse(url)
        if (uri.scheme in listOf("https", "http")) {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
fun NOpenLink(label: String, url: String) {
    val context = LocalContext.current
    NRow(label, trailingIcon = "external", onClick = { nativeOpenLink(context, url) })
}
