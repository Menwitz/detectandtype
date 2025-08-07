package com.menwitz.humanliketyping.ui.common

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.appcompat.app.AppCompatActivity

object SystemBars {
    fun edgeToEdge(activity: AppCompatActivity, root: View) {
        // Let content go behind system bars
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        // Add padding equal to bars so content is not obscured
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
    }
}