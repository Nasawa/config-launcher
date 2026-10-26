package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #182 (Pragma, ux.md §12.1): the top-row `Apps`, `⋮` and `Done` buttons sit on the wallpaper, so in both themes they
 * are white with the home-label shadow, plus the label scrim only while `Label backdrop` is on.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WallpaperButtonTest {
    @get:Rule val rule = createComposeRule()

    private data class Seen(val homeDark: Boolean, val color: Color, val shadow: Shadow?)

    /** Inside the home theme, as HomeScreen draws it: the colour and text style the button's label gets. */
    private fun seen(backdrop: Boolean = false): Seen {
        var out: Seen? = null
        rule.setContent {
            val dark = homeDark()
            HomeThemed(dark) {
                WallpaperTextButton({}, backdrop) { out = Seen(dark, LocalContentColor.current, LocalTextStyle.current.shadow); Text("Apps") }
            }
        }
        rule.waitForIdle()
        return out!!
    }

    @Test @Config(qualifiers = "night") fun whiteWithTheLabelShadowInDarkMode() {
        val s = seen()
        assertTrue(s.homeDark)
        assertEquals(Color.White, s.color)
        assertEquals("the home-label shadow", LabelShadow.shadow, s.shadow)
    }

    @Test @Config(qualifiers = "notnight") fun whiteWithTheLabelShadowInLightMode() {
        val s = seen()
        assertFalse(s.homeDark)
        assertEquals("not the light primary #6750A4", Color.White, s.color)
        assertEquals("the home-label shadow", LabelShadow.shadow, s.shadow)
    }

    @Test @Config(qualifiers = "notnight") fun theShadowStaysWithTheBackdropOn() {
        val s = seen(backdrop = true)
        assertEquals(Color.White, s.color); assertEquals(LabelShadow.shadow, s.shadow)
    }

    /** Share of the button's pixels that are dark (the scrim) when drawn over a white wallpaper. */
    private fun darkShare(backdrop: Boolean): Double {
        rule.setContent {
            Box(Modifier.background(Color.White)) {
                HomeThemed(false) { WallpaperTextButton({}, backdrop, Modifier.testTag("b")) { Text("Apps") } }
            }
        }
        rule.waitForIdle()
        val px = rule.onNodeWithTag("b").captureToImage().toPixelMap()
        var dark = 0
        for (x in 0 until px.width) for (y in 0 until px.height) if (px[x, y].red < 0.5f) dark++
        return dark.toDouble() / (px.width * px.height)
    }

    @Test fun scrimOnlyWithLabelBackdropOn() {
        val off = darkShare(false)
        assertTrue("no scrim with Label backdrop off: $off", off < 0.02)
    }

    @Test fun scrimWithLabelBackdropOn() {
        val on = darkShare(true)
        assertTrue("the 60 % scrim behind the text with Label backdrop on: $on", on > 0.15)
    }
}
