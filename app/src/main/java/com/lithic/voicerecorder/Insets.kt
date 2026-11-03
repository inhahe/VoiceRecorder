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
    val base = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    val actionBar = rootView.findViewById<View>(androidx.appcompat.R.id.action_bar_container)

    fun apply() {
        val insets = ViewCompat.getRootWindowInsets(this) ?: return
        // The side bars (three-button navigation in landscape) and a camera cutout, too.
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val here = IntArray(2).also { getLocationInWindow(it) }
        val bar = actionBar ?: rootView.findViewById(androidx.appcompat.R.id.action_bar_container)
        val barBottom = bar?.takeIf { it.isShown }?.let { IntArray(2).also { at -> it.getLocationInWindow(at) }[1] + it.height } ?: 0
        val window = rootView
        val left = (bars.left - here[0]).coerceAtLeast(0)
        val top = (maxOf(bars.top, barBottom) - here[1]).coerceAtLeast(0)
        val right = (here[0] + width - (window.width - bars.right)).coerceAtLeast(0)
        val bottom = (here[1] + height - (window.height - bars.bottom)).coerceAtLeast(0)
        if (paddingLeft != base[0] + left || paddingTop != base[1] + top || paddingRight != base[2] + right || paddingBottom != base[3] + bottom) {
            updatePadding(left = base[0] + left, top = base[1] + top, right = base[2] + right, bottom = base[3] + bottom)
        }
    }

    // Posted, not done in the listener: a layout request made while this view is being laid out
    // is dropped, so the children would keep the padding they were just laid out with. Padding
    // moves the content, not the view, so once applied the next pass finds nothing to change.
    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { apply() } }
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
        post { apply() } // the insets arrive before the layout they change
        insets
    }
}
