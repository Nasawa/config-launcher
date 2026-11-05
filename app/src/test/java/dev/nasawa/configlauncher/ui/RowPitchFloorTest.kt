package dev.nasawa.configlauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #235, ux.md §1.5 Row-pitch floors: 320×512 dp, 6 rows — no overlap, no clipped label, all rows shown. */
class RowPitchFloorTest {
    private val labelLine = 17f

    @Test fun belowSixtyClampsIconAndHidesLabels() {
        assertEquals(44, HomeGeometry.gridIconDp(HomeGeometry.IconStep.Icon48NoLabel, 48f))
        assertEquals(28, HomeGeometry.gridIconDp(HomeGeometry.IconStep.Icon48NoLabel, 32f))
        assertFalse(HomeGeometry.gridShowsLabel(HomeGeometry.IconStep.Icon48Label, 59f))
        assertEquals(56, HomeGeometry.gridIconDp(HomeGeometry.IconStep.Icon56Label, 84f))
        assertTrue(HomeGeometry.gridShowsLabel(HomeGeometry.IconStep.Icon56Label, 84f))
    }

    @Test fun compact320x512SixRowsNeverOverlap() {
        // Grid area left after chrome varies with density / title bar; sweep every height 6 rows of ≥ 32 dp can get.
        var h = 6 * 32f
        while (h <= 512f) {
            val p = HomeGeometry.pitch(320f, h, 4, 6, HomeGeometry.widthClass(320f))
            val cellH = (p.blockH - 16f) / 6
            val step = HomeGeometry.iconStep(p.pitchW - HomeGeometry.CONTENT_PAD_DP, cellH - HomeGeometry.CONTENT_PAD_DP, labelLine)
            val icon = HomeGeometry.gridIconDp(step, cellH)
            val label = HomeGeometry.gridShowsLabel(step, cellH)
            assertTrue("all 6 rows fit at $h", 6 * cellH <= h)
            // Icon centred in its cell with a gap: row r spans [r·cellH + (cellH−icon)/2, …+icon]; neighbours can't meet.
            assertTrue("icon $icon fits cell $cellH with a gap at $h", icon + (if (cellH < 60f) 4f else 0f) <= cellH)
            if (label) assertTrue("label not clipped at $h", icon + labelLine <= cellH - HomeGeometry.CONTENT_PAD_DP)
            if (cellH < 60f) assertFalse("labels hidden below 60 at $h", label)
            h += 1f
        }
    }
}
