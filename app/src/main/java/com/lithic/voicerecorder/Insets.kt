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
 * (the API 36 emulator) it already starts below the action bar. So the cover is measured, in
 * window coordinates, once the view is laid out: the action bar's bottom (or the status bar's,
 * with no action bar) above the view's top, the navigation bar over its bottom, and the side bars
 * and a camera cutout over its sides. Applied to the root of each activity; the view's own
 * padding is kept and added to.
 */
fun View.padBelowActionBar() {
    val base = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    val actionBar = rootView.findViewById<View>(androidx.appcompat.R.id.action_bar_container)

    /** Pads by the cover as it is now; true if that changed the padding. */
    fun apply(): Boolean {
        val insets = ViewCompat.getRootWindowInsets(this) ?: return false
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
        if (paddingLeft == base[0] + left && paddingTop == base[1] + top && paddingRight == base[2] + right && paddingBottom == base[3] + bottom) {
            return false
        }
        updatePadding(left = base[0] + left, top = base[1] + top, right = base[2] + right, bottom = base[3] + bottom)
        return true
    }

    // Applied just before a frame is drawn, after the layout that places the view: not in the
    // layout itself, where the request the new padding makes would be dropped, and not posted,
    // which would draw a frame unpadded first. A change skips that frame and lays out again with
    // it; padding moves the content, not the view, so the next look finds nothing to change.
    var stale = true
    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> stale = true }
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        stale = true
        view.invalidate()
        insets
    }
    viewTreeObserver.addOnPreDrawListener {
        if (!stale) return@addOnPreDrawListener true
        stale = false
        !apply()
    }
}
