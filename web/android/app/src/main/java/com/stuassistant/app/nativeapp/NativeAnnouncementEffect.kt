package com.stuassistant.app.nativeapp

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.preferredFrameRate
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
import kotlinx.coroutines.delay

/** A decorative, local vector effect. It owns no pointer or accessibility targets. */
@Composable
internal fun NativeAnnouncementEffect(effect: String?, modifier: Modifier = Modifier) {
    var finished by remember(effect) { mutableStateOf(false) }
    if (effect == null || finished || LocalLiquidGlassReducedMotion.current) return
    val composition = rememberLottieComposition(LottieCompositionSpec.Asset("announcement-effects/$effect.json"))
    val playback = remember { NativeAnnouncementPlayback() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) finished = true
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(effect, lifecycle) {
        // Await inside the stable presentation job. Changing the load result must
        // not cancel playback and incorrectly consume this one-shot effect.
        val loaded = try { composition.await() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { finished = true; return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Let the dialog settle before the burst starts; never loop or replay on resume.
            delay(600)
            playback.play(loaded.duration)
            finished = true
        }
    }
    LottieAnimation(composition.value, progress = { playback.progress.value },
        modifier = modifier.preferredFrameRate(FrameRateCategory.High).clearAndSetSemantics {}, safeMode = true)
}
