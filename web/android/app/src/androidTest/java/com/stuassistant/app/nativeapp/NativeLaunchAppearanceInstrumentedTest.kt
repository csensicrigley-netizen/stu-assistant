package com.stuassistant.app.nativeapp

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stuassistant.app.NativeActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLaunchAppearanceInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun allLaunchVariantsMatchPageColorsEvenWhenSystemThemeDisagrees() {
        for (systemDark in listOf(false,true)) for (palette in NATIVE_PALETTE_KEYS) for (theme in listOf("system","light","dark")) {
            val config = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if(systemDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val wrapper = ContextThemeWrapper(context.createConfigurationContext(config), NativeLaunchAppearance.themeId(theme,palette))
            val attr = TypedValue()
            assertTrue(wrapper.theme.resolveAttribute(androidx.core.splashscreen.R.attr.windowSplashScreenBackground,attr,true))
            val actual = if(attr.resourceId != 0) wrapper.getColor(attr.resourceId) else attr.data
            val expected = nativeColors(theme=="dark" || (theme=="system" && systemDark),palette).bg.toArgb()
            assertEquals("$theme/$palette systemDark=$systemDark",expected,actual)
            assertTrue(wrapper.theme.resolveAttribute(androidx.core.splashscreen.R.attr.windowSplashScreenAnimatedIcon,attr,true))
            assertNotNull(wrapper.getDrawable(attr.resourceId))
        }
    }

    @Test fun savedThemeIsAppliedBeforeSplashInstallAndWindowMatchesAfterRecreation() {
        val prefs=context.getSharedPreferences("stu.native.settings.v1",Context.MODE_PRIVATE)
        val oldTheme=prefs.getString("theme",null);val oldPalette=prefs.getString("palette",null)
        try {
            for ((theme,palette) in listOf("dark" to "sweet","light" to "classic")) {
                assertTrue(prefs.edit().putString("theme",theme).putString("palette",palette).commit())
                ActivityScenario.launch<NativeActivity>(Intent(context,NativeActivity::class.java).putExtra("preview",true)).use { scenario ->
                    scenario.recreate()
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity { activity ->
                        // Read the actual production window after recreation; the test does not apply the theme.
                        val bg=activity.window.decorView.background as ColorDrawable
                        assertEquals(nativeColors(theme=="dark",palette).bg.toArgb(),bg.color)
                    }
                }
            }
        } finally {
            prefs.edit().putString("theme",oldTheme).putString("palette",oldPalette).commit()
            ActivityScenario.launch<NativeActivity>(Intent(context,NativeActivity::class.java).putExtra("preview",true)).use { scenario ->
                scenario.onActivity { activity ->
                    val systemDark=activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                    NativeLaunchAppearance.sync(activity,oldTheme?:"system",oldPalette?:"sweet",oldTheme=="dark" || ((oldTheme==null||oldTheme=="system")&&systemDark))
                }
            }
        }
    }
}
