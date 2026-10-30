package com.stuassistant.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.stuassistant.app.glass.LiquidBottomTab
import com.stuassistant.app.glass.LiquidBottomTabs
import com.stuassistant.app.glass.LocalLiquidGlassAccentColor
import com.stuassistant.app.glass.LocalLiquidGlassDarkTheme
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion

data class GlassNavigationTab(val key: String, val label: String, val badge: Int = 0)

data class GlassNavigationState(
    val current: String,
    val tabs: List<GlassNavigationTab>,
    val dark: Boolean = false,
    val palette: String = "sweet",
    val strength: Int = 100,
    val bottomInset: Float = 0f,
    val reducedMotion: Boolean = false,
)

/**
 * App adapter around the source-port of Kyant's official LiquidBottomTabs.
 * Material, combined capture, dragging, velocity deformation and highlight
 * live in glass/ and remain directly comparable to the fixed upstream source.
 * This adapter consumes the live WebView backdrop and owns only app tab content.
 */
@Composable
fun LiquidGlassBottomBar(
    state: GlassNavigationState,
    backdrop: Backdrop,
    onSelect: (String) -> Unit,
) {
    if (state.tabs.isEmpty()) return
    val latestState by rememberUpdatedState(state)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val selectedTabIndex: () -> Int = remember {
        { latestState.tabs.indexOfFirst { it.key == latestState.current }.coerceAtLeast(0) }
    }
    val onTabSelected: (Int) -> Unit = remember {
        { index -> latestState.tabs.getOrNull(index)?.let { latestOnSelect(it.key) } }
    }
    val colors = remember(state.dark, state.palette) { navigationColors(state.dark, state.palette) }
    Box(Modifier.fillMaxSize().testTag("native-liquid-glass-navigation")) {
        CompositionLocalProvider(
            LocalLiquidGlassDarkTheme provides state.dark,
            LocalLiquidGlassAccentColor provides colors.accent,
            LocalLiquidGlassReducedMotion provides state.reducedMotion,
        ) {
            LiquidBottomTabs(
                selectedTabIndex = selectedTabIndex,
                onTabSelected = onTabSelected,
                backdrop = backdrop,
                tabsCount = state.tabs.size,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // Extra 20 dp above the normal position accommodates upstream
                    // velocity deformation without clipping the moving lens.
                    .padding(start = 12.dp, end = 12.dp, top = 30.dp)
                    .fillMaxWidth(),
            ) {
                state.tabs.forEach { tab ->
                    val isSelected = tab.key == state.current
                    val ink = if (isSelected) colors.accent else colors.ink
                    LiquidBottomTab(
                        onClick = { latestOnSelect(tab.key) },
                        modifier = Modifier.testTag("native-tab-${tab.key}").semantics {
                            selected = isSelected
                            contentDescription = if (tab.badge > 0) {
                                "${tab.label}，${tab.badge} 项待处理"
                            } else tab.label
                        },
                    ) {
                        Box(Modifier.size(24.dp)) {
                            NavigationGlyph(tab.key, ink, Modifier.fillMaxSize())
                            if (tab.badge > 0) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .absoluteOffset(x = 9.dp, y = (-4).dp)
                                        .size(17.dp)
                                        .background(colors.badge, CircleShape)
                                        .clearAndSetSemantics {},
                                    contentAlignment = Alignment.Center,
                                ) {
                                    BasicText(
                                        if (tab.badge > 9) "9+" else tab.badge.toString(),
                                        style = TextStyle(
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                    )
                                }
                            }
                        }
                        BasicText(
                            text = tab.label,
                            maxLines = 1,
                            style = TextStyle(
                                color = ink,
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 14.sp,
                                lineHeight = 19.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                }
            }
        }
    }
}

private data class NavigationColors(
    val paper: Color,
    val ink: Color,
    val accent: Color,
    val badge: Color = Color(0xFFB9364B),
)

private fun navigationColors(dark: Boolean, palette: String): NavigationColors {
    val classic = palette == "classic"
    return if (dark) {
        NavigationColors(
            paper = if (classic) Color(0xFF0B0E14) else Color(0xFF1F161E),
            ink = Color(0xFFD5CCD9),
            accent = if (classic) Color(0xFF9AC5FF) else Color(0xFFD0C2FF),
        )
    } else {
        NavigationColors(
            paper = if (classic) Color(0xFFF9F6F1) else Color(0xFFFFF0F1),
            ink = if (classic) Color(0xFF514E4A) else Color(0xFF64586A),
            accent = if (classic) Color(0xFF235DA1) else Color(0xFF6756B9),
        )
    }
}

/** Compact native paths match the existing app's six symbols without an icon font. */
@Composable
private fun NavigationGlyph(key: String, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = 1.8f, cap = StrokeCap.Round)
            when (key) {
                "today" -> {
                    drawCircle(color, radius = 3.8f, center = Offset(12f, 12f), style = stroke)
                    line(12f, 3.5f, 12f, 5.5f); line(12f, 18.5f, 12f, 20.5f)
                    line(3.5f, 12f, 5.5f, 12f); line(18.5f, 12f, 20.5f, 12f)
                }
                "plan", "items" -> {
                    val path = Path().apply { moveTo(5f, 12.5f); lineTo(9.5f, 17f); lineTo(19f, 7.5f) }
                    drawPath(path, color, style = stroke)
                }
                "week" -> {
                    drawRoundRect(color, Offset(4.5f, 5f), Size(15f, 15f), CornerRadius(2.5f), style = stroke)
                    line(4.5f, 9.8f, 19.5f, 9.8f); line(8.5f, 3.5f, 8.5f, 6.5f)
                    line(15.5f, 3.5f, 15.5f, 6.5f); line(8f, 13.5f, 11f, 13.5f)
                    line(8f, 16.5f, 14f, 16.5f)
                }
                "study" -> {
                    val path = Path().apply {
                        moveTo(4.5f, 6.2f); cubicTo(7.1f, 5.2f, 9.6f, 5.4f, 12f, 6.9f)
                        cubicTo(14.4f, 5.4f, 16.9f, 5.2f, 19.5f, 6.2f)
                        lineTo(19.5f, 18.6f); cubicTo(16.9f, 17.6f, 14.4f, 17.8f, 12f, 19.3f)
                        cubicTo(9.6f, 17.8f, 7.1f, 17.6f, 4.5f, 18.6f); close()
                        moveTo(12f, 6.9f); lineTo(12f, 19.3f)
                    }
                    drawPath(path, color, style = stroke)
                }
                "campus" -> {
                    val path = Path().apply {
                        moveTo(3.5f, 11.5f); lineTo(12f, 5f); lineTo(20.5f, 11.5f)
                        moveTo(3.5f, 20f); lineTo(20.5f, 20f)
                        moveTo(5.5f, 20f); lineTo(5.5f, 11.5f)
                        moveTo(18.5f, 20f); lineTo(18.5f, 11.5f)
                        moveTo(9.5f, 20f); lineTo(9.5f, 15f); lineTo(14.5f, 15f); lineTo(14.5f, 20f)
                    }
                    drawPath(path, color, style = stroke)
                }
                else -> {
                    drawCircle(color, radius = 3.7f, center = Offset(12f, 8.5f), style = stroke)
                    val path = Path().apply { moveTo(5f, 20f); cubicTo(5.8f, 16.5f, 8.6f, 14.6f, 12f, 14.6f); cubicTo(15.4f, 14.6f, 18.2f, 16.5f, 19f, 20f) }
                    drawPath(path, color, style = stroke)
                }
            }
        }
    }
}
