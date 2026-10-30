package me.eternal.purrfect.core.util.ktx

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.content.res.Resources.Theme
import android.content.res.TypedArray
import android.graphics.drawable.Drawable
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.graphics.ColorUtils
import me.eternal.purrfect.common.Constants
import me.eternal.purrfect.common.logger.AbstractLogger

val notFoundCache = mutableSetOf<String>()

@SuppressLint("DiscouragedApi")
fun Resources.getIdentifier(name: String, type: String): Int {
    val key = "$type#$name"
    if (key in notFoundCache) return 0

    return getIdentifier(name, type, Constants.SNAPCHAT_PACKAGE_NAME).also { id ->
        if (id != 0) return@also
        AbstractLogger.directDebug("Resource not found: $key")
        notFoundCache.add(key)
    }
}

fun Resources.getId(name: String): Int {
    return getIdentifier(name, "id")
}

fun Resources.getLayoutId(name: String): Int {
    return getIdentifier(name, "layout")
}

fun Resources.getDimens(name: String): Int {
    val id = getIdentifier(name, "dimen")
    if (id <= 0) return 0
    return getDimensionPixelSize(id)
}

fun Resources.getDimensFloat(name: String): Float {
    val id = getIdentifier(name, "dimen")
    if (id <= 0) return 0F
    return getDimension(id)
}

fun Resources.getStyledAttributes(name: String, theme: Theme): TypedArray? {
    val id = getIdentifier(name, "attr")
    if (id <= 0) return null
    return theme.obtainStyledAttributes(intArrayOf(id))
}

fun Resources.getDrawable(name: String, theme: Theme): Drawable? {
    val id = getIdentifier(name, "drawable")
    if (id <= 0) return null
    return getDrawable(id, theme)
}

@SuppressLint("MissingPermission")
fun Context.vibrateLongPress() {
    getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
}

fun Context.isDarkTheme(): Boolean {
    return theme.obtainStyledAttributes(
        intArrayOf(android.R.attr.colorPrimary)
    ).getColor(0, 0).let {
        ColorUtils.calculateLuminance(it) < 0.5
    }
}
