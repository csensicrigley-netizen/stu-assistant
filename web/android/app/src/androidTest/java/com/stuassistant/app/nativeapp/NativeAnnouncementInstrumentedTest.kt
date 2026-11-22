package com.stuassistant.app.nativeapp

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Rect
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.stuassistant.app.NativeActivity
import com.stuassistant.app.glass.LocalLiquidGlassDarkTheme
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real same-window dialog and pointer/back delivery without Compose test APIs. */
@RunWith(AndroidJUnit4::class)
class NativeAnnouncementInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<NativeActivity>
    private val message = NativeAnnouncement(
        id = "github-star", revision = 1, title = "给小助手一颗 Star",
        body = "如果小助手帮到了你，欢迎来 GitHub 点亮一颗 Star。\n也欢迎提交 Issue 反馈问题、分享想法，或贡献 PR，一起让它变得更好。",
        eyebrow = "开发者来信", imageUrl = null, imageAlt = "STU 事项助手",
        actionLabel = "去 GitHub 点亮 Star", actionUrl = "https://github.com/csensicrigley-netizen/stu-assistant",
        display = "once", priority = 0,
    )

    @Before fun setUp() {
        scenario = ActivityScenario.launch(Intent(instrumentation.targetContext, NativeActivity::class.java)
            .putExtra("preview", true))
    }

    @After fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    @Test fun lightDialogRendersAndCloseButtonDismissesOnceThenRestoresThePage() {
        val fixture = showDialog(dark = false)
        assertNotNull(awaitNode(message.title))
        assertNotNull(awaitNode(message.eyebrow))
        assertRenderedFrame("announcement-light.png")
        tap("关闭")
        awaitClosed(fixture)
        assertEquals(1, fixture.dismissals.get())
        assertEquals(0, fixture.actions.get())
        assertNotNull(awaitNode("快到期"))
    }

    @Test fun darkDialogPrimaryActionInvokesTheCtaOnceAndExits() {
        val fixture = showDialog(dark = true)
        assertNotNull(awaitNode(message.title))
        assertRenderedFrame("announcement-dark.png")
        tap(message.actionLabel + " ↗")
        awaitClosed(fixture)
        assertEquals(1, fixture.actions.get())
        assertEquals(0, fixture.dismissals.get())
    }

    @Test fun systemBackDismissesTheModalWithoutInvokingItsAction() {
        val fixture = showDialog(dark = true, reducedMotion = false)
        awaitNode(message.title)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitClosed(fixture)
        assertEquals(1, fixture.dismissals.get())
        assertEquals(0, fixture.actions.get())
    }

    @Test fun textOnlyMessagesHaveAnAcknowledgementActionAndNoExternalCta() {
        val fixture = showDialog(dark = false, content = message.copy(actionUrl = null, body = "校园服务将在周末进行维护。"))
        assertNotNull(awaitNode("知道了"))
        assertNull(findNode(message.actionLabel + " ↗"))
        tap("知道了")
        awaitClosed(fixture)
        assertEquals(1, fixture.dismissals.get())
        assertEquals(0, fixture.actions.get())
    }

    @Test fun everyBundledEffectRendersAndNeverBlocksClosingTheDialog() {
        for (effect in NativeAnnouncementRules.effectIds) {
            val composition = com.airbnb.lottie.LottieCompositionFactory.fromAssetSync(
                instrumentation.targetContext, "announcement-effects/$effect.json").value
            assertNotNull("Cannot parse bundled effect $effect", composition)
            assertTrue("Effect must be a short one-shot", composition!!.duration in 1000f..4000f)
            val fixture = showDialog(dark = true, reducedMotion = false, content = message.copy(effects = listOf(effect)), waitForIdle = false)
            assertEffectFramesChange(effect)
            SystemClock.sleep(InstrumentationRegistry.getArguments().getString("effectPreviewMs")?.toLongOrNull() ?: 0L)
            tap("关闭")
            awaitClosed(fixture)
            assertEquals(1, fixture.dismissals.get())
            assertEquals(0, fixture.actions.get())
        }
    }

    @Test fun githubAppIsTriedFirstAndMissingAppFallsBackToGenericHttps() {
        val githubUrl = message.actionUrl!!
        val installed = RecordingLinkContext(instrumentation.targetContext)
        openAnnouncementLink(installed, githubUrl)
        assertEquals(1, installed.attempts.size)
        assertEquals("com.github.android", installed.attempts.single().`package`)
        assertEquals(Intent.ACTION_VIEW, installed.attempts.single().action)
        assertEquals(githubUrl, installed.attempts.single().dataString)

        val missing = RecordingLinkContext(instrumentation.targetContext, rejectGithub = true)
        openAnnouncementLink(missing, githubUrl)
        assertEquals(2, missing.attempts.size)
        assertEquals("com.github.android", missing.attempts.first().`package`)
        assertNull(missing.attempts.last().`package`)
        assertEquals(githubUrl, missing.attempts.last().dataString)

        val generic = RecordingLinkContext(instrumentation.targetContext)
        openAnnouncementLink(generic, "https://example.test/notice")
        assertEquals(1, generic.attempts.size)
        assertNull(generic.attempts.single().`package`)
        openAnnouncementLink(generic, "javascript:alert(1)")
        assertEquals("Unsafe actions must not launch anything", 1, generic.attempts.size)
    }

    @Test fun entranceAndExitKeepTheCardUntilFadeCompletes() {
        shellOutput("dumpsys gfxinfo ${instrumentation.targetContext.packageName} reset")
        for (dark in listOf(false, true)) {
            val fixture = showDialog(dark = dark, reducedMotion = false, content = message.copy(effects = listOf("star-struck")), waitForIdle = false)
            SystemClock.sleep(3800)
            assertRenderedFrame("motion-${if (dark) "dark" else "light"}-settled.png")
            instrumentation.runOnMainSync { fixture.shown.value = false }
            SystemClock.sleep(70)
            instrumentation.runOnMainSync {
                assertFalse("Exit disposed the card immediately", fixture.controller.entries.isEmpty())
                assertEquals("External action must wait for the fade", 0, fixture.exits.get())
            }
            assertRenderedFrame("motion-${if (dark) "dark" else "light"}-leaving.png")
            awaitClosed(fixture)
            assertEquals(1, fixture.exits.get())
            SystemClock.sleep(500)
        }
        File(instrumentation.targetContext.getExternalFilesDir(null), "motion-gfxinfo.txt")
            .writeText(shellOutput("dumpsys gfxinfo ${instrumentation.targetContext.packageName} framestats"))
    }

    private fun shellOutput(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private class Fixture {
        val shown = mutableStateOf(true)
        val controller = NativeSheetController()
        val dismissals = AtomicInteger()
        val actions = AtomicInteger()
        val exits = AtomicInteger()
        val presented = CountDownLatch(1)
    }

    private fun showDialog(dark: Boolean, reducedMotion: Boolean = true, content: NativeAnnouncement = message, waitForIdle: Boolean = true): Fixture {
        val fixture = Fixture()
        scenario.onActivity { activity ->
            androidx.core.view.WindowCompat.getInsetsController(activity.window,activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            activity.setContent {
                val backdrop = rememberLayerBackdrop()
                val page = rememberLayerBackdrop()
                val combined = rememberCombinedBackdrop(backdrop,page)
                val previewStore = remember { NativeStore(activity).apply { enterPreview() } }
                DisposableEffect(previewStore) { onDispose { previewStore.close() } }
                val colors = nativeColors(dark, classic = false)
                val modalPresent = fixture.controller.entries.isNotEmpty()
                LaunchedEffect(modalPresent) {
                    if (modalPresent) {
                        // A title can remain in UiAutomation's cache from the previous activity.
                        // Wait for this fixture's registration and two Compose frame boundaries.
                        withFrameNanos { }
                        withFrameNanos { }
                        fixture.presented.countDown()
                    }
                }
                CompositionLocalProvider(
                    LocalNativeColors provides colors,
                    LocalNativePalette provides "sweet",
                    LocalNativeBackdrop provides backdrop,
                    LocalNativeSheets provides fixture.controller,
                    LocalNativeStrength provides 0,
                    LocalLiquidGlassDarkTheme provides dark,
                    com.stuassistant.app.glass.LocalLiquidGlassAccentColor provides colors.brand,
                    LocalLiquidGlassReducedMotion provides reducedMotion,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(colors.bg))
                        Box(Modifier.fillMaxSize().layerBackdrop(page).nativeSheetBackground(fixture.controller)) {
                            NativeTodayScreen(previewStore, previewStore.state.value, {}, {})
                        }
                        Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth().height(128.dp).nativeSheetBackground(fixture.controller)) {
                            com.stuassistant.app.LiquidGlassBottomBar(com.stuassistant.app.GlassNavigationState(
                                "today",listOf(com.stuassistant.app.GlassNavigationTab("today","今天"),com.stuassistant.app.GlassNavigationTab("week","课表"),com.stuassistant.app.GlassNavigationTab("items","事项"),com.stuassistant.app.GlassNavigationTab("campus","校园"),com.stuassistant.app.GlassNavigationTab("me","我的")),
                                dark = dark,palette = "sweet",strength = 0,bottomInset = 24f,reducedMotion = true),combined) {}
                        }
                        if (fixture.shown.value) NDialog(content.title, onDismiss = {
                            fixture.dismissals.incrementAndGet()
                            fixture.shown.value = false
                        }, onExited = { fixture.exits.incrementAndGet() }) {
                            NativeAnnouncementContent(content, onDismiss = {
                                fixture.dismissals.incrementAndGet()
                                fixture.shown.value = false
                            }, onAction = {
                                fixture.actions.incrementAndGet()
                                fixture.shown.value = false
                            })
                        }
                        NativeSheetHost(fixture.controller, combined)
                    }
                }
            }
        }
        assertTrue("Current dialog never reached its presentation frames", fixture.presented.await(5, TimeUnit.SECONDS))
        if (waitForIdle) {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(100, 5000)
        }
        awaitNode(content.title)
        return fixture
    }

    private fun awaitClosed(fixture: Fixture) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            var closed = false
            instrumentation.runOnMainSync { closed = !fixture.shown.value && fixture.controller.entries.isEmpty() }
            if (closed) {
                instrumentation.waitForIdleSync()
                // Compose state can settle before UiAutomation receives its accessibility
                // subtree update. Wait for both contracts, rather than asserting a cached root.
                if (findNode(message.title) == null) return
            }
            SystemClock.sleep(25)
        }
        var shown = true
        var entries = -1
        instrumentation.runOnMainSync {
            shown = fixture.shown.value
            entries = fixture.controller.entries.size
        }
        assertFalse("Dialog did not dismiss", shown)
        assertEquals("Dialog exit did not release its stack/focus barrier", 0, entries)
        assertNull("Dismissed dialog remained in the accessibility tree", findNode(message.title))
    }

    private fun awaitNode(label: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            findNode(label)?.let { return it }
            SystemClock.sleep(30)
        }
        throw AssertionError("Accessible element missing: $label")
    }

    private fun findNode(label: String): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::addLast) }
        }
        return null
    }

    private fun tap(label: String) {
        val bounds = Rect().also { awaitNode(label).getBoundsInScreen(it) }
        assertFalse("Interactive element has no bounds: $label", bounds.isEmpty)
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Pointer delivery rejected", instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(60)
        }
    }

    private fun assertRenderedFrame(filename: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("Dialog frame could not be captured", bitmap)
        assertTrue(bitmap!!.width > 0 && bitmap.height > 0)
        try {
            val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
            directory.mkdirs()
            File(directory, filename).outputStream().use { output ->
                assertTrue("Dialog preview could not be saved", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally { bitmap.recycle() }
    }

    private fun assertEffectFramesChange(effect: String) {
        // Wait beyond the dialog's entrance, but do not wait for UI idle: the
        // animation itself keeps the UI active until its final (empty) frame.
        val top = Rect().also { awaitNode(message.eyebrow).getBoundsInScreen(it) }.bottom
        val bottom = Rect().also { awaitNode(message.title).getBoundsInScreen(it) }.top
        SystemClock.sleep(750)
        val first = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        SystemClock.sleep(350)
        val next = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            var changed = 0
            for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(first.height) step 4) {
                for (x in first.width / 10 until first.width * 9 / 10 step 4) {
                    if (first.getPixel(x, y) != next.getPixel(x, y)) changed++
                }
            }
            assertTrue("$effect loaded but no animation frames were drawn", changed > 50)
            val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
            File(directory, "effect-$effect.png").outputStream().use { next.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            first.recycle()
            next.recycle()
        }
    }

    private class RecordingLinkContext(base: Context, private val rejectGithub: Boolean = false) : ContextWrapper(base) {
        val attempts = CopyOnWriteArrayList<Intent>()
        override fun startActivity(intent: Intent) {
            attempts.add(Intent(intent))
            if (rejectGithub && intent.`package` == "com.github.android") throw ActivityNotFoundException("Test: GitHub App missing")
        }
    }
}
