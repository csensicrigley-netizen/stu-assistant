package com.stuassistant.app.nativeapp

import androidx.compose.ui.graphics.Color
import kotlin.math.*

/** Exact OKLCH recipes from web/src/styles/tokens.css, converted into sRGB. */
internal fun nativeOklch(l:Double,c:Double,h:Double):Color {
    val a=c*cos(h*PI/180);val b=c*sin(h*PI/180)
    val x=(l+.3963377774*a+.2158037573*b).pow(3)
    val y=(l-.1055613458*a-.0638541728*b).pow(3)
    val z=(l-.0894841775*a-1.291485548*b).pow(3)
    fun channel(n:Double)=((if(n<=.0031308)12.92*n else 1.055*n.pow(1/2.4)-.055).coerceIn(0.0,1.0)).toFloat()
    return Color(channel(4.0767416621*x-3.3077115913*y+.2309699292*z),channel(-1.2684380046*x+2.6097574011*y-.3413193965*z),channel(-.0041960863*x-.7034186147*y+1.707614701*z))
}
internal fun nativeColors(dark:Boolean,classic:Boolean):NativeColors {
    fun color(l:Double,c:Double,h:Int)=nativeOklch(l,c,h.toDouble())
    return when {
        dark&&classic->NativeColors(color(.165,.012,262),color(.205,.014,262),color(.955,.006,85),color(.70,.012,262),color(.74,.13,258),color(.72,.15,35),color(.29,.016,262),color(.17,.03,258))
        dark->NativeColors(color(.215,.022,330),color(.255,.024,330),color(.96,.008,30),color(.75,.018,20),color(.80,.10,288),color(.78,.10,22),color(.345,.024,330),color(.22,.05,288))
        classic->NativeColors(color(.974,.007,85),color(.995,.003,85),color(.22,.022,262),color(.50,.016,262),color(.48,.17,258),color(.55,.18,32),color(.895,.009,85),color(.99,.004,258))
        else->NativeColors(color(.969,.018,16),color(.996,.004,18),color(.26,.035,355),color(.49,.024,355),color(.52,.15,288),color(.56,.15,22),color(.901,.016,18),color(.994,.005,288))
    }
}
