package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
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

/**
 * Top-row text buttons (`Apps`, `⋮`, `Done`) sit on the wallpaper, not on a themed surface, so they're drawn like the
 * home labels in both themes (#182, Pragma, ux.md §12.1): white with the home-label shadow ([LabelShadow]), plus the
 * [LabelBackdrop] scrim around the text when [backdrop] (the `Label backdrop` setting) is on. The old `#D0BCFF` accent
 * was only about 1.7:1 on a white wallpaper; white on the scrim is 5.7:1 on any wallpaper.
 */
object OnWallpaperButton {
    val content: Color = Color.White
}

@Composable
fun WallpaperTextButton(onClick: () -> Unit, backdrop: Boolean, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) =
    TextButton(onClick, modifier, colors = ButtonDefaults.textButtonColors(contentColor = OnWallpaperButton.content)) {
        ProvideTextStyle(LocalTextStyle.current.copy(shadow = LabelShadow.shadow)) {
            Row(LabelBackdrop.modifier(backdrop), verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
