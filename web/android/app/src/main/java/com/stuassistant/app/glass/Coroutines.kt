/*
 * Source: Kyant0/AndroidLiquidGlass, licensed under Apache-2.0.
 * Upstream: https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/app/src/androidMain/kotlin/com/kyant/backdrop/catalog/utils/Coroutines.kt
 * Commit: 65ab177e90e5c1d8c62e70cf7755841982da65f6
 * Local adaptations are recorded in UPSTREAM.md; original material/physics remain upstream.
 */
package com.stuassistant.app.glass

import kotlinx.coroutines.android.awaitFrame as awaitAndroidFrame

suspend fun awaitFrame() {
    awaitAndroidFrame()
}

