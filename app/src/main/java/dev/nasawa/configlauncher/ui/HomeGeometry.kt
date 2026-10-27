package dev.nasawa.configlauncher.ui

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.max

/**
 * ux.md §1.5: window width class, capped/centred cell pitch, the icon ladder, and the dock rail.
 * Pure math so unit tests can pin 320 / 411 / 600 / 840 dp and landscape without a device.
 */
object HomeGeometry {
    /** Compact < 600, Medium 600–839, Expanded ≥ 840 (current window width, dp). */
    enum class WidthClass { Compact, Medium, Expanded }

    fun widthClass(widthDp: Float): WidthClass = when {
        widthDp < 600f -> WidthClass.Compact
        widthDp < 840f -> WidthClass.Medium
        else -> WidthClass.Expanded
    }

    /** Cap defaults from §1.5 (overrideable later via windowOverrides.cellCap). */
    const val CAP_WIDTH_DP = 144f
    const val MIN_CELL_DP = 64f
    const val MAX_W_OVER_H = 1.25f
    const val MAX_H_OVER_W = 1.5f

    /** Preferred icon; 48 dp is only the ladder fallback. */
    const val ICON_DP = 56
    const val ICON_FALLBACK_DP = 48

    /**
     * Cell content box = pitch − 4 dp cell padding − 8 dp tile padding (§1.5 icon ladder).
     * Matches PageGrid's 2.dp cell pad and Tile's 4.dp pad.
     */
    const val CONTENT_PAD_DP = 12f

    /** §1.5 content-width floor for chrome rows on Medium/Expanded (banner, notice, title bar, bottom dock). */
    const val CONTENT_WIDTH_FLOOR_DP = 576f

    /** Rail when width÷height ≥ 1.4, or height is Compact (< 480 dp). */
    const val RAIL_ASPECT = 1.4f
    const val HEIGHT_COMPACT_DP = 480f
    const val RAIL_WIDTH_DP = 80f
    /**
     * Horizontal pad around a measured Apps/Done label when sizing the rail (§1.5): TextButton
     * content padding (12 dp × 2). The rail Column's own 4 dp inset is inside this width.
     */
    const val RAIL_HEAD_PAD_DP = 24f
    /**
     * Advisory chrome above/below the grid on t7-land for L4 pitch math only (title + dots, no
     * banner / top row / bottom dock). Runtime measures real height; this must never feed
     * [TitleBarLayout.fitWarning] / `warn_titlebar_fit` (Q-A12).
     */
    const val T7_LAND_CHROME_ESTIMATE_DP = 175f
    const val RAIL_SLOT_DP = 64f
    const val RAIL_SLOT_MIN_DP = 48f
    /** Icon inside a shrunk 48 dp rail slot (§12 touch-target floor). */
    const val RAIL_ICON_SHRUNK_DP = 40

    data class Pitch(
        /** Centre-to-centre cell width (dp). */
        val pitchW: Float,
        /** Centre-to-centre cell height (dp). */
        val pitchH: Float,
        /** Placed grid block width = columns × pitchW. */
        val blockW: Float,
        /** Placed grid block height = rows × pitchH. */
        val blockH: Float,
        /** True when Medium/Expanded applied the 144 dp / aspect caps (block may be centred). */
        val capped: Boolean,
    )

    /**
     * Rail when the window is at least 1.4 : 1 (width ÷ height), or its height is Compact (< 480 dp).
     * Bottom otherwise — including the open fold (~1.2 : 1).
     */
    fun useRail(widthDp: Float, heightDp: Float): Boolean {
        if (widthDp <= 0f || heightDp <= 0f) return false
        return heightDp < HEIGHT_COMPACT_DP || widthDp / heightDp >= RAIL_ASPECT
    }

    /**
     * Rail width from a measured Apps/Done label (§1.5): `max(80, measured + horizontal pad)`.
     * Never clip, ellipsize or hide the label — grow the rail instead. Pass the wider of Apps and
     * Done (and backdrop pad when the scrim is on) at the current font scale and locale.
     */
    fun railWidthDp(headLabelWidthDp: Float): Float =
        maxOf(RAIL_WIDTH_DP, headLabelWidthDp + RAIL_HEAD_PAD_DP)

    /**
     * Chrome content width on Medium/Expanded (§1.5): wider of [blockW] and 576 dp, capped to [pageAreaW].
     * Compact callers pass the full width and get it back.
     */
    fun contentWidthDp(pageAreaW: Float, blockW: Float, widthClass: WidthClass): Float {
        if (widthClass == WidthClass.Compact) return pageAreaW
        return min(pageAreaW, max(blockW, CONTENT_WIDTH_FLOOR_DP))
    }

    /**
     * Rail slot height (dp). Prefer 64; if count×64 doesn't fit, shrink toward 48; below that the rail scrolls
     * (caller uses [needsRailScroll]).
     */
    fun railSlotDp(itemCount: Int, availHeightDp: Float): Float {
        if (itemCount <= 0) return RAIL_SLOT_DP
        if (itemCount * RAIL_SLOT_DP <= availHeightDp) return RAIL_SLOT_DP
        if (itemCount * RAIL_SLOT_MIN_DP <= availHeightDp) {
            return (availHeightDp / itemCount).coerceAtLeast(RAIL_SLOT_MIN_DP)
        }
        return RAIL_SLOT_MIN_DP
    }

