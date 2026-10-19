package dev.nasawa.configlauncher.ui

/** #75: which grid cell a point in the grid falls in (gutters included), clamped to the grid. */
object GridHit {
    fun cell(x: Float, y: Float, width: Float, height: Float, columns: Int, rows: Int): Pair<Int, Int> {
        val cx = if (width <= 0f) 0 else (x / width * columns).toInt().coerceIn(0, columns - 1)
        val cy = if (height <= 0f) 0 else (y / height * rows).toInt().coerceIn(0, rows - 1)
        return cx to cy
    }
}
