package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.ResolvedPage
import dev.nasawa.configlauncher.model.TitleBarModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Grace's crash on #167 at da3849b (A51 config at 320 dp): rendering the real [TitleBarRow], not just the ladder
 * function. Step 1's ▾-only slot laid itself out at the incoming max width, which is infinite while the ladder
 * measures intrinsics, and threw `Size(2147483647 x …) is out of range`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h711dp-xhdpi")
class TitleBarRenderTest {
    @get:Rule val rule = createComposeRule()

    /** The title bar and pages of Grace's A51 config (cl-qa dev6-recheck/a51-launcher.json). */
    private val a51 = """{"schemaVersion":1,"grid":{"columns":4,"rows":5},
        "pages":[{"id":"home","title":"Home","items":[]},{"id":"second","title":"Second","items":[]}],
        "elements":[{"id":"t-main","type":"titleBar","anchor":"top",
          "content":{"slots":{"start":"{date:EEE d MMM yyyy}","center":"{page}","end":"{time:HH:mm}"}},
          "style":{"size":"large","color":"light","padding":{"horizontal":16,"vertical":6},"backdrop":{"color":"#000000","opacity":60,"radius":12}}}]}"""
    private val bar = TitleBarModel.forPage((ConfigCodec.decode(a51) as ParseResult.Ok).config, "home")!!
    private val pages = listOf(ResolvedPage("home", "Home", emptyList()), ResolvedPage("second", "Second", emptyList()))

    @Test fun a51BarRendersAt320dp() {
        val fits = mutableListOf<String?>()
        rule.setContent { MaterialTheme { TitleBarRow(bar, pages, 0, enabled = true, labelBackdrop = false, onFit = { fits += it }) {} } }
        rule.waitForIdle()
        // At 320 dp this bar needs step 2 (Grace's run): the ▾-only rung before it was measured without crashing.
        assertTrue("step 2 at 320 dp: $fits", fits.lastOrNull()?.contains("fit step 2 of 4") == true)
    }

    /** Every width from 411 dp down to 120 dp, so every rung of the ladder, step 1's ▾-only one included, is drawn. */
    @Test fun a51BarRendersAtEveryWidth() {
        var width by mutableIntStateOf(411)
        val steps = sortedSetOf<String>()
        rule.setContent {
            MaterialTheme {
                Box(Modifier.requiredWidth(width.dp)) {
                    TitleBarRow(bar, pages, 0, enabled = true, labelBackdrop = false, onFit = { w -> steps += (w?.substringAfter("fit step ")?.take(1) ?: "0") }) {}
                }
            }
        }
        // Every narrower rung is tried only after step 1's ▾-only one, so reaching steps 2–4 means it was measured and drawn.
        for (w in 411 downTo 120 step 3) { width = w; rule.waitForIdle() }
        assertEquals(sortedSetOf("0", "2", "3", "4"), steps)
    }

    /**
     * Grace's A51 widths (dev6-a51/widths): at 320 dp the {page} 48 dp touch area overlaps the date and the clock.
     * Pragma: that's allowed only because neither is tappable (ux.md §15 #78: only the {page} slot is, in this release).
     * The touch area then reaches over the 8 dp gap and onto each neighbour, never past what makes it 48 dp.
     */
    @Test fun a51PageTouchAreaAt320dpOnlyOverlapsSlotsThatArentTappable() {
        rule.setContent { MaterialTheme { TitleBarRow(bar, pages, 0, enabled = true, labelBackdrop = false, onFit = {}) {} } }
        rule.waitForIdle()
        // The rungs the ladder measured but didn't pick stay composed (unplaced); only the placed ones are on screen.
        val shown = rule.onAllNodes(isRoot().not()).fetchSemanticsNodes().filter { it.layoutInfo.isPlaced && it.boundsInRoot.width > 0 }
        fun said(n: SemanticsNode) = n.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }.orEmpty().joinToString()
        val taps = shown.filter { SemanticsActions.OnClick in it.config }
        assertEquals("only the {page} slot is tappable", listOf("Home, Pages"), taps.map(::said))
        val date = shown.single { said(it) == "Mon 26 Oct 2026" }.boundsInRoot
        val clock = shown.single { said(it).matches(Regex("\\d\\d:\\d\\d")) }.boundsInRoot
        val page = taps.single().touchBoundsInRoot
        val dp = rule.density.density
        // Over a neighbour that isn't tappable it may overlap it (as on Grace's A51), but only by what 48 dp needs.
        val over = maxOf(date.right - page.left, 0f) + maxOf(page.right - clock.left, 0f)
        assertTrue("it overlaps the date or clock here: $date $page $clock", over > 0)
        assertEquals("and it is 48 dp, no wider", 48 * dp, page.width, 1f)
    }
}
