package com.stuassistant.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.stuassistant.app.glass.LiquidBottomTab
import com.stuassistant.app.glass.LiquidBottomTabs
import com.stuassistant.app.glass.LocalLiquidGlassAccentColor
import com.stuassistant.app.glass.LocalLiquidGlassDarkTheme
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion
import com.stuassistant.app.nativeapp.NativeFontFamily
import com.stuassistant.app.nativeapp.NativeIcon
import com.stuassistant.app.nativeapp.nativeColors

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
 *
 * Notice how unselected `colors.ink` is supplied inside `LiquidBottomTabs` so the
 * moving Liquid Glass lens optically reveals the `accentColor`-tinted pass smoothly
 * without premature color jumps, while badges sit in a crisp non-tinted overlay above
 * the glass lens so `ColorFilter.tint` never muddies badge text.
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
                    .padding(start = 16.dp, end = 16.dp, top = 30.dp)
                    .fillMaxWidth(),
            ) {
                state.tabs.forEach { tab ->
                    val isSelected = tab.key == state.current
                    val tabColor = if (isSelected) colors.accent else colors.ink
                    LiquidBottomTab(
                        onClick = { latestOnSelect(tab.key) },
                        modifier = Modifier.testTag("native-tab-${tab.key}").semantics {
                            selected = isSelected
                            contentDescription = if (tab.badge > 0) {
                                "${tab.label}，${tab.badge} 项待处理"
                            } else tab.label
                        },
                    ) {
                        NativeIcon(
                            name = tab.key,
                            color = tabColor,
                            size = 22.dp,
                            strokeWidth = if (isSelected) 1.98f else 1.78f,
                        )
                        BasicText(
                            text = tab.label,
                            maxLines = 1,
                            softWrap = false,
                            style = TextStyle(
                                color = tabColor,
                                fontFamily = NativeFontFamily,
                                fontSize = 11.5.sp,
                                lineHeight = 14.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeightStyle = LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.None,
                                ),
                            ),
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                }
            }

            if (state.tabs.any { it.badge > 0 }) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(start = 20.dp, end = 20.dp, top = 30.dp)
                        .height(64.dp)
                        .fillMaxWidth()
                        .clearAndSetSemantics {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.tabs.forEach { tab ->
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (tab.badge > 0) {
                                Box(
                                    Modifier
                                        .offset(x = 12.dp, y = (-13).dp)
                                        .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                                        .background(colors.badge, CircleShape)
                                        .border(1.25.dp, colors.surface, CircleShape)
                                        .padding(horizontal = 3.5.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    BasicText(
                                        if (tab.badge > 9) "9+" else tab.badge.toString(),
                                        maxLines = 1,
                                        softWrap = false,
                                        style = TextStyle(
                                            color = Color.White,
                                            fontFamily = NativeFontFamily,
                                            fontSize = 9.5.sp,
                                            lineHeight = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFeatureSettings = "tnum",
                                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                                            lineHeightStyle = LineHeightStyle(
                                                alignment = LineHeightStyle.Alignment.Center,
                                                trim = LineHeightStyle.Trim.None,
                                            ),
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
}

private data class NavigationColors(
    val paper: Color,
    val surface: Color,
    val ink: Color,
    val accent: Color,
    val badge: Color = Color(0xFFB9364B),
)

private fun navigationColors(dark: Boolean, palette: String): NavigationColors {
    val tokens = nativeColors(dark, palette)
    return NavigationColors(
        paper = tokens.bg,
        surface = tokens.surface,
        ink = tokens.ink.copy(alpha = if (dark) 0.80f else 0.72f),
        accent = tokens.brand,
        badge = if (dark) tokens.danger else Color(0xFFB9364B),
    )
}
