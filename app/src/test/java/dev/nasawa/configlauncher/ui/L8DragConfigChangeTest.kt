package dev.nasawa.configlauncher.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.model.AppItem
import dev.nasawa.configlauncher.model.Edit
import dev.nasawa.configlauncher.model.ResolvedItem
import dev.nasawa.configlauncher.store.ConfigEngine
import dev.nasawa.configlauncher.store.LastGoodStore
import dev.nasawa.configlauncher.store.LocalFileSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * test-plan §20 L8 (#197): drag on a config change → cancelled, nothing written, item in old cell.
 *
 * The production path on rotate is Activity recreation (no configChanges in the manifest): the
 * window cancels the pointer (a lift that arrives consumed) and the gesture coroutine is cancelled;
 * both must end in [DragController.cancel], never [DragController.drop] (#214). This JVM test pins
 * the cancel contract and the pointer-cancel path through `Modifier.tile`; the whole app through a
 * recreate is [HomeWindowChangeTest]. Full rotate-mid-drag on a device is androidTest
 * [dev.nasawa.configlauncher.L8RotateMidDragTest].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class L8DragConfigChangeTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val rule = androidx.compose.ui.test.junit4.createComposeRule()

    @Test fun pointerCancelMidDragCancelsAndWritesNothing() {
        val e = engine()
        val submitted = mutableListOf<Edit>()
        var cancelled = 0
        val drag = DragController({ e }, onCancel = { cancelled++ }) { submitted += it }
        // Any drop would land in the grid and submit a MoveToCell.
        drag.gridRect = Rect(0f, 0f, 10_000f, 10_000f); drag.currentPageId = "p"
        val item = ResolvedItem(AppItem(id = "a", packageName = "p.a"), dev.nasawa.configlauncher.model.InstalledApp("p.a", "p.a.Main", "Alpha"))
        val apps = dev.nasawa.configlauncher.apps.InstalledApps(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        rule.setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(400.dp)) {
                with(drag) { Tile(item, apps, androidx.compose.ui.Modifier.tile(item, true, {}, {})) }
            }
        }
        rule.onNodeWithText("Alpha").performTouchInput {
            down(center); advanceEventTime(1_000); moveBy(Offset(100f, 100f)); moveBy(Offset(100f, 100f))
        }
        rule.waitForIdle()
        assertTrue("dragging", e.dragging)
        rule.onNodeWithText("Alpha").performTouchInput { cancel() }
        rule.waitForIdle()
        assertTrue("a pointer cancel is not a drop", submitted.isEmpty())
        assertEquals(1, cancelled)
        assertNull(drag.dragged)
        assertFalse(e.dragging)
    }

    @Test fun tileLeavingCompositionMidDragCancels() {
        val e = engine()
        val submitted = mutableListOf<Edit>()
        var cancelled = 0
        val drag = DragController({ e }, onCancel = { cancelled++ }) { submitted += it }
        drag.gridRect = Rect(0f, 0f, 10_000f, 10_000f); drag.currentPageId = "p"
        val item = ResolvedItem(AppItem(id = "a", packageName = "p.a"), dev.nasawa.configlauncher.model.InstalledApp("p.a", "p.a.Main", "Alpha"))
        val apps = dev.nasawa.configlauncher.apps.InstalledApps(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        var shown by androidx.compose.runtime.mutableStateOf(true)
        rule.setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(400.dp)) {
                if (shown) with(drag) { Tile(item, apps, androidx.compose.ui.Modifier.tile(item, true, {}, {})) }
            }
        }
        rule.onNodeWithText("Alpha").performTouchInput {
            down(center); advanceEventTime(1_000); moveBy(Offset(100f, 100f)); moveBy(Offset(100f, 100f))
        }
        rule.waitForIdle()
        assertTrue(e.dragging)
        shown = false
        rule.waitForIdle()
        assertTrue(submitted.isEmpty())
        assertEquals(1, cancelled)
        assertFalse("the engine's drag hold is released", e.dragging)
    }

    private fun engine(): ConfigEngine =
        ConfigEngine(LocalFileSource(tmp.newFolder("src")), LastGoodStore(tmp.newFolder("lg")), stabilityMs = 0)

    @Test fun cancelMidDragSubmitsNothingAndClearsDragging() {
        val e = engine()
        val submitted = mutableListOf<Edit>()
        var cancelled = 0
        val drag = DragController({ e }, onCancel = { cancelled++ }, submit = { submitted += it })
        val item = ResolvedItem(AppItem("a", "p.a", x = 0, y = 0), null)
        drag.start(item, Rect(0f, 0f, 100f, 100f))
        drag.move(Offset(40f, 10f))
        assertTrue(e.dragging)
        assertEquals(item, drag.dragged)

        drag.cancel() // config-change path

        assertNull(drag.dragged)
        assertFalse(e.dragging)
        assertTrue("no MoveTo* written on cancel", submitted.isEmpty())
        assertEquals(1, cancelled)
    }

    @Test fun dropStillSubmitsWhenNotCancelled() {
        val e = engine()
        val submitted = mutableListOf<Edit>()
        val drag = DragController({ e }, submit = { submitted += it })
        drag.gridRect = Rect(0f, 0f, 400f, 500f)
        drag.currentPageId = "home"
        drag.grid = dev.nasawa.configlauncher.model.Grid(4, 5)
        val item = ResolvedItem(AppItem("a", "p.a", x = 0, y = 0), null)
        drag.start(item, Rect(0f, 0f, 100f, 100f))
        drag.move(Offset(250f, 250f)) // into grid
        drag.drop()
        assertEquals(1, submitted.size)
        assertTrue(submitted[0] is Edit.MoveToCell)
        assertFalse(e.dragging)
    }
}
