package com.stuassistant.app.nativeapp

import androidx.compose.ui.graphics.Color
import kotlin.math.*

data class NativePaletteSpec(
    val key: String,
    val label: String,
    val subtitle: String,
    val swatchPaper: Color,
    val swatchBrand: Color,
    val swatchAccent: Color,
)

data class NativeLogoPalette(
    val hi: Color,
    val lo: Color,
    val paper: Color,
    val bead: Color,
)

data class NativeSemanticTones(
    val soft: Color,
    val softInk: Color,
    val amber: Color,
    val dangerInk: Color,
    val leaf: Color,
)

data class NativeCourseSwatch(
    val mark: Color,
    val soft: Color,
    val mid: Color,
    val ink: Color,
)

/** Exact OKLCH recipes mirrored in web/src/styles/tokens.css, converted into sRGB. */
internal fun nativeOklch(l: Double, c: Double, h: Double): Color {
    val a = c * cos(h * PI / 180)
    val b = c * sin(h * PI / 180)
    val x = (l + .3963377774 * a + .2158037573 * b).pow(3)
    val y = (l - .1055613458 * a - .0638541728 * b).pow(3)
    val z = (l - .0894841775 * a - 1.291485548 * b).pow(3)
    fun channel(n: Double) =
        ((if (n <= .0031308) 12.92 * n else 1.055 * n.pow(1 / 2.4) - .055).coerceIn(0.0, 1.0)).toFloat()
    return Color(
        channel(4.0767416621 * x - 3.3077115913 * y + .2309699292 * z),
        channel(-1.2684380046 * x + 2.6097574011 * y - .3413193965 * z),
        channel(-.0041960863 * x - .7034186147 * y + 1.707614701 * z),
    )
}

private fun ok(l: Double, c: Double, h: Int): Color = nativeOklch(l, c, h.toDouble())

val NATIVE_PALETTES: List<NativePaletteSpec> = listOf(
    NativePaletteSpec("sweet", "樱粉", "春日奶纸", ok(.969, .018, 16), ok(.58, .15, 352), ok(.62, .14, 24)),
    NativePaletteSpec("classic", "海蓝", "桑浦海风", ok(.974, .007, 85), ok(.48, .17, 258), ok(.58, .17, 32)),
    NativePaletteSpec("phoenix", "凤凰", "盛夏红棉", ok(.971, .017, 58), ok(.55, .165, 36), ok(.64, .14, 65)),
    NativePaletteSpec("pine", "青松", "水库林荫", ok(.972, .013, 152), ok(.49, .125, 164), ok(.58, .14, 42)),
    NativePaletteSpec("lilac", "暮紫", "钟楼晚霞", ok(.970, .015, 296), ok(.52, .16, 286), ok(.60, .14, 18)),
    NativePaletteSpec("latte", "燕麦", "自习暖咖", ok(.968, .014, 78), ok(.51, .12, 62), ok(.57, .15, 28)),
)

val NATIVE_PALETTE_KEYS: Set<String> = NATIVE_PALETTES.mapTo(linkedSetOf()) { it.key }

fun normalizeNativePalette(raw: String?): String =
    raw?.takeIf { it in NATIVE_PALETTE_KEYS } ?: "sweet"

fun nativeColors(dark: Boolean, palette: String): NativeColors = when (normalizeNativePalette(palette)) {
    "classic" -> if (dark) {
        NativeColors(ok(.165, .012, 262), ok(.208, .014, 262), ok(.955, .006, 85), ok(.70, .012, 262), ok(.74, .13, 258), ok(.72, .15, 35), ok(.295, .016, 262), ok(.17, .03, 258))
    } else {
        NativeColors(ok(.974, .007, 85), ok(.995, .003, 85), ok(.22, .022, 262), ok(.50, .016, 262), ok(.48, .17, 258), ok(.55, .18, 32), ok(.895, .009, 85), ok(.99, .004, 258))
    }
    "phoenix" -> if (dark) {
        NativeColors(ok(.205, .020, 42), ok(.248, .022, 42), ok(.958, .010, 62), ok(.74, .020, 52), ok(.77, .135, 40), ok(.76, .12, 20), ok(.34, .022, 42), ok(.20, .045, 38))
    } else {
        NativeColors(ok(.971, .017, 58), ok(.996, .005, 58), ok(.25, .036, 38), ok(.48, .026, 42), ok(.54, .165, 36), ok(.55, .16, 18), ok(.898, .018, 55), ok(.995, .005, 58))
    }
    "pine" -> if (dark) {
        NativeColors(ok(.195, .018, 162), ok(.238, .020, 162), ok(.955, .008, 152), ok(.74, .018, 158), ok(.77, .115, 162), ok(.75, .12, 35), ok(.33, .020, 162), ok(.19, .04, 162))
    } else {
        NativeColors(ok(.972, .013, 152), ok(.996, .004, 152), ok(.24, .032, 162), ok(.47, .024, 162), ok(.48, .125, 164), ok(.55, .15, 32), ok(.898, .014, 152), ok(.995, .004, 152))
    }
    "lilac" -> if (dark) {
        NativeColors(ok(.198, .022, 292), ok(.240, .024, 292), ok(.958, .008, 296), ok(.745, .020, 292), ok(.78, .125, 286), ok(.77, .11, 20), ok(.335, .024, 292), ok(.20, .05, 286))
    } else {
        NativeColors(ok(.970, .015, 296), ok(.996, .004, 296), ok(.245, .036, 292), ok(.48, .026, 292), ok(.51, .165, 286), ok(.56, .15, 18), ok(.898, .016, 296), ok(.995, .005, 296))
    }
    "latte" -> if (dark) {
        NativeColors(ok(.202, .016, 65), ok(.244, .018, 65), ok(.955, .009, 80), ok(.74, .016, 72), ok(.78, .11, 68), ok(.75, .125, 32), ok(.335, .018, 65), ok(.20, .04, 65))
    } else {
        NativeColors(ok(.968, .014, 78), ok(.995, .005, 78), ok(.25, .030, 62), ok(.48, .022, 65), ok(.50, .12, 62), ok(.54, .155, 28), ok(.895, .016, 76), ok(.995, .005, 78))
    }
    else -> if (dark) {
        NativeColors(ok(.215, .022, 330), ok(.255, .024, 330), ok(.96, .008, 30), ok(.75, .018, 20), ok(.80, .10, 288), ok(.78, .10, 22), ok(.345, .024, 330), ok(.22, .05, 288))
    } else {
        NativeColors(ok(.969, .018, 16), ok(.996, .004, 18), ok(.26, .035, 355), ok(.49, .024, 355), ok(.52, .15, 288), ok(.56, .15, 22), ok(.901, .016, 18), ok(.994, .005, 288))
    }
}

