package com.lithic.voicerecorder

import android.util.TypedValue
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * On API 35 (targetSdk 35) the window is edge-to-edge and the AppCompat action bar *overlays*
 * the content frame — the content view is laid out from the very top of the window (y=0), so the
 * first row of a screen ends up hidden behind the action bar + status bar.
 *
 * This pads [this] view down by (status-bar inset + actionBarSize) so its content starts just
 * below the action bar. Applied to the scrollable root of each activity.
 */
fun View.padBelowActionBar() {
    val tv = TypedValue()
    val actionBarSize = if (context.theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
        TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
    } else {
        0
    }
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.updatePadding(top = bars.top + actionBarSize, bottom = bars.bottom)
        insets
    }
}
