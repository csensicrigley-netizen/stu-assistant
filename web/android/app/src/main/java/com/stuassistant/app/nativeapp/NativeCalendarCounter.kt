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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion
import kotlinx.coroutines.delay

private val DigitPopEase = CubicBezierEasing(.34f, 1.45f, .64f, 1f)

private sealed interface CounterToken {
    data class Digit(val slotKey: String, val index: Int, val char: Char) : CounterToken
    data class TextRun(val slotKey: String, val text: String) : CounterToken
}

private fun tokenizeCounterLabel(label: String): List<CounterToken> {
    val result = ArrayList<CounterToken>(label.length)
    var i = 0
    var groupIndex = 0
    var digitIndex = 0
    while (i < label.length) {
        if (label[i].isDigit()) {
            var end = i
            while (end < label.length && label[end].isDigit()) end++
            val len = end - i
            for (k in 0 until len) {
                // Anchor right-aligned within each numeric group so 9 -> 10 keeps the ones-place slot stable.
                val placeFromRight = len - 1 - k
                result += CounterToken.Digit(
                    slotKey = "num-$groupIndex-p$placeFromRight",
                    index = digitIndex++,
                    char = label[i + k],
                )
            }
            groupIndex++
            i = end
        } else {
            val start = i
            while (i < label.length && !label[i].isDigit()) i++
            result += CounterToken.TextRun(
                slotKey = "txt-$groupIndex",
                text = label.substring(start, i),
            )
            groupIndex++
        }
    }
    return result
}

/**
 * Native rendition of the user's Transitions.dev number-pop reference.
 * Aligns CJK runs and Latin tabular digits on a unified optical center (`includeFontPadding = false`),
 * keeping spaces and CJK punctuation intact while animating numeric places smoothly.
 */
@Composable
internal fun CalendarCounter(
    label: String,
    order: Long,
    style: TextStyle,
    modifier: Modifier = Modifier,
    duration: Int = 500,
) {
    val reduced = LocalLiquidGlassReducedMotion.current
    val effectiveLineHeight = if (style.lineHeight.isUnspecified) {
        (style.fontSize.value * 1.28f).sp
    } else {
        style.lineHeight
    }
    val alignedStyle = style.copy(
        fontFeatureSettings = "tnum",
        lineHeight = effectiveLineHeight,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )
    var previousOrder by remember { mutableLongStateOf(order) }
    val direction = remember(order) { if (order < previousOrder) -1f else 1f }
    SideEffect { previousOrder = order }
    if (reduced) {
        BasicText(label, modifier, style = alignedStyle, maxLines = 1, softWrap = false)
        return
    }
    val tokens = remember(label) { tokenizeCounterLabel(label) }
    Row(
        modifier
            .animateContentSize(tween(160))
            .clearAndSetSemantics { text = AnnotatedString(label) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tokens.forEach { token ->
            when (token) {
                is CounterToken.Digit -> key(token.slotKey) {
                    CalendarDigit(token.char, direction, token.index, alignedStyle, duration)
                }
                is CounterToken.TextRun -> key(token.slotKey) {
                    val runStyle = if (token.text == ":") alignedStyle.copy(fontFamily = FontFamily.Default) else alignedStyle
                    BasicText(
                        text = token.text.replace(' ', '\u00A0'),
                        style = runStyle,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarDigit(
    target: Char,
    targetDirection: Float,
    index: Int,
    style: TextStyle,
    duration: Int,
) {
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
        if (!rapid) delay(index.coerceAtMost(2) * 55L)
        progress.animateTo(
            1f,
            tween(
                if (rapid) 220 else duration,
                easing = if (rapid) FastOutSlowInEasing else DigitPopEase,
            ),
        )
        outgoing = null
    }
    val remaining = (1f - progress.value).coerceIn(0f, 1f)
    Box(contentAlignment = Alignment.Center) {
        outgoing?.let { old ->
            val outBlur = 2f * (1f - remaining)
            BasicText(
                old.toString(),
                Modifier
                    .graphicsLayer {
                        alpha = remaining
                        translationY = -direction * 7.dp.toPx() * (1f - remaining)
                    }
                    .then(if (outBlur > 0.05f) Modifier.blur(outBlur.dp, BlurredEdgeTreatment.Unbounded) else Modifier),
                style = style,
                maxLines = 1,
                softWrap = false,
            )
        }
        BasicText(
            current.toString(),
            Modifier
                .graphicsLayer {
                    alpha = if (rapid) 1f else (1f - remaining)
                    translationY = direction * (if (rapid) 2.dp.toPx() else 7.dp.toPx()) * (1f - progress.value)
                }
                .then(if (remaining > 0.02f) Modifier.blur((2f * remaining).dp, BlurredEdgeTreatment.Unbounded) else Modifier),
            style = style,
            maxLines = 1,
            softWrap = false,
        )
    }
}
