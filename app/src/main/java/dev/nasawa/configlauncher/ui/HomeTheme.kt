package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * ux.md §12.1 (Q-T6): which home theme to draw. Home follows the system light/dark setting (#180). #156 adds the
 * top-level launcher.json `"theme"` key (system | light | dark), which maps onto this; until then it's always [SYSTEM].
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * #150/#180: the colour schemes home is drawn with. HomeScreen wraps everything in [HomeThemed], and the title bar's
 * theme-colour contrast check (#125) measures the same scheme, so the warning always describes the colours on screen.
 * Dark is the Material 3 baseline dark palette (unchanged since #153); light is the Material 3 baseline light one.
 * Home labels over the wallpaper don't follow the theme (§12.1): they stay white with the label shadow.
 */
object HomeTheme {
    private val dark = darkColorScheme()
    private val light = lightColorScheme()

    /** The one place the theme is decided; #156 passes the file's `theme` as [mode]. */
    fun isDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    fun scheme(dark: Boolean): ColorScheme = if (dark) this.dark else light

    /**
     * Text buttons in the top row (`Apps`, `⋮`, `Done`) sit on the wallpaper, not on a themed surface, so like the
     * labels they keep the dark palette's accent in both themes (light `primary` #6750A4 would be hard to read on a
     * dark wallpaper).
     */
    val onWallpaperAccent: Color get() = dark.primary

    /** §12.1 banner colours. [hint] and [icon] are for the error banner's hint line and ■ (warning: ▲). */
    data class Banner(val background: Color, val title: Color, val detail: Color, val hint: Color, val icon: Color, val button: Color)

    /** B1, B2, B4–B9 style warning banner (the B6 conflict banner uses it). */
    fun warningBanner(dark: Boolean): Banner = if (dark)
        Banner(Color(0xFF4A3B00), Color(0xFFFFFFFF), Color(0xFFFFFFFF), Color(0xFFCAC4D0), Color(0xFFFFC400), Color(0xFFD0BCFF))
    else Banner(Color(0xFFFFF0C2), Color(0xFF3D2E00), Color(0xFF3D2E00), Color(0xFF49454F), Color(0xFF7A5900), Color(0xFF5C4300))

    /** B3 / invalid-config error banner. */
    fun errorBanner(dark: Boolean): Banner = if (dark)
        Banner(Color(0xFF5C1F1F), Color(0xFFFFFFFF), Color(0xFFFFCDD2), Color(0xFFCAC4D0), Color(0xFFFF8A80), Color(0xFFD0BCFF))
    else Banner(Color(0xFFFFDAD6), Color(0xFF410002), Color(0xFF8C1D18), Color(0xFF49454F), Color(0xFFB3261E), Color(0xFF8C1D18))
}

/** Whether home is drawn dark. Null outside [HomeThemed] (tests, previews): callers fall back to the system setting. */
val LocalHomeDark = compositionLocalOf<Boolean?> { null }

/** Home's dark/light decision for this composition: [mode] over the system setting (re-read on every uiMode change). */
@Composable
fun homeDark(mode: ThemeMode = ThemeMode.SYSTEM): Boolean = HomeTheme.isDark(mode, isSystemInDarkTheme())

/** The home theme as drawn: [LocalHomeDark] inside, or the system setting outside [HomeThemed]. */
@Composable
fun currentHomeDark(): Boolean = LocalHomeDark.current ?: isSystemInDarkTheme()

/** Draws [content] in the home theme: the scheme for [dark], and [LocalHomeDark] for the parts that pick colours themselves. */
@Composable
fun HomeThemed(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHomeDark provides dark) {
        MaterialTheme(colorScheme = HomeTheme.scheme(dark), content = content)
    }
}
