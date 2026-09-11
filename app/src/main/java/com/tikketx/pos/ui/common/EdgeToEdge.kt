package com.tikketx.pos.ui.common

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Edge-to-edge helpers. targetSdk 35 forces content behind the system bars on
 * Android 15+ (statusBarColor is ignored there), so every screen draws its own
 * gradient/branding under the transparent status bar and opts its chrome views
 * in through these padders.
 */

/** Lay the activity out behind the system bars and pick icon contrast. */
fun Activity.drawBehindSystemBars(darkStatusIcons: Boolean = false, darkNavIcons: Boolean = true) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.isAppearanceLightStatusBars = darkStatusIcons
    controller.isAppearanceLightNavigationBars = darkNavIcons
}

/**
 * Adds the system-bar (and optionally keyboard) heights on top of the view's
 * own padding. The view's background fills the padded area, so gradient
 * headers extend under the status bar.
 */
fun View.padWithSystemBars(
    top: Boolean = false,
    bottom: Boolean = false,
    includeIme: Boolean = false,
) {
    val basePadTop = paddingTop
    val basePadBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        var types = WindowInsetsCompat.Type.systemBars()
        if (includeIme) types = types or WindowInsetsCompat.Type.ime()
        val bars = insets.getInsets(types)
        v.setPadding(
            v.paddingLeft,
            if (top) basePadTop + bars.top else v.paddingTop,
            v.paddingRight,
            if (bottom) basePadBottom + bars.bottom else v.paddingBottom,
        )
        insets
    }
    requestApplyInsetsWhenAttached()
}

/** Pushes an overlay view down by the status-bar height via its top margin. */
fun View.marginTopSystemBars() {
    val baseMargin = (layoutParams as ViewGroup.MarginLayoutParams).topMargin
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val lp = v.layoutParams as ViewGroup.MarginLayoutParams
        if (lp.topMargin != baseMargin + bars.top) {
            lp.topMargin = baseMargin + bars.top
            v.layoutParams = lp
        }
        insets
    }
    requestApplyInsetsWhenAttached()
}

private fun View.requestApplyInsetsWhenAttached() {
    if (isAttachedToWindow) {
        ViewCompat.requestApplyInsets(this)
    } else {
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                ViewCompat.requestApplyInsets(v)
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }
}
