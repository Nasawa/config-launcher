package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * ux.md §6.1 notice row (#173 L1, Pragma's #175): the collapsed chips (the B-banner ▲ chip and the §17.4 count chip)
 * share one 48 dp row at the top of the page area, and the §1.3 page label moves into it while a chip shows: label
 * centred, chips at the end with a 12 dp end margin. The page label's touch area stops 8 dp short of the first chip.
 * With a page label showing, a chip costs no grid height; with none (one untitled page) the row takes 48 dp, only
 * while a chip is up. The chips never float over the grid.
 */
object NoticeRow {
    const val HEIGHT_DP = 48
    const val END_MARGIN_DP = 12
    /** Between the page label's touch area and the first chip. */
    const val LABEL_GAP_DP = 8
    /** Between two chips (§17.4: the count chip sits to the start of the ▲ chip, 8 dp apart). */
    const val CHIP_GAP_DP = 8
    /** The page label's start limit: the §1.3 max width is the screen width − 32 dp, so 16 dp a side. */
    const val START_MARGIN_DP = 16

    /** Where things go in a row [width] wide, in px: the chips' x, the label's max width, and its x for a [labelW] label. */
    data class Placement(val chipsX: Int, val labelMax: Int, val labelX: Int)

    /**
     * The label is centred on the row when it fits there; a label too wide to stay centred left of the chips shifts
     * toward the start, and one wider than the room left ellipsizes (it's measured at most [Placement.labelMax] wide).
     */
    fun place(width: Int, chipsW: Int, labelW: Int, endMargin: Int, gap: Int, start: Int): Placement {
        val chipsX = (width - endMargin - chipsW).coerceAtLeast(0)
        val limit = chipsX - gap
        val labelMax = (limit - start).coerceAtLeast(0)
        val w = labelW.coerceAtMost(labelMax)
        val x = if (labelMax == 0) start.coerceAtMost(chipsX) else ((width - w) / 2).coerceIn(start, limit - w)
        return Placement(chipsX, labelMax, x)
    }
}

/** The §6.1 notice row: [label] (the §1.3 page label, or null for none) and the collapsed [chips], 8 dp apart. */
@Composable
fun NoticeRow(label: (@Composable () -> Unit)?, modifier: Modifier = Modifier, chips: @Composable RowScope.() -> Unit) {
    val labelContent: @Composable () -> Unit = { label?.invoke() }
    val chipsContent: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(NoticeRow.CHIP_GAP_DP.dp), verticalAlignment = Alignment.CenterVertically, content = chips)
    }
    Layout(listOf(labelContent, chipsContent), modifier.fillMaxWidth().height(NoticeRow.HEIGHT_DP.dp)) { (labelM, chipM), c ->
        val w = c.maxWidth
        val h = c.maxHeight
        val chipsP = chipM.map { it.measure(Constraints(maxWidth = w, maxHeight = h)) }
        val chipsW = chipsP.sumOf { it.width }
        val room = NoticeRow.place(w, chipsW, 0, NoticeRow.END_MARGIN_DP.dp.roundToPx(), NoticeRow.LABEL_GAP_DP.dp.roundToPx(), NoticeRow.START_MARGIN_DP.dp.roundToPx())
        val labelP = labelM.map { it.measure(Constraints(maxWidth = room.labelMax, maxHeight = h)) }
        val at = NoticeRow.place(w, chipsW, labelP.maxOfOrNull { it.width } ?: 0, NoticeRow.END_MARGIN_DP.dp.roundToPx(),
            NoticeRow.LABEL_GAP_DP.dp.roundToPx(), NoticeRow.START_MARGIN_DP.dp.roundToPx())
        layout(w, h) {
            labelP.forEach { it.placeRelative(at.labelX, (h - it.height) / 2) }
            chipsP.forEach { it.placeRelative(at.chipsX, (h - it.height) / 2) }
        }
    }
}
