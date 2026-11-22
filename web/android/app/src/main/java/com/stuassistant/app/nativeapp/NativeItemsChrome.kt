package com.stuassistant.app.nativeapp

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp as lerpFloat
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.stuassistant.app.glass.DampedDragAnimation
import com.stuassistant.app.glass.InteractiveHighlight
import com.stuassistant.app.glass.LiquidDialogSurface
import com.stuassistant.app.glass.LocalLiquidGlassDarkTheme
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion
import kotlinx.coroutines.delay
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Lets full-height Items overlays (edit / quick-add sheets, academic push) retract the app's global bottom navigation. */
class NativeBottomBarSuppressor {
    private val owners = mutableStateMapOf<Any, Unit>()
    val suppressed: Boolean get() = owners.isNotEmpty()

    internal fun set(owner: Any, active: Boolean) {
        if (active) owners[owner] = Unit else owners.remove(owner)
    }
}

val LocalNativeBottomBarSuppressor = staticCompositionLocalOf<NativeBottomBarSuppressor?> { null }

@Composable
internal fun NativeSuppressBottomBar(active: Boolean) {
    val controller = LocalNativeBottomBarSuppressor.current ?: return
    val owner = remember { Any() }
    DisposableEffect(controller, owner, active) {
        controller.set(owner, active)
        onDispose { controller.set(owner, false) }
    }
}

/** Compact refractive Kyant Liquid Glass circular icon button (no hardcoded 48dp internal padding clipping). */
@Composable
internal fun ItemsLiquidIconButton(
    backdrop: Backdrop,
    contentDescriptionText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    surfaceColor: Color? = null,
    tint: Color = Color.Unspecified,
    haptic: NativeButtonHaptic = NativeButtonHaptic.Utility,
    content: @Composable () -> Unit,
) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val reduced = LocalLiquidGlassReducedMotion.current
    val scope = rememberCoroutineScope()
    val source = remember { MutableInteractionSource() }
    val release = NativePressFeedback(source, true, haptic)
    val highlight = remember(scope, reduced) { InteractiveHighlight(scope, reducedMotion = reduced) }
    val fill = surfaceColor ?: c.surface.copy(alpha = if (dark) 0.44f else 0.50f)

    Box(
        modifier
            .size(size)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(6f.dp.toPx())
                    lens(10f.dp.toPx(), 18f.dp.toPx())
                },
                layerBlock = if (!reduced) {
                    {
                        val p = highlight.pressProgress
                        val s = 1f + p * 0.08f
                        scaleX = s
                        scaleY = s
                    }
                } else null,
                onDrawSurface = {
                    if (tint.isSpecified) {
                        drawRect(tint, blendMode = BlendMode.Hue)
                        drawRect(tint.copy(alpha = 0.78f))
                    }
                    drawRect(fill)
                },
            )
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                release()
                onClick()
            }
            .then(highlight.modifier)
            .then(highlight.gestureModifier)
            .semantics { contentDescription = contentDescriptionText },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Compact refractive Kyant Liquid Glass capsule pill button (`学业`, `‹ 事项`, etc.). */
@Composable
internal fun ItemsLiquidPillButton(
    backdrop: Backdrop,
    contentDescriptionText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 38.dp,
    horizontalPadding: Dp = 13.dp,
    surfaceColor: Color? = null,
    haptic: NativeButtonHaptic = NativeButtonHaptic.Navigation,
    content: @Composable RowScope.() -> Unit,
) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val reduced = LocalLiquidGlassReducedMotion.current
    val scope = rememberCoroutineScope()
    val source = remember { MutableInteractionSource() }
    val release = NativePressFeedback(source, true, haptic)
    val highlight = remember(scope, reduced) { InteractiveHighlight(scope, reducedMotion = reduced) }
    val fill = surfaceColor ?: c.surface.copy(alpha = if (dark) 0.44f else 0.52f)

    Row(
        modifier
            .height(height)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(6f.dp.toPx())
                    lens(10f.dp.toPx(), 18f.dp.toPx())
                },
                layerBlock = if (!reduced) {
                    {
                        val p = highlight.pressProgress
                        val s = 1f + p * 0.05f
                        scaleX = s
                        scaleY = s
                    }
                } else null,
                onDrawSurface = { drawRect(fill) },
            )
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                release()
                onClick()
            }
            .then(highlight.modifier)
            .then(highlight.gestureModifier)
            .padding(horizontal = horizontalPadding)
            .semantics { contentDescription = contentDescriptionText },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        content = content,
    )
}

