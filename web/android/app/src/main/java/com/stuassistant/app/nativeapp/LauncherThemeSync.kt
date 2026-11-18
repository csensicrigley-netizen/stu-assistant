package com.stuassistant.app.nativeapp

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.stuassistant.app.R

/**
 * Synchronizes both:
 * 1. Android 12+ (API 31+) system-persisted StartingWindow splash theme (`SplashScreen.setSplashScreenTheme`),
 *    applied immediately whenever `themeMode` ("system" | "light" | "dark") is observed;
 * 2. Desktop launcher icon via `<activity-alias>` + `PackageManager.setComponentEnabledSetting`,
 *    committed safely in `Activity.onStop()` (`!isChangingConfigurations`) so toggling appearance inside
 *    Settings never triggers AOSP's foreground task finish on the currently active alias.
 */
internal object LauncherThemeSync {
    private const val NAMESPACE = "com.stuassistant.app"
    internal const val ALIAS_SYSTEM = "$NAMESPACE.LauncherSystem"
    internal const val ALIAS_LIGHT = "$NAMESPACE.LauncherLight"
    internal const val ALIAS_DARK = "$NAMESPACE.LauncherDark"

    private val ALL_ALIASES = listOf(ALIAS_SYSTEM, ALIAS_LIGHT, ALIAS_DARK)

    internal fun targetAliasForTheme(themeMode: String): String = when (themeMode) {
        "light" -> ALIAS_LIGHT
        "dark" -> ALIAS_DARK
        else -> ALIAS_SYSTEM
    }

    // API24..30 StartingWindow follows the system; Compose still honors the saved app appearance.
    // Alias themes are not supported by Android, so fixed splash themes rely on this API31+ override.
    fun syncSplashThemeImmediate(activity: Activity, themeMode: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val styleRes = when (themeMode) {
                "light" -> R.style.AppTheme_NoActionBarLaunch_Light
                "dark" -> R.style.AppTheme_NoActionBarLaunch_Dark
                else -> 0 // Resources.ID_NULL -> restores automatic values / values-night resolution
            }
            runCatching {
                activity.splashScreen.setSplashScreenTheme(styleRes)
            }
        }
    }

    fun syncLauncherAliasOnStop(context: Context, themeMode: String) {
        val pkg = context.packageName
        val pm = context.packageManager
        val targetCls = targetAliasForTheme(themeMode)

        // Check whether the desired alias is already the sole enabled launcher alias (idempotent fast path)
        val alreadyAligned = ALL_ALIASES.all { cls ->
            val state = runCatching { pm.getComponentEnabledSetting(ComponentName(pkg, cls)) }
                .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            val effectivelyEnabled = when (state) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> (cls == ALIAS_SYSTEM)
                else -> false
            }
            effectivelyEnabled == (cls == targetCls)
        }
        if (alreadyAligned) return

        runCatching {
            // Enable the target alias FIRST so the package never momentarily exposes zero launcher entries
            pm.setComponentEnabledSetting(
                ComponentName(pkg, targetCls),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            for (cls in ALL_ALIASES) {
                if (cls != targetCls) {
                    pm.setComponentEnabledSetting(
                        ComponentName(pkg, cls),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            }
        }
    }
}
