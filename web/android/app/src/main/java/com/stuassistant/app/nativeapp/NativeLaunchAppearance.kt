package com.stuassistant.app.nativeapp

import android.app.Activity
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import com.stuassistant.app.R

/** Stable names are persisted by Android for the next cold start, before our process exists. */
internal object NativeLaunchAppearance {
    fun themeId(theme: String, palette: String): Int = when (normalizeNativePalette(palette)) {
        "classic" -> when (theme) {
            "dark" -> R.style.AppTheme_Launch_Classic_Dark
            "light" -> R.style.AppTheme_Launch_Classic_Light
            else -> R.style.AppTheme_Launch_Classic_System
        }
        "phoenix" -> when (theme) {
            "dark" -> R.style.AppTheme_Launch_Phoenix_Dark
            "light" -> R.style.AppTheme_Launch_Phoenix_Light
            else -> R.style.AppTheme_Launch_Phoenix_System
        }
        "pine" -> when (theme) {
            "dark" -> R.style.AppTheme_Launch_Pine_Dark
            "light" -> R.style.AppTheme_Launch_Pine_Light
            else -> R.style.AppTheme_Launch_Pine_System
        }
        "lilac" -> when (theme) {
            "dark" -> R.style.AppTheme_Launch_Lilac_Dark
            "light" -> R.style.AppTheme_Launch_Lilac_Light
            else -> R.style.AppTheme_Launch_Lilac_System
        }
        "latte" -> when (theme) {
            "dark" -> R.style.AppTheme_Launch_Latte_Dark
            "light" -> R.style.AppTheme_Launch_Latte_Light
            else -> R.style.AppTheme_Launch_Latte_System
        }
        else -> when (theme) {
            "dark" -> R.style.AppTheme_Launch_Sweet_Dark
            "light" -> R.style.AppTheme_Launch_Sweet_Light
            else -> R.style.AppTheme_Launch_Sweet_System
        }
    }

    fun prepare(activity: Activity) {
        val prefs = activity.getSharedPreferences("stu.native.settings.v1", Context.MODE_PRIVATE)
        activity.setTheme(themeId(prefs.getString("theme", "system") ?: "system", prefs.getString("palette", "sweet") ?: "sweet"))
    }

    fun sync(activity: Activity, theme: String, palette: String, dark: Boolean) {
        activity.window.setBackgroundDrawable(ColorDrawable(nativeColors(dark, palette).bg.toArgb()))
        if (Build.VERSION.SDK_INT >= 31) activity.splashScreen.setSplashScreenTheme(themeId(theme, palette))
    }
}
