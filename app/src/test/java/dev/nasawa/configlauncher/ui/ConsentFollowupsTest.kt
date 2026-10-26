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
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.graphics.luminance
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
        var detailFor by mutableStateOf<String?>("w-1")
        var reviewOpen by mutableStateOf(true)
        lateinit var host: ConsentSnackHost
        // HomeScreen's own pieces (ConsentSnackHost and its timer, forDetail / forReview / forHome) over the real
        // detail page and review list; the screen itself needs the whole app graph.
        rule.setContent {
            MaterialTheme {
                host = rememberConsentSnackHost()
                if (reviewOpen) ReviewScreen(ConsentUi.review(cfg, s.report(cfg), s.entries()), {}, { detailFor = it }, { reviewOpen = false },
                    snack = host.forReview(detailFor), onUndo = host::undo)
                detailFor?.let { id ->
                    DetailScreen(d, { it }, onTurnOff = { { undone++ } }, onAllow = {}, onForget = {}, onRemoveSite = { null },
                        onDismiss = { detailFor = null }, snack = host.forDetail(id), onSnack = host::show, onUndo = host::undo)
                }
                host.forHome(detailFor, reviewOpen)?.let { ConsentSnackbar(it, host::undo) }
            }
        }
        rule.onNodeWithText(ctx.getString(R.string.detail_turn_off)).performClick()
        val said = ctx.getString(R.string.turned_off_snack, "Weather")
        rule.onNodeWithText(said).fetchSemanticsNode()
        rule.runOnIdle { assertEquals("w-1", host.snack!!.widgetId) }
        // Detail closed: over the review list. List closed: at home. Undo still works there.
        rule.runOnIdle { detailFor = null }
        rule.onNodeWithText(said).fetchSemanticsNode()
        rule.runOnIdle { reviewOpen = false }
        rule.onNodeWithText(said).fetchSemanticsNode()
        rule.onNodeWithText(ctx.getString(R.string.merge_undo)).performClick()
        assertEquals(1, undone)
        rule.runOnIdle { assertNull(host.snack) }
    }

    @Test fun undoSnackTimesOutAfterTenSeconds() {
        lateinit var host: ConsentSnackHost
        rule.setContent { host = rememberConsentSnackHost(); host.snack?.let { ConsentSnackbar(it, host::undo) } }
        val snack = ConsentSnack("x removed", {}, UndoSnack.DURATION_MS, "w-1")
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { host.show(snack) }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle() // the timer starts
        rule.mainClock.advanceTimeBy(UndoSnack.DURATION_MS - 1_000)
        rule.runOnIdle { assertEquals(snack, host.snack) }
        rule.mainClock.advanceTimeBy(2_000)
        rule.runOnIdle { assertNull(host.snack) }
    }

    @Test fun aWidgetsSnackIsNeverShownOnAnotherWidgetsDetailPage() {
        // #173 nit: "example.org removed · Undo" doesn't name its widget, so it stays off other widgets' pages.
        val h = ConsentSnackHost()
        h.show(ConsentSnack("example.org removed", {}, UndoSnack.DURATION_MS, "w-1"))
        assertNotNull(h.forDetail("w-1"))
        assertNull(h.forDetail("w-2"))
        assertNotNull(h.forReview(detailFor = null))
        assertNull(h.forReview(detailFor = "w-2"))
        assertNull(h.forHome(detailFor = null, reviewOpen = true))
        assertNotNull(h.forHome(detailFor = null, reviewOpen = false))
    }

    @Test fun focusedLiveCardTakesEnterAndCentreWithoutTalkBack() {
        val s = allowed()
        val live = ConsentUi.live(cfg, s.report(cfg), "home").single()
        var taps = 0; var longs = 0
        rule.setContent { MaterialTheme { Box(Modifier.size(160.dp)) { LiveCardTarget(live, { longs++ }, {}, onTap = { taps++ }) } } }
        val n = rule.onNodeWithContentDescription(ctx.getString(R.string.card_a11y, "Weather"))
        rule.runOnIdle { n.fetchSemanticsNode().config[SemanticsActions.RequestFocus].action!!.invoke() }
        n.assertIsFocused()
        n.performKeyInput { pressKey(Key.DirectionCenter) }
        n.performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertEquals(2, taps); assertEquals(0, longs) }
        // Held past the key-repeat delay: the long-press sheet, once, and no tap on release.
        n.performKeyInput { keyDown(Key.DirectionCenter); advanceEventTime(1_000); keyUp(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(2, taps); assertEquals(1, longs) }
        // Arrows aren't taken.
        n.performKeyInput { pressKey(Key.DirectionDown) }
        rule.runOnIdle { assertEquals(2, taps); assertEquals(1, longs) }
    }

    @Test fun centreKeyFollowsTheSystemsRepeatAndLongPressFlag() {
        var taps = 0; var longs = 0
        val k = CenterKey({ taps++ }, { longs++ })
        fun ev(action: Int, repeat: Int, flags: Int = 0) = androidx.compose.ui.input.key.KeyEvent(
            android.view.KeyEvent(0, 0, action, android.view.KeyEvent.KEYCODE_DPAD_CENTER, repeat, 0, 0, 0, flags))
        assertTrue(k.onKey(ev(android.view.KeyEvent.ACTION_DOWN, 0))); k.onKey(ev(android.view.KeyEvent.ACTION_UP, 0))
        assertEquals(1, taps)
        k.onKey(ev(android.view.KeyEvent.ACTION_DOWN, 0))
        k.onKey(ev(android.view.KeyEvent.ACTION_DOWN, 1, android.view.KeyEvent.FLAG_LONG_PRESS))
        k.onKey(ev(android.view.KeyEvent.ACTION_DOWN, 2)); k.onKey(ev(android.view.KeyEvent.ACTION_DOWN, 3))
        k.onKey(ev(android.view.KeyEvent.ACTION_UP, 0))
        assertEquals(1, taps); assertEquals(1, longs)
        assertFalse(k.onKey(androidx.compose.ui.input.key.KeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT))))
    }

    @Test fun aSingleLongPressFlagOpensTheSheetOnceWithNoRepeats() {
        // #173 (Grace's #174 device check): a remote that doesn't repeat held keys sends a down with FLAG_LONG_PRESS
        // and no repeats. All three keys open the sheet once from it, and never twice in one hold.
        val flag = android.view.KeyEvent.FLAG_LONG_PRESS
        val down = android.view.KeyEvent.ACTION_DOWN; val up = android.view.KeyEvent.ACTION_UP
        for (code in listOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            var taps = 0; var longs = 0
            val k = CenterKey({ taps++ }, { longs++ })
            fun ev(action: Int, repeat: Int, flags: Int = 0) = androidx.compose.ui.input.key.KeyEvent(
                android.view.KeyEvent(0, 0, action, code, repeat, 0, 0, 0, flags))
            // Plain down, then one flagged down with repeatCount 0, then up.
            assertTrue(k.onKey(ev(down, 0))); assertTrue(k.onKey(ev(down, 0, flag))); assertTrue(k.onKey(ev(up, 0)))
            assertEquals("$code: flagged down after a down", 1, longs); assertEquals(0, taps)
            // A lone flagged down (no plain down first), then up.
            k.onKey(ev(down, 0, flag)); k.onKey(ev(up, 0))
            assertEquals("$code: lone flagged down", 2, longs); assertEquals(0, taps)
            // Flag and repeats in the same hold: still once.
            k.onKey(ev(down, 0)); k.onKey(ev(down, 0, flag)); k.onKey(ev(down, 1, flag)); k.onKey(ev(down, 2)); k.onKey(ev(up, 0))
            assertEquals("$code: flag then repeats", 3, longs)
            k.onKey(ev(down, 0)); k.onKey(ev(down, 1)); k.onKey(ev(down, 2, flag)); k.onKey(ev(up, 0))
            assertEquals("$code: repeats then flag", 4, longs)
            // A bare repeat with no down before it (focus arrived mid-hold) opens nothing.
            k.onKey(ev(down, 3)); k.onKey(ev(up, 0))
            assertEquals("$code: mid-hold repeat", 4, longs); assertEquals(0, taps)
            // And a short press afterwards is still a tap.
            k.onKey(ev(down, 0)); k.onKey(ev(up, 0))
            assertEquals(1, taps); assertEquals(4, longs)
        }
    }

    @Test fun liveCardAsHomeScreenWiresItTakesLongEnterNumpadEnterAndTheFlag() {
        // Grace's #174 missing tests: the card exactly as HomeScreen passes it (no onTap). A short Enter or centre
        // does nothing and is consumed (Grace's L1, unchanged); holding Enter, numpad Enter or centre opens the sheet.
        val s = allowed()
        val live = ConsentUi.live(cfg, s.report(cfg), "home").single()
        var longs = 0; var parent = 0
        lateinit var view: android.view.View
        rule.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            MaterialTheme {
                Box(Modifier.size(160.dp).onKeyEvent { parent++; false }) {
                    LiveCardTarget(live, onLongPress = { longs++ }, onPermissions = {})
                }
            }
        }
        val n = rule.onNodeWithContentDescription(ctx.getString(R.string.card_a11y, "Weather"))
        rule.runOnIdle { n.fetchSemanticsNode().config[SemanticsActions.RequestFocus].action!!.invoke() }
        n.assertIsFocused()
        n.performKeyInput { pressKey(Key.Enter) }
        n.performKeyInput { pressKey(Key.NumPadEnter) }
        n.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(0, longs); assertEquals("short presses are consumed (L1)", 0, parent) }
        n.performKeyInput { keyDown(Key.Enter); advanceEventTime(1_000); keyUp(Key.Enter) }
        rule.runOnIdle { assertEquals(1, longs) }
        n.performKeyInput { keyDown(Key.NumPadEnter); advanceEventTime(1_000); keyUp(Key.NumPadEnter) }
        rule.runOnIdle { assertEquals(2, longs) }
        // A remote with no key repeat: one down, one flagged down, up, through the real view.
        val t = android.os.SystemClock.uptimeMillis()
        rule.runOnIdle {
            view.dispatchKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER, 0))
            view.dispatchKeyEvent(android.view.KeyEvent(t, t + 500, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER, 0, 0, 0, 0, android.view.KeyEvent.FLAG_LONG_PRESS))
            view.dispatchKeyEvent(android.view.KeyEvent(t, t + 900, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER, 0))
        }
        rule.runOnIdle { assertEquals(3, longs); assertEquals(0, parent) }
        // Arrows still reach the parent.
        n.performKeyInput { pressKey(Key.DirectionDown) }
        rule.runOnIdle { assertTrue(parent > 0); assertEquals(3, longs) }
    }

    @Test fun focusRingIsTwoTone() {
        assertNotEquals(FocusRing.RING, FocusRing.OUTLINE)
        assertTrue(FocusRing.RING.luminance() > 0.9f && FocusRing.OUTLINE.luminance() < 0.1f)
    }
}
