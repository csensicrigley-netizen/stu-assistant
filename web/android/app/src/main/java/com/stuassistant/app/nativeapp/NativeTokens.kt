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

const val NATIVE_DEFAULT_PALETTE: String = "classic"
const val NATIVE_DEFAULT_STYLE: String = "glass"

val NATIVE_STYLE_KEYS: Set<String> = setOf("glass", "modern")

fun normalizeNativeStyle(raw: String?): String =
    raw?.takeIf { it in NATIVE_STYLE_KEYS } ?: NATIVE_DEFAULT_STYLE

private val NATIVE_PAPER_LIGHT: Color = ok(.975, .008, 255)
private val NATIVE_PAPER_DARK: Color = ok(.170, .018, 265)

val NATIVE_PALETTES: List<NativePaletteSpec> = listOf(
    NativePaletteSpec("classic", "海蓝", "桑浦海风", NATIVE_PAPER_LIGHT, ok(.56, .20, 262), ok(.62, .16, 255)),
    NativePaletteSpec("sweet", "樱粉", "春日奶纸", NATIVE_PAPER_LIGHT, ok(.58, .17, 352), ok(.64, .14, 18)),
    NativePaletteSpec("phoenix", "凤凰", "盛夏红棉", NATIVE_PAPER_LIGHT, ok(.55, .17, 36), ok(.62, .14, 58)),
    NativePaletteSpec("pine", "青松", "水库林荫", NATIVE_PAPER_LIGHT, ok(.50, .135, 164), ok(.58, .13, 152)),
    NativePaletteSpec("lilac", "暮紫", "钟楼晚霞", NATIVE_PAPER_LIGHT, ok(.53, .17, 286), ok(.60, .14, 296)),
    NativePaletteSpec("latte", "燕麦", "自习暖咖", NATIVE_PAPER_LIGHT, ok(.52, .125, 62), ok(.58, .13, 42)),
)

val NATIVE_PALETTE_KEYS: Set<String> = NATIVE_PALETTES.mapTo(linkedSetOf()) { it.key }

fun normalizeNativePalette(raw: String?): String =
    raw?.takeIf { it in NATIVE_PALETTE_KEYS } ?: NATIVE_DEFAULT_PALETTE

private data class NativeAccent(val hue: Int, val light: Color, val dark: Color)

private fun paletteAccent(palette: String): NativeAccent = when (normalizeNativePalette(palette)) {
    "sweet" -> NativeAccent(352, ok(.56, .17, 352), ok(.74, .14, 352))
    "phoenix" -> NativeAccent(36, ok(.54, .17, 36), ok(.75, .145, 38))
    "pine" -> NativeAccent(164, ok(.49, .135, 164), ok(.73, .125, 162))
    "lilac" -> NativeAccent(286, ok(.52, .17, 286), ok(.75, .14, 286))
    "latte" -> NativeAccent(62, ok(.50, .125, 62), ok(.75, .115, 65))
    else -> NativeAccent(262, ok(.55, .20, 262), ok(.72, .165, 262))
}

/**
 * MediAI-inspired cool slate paper + high-contrast ink; palettes recolor brand accent tokens
 * while keeping the app canvas and cards clean, neutral, and easy to scan.
 */
fun nativeColors(dark: Boolean, palette: String): NativeColors {
    val accent = paletteAccent(palette)
    return if (dark) {
        NativeColors(
            bg = NATIVE_PAPER_DARK,
            surface = ok(.215, .020, 265),
            ink = ok(.965, .006, 255),
            muted = ok(.720, .016, 260),
            brand = accent.dark,
            danger = ok(.72, .16, 28),
            border = ok(.305, .020, 265),
            onBrand = Color.White,
        )
    } else {
        NativeColors(
            bg = NATIVE_PAPER_LIGHT,
            surface = Color.White,
            ink = ok(.215, .032, 265),
            muted = ok(.535, .022, 262),
            brand = accent.light,
            danger = ok(.56, .19, 28),
            border = ok(.910, .010, 258),
            onBrand = Color.White,
        )
    }
}

internal fun nativeColors(dark: Boolean, classic: Boolean): NativeColors =
    nativeColors(dark, if (classic) "classic" else "sweet")

internal fun nativeSemanticTones(dark: Boolean, palette: String): NativeSemanticTones {
    val accent = paletteAccent(palette)
    return if (dark) {
        NativeSemanticTones(
            soft = ok(.285, .055, accent.hue),
            softInk = ok(.88, .085, accent.hue),
            amber = ok(.82, .12, 78),
            dangerInk = ok(.84, .11, 28),
            leaf = ok(.77, .12, 155),
        )
    } else {
        NativeSemanticTones(
            soft = ok(.942, .032, accent.hue),
            softInk = ok(.40, .155, accent.hue),
            amber = ok(.50, .12, 72),
            dangerInk = ok(.46, .17, 28),
            leaf = ok(.47, .12, 155),
        )
    }
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

internal fun nativeLogoPalette(palette: String): NativeLogoPalette {
    normalizeNativePalette(palette)
    return NativeLogoPalette(
        hi = Color(0xFFEAEAEA),
        lo = Color(0xFFD4D4D4),
        paper = Color(0xFF161617),
        bead = Color(0xFFFFFFFF),
    )
}
