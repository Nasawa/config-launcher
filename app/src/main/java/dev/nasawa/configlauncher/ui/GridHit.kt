package dev.nasawa.configlauncher.ui

/**
 * #75 / §1.5: which grid cell a point falls in (gutters included).
 * [cell] maps over a box that *is* the grid (Compact fill). [cellInBlock] maps over a placed,
 * possibly centred block and returns null in the side/vertical margins (home, not a cell).
 */
object GridHit {
    fun cell(x: Float, y: Float, width: Float, height: Float, columns: Int, rows: Int): Pair<Int, Int> {
        val cx = if (width <= 0f) 0 else (x / width * columns).toInt().coerceIn(0, columns - 1)
        val cy = if (height <= 0f) 0 else (y / height * rows).toInt().coerceIn(0, rows - 1)
        return cx to cy
    }

    /**
     * Hit-test against the placed grid block. Null when [x],[y] is outside the block (a §1.5 margin:
     * long-press opens the home menu, a drop is refused). RTL mirroring is #187 follow-up.
     */
    fun cellInBlock(
        x: Float, y: Float,
        blockLeft: Float, blockTop: Float, blockW: Float, blockH: Float,
        columns: Int, rows: Int,
    ): Pair<Int, Int>? {
        if (blockW <= 0f || blockH <= 0f || columns <= 0 || rows <= 0) return null
        val lx = x - blockLeft
        val ly = y - blockTop
        if (lx < 0f || ly < 0f || lx >= blockW || ly >= blockH) return null
        val cx = (lx / blockW * columns).toInt().coerceIn(0, columns - 1)
        val cy = (ly / blockH * rows).toInt().coerceIn(0, rows - 1)
        return cx to cy
    }
}
