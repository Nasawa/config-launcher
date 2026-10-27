package dev.nasawa.configlauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ux.md §1.5: cell cap, centring and icon ladder across Compact / Medium / Expanded and landscape. */
class HomeGeometryTest {

    @Test fun widthClassesAtBoundaries() {
        assertEquals(HomeGeometry.WidthClass.Compact, HomeGeometry.widthClass(320f))
        assertEquals(HomeGeometry.WidthClass.Compact, HomeGeometry.widthClass(411f))
        assertEquals(HomeGeometry.WidthClass.Compact, HomeGeometry.widthClass(599f))
        assertEquals(HomeGeometry.WidthClass.Medium, HomeGeometry.widthClass(600f))
        assertEquals(HomeGeometry.WidthClass.Medium, HomeGeometry.widthClass(839f))
        assertEquals(HomeGeometry.WidthClass.Expanded, HomeGeometry.widthClass(840f))
        assertEquals(HomeGeometry.WidthClass.Expanded, HomeGeometry.widthClass(1280f))
    }

    @Test fun compactPhoneFillsAndDoesNotCap() {
        // 320 and 411 dp phones: cells fill the width; 411/4 ≈ 103 < 144, so a cap would be a no-op anyway.
        for (w in listOf(320f, 411f)) {
            val p = HomeGeometry.pitch(w, 640f, 4, 5, HomeGeometry.WidthClass.Compact)
            assertFalse(p.capped)
            assertEquals(w / 4f, p.pitchW, 0.01f)
            assertEquals(640f / 5f, p.pitchH, 0.01f)
            assertEquals(w, p.blockW, 0.01f)
            assertEquals(640f, p.blockH, 0.01f)
            val (ox, oy) = HomeGeometry.blockOrigin(w, 640f, p)
            assertEquals(0f, ox, 0.01f)
            assertEquals(0f, oy, 0.01f)
        }
    }

    @Test fun medium600CapsAt144AndCentres() {
        // t7-port-ish: 600 × 800 grid area, 4 × 5 → pitchW = 144, block 576, 12 dp side margins.
        val p = HomeGeometry.pitch(600f, 800f, 4, 5, HomeGeometry.WidthClass.Medium)
        assertTrue(p.capped)
        assertEquals(144f, p.pitchW, 0.01f)
        assertEquals(576f, p.blockW, 0.01f)
        assertTrue("pitchH ≤ H0", p.pitchH <= 800f / 5f + 0.01f)
        assertTrue("W/H ≤ 1.25", p.pitchW / p.pitchH <= 1.25f + 0.01f)
        assertTrue("H/W ≤ 1.5", p.pitchH / p.pitchW <= 1.5f + 0.01f)
        val (ox, oy) = HomeGeometry.blockOrigin(600f, 800f, p)
        assertEquals((600f - 576f) / 2f, ox, 0.01f)
        assertEquals((800f - p.blockH) / 2f, oy, 0.01f)
        assertEquals(ox, 600f - p.blockW - ox, 0.01f) // equal side margins
    }

    @Test fun expanded840CapsAndCentres() {
        val p = HomeGeometry.pitch(840f, 700f, 4, 5, HomeGeometry.WidthClass.Expanded)
        assertTrue(p.capped)
        assertTrue(p.pitchW <= 144f + 0.01f)
        assertEquals(4 * p.pitchW, p.blockW, 0.01f)
        val (ox, _) = HomeGeometry.blockOrigin(840f, 700f, p)
        assertEquals((840f - p.blockW) / 2f, ox, 0.01f)
    }

    @Test fun landscapeShortRowsStillRespectAspectCap() {
        // Tablet landscape-ish avail: 960 × 400 for the grid (after chrome), 4 × 5.
        // H0 = 80; pitchW = min(240, 144, max(64, 1.25×80)) = min(240, 144, 100) = 100; pitchH = min(80, 150) = 80.
        val p = HomeGeometry.pitch(960f, 400f, 4, 5, HomeGeometry.WidthClass.Expanded)
        assertTrue(p.capped)
        assertEquals(100f, p.pitchW, 0.01f)
        assertEquals(80f, p.pitchH, 0.01f)
        assertEquals(400f, p.blockW, 0.01f)
        assertEquals(400f, p.blockH, 0.01f)
        assertTrue(p.pitchW <= 144f)
        assertTrue(p.pitchW / p.pitchH <= 1.25f + 0.01f)
        val (ox, oy) = HomeGeometry.blockOrigin(960f, 400f, p)
        assertEquals((960f - 400f) / 2f, ox, 0.01f)
        assertEquals(0f, oy, 0.01f)
    }

