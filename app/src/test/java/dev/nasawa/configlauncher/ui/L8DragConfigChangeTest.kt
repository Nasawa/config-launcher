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
 * pointer coroutine ends and [DragController.cancel] runs. This JVM test pins the cancel contract
 * that path must honour. Full rotate-mid-drag on a device is androidTest
 * [dev.nasawa.configlauncher.L8RotateMidDragTest].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class L8DragConfigChangeTest {
    @get:Rule val tmp = TemporaryFolder()

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
