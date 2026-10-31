package com.stuassistant.app.nativeapp

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** The content lambda must render this snapshot rather than the latest outer route. */
internal data class NativePageRoute(val route: String, val campusView: String) {
    val tab: String
        get() = when (route.substringBefore(':')) {
            "import", "medical" -> "week"
            "today", "week", "items", "campus", "me" -> route.substringBefore(':')
            else -> "today"
        }
    val index: Int get() = when (tab) {
        "week" -> 1
        "items" -> 2
        "campus" -> 3
        "me" -> 4
        else -> 0
    }
}

private data class PageVisual(val alpha: Float, val x: Float, val scale: Float, val blur: Float)
private data class DepartingPage(val slot: Int, val visual: PageVisual)
private data class PageFrame(
    val page: NativePageRoute,
    val slot: Int = 0,
    val direction: Int = 1,
    val generation: Int = 0,
    val animated: Boolean = false,
    val departing: DepartingPage? = null,
)

private fun enteringVisual(progress: Float, direction: Int): PageVisual {
    val p = progress.coerceIn(0f, 1f)
    return PageVisual(p, direction * 20f * (1f - progress), .994f + .006f * progress, 6f * (1f - p))
}

private fun departingVisual(from: PageVisual, progress: Float, direction: Int): PageVisual {
    val p = progress.coerceIn(0f, 1f)
    return PageVisual(from.alpha * (1f - p), from.x + (-direction * 14f - from.x) * p,
        from.scale + (.996f - from.scale) * p, from.blur * (1f - p))
}

/**
 * JS pageVariants parity: enter x=dir*20, scale=.994, opacity=0; exit x=-dir*14,
 * scale=.996, opacity=0 over 100ms cubic(.4,0,1,1). The JS spring's mass .85 is
 * normalized to Compose's unit mass: stiffness=380/.85, dampingRatio=32/(2√(380*.85)).
 * The short 6dp incoming blur is an addition to that source transition (API 31+).
 *
 * Only one page is composed. Two GPU display-list layers retain the previous drawing
 * for its short exit without keeping old effects, coroutines, touch targets or semantics
 * alive. This never reads pixels or creates a Bitmap; the parent's page backdrop records
 * both animated GPU layers, while the bottom navigation stays outside this host.
 */
@Composable
internal fun NativePageTransition(
    target: NativePageRoute,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (NativePageRoute) -> Unit,
) {
    val first = rememberGraphicsLayer()
    val second = rememberGraphicsLayer()
    val layers = remember(first, second) { listOf(first, second) }
    // Draw bookkeeping deliberately is not Compose state: recording must not schedule
    // another composition/draw. Each slot is written by only the current page.
    val recorded = remember { booleanArrayOf(false, false) }
    var frame by remember { mutableStateOf(PageFrame(target)) }
    val entering = remember { Animatable(1f) }
    val exiting = remember { Animatable(1f) }
    val density = LocalDensity.current
    val pxPerDp = density.density

    LaunchedEffect(target, reducedMotion) {
        val previous = frame
        if (target == previous.page && !reducedMotion) return@LaunchedEffect
        if (!reducedMotion && target.tab == previous.page.tab) {
            // Filters, campus sections, Import and Medical are ordinary same-tab changes.
            // Preserve any already-running primary transition rather than restarting it.
            frame = previous.copy(page = target)
        } else {
            val live = if (recorded[previous.slot]) DepartingPage(previous.slot,
                enteringVisual(if (previous.animated) entering.value else 1f, previous.direction)) else null
            val alreadyDeparting = previous.departing?.let {
                DepartingPage(it.slot, departingVisual(it.visual, exiting.value, previous.direction))
            }
            // A→B→C may happen while B is almost transparent. Retain the more visible
            // actual drawing (including its in-flight transform), rather than dropping A
            // for a barely visible B and flashing an empty background before C enters.
            val retained = if (alreadyDeparting != null &&
                (live == null || alreadyDeparting.visual.alpha > live.visual.alpha)) alreadyDeparting else live
            if (!reducedMotion && retained != null && retained.visual.alpha > .001f) {
                val nextSlot = 1 - retained.slot
                recorded[nextSlot] = false
                entering.snapTo(0f)
                exiting.snapTo(0f)
                frame = PageFrame(target, nextSlot, if (target.index > previous.page.index) 1 else -1,
                    previous.generation + 1, true, retained)
            } else {
                // Initial display, reduced motion, or no page drawing available yet.
                entering.snapTo(1f)
                exiting.snapTo(1f)
                frame = PageFrame(target, previous.slot, generation = previous.generation + 1)
            }
        }
    }

    LaunchedEffect(frame.generation) {
        if (!frame.animated) return@LaunchedEffect
        val generation = frame.generation
        coroutineScope {
            launch {
                entering.animateTo(1f, spring(dampingRatio = .8902638f,
                    stiffness = 447.05882f, visibilityThreshold = .001f))
            }
            launch {
                exiting.animateTo(1f, tween(100, easing = CubicBezierEasing(.4f, 0f, 1f, 1f)))
                if (frame.generation == generation) frame = frame.copy(departing = null)
            }
        }
    }

    val current = frame
    Box(modifier.fillMaxSize().clipToBounds()) {
        current.departing?.let { departing ->
            // A draw-only Box has no outgoing pointer handler, focus target or semantics.
            Box(Modifier.fillMaxSize().graphicsLayer {
                val visual = departingVisual(departing.visual, exiting.value, current.direction)
                alpha = visual.alpha
                translationX = visual.x * pxPerDp
                scaleX = visual.scale
                scaleY = scaleX
                val radius = visual.blur * pxPerDp
                renderEffect = if (Build.VERSION.SDK_INT >= 31 && radius > .05f)
                    BlurEffect(radius, radius, TileMode.Decal) else null
            }.drawWithContent { drawLayer(layers[departing.slot]) })
        }
        key(current.page.tab) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                val visual = enteringVisual(if (current.animated) entering.value else 1f, current.direction)
                alpha = visual.alpha
                translationX = visual.x * pxPerDp
                scaleX = visual.scale
                scaleY = visual.scale
                val radius = visual.blur * pxPerDp
                renderEffect = if (Build.VERSION.SDK_INT >= 31 && radius > .05f)
                    BlurEffect(radius, radius, TileMode.Decal) else null
            }.drawWithContent {
                val layer = layers[current.slot]
                layer.record { this@drawWithContent.drawContent() }
                recorded[current.slot] = true
                drawLayer(layer)
            }) { content(current.page) }
        }
    }
}
