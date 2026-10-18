package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Whether home-screen labels get the backdrop scrim; provided around the pager and dock only. */
val LocalLabelBackdrop = compositionLocalOf { false }

object LabelBackdrop {
    /** 40 % black (§18.1 Q-C4). */
    val scrim = Color(0x66000000)

    fun modifier(enabled: Boolean): Modifier =
        if (enabled) Modifier.background(scrim, RoundedCornerShape(6.dp)).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier
}
