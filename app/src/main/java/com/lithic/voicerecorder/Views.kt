package com.lithic.voicerecorder

import android.widget.TextView

/**
 * Sets the text only when it differs: a `TextView` lays itself out again on every set, changed
 * or not, and the timer and the player's position are set many times a second.
 */
fun TextView.setTextIfChanged(value: CharSequence) {
    if (!text.contentEquals(value)) text = value
}
