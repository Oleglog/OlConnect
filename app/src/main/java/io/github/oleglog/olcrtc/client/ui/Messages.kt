package io.github.oleglog.olcrtc.client.ui

import android.app.Activity
import android.view.View
import android.widget.Toast
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar

/**
 * Feedback in the design's own clothes: a Snackbar anchored to the activity
 * surface, tinted from the theme, instead of a system toast that would ignore
 * the palette. Falls back to a toast when there is no view to anchor to yet.
 */
internal fun Activity.showMessage(text: CharSequence, long: Boolean = false) {
    val root = findViewById<View>(android.R.id.content)
    if (root == null) {
        Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        return
    }
    Snackbar.make(root, text, if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT)
        .setBackgroundTint(
            MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurfaceContainerHigh),
        )
        .setTextColor(MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurface))
        .show()
}
