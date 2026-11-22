package com.stuassistant.app.nativeapp

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.RoundedRectangularShape
import com.stuassistant.app.glass.LiquidBottomTabs
import com.stuassistant.app.glass.LiquidDialogSurface
import com.stuassistant.app.glass.LiquidSlider
import com.stuassistant.app.glass.LocalLiquidGlassAccentColor
import com.stuassistant.app.glass.LocalLiquidGlassDarkTheme
import kotlin.math.roundToInt

internal fun isModernStyle(style: String = NATIVE_DEFAULT_STYLE): Boolean =
    style == "modern"

@Composable
internal fun NativeStyledTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    isLightTheme: Boolean = !(LocalLiquidGlassDarkTheme.current ?: isSystemInDarkTheme()),
    accentColor: Color = LocalLiquidGlassAccentColor.current ?: LocalNativeColors.current.brand,
    containerColor: Color = Color.Unspecified,
    compact: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    if (!isModernStyle(LocalNativeStyle.current)) {
        LiquidBottomTabs(
            selectedTabIndex = selectedTabIndex,
            onTabSelected = onTabSelected,
            backdrop = backdrop,
            tabsCount = tabsCount,
            modifier = modifier,
            content = content,
        )
        return
    }
    val c = LocalNativeColors.current
    val count = tabsCount.coerceAtLeast(1)
    val selected = selectedTabIndex().coerceIn(0, count - 1)
    val outerHeight = if (compact) 46.dp else 64.dp
    val outerRadius = if (compact) 23.dp else 32.dp
    val innerRadius = if (compact) 19.dp else 28.dp
    val shape = RoundedCornerShape(outerRadius)
    val fill = if (containerColor != Color.Unspecified) {
        containerColor
    } else {
        c.surface
    }
    val stroke = if (isLightTheme) {
        c.border.copy(alpha = 0.65f)
    } else {
        c.border.copy(alpha = 0.85f)
    }
    val indicatorColor by animateColorAsState(
        targetValue = if (isLightTheme) {
            accentColor.copy(alpha = 0.14f)
        } else {
            accentColor.copy(alpha = 0.24f)
        },
        animationSpec = NativeMotion.select(),
        label = "modern_tab_indicator_color",
    )
    Box(
        modifier = modifier
            .height(outerHeight)
            .shadow(
                elevation = if (compact) {
                    0.dp
                } else if (isLightTheme) {
                    8.dp
                } else {
                    12.dp
                },
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.14f),
            )
            .clip(shape)
            .background(fill, shape)
            .border(0.75.dp, stroke, shape)
            .padding(4.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val tabWidth: Dp = maxWidth / count
            val indicatorOffset by animateDpAsState(
                targetValue = tabWidth * selected,
                animationSpec = NativeMotion.dpSelect(),
                label = "modern_tab_offset",
            )
            val indicatorShape = RoundedCornerShape(innerRadius)
            Box(
                Modifier
                    .offset(x = indicatorOffset)
                    .width(tabWidth)
                    .fillMaxHeight()
                    .clip(indicatorShape)
                    .background(indicatorColor, indicatorShape)
                    .border(0.75.dp, accentColor.copy(alpha = 0.28f), indicatorShape),
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

@Composable
internal fun RowScope.NativeStyledTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = rememberPressScale(pressed, NativeMotion.PressRow)
    Column(
        modifier = modifier
            .nativeScale(scale)
            .clip(RoundedCornerShape(28.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .fillMaxHeight()
            .weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
internal fun NativeStyledDialogSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    shape: RoundedRectangularShape = RoundedRectangle(48.dp),
    frosted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!isModernStyle(LocalNativeStyle.current)) {
        LiquidDialogSurface(
            backdrop = backdrop,
            modifier = modifier,
            shape = shape,
            frosted = frosted,
            content = content,
        )
        return
    }
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current ?: isSystemInDarkTheme()
    val stroke = if (!dark) {
        c.border.copy(alpha = 0.72f)
    } else {
        c.border.copy(alpha = 0.88f)
    }
    Column(
        modifier = modifier
            .shadow(
                elevation = if (!dark) 14.dp else 20.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.14f),
                spotColor = Color.Black.copy(alpha = 0.22f),
            )
            .clip(shape)
            .background(c.surface, shape)
            .border(0.75.dp, stroke, shape),
        content = content,
    )
}

@Composable
internal fun NativeStyledSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    visibilityThreshold: Float,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: () -> Unit = {},
    onValueChangeCancelled: () -> Unit = {},
) {
    if (!isModernStyle(LocalNativeStyle.current)) {
        LiquidSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            visibilityThreshold = visibilityThreshold,
            backdrop = backdrop,
            modifier = modifier,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            onValueChangeCancelled = onValueChangeCancelled,
        )
        return
    }
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current ?: isSystemInDarkTheme()
    val accentColor = LocalLiquidGlassAccentColor.current ?: c.brand
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val min = valueRange.start
    val max = valueRange.endInclusive
    val span = (max - min).takeIf { it > 0f } ?: 1f
    val clamped = value().coerceIn(min, max)
    val fraction = ((clamped - min) / span).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = NativeMotion.select(0.001f),
        label = "modern_slider_fraction",
    )
    var widthPx by remember { mutableFloatStateOf(1f) }
    val trackBg = if (!dark) {
        c.ink.copy(alpha = 0.12f)
    } else {
        Color.White.copy(alpha = 0.14f)
    }
    val thumbStroke = accentColor.copy(alpha = 0.45f)

    fun updateFromX(x: Float) {
        val ratio = (x / widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val raw = min + ratio * span
        val stepped = if (visibilityThreshold > 0f) {
            (raw / visibilityThreshold).roundToInt() * visibilityThreshold
        } else {
            raw
        }
        currentOnValueChange(stepped.coerceIn(min, max))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(clamped, valueRange, 0)
                if (!enabled) {
                    disabled()
                } else {
                    setProgress { target ->
                        currentOnValueChange(target.coerceIn(min, max))
                        currentOnValueChangeFinished()
                        true
                    }
                }
            }
            .pointerInput(enabled, min, max, visibilityThreshold) {
                if (!enabled) return@pointerInput
                detectTapGestures { pos ->
                    updateFromX(pos.x)
                    currentOnValueChangeFinished()
                }
            }
            .pointerInput(enabled, min, max, visibilityThreshold) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { pos -> updateFromX(pos.x) },
                    onDragEnd = { currentOnValueChangeFinished() },
                    onDragCancel = { onValueChangeCancelled() },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        updateFromX(change.position.x)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
        ) {
            val trackHeight = 6.dp.toPx()
            val thumbRadius = 11.dp.toPx()
            val left = thumbRadius
            val right = (size.width - thumbRadius).coerceAtLeast(left + 1f)
            val usable = right - left
            val centerY = size.height / 2f
            val activeX = left + usable * animatedFraction

            drawRoundRect(
                color = trackBg,
                topLeft = Offset(left, centerY - trackHeight / 2f),
                size = Size(usable, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f),
            )
            drawRoundRect(
                color = accentColor,
                topLeft = Offset(left, centerY - trackHeight / 2f),
                size = Size((activeX - left).coerceAtLeast(trackHeight), trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f),
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.14f),
                radius = thumbRadius + 1.dp.toPx(),
                center = Offset(activeX, centerY + 1.dp.toPx()),
            )
            drawCircle(
                color = Color.White,
                radius = thumbRadius,
                center = Offset(activeX, centerY),
            )
            drawCircle(
                color = thumbStroke,
                radius = thumbRadius - 1.dp.toPx(),
                center = Offset(activeX, centerY),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
            )
        }
    }
}
