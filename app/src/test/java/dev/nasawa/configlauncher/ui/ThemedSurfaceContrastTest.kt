package dev.nasawa.configlauncher.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #182 (Grace's NO-GO): text on themed surfaces and fixed banners reaches WCAG 4.5:1 in light and dark. Drawer and
 * folder labels use `onSurface` (white was 1.05:1 on the light drawer), the B9 `Review` and the snackbar `Undo` / `✕`
 * get colours that read on their containers, and `Editing layout` gets the wallpaper label treatment.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ThemedSurfaceContrastTest {
    @get:Rule val rule = createComposeRule()

    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance() + 0.05, b.luminance() + 0.05).sortedDescending()
        return hi / lo
    }

    private fun scheme(dark: Boolean): Pair<ColorScheme, Color> {
        var s: ColorScheme? = null; var action: Color? = null
        rule.setContent { HomeThemed(dark) { s = MaterialTheme.colorScheme; action = snackbarActionColors().contentColor } }
        rule.waitForIdle()
        return s!! to action!!
    }

    private fun drawerAndFolderLabels(dark: Boolean) {
        val (s, _) = scheme(dark)
        val label = TileLabel.color(onWallpaper = false, scheme = s)
        assertEquals(s.onSurface, label)
        // The drawer sheet is drawn on `surface` (AppDrawerSheet containerColor); the folder AlertDialog on surfaceContainerHigh.
        assertTrue("drawer label ${ratio(label, s.surface)}", ratio(label, s.surface) >= 4.5)
        assertTrue("folder label ${ratio(label, s.surfaceContainerHigh)}", ratio(label, s.surfaceContainerHigh) >= 4.5)
        assertTrue("white would fail on the light drawer", dark || ratio(Color.White, s.surface) < 1.1)
    }

    @Test fun drawerAndFolderLabelsLight() = drawerAndFolderLabels(dark = false)
    @Test fun drawerAndFolderLabelsDark() = drawerAndFolderLabels(dark = true)

    @Test fun themedSurfaceLabelsHaveNoWallpaperShadow() {
        val base = Typography().labelSmall
        assertNull(TileLabel.style(base, onWallpaper = false).shadow)
        assertEquals(LabelShadow.shadow, TileLabel.style(base, onWallpaper = true).shadow)
        assertEquals(Color.White, TileLabel.color(onWallpaper = true, scheme = androidx.compose.material3.lightColorScheme()))
    }

    @Test fun b9ReviewReadsOnItsFixedBanner() {
        assertEquals(Color(0xFF263238), B9Colors.background)
        assertTrue("B9 Review ${ratio(B9Colors.review, B9Colors.background)}", ratio(B9Colors.review, B9Colors.background) >= 4.5)
    }

    private fun snackbarActions(dark: Boolean) {
        val (s, action) = scheme(dark)
        assertEquals(s.inversePrimary, action)
        assertTrue("snackbar action ${ratio(action, s.inverseSurface)}", ratio(action, s.inverseSurface) >= 4.5)
        assertTrue("primary is what failed", ratio(s.primary, s.inverseSurface) < 4.5)
    }

    @Test fun snackbarActionsLight() = snackbarActions(dark = false)
    @Test fun snackbarActionsDark() = snackbarActions(dark = true)

    @Test fun editingLayoutOnWhiteWallpaperWithBackdrop() {
        // White text on the 60 % scrim over a pure white wallpaper: the §1.1 worst case.
        val scrimOverWhite = Color(0xFF666666)
        assertTrue(ratio(OnWallpaperButton.content, scrimOverWhite) >= 4.5)
    }
}
