package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Whether home-screen labels get the backdrop scrim; provided around the home-grid pager only (not the dock or drawer). */
val LocalLabelBackdrop = compositionLocalOf { false }

object LabelBackdrop {
    /** 60 % black (docs/ux.md §1.1, AQ-10: 5.7:1 for white text even over a white wallpaper). */
    val scrim = Color(0x99000000)

    fun modifier(enabled: Boolean): Modifier =
        if (enabled) Modifier.background(scrim, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp) else Modifier
}
