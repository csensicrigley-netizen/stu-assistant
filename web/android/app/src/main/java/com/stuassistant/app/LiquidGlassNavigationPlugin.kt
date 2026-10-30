package com.stuassistant.app

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/** Reuses the existing Capacitor WebView in the same GPU layer tree as Kyant's tabs. */
@CapacitorPlugin(name = "LiquidGlassNavigation")
class LiquidGlassNavigationPlugin : Plugin() {
    private var view: ComposeView? = null
    private var host: FrameLayout? = null
    private var originalParent: ViewGroup? = null
    private var originalParams: ViewGroup.LayoutParams? = null
    private var originalIndex = 0
    private var existingWebView: WebView? = null
    private var state by mutableStateOf(GlassNavigationState("today", emptyList()))
    private var navigationVisible by mutableStateOf(false)
    private var requestedVisible = false
    private var sheetOpen = false
    private var keyboardFromPage = false
    private var keyboardFromInsets = false
    private var hasWindowInsets = false
    private var resumed = true
    private var backgroundRecords = 0L
    private var bottomInsetPx = 0
    private val keys = setOf("today", "plan", "week", "study", "campus", "me")

    @PluginMethod
    fun setState(call: PluginCall) {
        activity.runOnUiThread {
            try {
                // RuntimeShader refraction needs Android 13. No screenshot fallback.
                check(Build.VERSION.SDK_INT >= 33) { "GPU refraction requires Android 13 or newer" }
                val incoming = call.getArray("tabs")
                val tabs = mutableListOf<GlassNavigationTab>()
                if (incoming != null) for (i in 0 until incoming.length()) {
                    val item = incoming.optJSONObject(i) ?: continue
                    val key = item.optString("key")
                    if (key !in keys || tabs.any { it.key == key }) continue
                    tabs += GlassNavigationTab(key, item.optString("label").take(12), item.optInt("badge", 0).coerceIn(0, 999))
                }
                if (tabs.size != 6) { call.reject("Expected all six navigation destinations"); return@runOnUiThread }
                val current = call.getString("current", "today") ?: "today"
                if (current !in keys) { call.reject("Unknown navigation destination"); return@runOnUiThread }
                state = GlassNavigationState(
                    current = current, tabs = tabs,
                    dark = call.getString("theme", "light") == "dark",
                    palette = if (call.getString("palette", "sweet") == "classic") "classic" else "sweet",
                    strength = (call.getInt("strength", 100) ?: 100).coerceIn(0, 100),
                    bottomInset = bottomInsetPx / activity.resources.displayMetrics.density,
                    reducedMotion = call.getBoolean("reducedMotion", false) ?: false,
                )
                requestedVisible = call.getBoolean("visible", true) ?: true
                sheetOpen = call.getBoolean("sheetOpen", false) ?: false
                keyboardFromPage = call.getBoolean("keyboardOpen", false) ?: false
                ensureView()
                updateVisibility()
                call.resolve(JSObject().put("mounted", true).put("renderer", "compose-webview-layer"))
            } catch (error: Exception) {
                hideNative()
                call.reject("Could not mount GPU navigation", error)
            }
        }
    }

    @PluginMethod
    fun hide(call: PluginCall) {
        activity.runOnUiThread { hideNative(); call.resolve() }
    }

    /** GPU display-list record counts are not presented FPS or motor telemetry. */
    @PluginMethod
    fun status(call: PluginCall) {
        activity.runOnUiThread {
            call.resolve(JSObject().put("mounted", view != null)
                .put("visible", navigationVisible).put("renderer", "compose-webview-layer")
                .put("backgroundRecords", backgroundRecords).put("screenshotCaptures", 0)
                .put("current", state.current))
        }
    }

    private fun ensureView() {
        if (view != null) return
        val web = bridge.webView ?: error("WebView is not ready")
        check(web.isHardwareAccelerated) { "A hardware accelerated WebView is required" }
        val parent = web.parent as? ViewGroup ?: error("WebView has no parent")
        val params = web.layoutParams
        val index = parent.indexOfChild(web)
        val glassContext = ContextThemeWrapper(activity, R.style.ThemeOverlay_STU_Glass)
        val container = FrameLayout(glassContext).apply { setBackgroundColor(Color.TRANSPARENT) }
        val compose = ComposeView(glassContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
        originalParent = parent
        originalParams = params
        originalIndex = index
        existingWebView = web
        // Same WebView instance: no recreation, loadUrl, history or cookie changes.
        parent.removeView(web)
        try {
            container.addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            parent.addView(container, index, params)
            host = container
            view = compose
            compose.setContent {
                LiquidGlassWebViewSurface(state, web, navigationVisible, ::select) { backgroundRecords++ }
            }
            // Always visible: keyboard/sheets hide only tabs, never the page.
            ViewCompat.setOnApplyWindowInsetsListener(container) { _, insets ->
                val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                val ime = insets.isVisible(WindowInsetsCompat.Type.ime())
                val changed = !hasWindowInsets || bottom != bottomInsetPx || ime != keyboardFromInsets
                hasWindowInsets = true
                bottomInsetPx = bottom
                keyboardFromInsets = ime
                if (changed) {
                    state = state.copy(bottomInset = bottom / activity.resources.displayMetrics.density)
                    updateVisibility()
                }
                insets
            }
            ViewCompat.requestApplyInsets(container)
        } catch (error: Exception) {
            releaseSurface(restoreWebView = true)
            throw error
        }
    }

    private fun select(key: String) {
        if (key !in keys || !canShow()) return
        if (key != state.current) {
            state = state.copy(current = key)
            try {
                val handle = bridge.getPlugin("InteractionHaptics")
                val haptics = (handle?.instance ?: handle?.load()) as? InteractionHapticsPlugin
                haptics?.playNavigationSelection(state.strength)
            } catch (_: Exception) { /* Feedback cannot block navigation. */ }
        }
        notifyListeners("select", JSObject().put("key", key))
    }

    private fun canShow() = requestedVisible && resumed && !sheetOpen &&
        !(if (hasWindowInsets) keyboardFromInsets else keyboardFromPage)

    private fun updateVisibility() { navigationVisible = canShow() }
    private fun hideNative() { requestedVisible = false; updateVisibility() }
    override fun handleOnPause() { resumed = false; updateVisibility() }
    override fun handleOnResume() { resumed = true; updateVisibility() }
    override fun handleOnConfigurationChanged(newConfig: Configuration) {
        host?.let { ViewCompat.requestApplyInsets(it) }
    }

    private fun releaseSurface(restoreWebView: Boolean) {
        navigationVisible = false
        view?.disposeComposition()
        host?.let { container ->
            ViewCompat.setOnApplyWindowInsetsListener(container, null)
            (container.parent as? ViewGroup)?.removeView(container)
        }
        if (restoreWebView) {
            existingWebView?.let { web ->
                (web.parent as? ViewGroup)?.removeView(web)
                originalParent?.let { parent -> parent.addView(web, originalIndex.coerceAtMost(parent.childCount), originalParams) }
            }
        }
        view = null
        host = null
        originalParent = null
        originalParams = null
        existingWebView = null
    }

    override fun handleOnDestroy() { releaseSurface(restoreWebView = false) }
}
