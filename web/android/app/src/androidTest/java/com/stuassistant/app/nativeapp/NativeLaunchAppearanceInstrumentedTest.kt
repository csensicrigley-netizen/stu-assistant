package com.stuassistant.app.nativeapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stuassistant.app.NativeActivity
import com.stuassistant.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLaunchAppearanceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun launchStagesUsePorcelainOrObsidianEvenWhenSystemThemeDisagrees() {
        val themes = mapOf(
            "system" to R.style.AppTheme_NoActionBarLaunch,
            "light" to R.style.AppTheme_NoActionBarLaunch_Light,
            "dark" to R.style.AppTheme_NoActionBarLaunch_Dark,
        )
        for (systemDark in listOf(false, true)) for ((mode, style) in themes) {
            assertLaunchStage(style, mode, systemDark, "$mode systemDark=$systemDark")
        }
    }

    @SuppressLint("DiscouragedApi") // These exact names were persisted by older app versions.
    @Test fun persistedPaletteStyleNamesResolveToTheNewLaunchStages() {
        for (palette in listOf("Sweet", "Classic", "Phoenix", "Pine", "Lilac", "Latte")) {
            for (mode in listOf("System", "Light", "Dark")) {
                val name = "AppTheme.Launch.$palette.$mode"
                val style = context.resources.getIdentifier(name, "style", context.packageName)
                assertNotEquals("Missing persisted theme $name", 0, style)
                for (systemDark in listOf(false, true)) {
                    assertLaunchStage(style, mode.lowercase(), systemDark, "$name systemDark=$systemDark")
                }
            }
        }
    }

    @Test fun savedAppearanceStillSetsThePageWindowBackgroundAfterRecreation() {
        val prefs = context.getSharedPreferences("stu.native.settings.v1", Context.MODE_PRIVATE)
        val oldTheme = prefs.getString("theme", null)
        val oldPalette = prefs.getString("palette", null)
        try {
            for ((theme, palette) in listOf("dark" to "sweet", "light" to "classic")) {
                assertTrue(prefs.edit().putString("theme", theme).putString("palette", palette).commit())
                launchPreview().use { scenario ->
                    scenario.recreate()
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        // The splash uses fixed stage colors; the real window must still
                        // match the selected page palette once Compose is displayed.
                        val background = activity.window.decorView.background
                        assertTrue("Expected an opaque page window background", background is ColorDrawable)
                        assertEquals(nativeColors(theme == "dark", palette).bg.toArgb(), (background as ColorDrawable).color)
                    }
                }
            }
        } finally {
            assertTrue(prefs.edit().putString("theme", oldTheme).putString("palette", oldPalette).commit())
            // Restore Android's persisted splash theme and launcher alias through the
            // normal activity lifecycle, in addition to restoring app preferences.
            launchPreview().use { instrumentation.waitForIdleSync() }
        }
    }

    private fun launchPreview(): ActivityScenario<NativeActivity> = ActivityScenario.launch(
        Intent(context, NativeActivity::class.java).putExtra("preview", true),
    )

    private fun assertLaunchStage(style: Int, mode: String, systemDark: Boolean, label: String) {
        val config = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (systemDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val wrapper = ContextThemeWrapper(context.createConfigurationContext(config), style)
        val dark = mode == "dark" || (mode == "system" && systemDark)
        val expectedColor = if (dark) 0xFF0E0E0F.toInt() else 0xFFE8E8EA.toInt()
        val expectedIcon = if (dark) R.drawable.ic_splash_truth_bell_dark else R.drawable.ic_splash_truth_bell_light
        assertColorAttribute(wrapper, androidx.core.splashscreen.R.attr.windowSplashScreenBackground, expectedColor, label)
        assertIconAttribute(wrapper, androidx.core.splashscreen.R.attr.windowSplashScreenAnimatedIcon, expectedIcon, label)
        if (Build.VERSION.SDK_INT >= 31) {
            assertColorAttribute(wrapper, android.R.attr.windowSplashScreenBackground, expectedColor, label)
            assertIconAttribute(wrapper, android.R.attr.windowSplashScreenAnimatedIcon, expectedIcon, label)
        }
    }

    private fun assertColorAttribute(wrapper: ContextThemeWrapper, attribute: Int, expected: Int, label: String) {
        val value = TypedValue()
        assertTrue("Missing splash background for $label", wrapper.theme.resolveAttribute(attribute, value, true))
        val actual = if (value.resourceId != 0) wrapper.getColor(value.resourceId) else value.data
        assertEquals(label, expected, actual)
    }

    private fun assertIconAttribute(wrapper: ContextThemeWrapper, attribute: Int, expected: Int, label: String) {
        val value = TypedValue()
        assertTrue("Missing splash icon for $label", wrapper.theme.resolveAttribute(attribute, value, true))
        assertEquals(label, expected, value.resourceId)
        assertNotNull("Unloadable splash icon for $label", wrapper.getDrawable(value.resourceId))
    }
}
