package com.stuassistant.app

import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** Hosts the existing WebView as the visible, hardware-backed source for Kyant's backdrop. */
@Composable
fun LiquidGlassWebViewSurface(
    state: GlassNavigationState,
    webView: WebView,
    navigationVisible: Boolean,
    onSelect: (String) -> Unit,
    onBackgroundRecorded: () -> Unit,
) {
    val latestOnBackgroundRecorded by rememberUpdatedState(onBackgroundRecorded)
    val recordContent: ContentDrawScope.() -> Unit = remember {
        {
            drawContent()
            latestOnBackgroundRecorded()
        }
    }
    val backdrop = rememberLayerBackdrop(onDraw = recordContent)
    val pageModifier = if (navigationVisible) {
        Modifier.fillMaxSize().layerBackdrop(backdrop)
    } else {
        Modifier.fillMaxSize()
    }

    Box(Modifier.fillMaxSize()) {
        // The host removes the old parent before composition. Reuse this instance
        // without loading a URL, destroying it, or hiding its visible drawing.
        AndroidView(
            factory = { webView },
            modifier = pageModifier,
        )
        if (navigationVisible) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height((104f + state.bottomInset).dp),
            ) {
                LiquidGlassBottomBar(state, backdrop, onSelect)
            }
        }
    }
}
