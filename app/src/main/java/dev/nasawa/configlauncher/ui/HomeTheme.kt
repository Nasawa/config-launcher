package dev.nasawa.configlauncher.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme

/**
 * #150: the one colour scheme home is drawn with. HomeScreen wraps everything in it, and the title bar's theme-colour
 * contrast check (#125) measures it, so the warning always describes the colours on screen. Home stays dark over the
 * wallpaper whatever the system theme; the system theme only picks a title bar `color` variant (light / dark).
 */
object HomeTheme {
    private val dark = darkColorScheme()
    fun scheme(): ColorScheme = dark
}
