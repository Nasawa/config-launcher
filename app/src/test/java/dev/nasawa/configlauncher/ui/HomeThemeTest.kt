package dev.nasawa.configlauncher.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #180 (ux.md §12.1, Q-T6): home follows the system light/dark setting, and both palettes are legible. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HomeThemeTest {
    @get:Rule val rule = createComposeRule()

    private data class Seen(val systemDark: Boolean, val homeDark: Boolean, val local: Boolean?, val scheme: ColorScheme)

    /** What HomeScreen sees: homeDark() for the system setting, then the scheme and LocalHomeDark inside HomeThemed. */
    private fun seen(mode: ThemeMode = ThemeMode.SYSTEM): Seen {
        var out: Seen? = null
        rule.setContent {
            val dark = homeDark(mode)
            val system = androidx.compose.foundation.isSystemInDarkTheme()
            HomeThemed(dark) { out = Seen(system, dark, LocalHomeDark.current, MaterialTheme.colorScheme) }
        }
        rule.waitForIdle()
        return out!!
    }

    @Test @Config(qualifiers = "night") fun darkUiModeDrawsDark() {
        val s = seen()
        assertTrue(s.systemDark); assertTrue(s.homeDark); assertEquals(true, s.local)
        assertEquals("§12.1 dark surface (drawer, first run)", Color(0xFF141218), s.scheme.surface)
        assertEquals(Color(0xFFE6E0E9), s.scheme.onSurface)
        assertEquals(Color(0xFF211F26), s.scheme.surfaceContainer)
    }

    @Test @Config(qualifiers = "notnight") fun lightUiModeDrawsLight() {
        val s = seen()
        assertFalse(s.systemDark); assertFalse(s.homeDark); assertEquals(false, s.local)
        assertEquals("§12.1 light surface (drawer, first run)", Color(0xFFFEF7FF), s.scheme.surface)
        assertEquals(Color(0xFF1D1B20), s.scheme.onSurface)
        assertEquals(Color(0xFFF3EDF7), s.scheme.surfaceContainer)
        assertEquals(Color(0xFF6750A4), s.scheme.primary)
    }

    @Test @Config(qualifiers = "night") fun aModeOverridesTheSystemSetting() {   // the slot #156's `theme` key uses
        var light: Boolean? = null; var dark: Boolean? = null; var lightSurface: Color? = null
        rule.setContent {
            light = homeDark(ThemeMode.LIGHT); dark = homeDark(ThemeMode.DARK)
            HomeThemed(light!!) { lightSurface = MaterialTheme.colorScheme.surface }
        }
        rule.waitForIdle()
        assertEquals(false, light); assertEquals(true, dark); assertEquals(Color(0xFFFEF7FF), lightSurface)
    }

    @Test fun modeMapping() {
        assertTrue(HomeTheme.isDark(ThemeMode.SYSTEM, systemDark = true))
        assertFalse(HomeTheme.isDark(ThemeMode.SYSTEM, systemDark = false))
        assertFalse(HomeTheme.isDark(ThemeMode.LIGHT, systemDark = true))
        assertTrue(HomeTheme.isDark(ThemeMode.DARK, systemDark = false))
    }

    @Test @Config(qualifiers = "night") fun outsideHomeThemedTheSystemSettingIsUsed() {
        var outside: Boolean? = null; var inside: Boolean? = null
        rule.setContent { outside = currentHomeDark(); HomeThemed(false) { inside = currentHomeDark() } }
        rule.waitForIdle()
        assertEquals(true, outside); assertEquals("the title bar follows the home theme, not the system", false, inside)
    }

    // ---- Legibility (WCAG 2.x, as tools/contrast.py): text 4.5:1, icons and controls 3:1.
    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance() + 0.05, b.luminance() + 0.05).sortedDescending()
        return hi / lo
    }
    private fun text(fg: Color, bg: Color, what: String) = assertTrue("$what ${"%.2f".format(ratio(fg, bg))}:1", ratio(fg, bg) >= 4.5)

    @Test fun bannersAreLegibleInBothThemes() {
        for (dark in listOf(true, false)) for ((name, b) in listOf("warning" to HomeTheme.warningBanner(dark), "error" to HomeTheme.errorBanner(dark))) {
            val t = if (dark) "dark" else "light"
            text(b.title, b.background, "$t $name title")
            text(b.detail, b.background, "$t $name detail")
            text(b.hint, b.background, "$t $name hint")
            text(b.button, b.background, "$t $name buttons")
            assertTrue("$t $name icon", ratio(b.icon, b.background) >= 3.0)
        }
        assertEquals("light warning banner, §12.1", Color(0xFFFFF0C2), HomeTheme.warningBanner(false).background)
        assertEquals("dark banners unchanged", Color(0xFF4A3B00), HomeTheme.warningBanner(true).background)
        assertEquals(Color(0xFFFFDAD6), HomeTheme.errorBanner(false).background)
        assertEquals(Color(0xFF5C1F1F), HomeTheme.errorBanner(true).background)
    }

    @Test fun sheetTextIsLegibleInBothThemes() {
        for (dark in listOf(true, false)) {
            val s = HomeTheme.scheme(dark); val t = if (dark) "dark" else "light"
            // Drawer (surface), long-press and other sheets (surfaceContainerLow), menus (surfaceContainer), dialogs (High).
            for ((bg, name) in listOf(s.surface to "surface", s.surfaceContainerLow to "sheet", s.surfaceContainer to "menu", s.surfaceContainerHigh to "dialog")) {
                text(s.onSurface, bg, "$t text on $name")
                text(s.onSurfaceVariant, bg, "$t secondary text on $name")
            }
            text(s.primary, s.surfaceContainerHigh, "$t dialog buttons")
            text(s.inverseOnSurface, s.inverseSurface, "$t snackbar text")
        }
        // The old secondary grey would fail on the light surface: that's why sheets use onSurfaceVariant now.
        assertTrue(ratio(Color.Gray, HomeTheme.scheme(false).surface) < 4.5)
    }

    @Test fun overTheWallpaperNothingChangesWithTheTheme() {
        // Labels and the default title bar stay white with the label shadow (§12.1); the top-row buttons keep one accent.
        assertEquals(Color.White, TitleBarLayout.color("light") { null })
        assertEquals(Color.White, TitleBarLayout.color("onWallpaper") { null })
        assertEquals(HomeTheme.scheme(true).primary, HomeTheme.onWallpaperAccent)
        assertTrue("top-row accent on a black wallpaper", ratio(HomeTheme.onWallpaperAccent, Color.Black) >= 4.5)
    }
}
