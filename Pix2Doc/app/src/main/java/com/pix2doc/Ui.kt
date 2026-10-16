package com.pix2doc

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** Pads [root] by the system bars so content is not drawn under them (edge-to-edge on Android 15+). */
fun applySystemBarInsets(root: View) {
    val l = root.paddingLeft
    val t = root.paddingTop
    val r = root.paddingRight
    val b = root.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.updatePadding(left = l + bars.left, top = t + bars.top, right = r + bars.right, bottom = b + bars.bottom)
        insets
    }
}
