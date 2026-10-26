package dev.nasawa.configlauncher.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.consent.ConsentStore
import dev.nasawa.configlauncher.consent.ConsentUi
import dev.nasawa.configlauncher.model.LauncherConfig
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #171 and Grace's #164 recheck: chip tap size, live-card focus, and the detail page's Undo outliving the page. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w320dp-h711dp-xhdpi")
class ConsentFollowupsTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private val doc = """{"schemaVersion":1,"grid":{"columns":4,"rows":6},"pages":[{"id":"home","items":[]}],"elements":[
        {"id":"w-1","type":"card","requires":4,"page":"home","x":0,"y":0,"w":2,"h":2,"title":"Weather",
         "capabilities":[{"kind":"data.file"}],"layouts":{"2x2":[{"type":"text","text":"hi"}]}}]}"""
    private val cfg: LauncherConfig = (ConfigCodec.decode(doc) as ParseResult.Ok).config
    private fun allowed() = ConsentStore(tmp.newFolder()).also { s -> s.settle(cfg); s.allow("w-1", s.report(cfg).single().lines, cfg) }

    @Test fun countChipTapAreaIsAtLeast48By48dp() {
        var taps = 0
        rule.setContent { MaterialTheme { ReviewCountChip(3, { taps++ }) } }
        val said = ctx.resources.getQuantityString(R.plurals.chip_review_a11y, 3, 3)
        val node = rule.onNodeWithContentDescription(said).fetchSemanticsNode()
        val density = ctx.resources.displayMetrics.density
        // The clickable node itself, not Compose's touch-size expansion around a smaller one (R1: it was 48 × 20).
        assertTrue("height ${node.boundsInRoot.height / density} dp", node.boundsInRoot.height / density >= 48f - 0.5f)
        assertTrue("width ${node.boundsInRoot.width / density} dp", node.boundsInRoot.width / density >= 48f - 0.5f)
        assertTrue(SemanticsActions.OnClick in node.config)
        rule.onNodeWithContentDescription(said).performClick()
        assertEquals(1, taps)
    }

    @Test fun liveCardIsFocusableWithNoClickAction() {
        val s = allowed()
        val live = ConsentUi.live(cfg, s.report(cfg), "home").single()
        var longs = 0
        rule.setContent { MaterialTheme { Box(Modifier.size(160.dp)) { LiveCardTarget(live, { longs++ }, {}) } } }
        val said = ctx.getString(R.string.card_a11y, "Weather")
        val n = rule.onNodeWithContentDescription(said)
        val c = n.fetchSemanticsNode().config
        assertTrue("focusable (R2)", SemanticsProperties.Focused in c)
        assertTrue(SemanticsActions.RequestFocus in c)
        assertFalse("no fake click action", SemanticsActions.OnClick in c)
        assertTrue(SemanticsActions.OnLongClick in c)
        rule.runOnIdle { c[SemanticsActions.RequestFocus].action!!.invoke() }
        n.assertIsFocused()
        rule.runOnIdle { c[SemanticsActions.OnLongClick].action!!.invoke() }
        assertEquals(1, longs)
    }

    @Test fun turnOffUndoOutlivesTheDetailPage() {
        val s = allowed()
        val d = ConsentUi.detail(cfg, s.report(cfg).single(), s.entry("w-1"))!!
        var undone = 0
        var open by mutableStateOf(true)
        var snack by mutableStateOf<ConsentSnack?>(null)
        val undo = { snack?.let { x -> snack = null; x.undo?.invoke() }; Unit }
        // The HomeScreen wiring in small: the caller holds the snack; the page sets and shows it, home shows it after.
        rule.setContent {
            MaterialTheme {
                if (open) DetailScreen(d, { it }, onTurnOff = { { undone++ } }, onAllow = {}, onForget = {}, onRemoveSite = { null },
                    onDismiss = { open = false }, snack = snack, onSnack = { snack = it }, onUndo = undo)
                else snack?.let { ConsentSnackbar(it, undo) }
            }
        }
        rule.onNodeWithText(ctx.getString(R.string.detail_turn_off)).performClick()
        val said = ctx.getString(R.string.turned_off_snack, "Weather")
        rule.onNodeWithText(said).fetchSemanticsNode()
        rule.runOnIdle { open = false }
        // Page gone: the Undo is still there and still works.
        rule.onNode(hasText(said)).fetchSemanticsNode()
        rule.onNodeWithText(ctx.getString(R.string.merge_undo)).performClick()
        assertEquals(1, undone)
        rule.runOnIdle { assertNull(snack) }
    }
}
