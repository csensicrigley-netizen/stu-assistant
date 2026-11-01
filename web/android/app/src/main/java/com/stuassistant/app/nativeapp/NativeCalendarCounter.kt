/*
 * Animated Counter transition adapted from skydoves (Jaewoong Eum), 2026.
 * Apache-2.0: https://www.apache.org/licenses/LICENSE-2.0
 * Source: compose-animations, AnimationExample5.kt, eb51389891c303a0ad79c73525bd97acfdf96547.
 * Modified for STU: date/time segments, temporal direction across rollover, tabular
 * numerals, one accessible label, interruptible short motion and reduced motion.
 * Distributed on an AS IS basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package com.stuassistant.app.nativeapp

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion

private data class CounterSegment(val text: String, val order: Long)
private val CounterParts = Regex("\\d+|\\D+")

/** Retargets immediately; never queues intermediate minutes while the finger moves. */
@Composable internal fun CalendarCounter(label: String, order: Long, style: TextStyle,
    modifier: Modifier = Modifier, duration: Int = 220) {
    val reduced = LocalLiquidGlassReducedMotion.current
    val numberStyle = style.copy(fontFeatureSettings = "tnum")
    if (reduced) { BasicText(label, modifier, style = numberStyle); return }
    Row(modifier.animateContentSize(tween(duration)).clearAndSetSemantics { text = AnnotatedString(label) },
        verticalAlignment = Alignment.CenterVertically) {
        CounterParts.findAll(label).forEach { part ->
            AnimatedContent(CounterSegment(part.value, order), modifier = Modifier.clipToBounds(),
                contentKey = { it.text }, transitionSpec = {
                    val direction = if (targetState.order != initialState.order) {
                        if (targetState.order > initialState.order) 1 else -1
                    } else if ((targetState.text.toLongOrNull() ?: 0) >= (initialState.text.toLongOrNull() ?: 0)) 1 else -1
                    ((slideInVertically(tween(duration, easing = FastOutSlowInEasing)) { direction * it } + fadeIn(tween(duration / 2))) togetherWith
                        (slideOutVertically(tween(duration, easing = FastOutSlowInEasing)) { -direction * it } + fadeOut(tween(duration / 2))))
                        .using(SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> tween(duration) }))
                }, label = "calendar-counter-segment") { value ->
                BasicText(value.text, style = numberStyle, maxLines = 1)
            }
        }
    }
}