    @Test fun iconLadderSteps() {
        // Content tall enough for 56 + 17 label.
        assertEquals(HomeGeometry.IconStep.Icon56Label, HomeGeometry.iconStep(80f, 80f, 17f))
        // 56 + label too tall, 48 + label fits (worked example ~73.6 content, 17 label).
        assertEquals(HomeGeometry.IconStep.Icon48Label, HomeGeometry.iconStep(80f, 72f, 17f))  // 56+17=73 misses; 48+17=65 fits
        // Only 48 with label hidden.
        assertEquals(HomeGeometry.IconStep.Icon48NoLabel, HomeGeometry.iconStep(80f, 50f, 17f))
        // Narrow cell: icon must fit the content width too.
        assertEquals(HomeGeometry.IconStep.Icon48Label, HomeGeometry.iconStep(50f, 80f, 17f))
        assertEquals(HomeGeometry.IconStep.Icon48NoLabel, HomeGeometry.iconStep(40f, 80f, 17f))
    }

    @Test fun preferredIconIs56() {
        assertEquals(56, HomeGeometry.ICON_DP)
        assertEquals(56, TileLabel.ICON_DP)
        assertEquals(48, HomeGeometry.ICON_FALLBACK_DP)
    }

    @Test fun aspectFloorAtNonIntegerDensity() {
        // #197 L3: W derived from H in px with floor; +1 px slack allowed on the check.
        val d = 3.5f
        val p = HomeGeometry.pitch(880f, 400f, 4, 5, HomeGeometry.WidthClass.Expanded, d)
        val wPx = p.pitchW * d
        val hPx = p.pitchH * d
        assertTrue(wPx <= 1.25f * hPx + 1f)
        assertTrue(hPx <= 1.5f * wPx + 1f)
    }

    /**
     * Grace #201 L13: cards-t10-land pitch is 144×120 (Medium/Expanded cap). Uncapped hosts would be
     * 2×2 = 288×240 and 4×1 = 576×120, past k ≤ 1.5 → span × 80 × 1.5 (240×240 / 480×120).
     */
    @Test fun widgetHostRespectsL13CapOnCardsT10Land() {
        val cellW = 144f
        val cellH = 120f
        // Uncapped would exceed; host must clamp each axis.
        assertEquals(288f, cellW * 2f, 0.01f)
        assertEquals(576f, cellW * 4f, 0.01f)
        val (w22, h22) = HomeGeometry.widgetHostSizeDp(2, 2, cellW, cellH)
        assertEquals(2f * HomeGeometry.CELL_REF_DP * HomeGeometry.WIDGET_K_MAX, w22, 0.01f) // 240
        assertEquals(2f * HomeGeometry.CELL_REF_DP * HomeGeometry.WIDGET_K_MAX, h22, 0.01f) // 240
        val (ox22, oy22) = HomeGeometry.widgetHostOffsetDp(2, 2, cellW, cellH)
        assertEquals((288f - 240f) / 2f, ox22, 0.01f)
        assertEquals(0f, oy22, 0.01f) // height already at cap

        val (w41, h41) = HomeGeometry.widgetHostSizeDp(4, 1, cellW, cellH)
        assertEquals(4f * HomeGeometry.CELL_REF_DP * HomeGeometry.WIDGET_K_MAX, w41, 0.01f) // 480
        assertEquals(120f, h41, 0.01f) // pitchH already ≤ 80 × 1.5
        val (ox41, oy41) = HomeGeometry.widgetHostOffsetDp(4, 1, cellW, cellH)
        assertEquals((576f - 480f) / 2f, ox41, 0.01f)
        assertEquals(0f, oy41, 0.01f)
    }

    @Test fun widgetHostUncappedWhenPitchUnderKMax() {
        // Compact phone pitch 80×80: span × pitch stays under the cap.
        val (w, h) = HomeGeometry.widgetHostSizeDp(2, 2, 80f, 80f)
        assertEquals(160f, w, 0.01f)
        assertEquals(160f, h, 0.01f)
        val (ox, oy) = HomeGeometry.widgetHostOffsetDp(2, 2, 80f, 80f)
        assertEquals(0f, ox, 0.01f)
        assertEquals(0f, oy, 0.01f)
    }
}