    fun needsRailScroll(itemCount: Int, availHeightDp: Float): Boolean =
        itemCount > 0 && itemCount * RAIL_SLOT_MIN_DP > availHeightDp

    fun railIconDp(slotDp: Float): Int =
        if (slotDp < RAIL_SLOT_DP - 0.01f) RAIL_ICON_SHRUNK_DP else ICON_DP

    /**
     * Side margins of a centred block inside a safe-area width, in px.
     * Equal within 1 px (§1.5 / #197 L3); any leftover pixel from odd widths goes to the end side.
     */
    fun sideMarginsPx(safeWidthPx: Int, blockWPx: Int): Pair<Int, Int> {
        val w = blockWPx.coerceAtMost(safeWidthPx.coerceAtLeast(0)).coerceAtLeast(0)
        val left = ((safeWidthPx - w) / 2).coerceAtLeast(0)
        val right = (safeWidthPx - w - left).coerceAtLeast(0)
        return left to right
    }

    /**
     * [availW] × [availH] is the page area left for the grid (pager slot), in dp.
     * Compact fills it (today's layout). Medium/Expanded caps and centres.
     *
     * When [density] > 0, aspect caps are applied in px with floor (§1.5 / #197 L3) so the on-screen
     * cell never rounds *up* past 1.25 × height (or height past 1.5 × width). A check may allow +1 px
     * of measurement slack.
     */
    fun pitch(
        availW: Float,
        availH: Float,
        columns: Int,
        rows: Int,
        widthClass: WidthClass,
        density: Float = 1f,
    ): Pitch {
        val cols = columns.coerceAtLeast(1)
        val rws = rows.coerceAtLeast(1)
        if (widthClass == WidthClass.Compact || availW <= 0f || availH <= 0f) {
            val w0 = availW / cols
            val h0 = availH / rws
            return Pitch(w0, h0, availW, availH, capped = false)
        }
        val d = density.coerceAtLeast(0.01f)
        val availWPx = floor(availW * d).toInt().coerceAtLeast(0)
        val availHPx = floor(availH * d).toInt().coerceAtLeast(0)
        val w0Px = availWPx / cols.toFloat()
        val h0Px = availHPx / rws.toFloat()
        val capWPx = floor(CAP_WIDTH_DP * d).toInt()
        val minCellPx = floor(MIN_CELL_DP * d).toInt()
        // W derived from H0 with floor so W ≤ 1.25·H0 never rounds up.
        val wFromHPx = floor(MAX_W_OVER_H * h0Px.toDouble()).toInt()
        val pitchWPx = min(floor(w0Px.toDouble()).toInt(), min(capWPx, max(minCellPx, wFromHPx)))
            .coerceAtLeast(0)
        // H capped with floor so H ≤ 1.5·W never rounds up.
        val hFromWPx = floor(MAX_H_OVER_W * pitchWPx.toDouble()).toInt()
        val pitchHPx = min(floor(h0Px.toDouble()).toInt(), hFromWPx).coerceAtLeast(0)
        val pitchW = pitchWPx / d
        val pitchH = pitchHPx / d
        return Pitch(pitchW, pitchH, cols * pitchW, rws * pitchH, capped = true)
    }

    /** Horizontal / vertical inset of the centred block inside [availW] × [availH]. */
    fun blockOrigin(availW: Float, availH: Float, pitch: Pitch): Pair<Float, Float> =
        ((availW - pitch.blockW) / 2f).coerceAtLeast(0f) to ((availH - pitch.blockH) / 2f).coerceAtLeast(0f)

    /**
     * Expected t7-land (960 × 600) row pitch once the rail and the top-row reclaim land (§1.5 / L4).
     * [chromeAboveBelowDp] defaults to [T7_LAND_CHROME_ESTIMATE_DP] — harness / advisory only.
     * Does not write `warn_titlebar_fit`; runtime layout measures the real page-column height.
     */
    fun t7LandPitchH(chromeAboveBelowDp: Float = T7_LAND_CHROME_ESTIMATE_DP): Float {
        // 960 × 600, rail 80 → page column 880 wide; height 600 − chrome for the grid.
        val availW = 960f - RAIL_WIDTH_DP
        val availH = (600f - chromeAboveBelowDp).coerceAtLeast(0f)
        return pitch(availW, availH, 4, 5, WidthClass.Expanded).pitchH
    }

    enum class IconStep(val iconDp: Int, val showLabel: Boolean) {
        Icon56Label(ICON_DP, true),
        Icon48Label(ICON_FALLBACK_DP, true),
        Icon48NoLabel(ICON_FALLBACK_DP, false),
    }

    /**
     * First ladder step that fits the cell's content box (§1.5).
     * Both axes: icon ≤ content width, and icon [+ label line] ≤ content height.
     */
    fun iconStep(contentWidthDp: Float, contentHeightDp: Float, labelLineDp: Float): IconStep {
        fun fits(icon: Int, label: Boolean): Boolean {
            if (icon > contentWidthDp) return false
            val need = icon + if (label) labelLineDp else 0f
            return need <= contentHeightDp
        }
        return when {
            fits(ICON_DP, true) -> IconStep.Icon56Label
            fits(ICON_FALLBACK_DP, true) -> IconStep.Icon48Label
            else -> IconStep.Icon48NoLabel
        }
    }
}