internal fun nativeColors(dark: Boolean, classic: Boolean): NativeColors =
    nativeColors(dark, if (classic) "classic" else "sweet")

internal fun nativeSemanticTones(dark: Boolean, palette: String): NativeSemanticTones = when (normalizeNativePalette(palette)) {
    "classic" -> if (dark) NativeSemanticTones(ok(.30, .06, 258), ok(.86, .08, 258), ok(.82, .11, 80), ok(.82, .10, 35), ok(.76, .12, 155))
    else NativeSemanticTones(ok(.935, .035, 258), ok(.38, .15, 258), ok(.48, .10, 70), ok(.45, .16, 32), ok(.48, .11, 155))
    "phoenix" -> if (dark) NativeSemanticTones(ok(.32, .055, 38), ok(.89, .075, 42), ok(.84, .10, 82), ok(.86, .07, 22), ok(.78, .09, 162))
    else NativeSemanticTones(ok(.945, .032, 42), ok(.42, .145, 36), ok(.49, .10, 76), ok(.45, .145, 20), ok(.48, .10, 162))
    "pine" -> if (dark) NativeSemanticTones(ok(.30, .05, 164), ok(.88, .07, 162), ok(.84, .09, 82), ok(.85, .075, 32), ok(.79, .105, 162))
    else NativeSemanticTones(ok(.942, .032, 162), ok(.38, .115, 164), ok(.49, .095, 76), ok(.45, .14, 30), ok(.47, .11, 162))
    "lilac" -> if (dark) NativeSemanticTones(ok(.31, .06, 286), ok(.89, .075, 286), ok(.84, .09, 82), ok(.87, .065, 20), ok(.79, .085, 166))
    else NativeSemanticTones(ok(.942, .034, 288), ok(.40, .145, 286), ok(.49, .095, 78), ok(.45, .14, 18), ok(.48, .095, 166))
    "latte" -> if (dark) NativeSemanticTones(ok(.31, .048, 65), ok(.89, .065, 68), ok(.84, .095, 82), ok(.85, .075, 30), ok(.78, .09, 160))
    else NativeSemanticTones(ok(.942, .028, 68), ok(.40, .11, 62), ok(.49, .095, 76), ok(.44, .14, 28), ok(.48, .095, 160))
    else -> if (dark) NativeSemanticTones(ok(.34, .06, 355), ok(.92, .07, 355), ok(.85, .08, 82), ok(.88, .06, 22), ok(.80, .08, 168))
    else NativeSemanticTones(ok(.955, .03, 355), ok(.42, .15, 355), ok(.50, .09, 78), ok(.45, .14, 22), ok(.49, .09, 168))
}

internal fun nativeCourseSwatch(hue: Int, dark: Boolean, palette: String): NativeCourseSwatch {
    val crisp = normalizeNativePalette(palette) in setOf("classic", "pine")
    return when {
        dark && crisp -> NativeCourseSwatch(ok(.70, .12, hue), ok(.26, .04, hue), ok(.42, .08, hue), ok(.88, .06, hue))
        dark -> NativeCourseSwatch(ok(.73, .10, hue), ok(.27, .035, hue), ok(.44, .065, hue), ok(.90, .05, hue))
        crisp -> NativeCourseSwatch(ok(.60, .13, hue), ok(.95, .032, hue), ok(.87, .07, hue), ok(.36, .10, hue))
        else -> NativeCourseSwatch(ok(.63, .11, hue), ok(.962, .03, hue), ok(.885, .06, hue), ok(.38, .09, hue))
    }
}

internal fun nativeLogoPalette(palette: String): NativeLogoPalette = when (normalizeNativePalette(palette)) {
    "classic" -> NativeLogoPalette(Color(0xFF2A7AE0), Color(0xFF0B3586), Color(0xFFF9F6F1), Color(0xFFF5714E))
    "phoenix" -> NativeLogoPalette(Color(0xFFF07A54), Color(0xFFB53B24), Color(0xFFFFF7F2), Color(0xFFFFCA7A))
    "pine" -> NativeLogoPalette(Color(0xFF42A889), Color(0xFF1C6B54), Color(0xFFF4FBF7), Color(0xFFF58D62))
    "lilac" -> NativeLogoPalette(Color(0xFF8B7BF0), Color(0xFF4E3CA3), Color(0xFFF8F5FF), Color(0xFFFF9887))
    "latte" -> NativeLogoPalette(Color(0xFFC58B59), Color(0xFF7D4E29), Color(0xFFFCF7F0), Color(0xFFEB6F52))
    else -> NativeLogoPalette(Color(0xFF9D8BF2), Color(0xFF6A55B0), Color(0xFFFFF7F4), Color(0xFFFF9D7E))
}
