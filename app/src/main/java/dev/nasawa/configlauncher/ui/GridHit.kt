package dev.nasawa.configlauncher.ui

/**
 * #75 / §1.5: which grid cell a point falls in (gutters included).
 * [cell] maps over a box that *is* the grid (Compact fill). [cellInBlock] maps over a placed,
 * possibly centred block and returns null in the side/vertical margins (home, not a cell).
 *
 * Points are physical (root / pointer px, x grows to the right). With [rtl] the columns mirror
 * (§1.5 RTL, #187): column `x = 0` is the start column, on the right. Rows never mirror.
 */
object GridHit {
    fun cell(x: Float, y: Float, width: Float, height: Float, columns: Int, rows: Int, rtl: Boolean = false): Pair<Int, Int> {
        val cx = if (width <= 0f) 0 else (x / width * columns).toInt().coerceIn(0, columns - 1)
        val cy = if (height <= 0f) 0 else (y / height * rows).toInt().coerceIn(0, rows - 1)
        return mirror(cx, columns, rtl) to cy
    }

    /**
     * Hit-test against the placed grid block. Null when [x],[y] is outside the block (a §1.5 margin:
     * long-press opens the home menu, a drop is refused). [blockLeft] is the block's physical left
     * edge in both directions; [rtl] mirrors the column.
     */
    fun cellInBlock(
        x: Float, y: Float,
        blockLeft: Float, blockTop: Float, blockW: Float, blockH: Float,
        columns: Int, rows: Int,
        rtl: Boolean = false,
    ): Pair<Int, Int>? {
        if (blockW <= 0f || blockH <= 0f || columns <= 0 || rows <= 0) return null
        val lx = x - blockLeft
        val ly = y - blockTop
        if (lx < 0f || ly < 0f || lx >= blockW || ly >= blockH) return null
        val cx = (lx / blockW * columns).toInt().coerceIn(0, columns - 1)
        val cy = (ly / blockH * rows).toInt().coerceIn(0, rows - 1)
        return mirror(cx, columns, rtl) to cy
    }

    /**
     * Dock drop index (0..[count], where [count] means "after the last") for a physical point in a dock
     * of [count] items laid out in [left]/[top]/[width]/[height]. A [vertical] rail follows Y top → bottom
     * in both directions (§1.5: the rail's order doesn't mirror). A bottom dock runs `dock[0]` at the start,
     * so in [rtl] index 0 is at the right edge.
     */
    fun dockIndex(
        x: Float, y: Float,
        left: Float, top: Float, width: Float, height: Float,
        count: Int, vertical: Boolean, rtl: Boolean = false,
    ): Int {
        val slots = count + 1
        return if (vertical) {
            if (height <= 0f) 0 else ((y - top) / height * slots).toInt().coerceIn(0, count)
        } else {
            if (width <= 0f) 0
            else {
                val along = if (rtl) (left + width - x) else (x - left)
                (along / width * slots).toInt().coerceIn(0, count)
            }
        }
    }

    private fun mirror(cx: Int, columns: Int, rtl: Boolean) = if (rtl) columns - 1 - cx else cx
}
