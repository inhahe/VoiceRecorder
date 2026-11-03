package com.lithic.voicerecorder

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Keeps [this] view's content clear of the action bar and the system bars, by padding it as far
 * as they actually cover it.
 *
 * On API 35 (targetSdk 35) the window is edge-to-edge, and whether the AppCompat action bar then
 * overlays the content frame differs between devices and Android versions: on some the content is
 * laid out from the very top of the window, behind the status bar and the action bar; on others
 * (the API 36 emulator) it already starts below the action bar, and padding it by the bar's height
 * again left a gap that tall. So the cover is measured, in window coordinates, once the view is
 * laid out: the action bar's bottom (or the status bar's, with no action bar) above the view's
 * top, and the navigation bar over its bottom. Applied to the root of each activity; the view's own
 * padding is kept and added to.
 */
fun View.padBelowActionBar() {
    val baseTop = paddingTop
    val baseBottom = paddingBottom

    fun apply() {
        val bars = ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.systemBars()) ?: return
        val here = IntArray(2).also { getLocationInWindow(it) }
        val actionBar = rootView.findViewById<View>(androidx.appcompat.R.id.action_bar_container)
        val barBottom = actionBar?.takeIf { it.isShown }?.let { bar ->
            IntArray(2).also { bar.getLocationInWindow(it) }[1] + bar.height
        } ?: 0
        val top = (maxOf(bars.top, barBottom) - here[1]).coerceAtLeast(0)
        val bottom = (here[1] + height - (rootView.height - bars.bottom)).coerceAtLeast(0)
        // Padding moves the content, not the view, so this settles after one more layout.
        if (paddingTop != baseTop + top || paddingBottom != baseBottom + bottom) {
            updatePadding(top = baseTop + top, bottom = baseBottom + bottom)
        }
    }

    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply() }
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
        post { apply() } // the insets arrive before the layout they change
        insets
    }
}