/** Compact 3-segment Kyant Liquid Glass scope control (`日程 / 待安排 N / 作业 N`) matching V4.8 proportions. */
@Composable
internal fun ItemsLiquidScopeBar(
    backdrop: Backdrop,
    currentScope: String,
    inboxCount: Int,
    schoolCount: Int,
    onSelectScope: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val reduced = LocalLiquidGlassReducedMotion.current
    val animScope = rememberCoroutineScope()
    val tick = nSelectionFeedback()
    val keys = remember { listOf("schedule", "inbox", "school") }
    val selectedIndex = keys.indexOf(currentScope).coerceAtLeast(0)
    val latestOnSelect by rememberUpdatedState(onSelectScope)
    val latestIndex by rememberUpdatedState(selectedIndex)
    val containerShape = remember { RoundedRectangle(15.dp) }
    val thumbShape = remember { RoundedRectangle(12.dp) }

    val containerBg = if (dark) Color(0xFF141218).copy(alpha = 0.50f) else Color(0xFFFAFAFA).copy(alpha = 0.56f)
    val thumbFill = if (dark) lerp(c.surface, Color.White, 0.12f).copy(alpha = 0.72f) else c.surface.copy(alpha = 0.78f)

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val padPx = with(density) { 3.dp.toPx() }
        val segWidthPx = ((constraints.maxWidth.toFloat() - padPx * 2f) / 3f).coerceAtLeast(1f)
        var currentIdx by remember { mutableIntStateOf(selectedIndex) }
        val damped = remember(animScope, reduced, segWidthPx) {
            DampedDragAnimation(
                animationScope = animScope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..2f,
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = if (reduced) 1f else 1.06f,
                reducedMotion = reduced,
                onDragStarted = {},
                onDragStopped = {
                    val idx = targetValue.roundToInt().coerceIn(0, 2)
                    currentIdx = idx
                    animateToValue(idx.toFloat())
                    val key = keys[idx]
                    if (key != keys[latestIndex]) {
                        tick()
                        latestOnSelect(key)
                    }
                },
                onDragCancelled = {
                    animateToValue(currentIdx.toFloat())
                },
                onDrag = { _, dragAmount ->
                    updateValue((targetValue + dragAmount.x / segWidthPx).fastCoerceIn(0f, 2f))
                },
            )
        }
        LaunchedEffect(selectedIndex) {
            currentIdx = selectedIndex
            damped.animateToValue(selectedIndex.toFloat())
        }

        val highlight = remember(animScope, reduced, segWidthPx) {
            InteractiveHighlight(
                animationScope = animScope,
                reducedMotion = reduced,
                position = { size, _ -> Offset((damped.value + 0.5f) * segWidthPx, size.height / 2f) },
            )
        }

        val labels = listOf(
            Triple("schedule", "日程", 0),
            Triple("inbox", "待安排", inboxCount),
            Triple("school", "作业", schoolCount),
        )

        Box(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .then(highlight.gestureModifier)
                .then(damped.modifier),
        ) {
            // Base frosted scope rail
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { containerShape },
                        effects = {
                            vibrancy()
                            blur(8f.dp.toPx())
                            lens(14f.dp.toPx(), 18f.dp.toPx())
                        },
                        onDrawSurface = { drawRect(containerBg) },
                    )
                    .border(0.75.dp, c.border.copy(alpha = if (dark) 0.65f else 0.78f), containerShape),
            )

            // Sliding refractive Liquid Glass lens thumb beneath the single crisp label pass
            Box(
                Modifier
                    .padding(3.dp)
                    .fillMaxHeight()
                    .fillMaxWidth(1f / 3f)
                    .graphicsLayer { translationX = damped.value * segWidthPx }
                    .shadow(
                        elevation = 3.dp,
                        shape = thumbShape,
                        ambientColor = Color.Black.copy(alpha = if (dark) 0.42f else 0.12f),
                        spotColor = Color.Black.copy(alpha = if (dark) 0.42f else 0.12f),
                    )
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { thumbShape },
                        effects = {
                            val p = damped.pressProgress
                            lens(8f.dp.toPx() * (0.25f + p * 0.75f), 12f.dp.toPx() * (0.25f + p * 0.75f), chromaticAberration = true)
                        },
                        highlight = { Highlight.Default.copy(alpha = 0.45f + 0.55f * damped.pressProgress) },
                        shadow = { Shadow(alpha = damped.pressProgress * 0.5f) },
                        innerShadow = { InnerShadow(radius = 6.dp * damped.pressProgress, alpha = damped.pressProgress) },
                        layerBlock = {
                            scaleX = damped.scaleX
                            scaleY = damped.scaleY
                        },
                        onDrawSurface = { drawRect(thumbFill) },
                    )
                    .border(0.6.dp, c.border.copy(alpha = if (dark) 0.35f else 0.65f), thumbShape),
            )

            // Single crisp accessible segment label + click pass
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                labels.forEach { (key, label, count) ->
                    val on = key == currentScope
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(thumbShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Tab,
                            ) {
                                if (key != currentScope) {
                                    tick()
                                    onSelectScope(key)
                                }
                            }
                            .testTag("items-scope-$key")
                            .semantics {
                                role = Role.Tab
                                selected = on
                                contentDescription = if (count > 0) "$label $count" else label
                            },
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            label,
                            maxLines = 1,
                            softWrap = false,
                            style = nativeTextStyle(
                                color = if (on) c.ink else c.muted,
                                sizeSp = 13f,
                                lineHeightSp = 16f,
                                weight = FontWeight.Bold,
                            ),
                        )
                        if (count > 0) {
                            BasicText(
                                count.toString(),
                                maxLines = 1,
                                softWrap = false,
                                style = nativeTextStyle(
                                    color = if (on) c.brand else c.muted.copy(alpha = 0.78f),
                                    sizeSp = 11.5f,
                                    lineHeightSp = 14f,
                                    weight = FontWeight.Bold,
                                    tabularNums = true,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Semantic day dot urgency for week strip & month drawer. */
internal enum class ItemsDayUrgency { NONE, NORMAL, URGENT }

/** Floating Liquid Glass Scale Picker Popover (`1日 / 3日 / 5日 / 周`). */
@Composable
internal fun ItemsScalePopover(
    open: Boolean,
    backdrop: Backdrop,
    selectedCols: Int,
    onSelectCols: (Int) -> Unit,
    onDismiss: () -> Unit,
    topPaddingDp: Dp,
) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val reduced = LocalLiquidGlassReducedMotion.current
    val tick = nSelectionFeedback()
    BackHandler(enabled = open) { onDismiss() }

    AnimatedVisibility(
        visible = open,
        enter = if (reduced) EnterTransition.None else fadeIn(tween(150)),
        exit = if (reduced) ExitTransition.None else fadeOut(tween(120)),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )
            LiquidDialogSurface(
                backdrop = backdrop,
                shape = RoundedRectangle(18.dp),
                modifier = Modifier
                    .padding(top = topPaddingDp, start = 10.dp)
                    .animateEnterExit(
                        enter = if (reduced) EnterTransition.None else scaleIn(spring(0.76f, 520f), initialScale = 0.88f, transformOrigin = TransformOrigin(0.1f, 0f)),
                        exit = if (reduced) ExitTransition.None else scaleOut(tween(120), targetScale = 0.9f, transformOrigin = TransformOrigin(0.1f, 0f)),
                    )
                    .width(168.dp)
                    .shadow(14.dp, RoundedRectangle(18.dp), ambientColor = Color.Black.copy(alpha = 0.2f)),
            ) {
                Column(Modifier.padding(5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(
                        Triple(1, "单日视图", "详细列宽"),
                        Triple(3, "三日视图", "均衡排期"),
                        Triple(5, "教学周", "周一至周五"),
                        Triple(7, "整周视图", "周一至周日"),
                    ).forEach { (cols, name, sub) ->
                        val active = cols == selectedCols
                        val rowShape = RoundedRectangle(12.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .clip(rowShape)
                                .background(if (active) c.brand.copy(alpha = if (dark) 0.22f else 0.14f) else Color.Transparent)
                                .clickable(role = Role.RadioButton) {
                                    tick()
                                    onSelectCols(cols)
                                }
                                .semantics { selected = active }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            BasicText(
                                name,
                                maxLines = 1,
                                softWrap = false,
                                style = nativeTextStyle(
                                    color = if (active) c.brand else c.ink,
                                    sizeSp = 12.5f,
                                    lineHeightSp = 16f,
                                    weight = FontWeight.Bold,
                                ),
                            )
                            BasicText(
                                sub,
                                maxLines = 1,
                                softWrap = false,
                                style = nativeTextStyle(
                                    color = if (active) c.brand.copy(alpha = 0.85f) else c.muted.copy(alpha = 0.72f),
                                    sizeSp = 10.5f,
                                    lineHeightSp = 14f,
                                    weight = FontWeight.SemiBold,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Collapsible full-month teaching-week calendar drawer anchored under titlebar. */
@Composable
internal fun ItemsMonthDrawer(
    open: Boolean,
    backdrop: Backdrop,
    dateWindow: ItemsDateWindow,
    selectedDate: String,
    centerWeekIndex: Int,
    dayHasCourse: (String) -> Boolean,
    dayUrgency: (String) -> ItemsDayUrgency,
    onPickDay: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val reduced = LocalLiquidGlassReducedMotion.current
    val tick = nSelectionFeedback()
    BackHandler(enabled = open) { onDismiss() }

    AnimatedVisibility(
        visible = open,
        enter = if (reduced) EnterTransition.None else expandVertically(spring(0.86f, 480f)) + fadeIn(tween(180)),
        exit = if (reduced) ExitTransition.None else shrinkVertically(tween(180)) + fadeOut(tween(140)),
    ) {
        val startWeek = (centerWeekIndex - 2).coerceIn(0, (dateWindow.totalWeeks - 6).coerceAtLeast(0))
        val endWeek = (startWeek + 5).coerceAtMost(dateWindow.totalWeeks - 1)
        val centerWed = dateWindow.weekDates(centerWeekIndex)[3]
        val centerMonth = runCatching { LocalDate.parse(centerWed).monthValue }.getOrNull()
        val centerYear = runCatching { LocalDate.parse(centerWed).year }.getOrNull() ?: 2026
        val pos = runCatching { AcademicCalendar.position(centerWed) }.getOrNull()

        LiquidDialogSurface(
            backdrop = backdrop,
            shape = RoundedRectangle(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)
                .shadow(14.dp, RoundedRectangle(20.dp), ambientColor = Color.Black.copy(alpha = 0.18f))
                .pointerInput(Unit) {
                    var dragY = 0f
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        dragY = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            dragY += ch.positionChange().y
                            if (dragY < -28.dp.toPx()) {
                                ch.consume()
                                onDismiss()
                                break
                            }
                        }
                    }
                },
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        "${centerYear}年 ${centerMonth ?: ""}月" + (pos?.let { " · 第${it.week}周" } ?: ""),
                        maxLines = 1,
                        softWrap = false,
                        style = nativeTextStyle(c.ink, 12.5f, 16f, FontWeight.Bold, tabularNums = true),
                    )
                    Box(
                        Modifier
                            .clip(Capsule())
                            .clickable(role = Role.Button) { tick(); onPickDay(dateWindow.today) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        BasicText(
                            "回今天",
                            maxLines = 1,
                            softWrap = false,
                            style = nativeTextStyle(c.brand, 12.5f, 16f, FontWeight.Bold),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                        BasicText("周", style = nativeTextStyle(c.muted, 10.5f, 14f, FontWeight.Bold))
                    }
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach { w ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            BasicText(w, style = nativeTextStyle(c.muted, 10.5f, 14f, FontWeight.Bold))
                        }
                    }
                }
                for (wkIdx in startWeek..endWeek) {
                    val wDays = dateWindow.weekDates(wkIdx)
                    val activeWk = wkIdx == centerWeekIndex
                    val wkPos = runCatching { AcademicCalendar.position(wDays[3]) }.getOrNull()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .width(28.dp)
                                .height(32.dp)
                                .clip(RoundedRectangle(8.dp))
                                .clickable { tick(); onPickDay(wDays.first()) },
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                wkPos?.let { "${it.week}周" } ?: "${wkIdx + 1}",
                                maxLines = 1,
                                softWrap = false,
                                style = nativeTextStyle(
                                    color = if (activeWk) c.brand else c.muted.copy(alpha = 0.72f),
                                    sizeSp = 9.5f,
                                    lineHeightSp = 12f,
                                    weight = FontWeight.Bold,
                                    tabularNums = true,
                                ),
                            )
                        }
                        wDays.forEach { d ->
                            val parsed = runCatching { LocalDate.parse(d) }.getOrNull()
                            val dom = parsed?.dayOfMonth ?: 1
                            val inCurMonth = centerMonth == null || parsed?.monthValue == centerMonth
                            val isSel = d == selectedDate
                            val isToday = d == dateWindow.today
                            val urg = dayUrgency(d)
                            val hasC = dayHasCourse(d)
                            val cellBg = when {
                                isSel -> c.brand
                                activeWk -> c.brand.copy(alpha = if (dark) 0.14f else 0.085f)
                                else -> Color.Transparent
                            }
                            Column(
                                Modifier
                                    .weight(1f)
                                    .height(33.dp)
                                    .padding(1.dp)
                                    .clip(RoundedRectangle(10.dp))
                                    .background(cellBg)
                                    .then(
                                        if (isToday && !isSel) {
                                            Modifier.border(1.dp, c.brand.copy(alpha = 0.45f), RoundedRectangle(10.dp))
                                        } else Modifier,
                                    )
                                    .alpha(if (inCurMonth || isSel) 1f else 0.40f)
                                    .clickable(role = Role.Button) {
                                        tick()
                                        onPickDay(d)
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                BasicText(
                                    dom.toString(),
                                    maxLines = 1,
                                    softWrap = false,
                                    style = nativeTextStyle(
                                        color = if (isSel) c.onBrand else if (isToday) c.brand else c.ink,
                                        sizeSp = 12.5f,
                                        lineHeightSp = 14f,
                                        weight = FontWeight.Bold,
                                        tabularNums = true,
                                    ),
                                )
                                Row(
                                    Modifier.height(4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (urg == ItemsDayUrgency.URGENT) {
                                        Box(Modifier.size(3.5.dp).clip(CircleShape).background(if (isSel) c.onBrand else c.danger))
                                    } else if (urg == ItemsDayUrgency.NORMAL) {
                                        Box(Modifier.size(3.5.dp).clip(CircleShape).background(if (isSel) c.onBrand else c.brand))
                                    } else if (hasC) {
                                        Box(Modifier.size(3.dp).clip(CircleShape).background(if (isSel) c.onBrand.copy(0.75f) else c.muted.copy(0.45f)))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Shared date strip with a single mode transition and a separate settled-date fill. */
@Composable
internal fun ItemsWeekStrip(
    dateWindow: ItemsDateWindow,
    displayWeek: Int,
    isGrid: Boolean,
    gridBlend: Float,
    dateMoving: Boolean,
    focusedDate: String,
    listOffset: Float,
    calOffset: Float,
    calColsF: Float,
    calCols: Int,
    scaleMenuOpen: Boolean,
    dayHasCourse: (String) -> Boolean,
    dayUrgency: (String) -> ItemsDayUrgency,
    onSelectDate: (String) -> Unit,
    onToggleScaleMenu: () -> Unit,
    onBeginCalScrub: () -> Float,
    onScrubCalOffset: (Float) -> Unit,
    onReleaseCalScrub: (velColsPerSec: Float) -> Unit,
    onBeginListScrub: () -> Float,
    onScrubListOffset: (Float) -> Unit,
    onReleaseListScrub: (velPagesPerSec: Float, startIdx: Int) -> Unit,
) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val reduced = LocalLiquidGlassReducedMotion.current
    val tick = nSelectionFeedback()

    val weekDays = remember(dateWindow, displayWeek) { dateWindow.weekDates(displayWeek) }
    val visualOffset = lerpFloat(listOffset, calOffset, gridBlend)
    val visualWidth = lerpFloat(1f, calColsF, gridBlend)
    val relativeOffset = visualOffset - displayWeek * 7f
    val rangeStart = relativeOffset.coerceIn(0f, 7f)
    val rangeEnd = (relativeOffset + visualWidth).coerceIn(rangeStart, 7f)
    val motionBlend by animateFloatAsState(
        targetValue = if (dateMoving) 1f else 0f,
        animationSpec = if (reduced) snap() else tween(130),
        label = "items-date-motion",
    )
    // The lens can pass any number of whole days during one gesture. Its fill only
    // becomes solid after the gesture and settling animation have both ended.
    val solidFill = (1f - motionBlend) * (if (calCols == 1) 1f else 1f - gridBlend)

    val latestCalColsF by rememberUpdatedState(calColsF)
    val latestCalCols by rememberUpdatedState(calCols)
    val beginCalScrub by rememberUpdatedState(onBeginCalScrub)
    val scrubCalOffset by rememberUpdatedState(onScrubCalOffset)
    val releaseCalScrub by rememberUpdatedState(onReleaseCalScrub)
    val beginListScrub by rememberUpdatedState(onBeginListScrub)
    val scrubListOffset by rememberUpdatedState(onScrubListOffset)
    val releaseListScrub by rememberUpdatedState(onReleaseListScrub)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height(48.dp)
            .padding(horizontal = lerpFloat(16f, 10f, gridBlend).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (gridBlend > 0f || isGrid) {
            val chevAngle by animateFloatAsState(
                if (scaleMenuOpen) 180f else 0f,
                if (reduced) snap() else spring(0.65f, 540f),
                label = "scale-chev",
            )
            Box(
                Modifier.width((38f * gridBlend).dp).height(48.dp).clipToBounds(),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column(
                    Modifier
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .width(34.dp)
                        .height(46.dp)
                        .graphicsLayer { alpha = gridBlend }
                        .clip(RoundedRectangle(14.dp))
                        .background(c.brand.copy(alpha = if (dark) 0.20f else 0.13f))
                        .border(0.75.dp, c.brand.copy(alpha = 0.26f), RoundedRectangle(14.dp))
                        .clickable(enabled = isGrid, role = Role.Button) {
                            tick()
                            onToggleScaleMenu()
                        }
                        .then(
                            if (isGrid) Modifier.testTag("items-scale-btn").semantics {
                                contentDescription = "切换日历视图刻度，当前${ItemsCalendarMath.scaleShortName(calCols)}"
                            } else Modifier.clearAndSetSemantics {},
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                ) {
                    BasicText(
                        ItemsCalendarMath.scaleShortName(calCols),
                        maxLines = 1,
                        softWrap = false,
                        style = nativeTextStyle(c.brand, 11.5f, 12f, FontWeight.Bold),
                    )
                    NativeIcon(
                        "chevron-down",
                        c.brand,
                        size = 10.dp,
                        strokeWidth = 2.4f,
                        modifier = Modifier.graphicsLayer { rotationZ = chevAngle },
                    )
                }
            }
        }

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .height(48.dp)
                .pointerInput(dateWindow.dates.first(), isGrid) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val velocity = ItemsDatePanVelocity(down)
                        val slotWidth = (size.width.toFloat() / 7f).coerceAtLeast(1f)
                        val sign = if (isGrid && latestCalCols == 7) -1f else 1f
                        var startX = down.position.x
                        var startOff = 0f
                        var totalDx = 0f
                        var totalDy = 0f
                        var scrubbing = false

                        fun scrubTo(pointerX: Float) {
                            val offset = startOff + sign * (pointerX - startX) / slotWidth
                            val cols = if (isGrid) latestCalColsF else 1f
                            val maxOff = (dateWindow.totalDays - cols).coerceAtLeast(0f)
                            val bounded = ItemsCalendarMath.rubberBandClamp(offset, 0f, maxOff)
                            if (isGrid) scrubCalOffset(bounded) else scrubListOffset(bounded)
                        }

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            velocity.add(change)
                            if (!change.pressed) {
                                if (scrubbing) scrubTo(change.position.x)
                                break
                            }

                            val delta = change.positionChange()
                            totalDx += delta.x
                            totalDy += delta.y
                            if (!scrubbing) {
                                if (abs(totalDy) > slop && abs(totalDy) >= abs(totalDx)) break
                                if (abs(totalDx) <= slop || abs(totalDx) <= abs(totalDy) * 1.1f) continue
                                scrubbing = true
                                startX += if (totalDx > 0f) slop else -slop
                                startOff = if (isGrid) beginCalScrub() else beginListScrub()
                            }

                            change.consume()
                            scrubTo(change.position.x)
                        }

                        if (scrubbing) {
                            val slotsPerSecond = sign * velocity.velocity() / slotWidth
                            if (isGrid) releaseCalScrub(slotsPerSecond)
                            else releaseListScrub(slotsPerSecond * 0.4f, startOff.roundToInt())
                        }
                    }
                },
        ) {
            val density = LocalDensity.current
            val totalW = constraints.maxWidth.toFloat()
            val gapPx = with(density) { 2.dp.toPx() }
            val slotStride = (totalW + gapPx) / 7f
            val minSpan = 0.28f
            val indStart = rangeStart.coerceAtMost(7f - minSpan)
            val indEnd = rangeEnd.coerceAtLeast(indStart + minSpan)
            val indLeft = indStart * slotStride
            val indWidth = ((indEnd - indStart) * slotStride - gapPx).coerceAtLeast(0f)
            val indShape = remember { RoundedRectangle(14.dp) }
            val indBg = lerp(c.brand.copy(alpha = if (dark) 0.20f else 0.14f), c.brand, solidFill)
            val indBorder = c.brand.copy(alpha = 0.46f * (1f - solidFill))

            Box(
                Modifier
                    .offset { IntOffset(indLeft.roundToInt(), 0) }
                    .width(with(density) { indWidth.toDp() })
                    .height(48.dp)
                    .shadow((6f * solidFill).dp, indShape, ambientColor = c.brand.copy(0.45f), spotColor = c.brand.copy(0.45f))
                    .clip(indShape)
                    .background(indBg)
                    .border(1.25.dp, indBorder, indShape),
            )

            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                weekDays.forEachIndexed { dow, d ->
                    val dom = runCatching { LocalDate.parse(d).dayOfMonth }.getOrDefault(1)
                    val isSelected = d == focusedDate
                    val coverage = (minOf(dow + 1f, rangeEnd) - maxOf(dow.toFloat(), rangeStart)).coerceIn(0f, 1f)
                    val onBrand = coverage * solidFill
                    val labelColor = lerp(lerp(c.muted, c.brand, coverage), c.onBrand, onBrand)
                    val numColor = lerp(lerp(if (d == dateWindow.today) c.brand else c.ink, c.brand, coverage), c.onBrand, onBrand)
                    val hasCourse = dayHasCourse(d)
                    val urg = dayUrgency(d)

                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(indShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                            ) {
                                tick()
                                onSelectDate(d)
                            }
                            .semantics {
                                selected = isSelected
                                contentDescription = "${AcademicClock.monthDay(d)} ${AcademicClock.weekdayText(dow + 1)}"
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        BasicText(
                            listOf("一", "二", "三", "四", "五", "六", "日")[dow],
                            maxLines = 1,
                            softWrap = false,
                            style = nativeTextStyle(labelColor, 11f, 13f, FontWeight.Medium),
                        )
                        BasicText(
                            dom.toString(),
                            maxLines = 1,
                            softWrap = false,
                            style = nativeTextStyle(
                                color = numColor,
                                sizeSp = 14.5f,
                                lineHeightSp = 17f,
                                weight = FontWeight.Bold,
                                tabularNums = true,
                                decoration = if (isGrid && calCols > 1 && isSelected) TextDecoration.Underline else TextDecoration.None,
                            ),
                        )
                        Row(
                            Modifier.height(5.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (hasCourse) {
                                Box(
                                    Modifier
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(lerp(lerp(c.muted.copy(alpha = 0.72f), c.brand, coverage), c.onBrand, onBrand)),
                                )
                            }
                            if (urg != ItemsDayUrgency.NONE) {
                                Box(
                                    Modifier
                                        .size(if (urg == ItemsDayUrgency.URGENT) 5.dp else 4.5.dp)
                                        .clip(CircleShape)
                                        .background(
                                            lerp(if (urg == ItemsDayUrgency.URGENT) c.danger else c.brand, c.onBrand, onBrand),
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Synchronized 22dp sticky column date headers (`周一 16 / 周二 17`) shown in 3D/5D calendar views. */
@Composable
internal fun ItemsGridColumnSubHeader(
    dateWindow: ItemsDateWindow,
    calOffset: Float,
    calColsF: Float,
    focusedDate: String,
    onSelectDate: (String) -> Unit,
) {
    val c = LocalNativeColors.current
    val tick = nSelectionFeedback()
    val density = LocalDensity.current
    val compact = calColsF >= 4f

    Row(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(36.dp))
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds(),
        ) {
            val vpWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val gapDp = ItemsCalendarMath.columnGapDp(calColsF)
            val gapPx = with(density) { gapDp.dp.toPx() }
            val stridePx = (vpWidthPx + gapPx) / calColsF.coerceIn(1f, 7f)
            val colWidthPx = (stridePx - gapPx).coerceAtLeast(1f)
            val colWidthDp = with(density) { colWidthPx.toDp() }

            val firstVis = kotlin.math.floor(calOffset.toDouble()).toInt().coerceIn(0, dateWindow.totalDays - 1)
            val lastVis = ceil((calOffset + calColsF + 0.5f).toDouble()).toInt().coerceIn(firstVis, dateWindow.totalDays - 1)

            for (idx in firstVis..lastVis) {
                val date = dateWindow.dates[idx]
                val parsed = runCatching { LocalDate.parse(date) }.getOrNull()
                val wd = (parsed?.dayOfWeek?.value ?: ((idx % 7) + 1)) - 1
                val dom = parsed?.dayOfMonth ?: 1
                val label = if (compact) {
                    "${listOf("一", "二", "三", "四", "五", "六", "日")[wd]} $dom"
                } else {
                    "${listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[wd]} $dom"
                }
                val active = date == focusedDate
                val xPx = (idx - calOffset) * stridePx
                Box(
                    Modifier
                        .graphicsLayer { translationX = xPx }
                        .width(colWidthDp)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            tick()
                            onSelectDate(date)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        label,
                        maxLines = 1,
                        softWrap = false,
                        style = nativeTextStyle(
                            color = if (active) c.brand else c.muted,
                            sizeSp = 10.5f,
                            lineHeightSp = 14f,
                            weight = FontWeight.Bold,
                            tabularNums = true,
                        ),
                    )
                }
            }
        }
    }
}

internal data class ItemsToastData(
    val id: Long,
    val message: String,
    val hasUndo: Boolean = false,
    val onUndo: (() -> Unit)? = null,
)

/** Floating refractive Liquid Glass 5-second circular countdown Undo Toast. */
@Composable
internal fun ItemsUndoToast(
    toast: ItemsToastData?,
    backdrop: Backdrop,
    onExpired: (Long) -> Unit,
    onUndoClicked: (ItemsToastData) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val reduced = LocalLiquidGlassReducedMotion.current
    val utilHaptic = nHapticFeedback(NativeButtonHaptic.Utility.release)
    val shape = remember { RoundedRectangle(18.dp) }

    AnimatedContent(
        targetState = toast,
        contentKey = { it != null },
        modifier = modifier,
        transitionSpec = {
            if (reduced) EnterTransition.None togetherWith ExitTransition.None
            else (fadeIn(tween(180)) + slideInToastEnter()) togetherWith (fadeOut(tween(160)) + slideOutToastExit())
        },
        label = "items-undo-toast",
    ) { current ->
        if (current == null) return@AnimatedContent
        val progressState = remember(current.id) { mutableFloatStateOf(1f) }
        val secondsState = remember(current.id) { mutableIntStateOf(5) }
        val durationMs = if (current.hasUndo) 5000 else 2400

        LaunchedEffect(current.id) {
            progressState.floatValue = 1f
            secondsState.intValue = 5
            val startUptime = android.os.SystemClock.uptimeMillis()
            while (true) {
                val elapsed = android.os.SystemClock.uptimeMillis() - startUptime
                val remaining = (1f - elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                progressState.floatValue = remaining
                val nextSec = ceil((remaining * 5f).toDouble()).toInt().coerceIn(1, 5)
                if (nextSec != secondsState.intValue) secondsState.intValue = nextSec
                if (elapsed >= durationMs) break
                delay(32L)
            }
            onExpired(current.id)
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.22f))
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        colorControls(brightness = if (!dark) 0.16f else 0f, saturation = 1.45f)
                        blur(16f.dp.toPx())
                        lens(12f.dp.toPx(), 20f.dp.toPx())
                    },
                    highlight = { Highlight.Plain },
                    onDrawSurface = {
                        drawRect((if (dark) Color(0xFF141218) else Color(0xFFFAFAFA)).copy(alpha = if (dark) 0.78f else 0.72f))
                    },
                )
                .border(0.75.dp, c.border.copy(alpha = if (dark) 0.45f else 0.75f), shape)
                .clip(shape),
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                if (current.hasUndo) {
                    Box(Modifier.size(24.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            val stroke = 2.2.dp.toPx()
                            drawCircle(c.brand.copy(alpha = 0.20f), style = Stroke(stroke))
                            drawArc(
                                color = c.brand,
                                startAngle = -90f,
                                sweepAngle = 360f * progressState.floatValue,
                                useCenter = false,
                                style = Stroke(stroke, cap = StrokeCap.Round),
                            )
                        }
                        ItemsCountdownDigit(
                            seconds = { secondsState.intValue },
                            color = c.brand,
                        )
                    }
                }
                BasicText(
                    current.message,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = nativeTextStyle(c.ink, 13f, 16f, FontWeight.SemiBold),
                )
                if (current.hasUndo && current.onUndo != null) {
                    Box(
                        Modifier
                            .height(32.dp)
                            .clip(RoundedRectangle(11.dp))
                            .background(c.brand.copy(alpha = if (dark) 0.24f else 0.14f))
                            .clickable(role = Role.Button) {
                                utilHaptic()
                                onUndoClicked(current)
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            "撤销",
                            maxLines = 1,
                            softWrap = false,
                            style = nativeTextStyle(c.brand, 12.5f, 16f, FontWeight.Bold),
                        )
                    }
                }
            }
            if (current.hasUndo) {
                val barColor = c.brand.copy(alpha = 0.78f)
                Canvas(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(2.dp)
                        .clearAndSetSemantics {},
                ) {
                    drawRect(
                        color = barColor,
                        size = Size(size.width * progressState.floatValue.coerceIn(0f, 1f), size.height),
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemsCountdownDigit(
    seconds: () -> Int,
    color: Color,
) {
    BasicText(
        seconds().toString(),
        maxLines = 1,
        softWrap = false,
        style = nativeTextStyle(color, 10.5f, 12f, FontWeight.Bold, tabularNums = true),
    )
}

private fun slideInToastEnter() =
    androidx.compose.animation.slideInVertically(spring(0.84f, 440f)) { it / 2 } + scaleIn(tween(200), initialScale = 0.96f)

private fun slideOutToastExit() =
    androidx.compose.animation.slideOutVertically(tween(160)) { it / 3 } + scaleOut(tween(160), targetScale = 0.97f)

/** Floating circular Kyant Liquid Glass `+` FAB with optional unsaved-draft dot. */
@Composable
internal fun ItemsFloatingAddButton(
    visible: Boolean,
    hasDraft: Boolean,
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalNativeColors.current
    val reduced = LocalLiquidGlassReducedMotion.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduced) EnterTransition.None else scaleIn(spring(0.62f, 520f), initialScale = 0.72f) + fadeIn(tween(180)),
        exit = if (reduced) ExitTransition.None else scaleOut(tween(140), targetScale = 0.72f) + fadeOut(tween(140)),
    ) {
        Box(Modifier.size(54.dp)) {
            ItemsLiquidIconButton(
                backdrop = backdrop,
                contentDescriptionText = "记一件事",
                onClick = onClick,
                size = 54.dp,
                tint = c.brand,
                surfaceColor = c.brand.copy(alpha = 0.80f),
                haptic = NativeButtonHaptic.Submit,
                modifier = Modifier
                    .shadow(10.dp, CircleShape, ambientColor = c.brand.copy(alpha = 0.35f), spotColor = c.brand.copy(alpha = 0.35f))
                    .testTag("items-fab"),
            ) {
                NativeIcon("plus", c.onBrand, size = 24.dp, strokeWidth = 2.4f)
            }
            if (hasDraft) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 6.dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(nativeSemanticTones(LocalLiquidGlassDarkTheme.current == true, LocalNativePalette.current).amber)
                        .border(1.5.dp, c.surface, CircleShape),
                )
            }
        }
    }
}

/**
 * Sticky Liquid Glass header containing:
 * Titlebar (`事项` + Month/Teaching-Week pill + `今` button + Grid/List toggle + `学业` push pill),
 * 3-segment Scope Bar (`日程 / 待安排 / 作业`), unified 7-day Week Strip, optional 22dp column strip,
 * and collapsible Month Teaching-Week drawer.
 */
@Composable
internal fun ItemsTopHeader(
    backdrop: Backdrop,
    dateWindow: ItemsDateWindow,
    scope: String,
    isGrid: Boolean,
    focusedDate: String,
    listOffset: Float,
    dateMoving: Boolean,
    calOffset: Float,
    calColsF: Float,
    calCols: Int,
    inboxCount: Int,
    schoolCount: Int,
    monthOpen: Boolean,
    scaleMenuOpen: Boolean,
    dayHasCourse: (String) -> Boolean,
    dayUrgency: (String) -> ItemsDayUrgency,
    onSelectScope: (String) -> Unit,
    onToggleView: () -> Unit,
    onToggleMonthDrawer: () -> Unit,
    onGoToday: () -> Unit,
    onOpenAcademic: () -> Unit,
    onSelectDate: (String) -> Unit,
    onToggleScaleMenu: () -> Unit,
    onBeginCalScrub: () -> Float,
    onScrubCalOffset: (Float) -> Unit,
    onReleaseCalScrub: (velColsPerSec: Float) -> Unit,
    onBeginListScrub: () -> Float,
    onScrubListOffset: (Float) -> Unit,
    onReleaseListScrub: (velPagesPerSec: Float, startIdx: Int) -> Unit,
    onHeaderHeightPx: (Int) -> Unit = {},
    onHeaderBottomPx: (Int) -> Unit = {},
) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val reduced = LocalLiquidGlassReducedMotion.current
    val tick = nSelectionFeedback()

    val onSched = scope == "schedule"
    val targetGridBlend = if (isGrid) 1f else 0f
    val gridBlend by animateFloatAsState(
        targetValue = targetGridBlend,
        animationSpec = if (reduced) snap() else tween(235, easing = FastOutSlowInEasing),
        label = "items-week-strip-mode",
    )
    val centerDate = when {
        !onSched -> dateWindow.today
        gridBlend != targetGridBlend || !dateMoving -> focusedDate
        isGrid -> dateWindow.centerDate(calOffset, calColsF)
        else -> dateWindow.dateAt(listOffset.roundToInt())
    }
    val dispWeekIdx = dateWindow.weekIndexOf(dateWindow.indexOf(centerDate).toFloat())
    val monthLabel = ItemsCalendarMath.headerMonthWeekLabel(centerDate)
    val todayVisible = !onSched || ItemsCalendarMath.isTodayVisible(
        todayIndex = dateWindow.todayIndex,
        isGrid = isGrid,
        listOffset = listOffset,
        calOffset = calOffset,
        calColsF = calColsF,
    )
    val showColSub = onSched && isGrid && !monthOpen && calColsF in 1.4f..6.4f

    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    onHeaderHeightPx(coords.size.height)
                    onHeaderBottomPx(coords.size.height)
                }
                .statusBarsPadding(),
        ) {
            // Titlebar row
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(44.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BasicText(
                        "事项",
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.semantics { heading() },
                        style = nativeTextStyle(c.ink, 23f, 28f, FontWeight.Bold),
                    )
                    val chevRot by animateFloatAsState(
                        if (monthOpen && onSched) 180f else 0f,
                        if (reduced) snap() else spring(0.65f, 520f),
                        label = "month-chev",
                    )
                    Row(
                        Modifier
                            .height(28.dp)
                            .clip(Capsule())
                            .background(
                                if (monthOpen && onSched) c.brand.copy(alpha = if (dark) 0.24f else 0.14f)
                                else Color.Transparent,
                            )
                            .clickable(role = Role.Button) {
                                tick()
                                if (!onSched) {
                                    onSelectScope("schedule")
                                    onGoToday()
                                } else {
                                    onToggleMonthDrawer()
                                }
                            }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        CalendarCounter(
                            label = monthLabel,
                            order = AcademicClock.dayStart(centerDate),
                            style = nativeTextStyle(
                                color = if (monthOpen && onSched) c.brand else c.muted,
                                sizeSp = 12.5f,
                                lineHeightSp = 16f,
                                weight = FontWeight.Bold,
                                tabularNums = true,
                            ),
                            duration = 260,
                        )
                        if (onSched) {
                            NativeIcon(
                                "chevron-down",
                                color = if (monthOpen) c.brand else c.muted,
                                size = 12.dp,
                                strokeWidth = 2.4f,
                                modifier = Modifier.graphicsLayer { rotationZ = chevRot },
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = !todayVisible,
                        enter = if (reduced) EnterTransition.None else expandHorizontally(spring(0.84f, 480f)) + scaleIn(tween(180), initialScale = 0.75f) + fadeIn(tween(160)),
                        exit = if (reduced) ExitTransition.None else shrinkHorizontally(tween(160)) + scaleOut(tween(140), targetScale = 0.75f) + fadeOut(tween(140)),
                    ) {
                        Box(
                            Modifier
                                .height(24.dp)
                                .defaultMinSize(minWidth = 26.dp)
                                .clip(RoundedRectangle(8.dp))
                                .background(c.brand.copy(alpha = if (dark) 0.24f else 0.14f))
                                .clickable(role = Role.Button) {
                                    tick()
                                    onGoToday()
                                }
                                .padding(horizontal = 6.dp)
                                .testTag("items-today-pill")
                                .semantics { contentDescription = "回到今天" },
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                "今",
                                maxLines = 1,
                                softWrap = false,
                                style = nativeTextStyle(c.brand, 11.5f, 14f, FontWeight.Bold),
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AnimatedVisibility(
                        visible = onSched,
                        enter = if (reduced) EnterTransition.None else expandHorizontally(spring(0.84f, 480f)) + scaleIn(tween(180), initialScale = 0.75f) + fadeIn(tween(160)),
                        exit = if (reduced) ExitTransition.None else shrinkHorizontally(tween(160)) + scaleOut(tween(140), targetScale = 0.75f) + fadeOut(tween(140)),
                    ) {
                        ItemsLiquidIconButton(
                            backdrop = backdrop,
                            contentDescriptionText = "切换清单与日历",
                            onClick = onToggleView,
                            size = 38.dp,
                            surfaceColor = if (isGrid) c.brand.copy(alpha = if (dark) 0.24f else 0.16f) else null,
                            modifier = Modifier.testTag("items-view-toggle"),
                        ) {
                            AnimatedContent(
                                targetState = isGrid,
                                transitionSpec = {
                                    if (reduced) EnterTransition.None togetherWith ExitTransition.None
                                    else (fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.82f)) togetherWith
                                        (fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.82f))
                                },
                                label = "items-view-icon",
                            ) { grid ->
                                NativeIcon(
                                    name = if (grid) "list" else "grid",
                                    color = if (grid) c.brand else c.ink,
                                    size = 18.dp,
                                    strokeWidth = 2.0f,
                                )
                            }
                        }
                    }
                    ItemsLiquidPillButton(
                        backdrop = backdrop,
                        contentDescriptionText = "查看考试与成绩",
                        onClick = onOpenAcademic,
                        modifier = Modifier.testTag("items-academic-btn"),
                    ) {
                        NativeIcon("academic", c.ink, size = 15.dp, strokeWidth = 2.0f)
                        BasicText(
                            "学业",
                            maxLines = 1,
                            softWrap = false,
                            style = nativeTextStyle(c.ink, 13f, 16f, FontWeight.Bold),
                        )
                    }
                }
            }

            // 3-Segment Liquid Glass Scope Bar
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                ItemsLiquidScopeBar(
                    backdrop = backdrop,
                    currentScope = scope,
                    inboxCount = inboxCount,
                    schoolCount = schoolCount,
                    onSelectScope = onSelectScope,
                )
            }

            // Unified Sticky 7-Day Week Strip
            AnimatedVisibility(
                visible = onSched && !monthOpen,
                enter = if (reduced) EnterTransition.None else expandVertically(spring(0.86f, 480f)) + fadeIn(tween(180)),
                exit = if (reduced) ExitTransition.None else shrinkVertically(tween(160)) + fadeOut(tween(140)),
            ) {
                ItemsWeekStrip(
                    dateWindow = dateWindow,
                    displayWeek = dispWeekIdx,
                    isGrid = isGrid,
                    gridBlend = gridBlend,
                    dateMoving = dateMoving,
                    focusedDate = focusedDate,
                    listOffset = listOffset,
                    calOffset = calOffset,
                    calColsF = calColsF,
                    calCols = calCols,
                    scaleMenuOpen = scaleMenuOpen,
                    dayHasCourse = dayHasCourse,
                    dayUrgency = dayUrgency,
                    onSelectDate = onSelectDate,
                    onToggleScaleMenu = onToggleScaleMenu,
                    onBeginCalScrub = onBeginCalScrub,
                    onScrubCalOffset = onScrubCalOffset,
                    onReleaseCalScrub = onReleaseCalScrub,
                    onBeginListScrub = onBeginListScrub,
                    onScrubListOffset = onScrubListOffset,
                    onReleaseListScrub = onReleaseListScrub,
                )
            }

            // Subtle hairline + optional 22dp Column Sub-Header in 3D/5D
            AnimatedVisibility(
                visible = showColSub,
                enter = if (reduced) EnterTransition.None else expandVertically(spring(0.86f, 480f)) + fadeIn(tween(160)),
                exit = if (reduced) ExitTransition.None else shrinkVertically(tween(150)) + fadeOut(tween(120)),
            ) {
                Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    ItemsGridColumnSubHeader(
                        dateWindow = dateWindow,
                        calOffset = calOffset,
                        calColsF = calColsF,
                        focusedDate = focusedDate,
                        onSelectDate = onSelectDate,
                    )
                    Box(Modifier.fillMaxWidth().height(0.75.dp).background(c.border.copy(alpha = 0.5f)))
                }
            }

            if (onSched && isGrid && !showColSub && !monthOpen) {
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(0.75.dp).background(c.border.copy(alpha = 0.5f)))
            }

            // Collapsible Month Teaching-Week Drawer
            ItemsMonthDrawer(
                open = onSched && monthOpen,
                backdrop = backdrop,
                dateWindow = dateWindow,
                selectedDate = focusedDate,
                centerWeekIndex = dispWeekIdx,
                dayHasCourse = dayHasCourse,
                dayUrgency = dayUrgency,
                onPickDay = onSelectDate,
                onDismiss = onToggleMonthDrawer,
            )
        }
    }
}
