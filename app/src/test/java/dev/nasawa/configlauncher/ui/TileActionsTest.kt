package dev.nasawa.configlauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** ux.md §12: custom actions per place. */
class TileActionsTest {
    @Test fun pageItemsGetMoveDockAndRemove() =
        assertEquals(listOf(TileAction.MOVE_TO, TileAction.MOVE_TO_DOCK, TileAction.REMOVE), TileActions.of(TilePlace.PAGE, dockFull = false))

    @Test fun fullDockDropsMoveToDock() =
        assertEquals(listOf(TileAction.MOVE_TO, TileAction.REMOVE), TileActions.of(TilePlace.PAGE, dockFull = true))

    @Test fun dockAndFolder() {
        assertEquals(listOf(TileAction.MOVE_TO, TileAction.REMOVE), TileActions.of(TilePlace.DOCK, dockFull = true))
        assertEquals(listOf(TileAction.REMOVE), TileActions.of(TilePlace.FOLDER, dockFull = false))
    }

    @Test fun appsAlsoGetHide() {
        assertEquals(listOf(TileAction.MOVE_TO, TileAction.MOVE_TO_DOCK, TileAction.REMOVE, TileAction.HIDE), TileActions.of(TilePlace.PAGE, false, canHide = true))
        assertEquals(listOf(TileAction.REMOVE, TileAction.HIDE), TileActions.of(TilePlace.FOLDER, false, canHide = true))
    }
}
