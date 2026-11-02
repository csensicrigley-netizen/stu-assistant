/*
 * Original counter reference: skydoves (Jaewoong Eum), 2026, Apache-2.0.
 * compose-animations / AnimationExample5.kt, eb51389891c303a0ad79c73525bd97acfdf96547.
 * https://www.apache.org/licenses/LICENSE-2.0 — AS IS, WITHOUT WARRANTIES.
 * Reworked for STU using the user-supplied Transitions.dev Number pop-in reference.
 */
package com.stuassistant.app.nativeapp

import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion
import kotlinx.coroutines.delay

private val DigitPopEase = CubicBezierEasing(.34f, 1.45f, .64f, 1f)

/** Native rendition of the user's Transitions.dev number-pop reference.
 * Each position owns at most two glyphs. Rapid changes replace the visible glyph
 * at full opacity and retain a small blur; they never restart an invisible entrance.
 */
@Composable internal fun CalendarCounter(label: String, order: Long, style: TextStyle,
    modifier: Modifier = Modifier, duration: Int = 500) {
    val reduced = LocalLiquidGlassReducedMotion.current
    val numberStyle = style.copy(fontFeatureSettings = "tnum")
    var previousOrder by remember { mutableLongStateOf(order) }
    val direction = remember(order) { if (order < previousOrder) -1f else 1f }
    SideEffect { previousOrder = order }
    if (reduced) { BasicText(label, modifier, style = numberStyle); return }
    Row(modifier.animateContentSize(tween(180)).clearAndSetSemantics { text = AnnotatedString(label) },
        verticalAlignment = Alignment.CenterVertically) {
        label.forEachIndexed { index, char ->
            val glyphStyle = if (char == ':') numberStyle.copy(fontFamily = FontFamily.Default) else numberStyle
            CalendarDigit(char, direction, index, glyphStyle, duration)
        }
    }
}

@Composable private fun CalendarDigit(target: Char, targetDirection: Float, index: Int, style: TextStyle, duration: Int) {
    var current by remember { mutableStateOf(target) }
    var outgoing by remember { mutableStateOf<Char?>(null) }
    var lastChange by remember { mutableLongStateOf(0L) }
    var direction by remember { mutableFloatStateOf(1f) }
    var rapid by remember { mutableStateOf(false) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target == current) return@LaunchedEffect
        val now = SystemClock.uptimeMillis()
        rapid = lastChange != 0L && now - lastChange < duration + 140
        direction = targetDirection
        lastChange = now
        outgoing = if (rapid) null else current
        progress.snapTo(if (rapid) .35f else 0f)
        current = target
        if (!rapid) delay(index.coerceAtMost(2) * 70L)
        progress.animateTo(1f, tween(if (rapid) 240 else duration,
            easing = if (rapid) FastOutSlowInEasing else DigitPopEase))
        outgoing = null
    }
    // Never clip the glyph's overshoot or soft blur at its cell boundary.
    // On pre-Android 12, Compose omits blur; the visible motion/fade still works.
    val remaining = (1f - progress.value).coerceIn(0f, 1f)
    Box(contentAlignment = Alignment.Center) {
        outgoing?.let { old ->
            BasicText(old.toString(), Modifier
                .graphicsLayer { alpha = remaining; translationY = -direction * 8.dp.toPx() * (1f-remaining) }
                .blur((2f * (1f-remaining)).dp, BlurredEdgeTreatment.Unbounded), style = style, maxLines = 1)
        }
        BasicText(current.toString(), Modifier
            .graphicsLayer {
                alpha = if (rapid) 1f else (1f-remaining)
                translationY = direction * (if (rapid) 2.dp.toPx() else 8.dp.toPx()) * (1f-progress.value)
            }
            .blur((2f * remaining).dp, BlurredEdgeTreatment.Unbounded), style = style, maxLines = 1)
    }
}
