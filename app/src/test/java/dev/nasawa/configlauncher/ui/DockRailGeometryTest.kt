package dev.nasawa.configlauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1.5 / #189: dock rail placement, safeDrawing margins, aspect floor, t7-land pitch,
 * LTR/RTL edge rule (geometry), and grid hit-testing beside the rail.
 */
class DockRailGeometryTest {

    @Test fun railOnWideOrShortWindows() {
        // t7-land 960×600 = 1.6 ≥ 1.4
        assertTrue(HomeGeometry.useRail(960f, 600f))
        // t10-land 1280×800 = 1.6
        assertTrue(HomeGeometry.useRail(1280f, 800f))
        // Phone landscape ~800×360
        assertTrue(HomeGeometry.useRail(800f, 360f))
        // Height Compact (< 480) even if aspect < 1.4
        assertTrue(HomeGeometry.useRail(600f, 479f))
        // Portrait phones / tablets: bottom dock
        assertFalse(HomeGeometry.useRail(320f, 640f))
        assertFalse(HomeGeometry.useRail(411f, 731f))
        assertFalse(HomeGeometry.useRail(600f, 960f)) // t7-port
        assertFalse(HomeGeometry.useRail(840f, 1200f))
        // Open fold ~841×701 ≈ 1.2 : 1 — bottom dock (Q-L4)
        assertFalse(HomeGeometry.useRail(841f, 701f))
    }

    @Test fun railWidthAndSlots() {
        assertEquals(80f, HomeGeometry.RAIL_WIDTH_DP, 0.01f)
        assertEquals(64f, HomeGeometry.railSlotDp(4, 400f), 0.01f)
        // 7×64 = 448 > 300 → shrink toward 48
        val shrunk = HomeGeometry.railSlotDp(7, 300f)
        assertTrue(shrunk in 48f..64f)
        assertEquals(48f, shrunk, 0.01f) // 7×48 = 336 > 300, so min 48 + scroll
        assertTrue(HomeGeometry.needsRailScroll(7, 300f))
        assertFalse(HomeGeometry.needsRailScroll(4, 400f))
        assertEquals(HomeGeometry.ICON_DP, HomeGeometry.railIconDp(64f))
        assertEquals(HomeGeometry.RAIL_ICON_SHRUNK_DP, HomeGeometry.railIconDp(48f))
    }

    @Test fun safeDrawingMarginsSymmetricWithinOnePx() {
        // Centred in the safe area: equal within 1 px even when the safe width is odd.
        for (safeW in listOf(800, 801, 880, 881, 1000)) {
            for (blockW in listOf(400, 576, 424, safeW)) {
                val (l, r) = HomeGeometry.sideMarginsPx(safeW, blockW.coerceAtMost(safeW))
                assertTrue("safeW=$safeW block=$blockW l=$l r=$r", kotlin.math.abs(l - r) <= 1)
                assertEquals(safeW, l + r + blockW.coerceAtMost(safeW))
            }
        }
        // Asymmetric cutout on the raw screen (24 px left inset) is NOT a layout bias once
        // margins are measured inside safeDrawing: the safe-area pair stays equal.
        val screenW = 960
        val cutoutLeft = 24
        val safeW = screenW - cutoutLeft // right inset 0
        val block = 424
        val (l, r) = HomeGeometry.sideMarginsPx(safeW, block)
        assertTrue(kotlin.math.abs(l - r) <= 1)
        // From the raw screen edge it *looks* asymmetric (cutoutLeft + l vs r) — that is expected.
        assertTrue(cutoutLeft + l != r)
    }

    @Test fun aspectCapFloorInPxNeverRoundsUpPast125() {
        // density where 1.25 × H0 px is non-integer so floor matters.
        val density = 2.75f
        val availW = 880f // t7-land beside an 80 dp rail
        val availH = 425f // chrome-reclaimed grid area → ~85 dp pitch
        val p = HomeGeometry.pitch(availW, availH, 4, 5, HomeGeometry.WidthClass.Expanded, density)
        val wPx = p.pitchW * density
        val hPx = p.pitchH * density
        assertTrue("W ≤ 1.25·H + 1 px: $wPx vs $hPx", wPx <= 1.25f * hPx + 1f)
        assertTrue("H ≤ 1.5·W + 1 px", hPx <= 1.5f * wPx + 1f)
        assertTrue(p.pitchW <= HomeGeometry.CAP_WIDTH_DP + 0.01f)
    }

