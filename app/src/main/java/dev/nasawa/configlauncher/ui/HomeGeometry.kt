package dev.nasawa.configlauncher.ui

import kotlin.math.min
import kotlin.math.max

/**
 * ux.md §1.5: window width class, capped/centred cell pitch, and the icon ladder.
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
     * [availW] × [availH] is the page area left for the grid (pager slot), in dp.
     * Compact fills it (today's layout). Medium/Expanded caps and centres.
     */
    fun pitch(availW: Float, availH: Float, columns: Int, rows: Int, widthClass: WidthClass): Pitch {
        val cols = columns.coerceAtLeast(1)
        val rws = rows.coerceAtLeast(1)
        val w0 = availW / cols
        val h0 = availH / rws
        if (widthClass == WidthClass.Compact || availW <= 0f || availH <= 0f) {
            return Pitch(w0, h0, availW, availH, capped = false)
        }
        // pitchW = min(W0, 144, max(64, 1.25 × H0)); pitchH = min(H0, 1.5 × pitchW)
        val pitchW = min(w0, min(CAP_WIDTH_DP, max(MIN_CELL_DP, MAX_W_OVER_H * h0)))
        val pitchH = min(h0, MAX_H_OVER_W * pitchW)
        return Pitch(pitchW, pitchH, cols * pitchW, rws * pitchH, capped = true)
    }

    /** Horizontal / vertical inset of the centred block inside [availW] × [availH]. */
    fun blockOrigin(availW: Float, availH: Float, pitch: Pitch): Pair<Float, Float> =
        ((availW - pitch.blockW) / 2f).coerceAtLeast(0f) to ((availH - pitch.blockH) / 2f).coerceAtLeast(0f)

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
