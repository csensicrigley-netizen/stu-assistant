package com.stuassistant.app.nativeapp

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** A decorative, local vector effect. It owns no pointer or accessibility targets. */
@Composable
internal fun NativeAnnouncementEffect(effect: String?, modifier: Modifier = Modifier) {
    var finished by remember(effect) { mutableStateOf(false) }
    if (effect == null || finished || LocalLiquidGlassReducedMotion.current) return
    val composition = rememberLottieComposition(LottieCompositionSpec.Asset("announcement-effects/$effect.json"))
    val playback = remember(effect) { NativeAnnouncementPlayback() }
    val dialogStart = LocalNativeDialogStart.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) finished = true
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(effect, lifecycle, dialogStart) {
        // Await inside the stable presentation job. Changing the load result must
        // not cancel playback and incorrectly consume this one-shot effect.
        val loaded = try { composition.await() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { finished = true; return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val start = dialogStart?.let { snapshotFlow { it.value }.filterNotNull().first() }
            playback.play(loaded.duration, start)
            finished = true
        }
    }
    LottieAnimation(composition.value, progress = { playback.progress.value },
        modifier = modifier.preferredFrameRate(FrameRateCategory.High)
            .graphicsLayer { alpha = if (playback.progress.floatValue > 0f) 1f else 0f }
            .clearAndSetSemantics {}, safeMode = true)
}