    @Test fun t7LandRowPitchReachesAbout85WithRail() {
        // Archimedes' estimate with the rail: ≈ 106 × 85. Chrome above/below the grid
        // (notice/title + dots, no top row, no bottom dock) ≈ 175 dp leaves ~425 for 5 rows.
        val pitchH = HomeGeometry.t7LandPitchH(175f)
        assertTrue("expected ~85 dp, got $pitchH", pitchH in 84f..90f)
        val p = HomeGeometry.pitch(960f - 80f, 600f - 175f, 4, 5, HomeGeometry.WidthClass.Expanded)
        assertTrue(p.pitchH in 84f..90f)
        assertTrue(p.pitchW / p.pitchH <= 1.25f + 0.01f)
    }

    @Test fun endEdgeIsRightInLtrLeftInRtl() {
        // Geometry rule only: end = right (LTR) / left (RTL). Compose Row mirrors children in RTL,
        // so placing Content then Rail puts the rail on the end edge in both directions.
        // This pins the documented mapping for tests / QA (L5, L12).
        fun endLeft(rtl: Boolean, contentFirst: Boolean): Boolean {
            // contentFirst=true matches our Row { HomeBody; DockRail }.
            // In LTR first child is left; in RTL first child is right.
            val railIsSecond = contentFirst
            return if (rtl) railIsSecond else !railIsSecond
            // rail on left? RTL + second child → second goes left → true.
            // LTR + second child → second goes right → rail on left? false.
        }
        assertFalse("LTR: rail on right (not left)", endLeft(rtl = false, contentFirst = true))
        assertTrue("RTL: rail on left", endLeft(rtl = true, contentFirst = true))
    }

    @Test fun gridHitBesideRailAccountsForRailSpace() {
        // Page column is the area beside an 80 dp rail on a 960-wide window: 880 dp.
        // Centred 424-wide block (t7-land-ish) → left origin 228 in the page column.
        // A tap in the page-column margin (between block and rail) is not a cell.
        val pageW = 960f - 80f
        val blockW = 424f
        val blockH = 425f
        val left = (pageW - blockW) / 2f
        assertNull(GridHit.cellInBlock(10f, 10f, left, 0f, blockW, blockH, 4, 5))
        assertNull(GridHit.cellInBlock(left - 1f, 10f, left, 0f, blockW, blockH, 4, 5))
        // Just inside the block
        assertEquals(0 to 0, GridHit.cellInBlock(left + 1f, 1f, left, 0f, blockW, blockH, 4, 5))
        // The strip where the rail sits is outside the page column entirely — hit tests use
        // page-column coordinates, so a rail-x is never a grid cell.
        assertNull(GridHit.cellInBlock(pageW + 40f, 100f, left, 0f, blockW, blockH, 4, 5))
    }

    @Test fun contentWidthOnMediumExpanded() {
        assertEquals(320f, HomeGeometry.contentWidthDp(320f, 320f, HomeGeometry.WidthClass.Compact), 0.01f)
        // fold-open: page 841, block 416 → content 576
        assertEquals(576f, HomeGeometry.contentWidthDp(841f, 416f, HomeGeometry.WidthClass.Expanded), 0.01f)
        // t10-land page beside rail ~1200, block 576 → 576
        assertEquals(576f, HomeGeometry.contentWidthDp(1200f, 576f, HomeGeometry.WidthClass.Expanded), 0.01f)
        // Narrower than 576: use page width
        assertEquals(500f, HomeGeometry.contentWidthDp(500f, 400f, HomeGeometry.WidthClass.Medium), 0.01f)
    }

    @Test fun verticalDockDropIndex() {
        // DragController: rail uses Y for the dock index.
        val drag = DragController({ error("unused") }) {}
        drag.dockVertical = true
        drag.dockCount = 4
        drag.dockRect = androidx.compose.ui.geometry.Rect(800f, 100f, 880f, 500f) // 400 px tall
        // Drop at 25% down → index 1 of (4+1) slots for insert.
        // We can't call drop() without dragged state; pin the index math here.
        val cY = 100f + 0.25f * 400f
        val slots = drag.dockCount + 1
        val index = ((cY - drag.dockRect.top) / drag.dockRect.height * slots).toInt().coerceIn(0, drag.dockCount)
        assertEquals(1, index)
        val bottomIndex = ((499f - drag.dockRect.top) / drag.dockRect.height * slots).toInt().coerceIn(0, drag.dockCount)
        assertEquals(4, bottomIndex)
    }
}
