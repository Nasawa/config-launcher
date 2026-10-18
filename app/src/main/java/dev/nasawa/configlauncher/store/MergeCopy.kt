package dev.nasawa.configlauncher.store

/** Snackbar copy for merges, per docs/ux.md §4.4 (table rows "kept" and "skipped"). */
object MergeCopy {
    fun skipped(n: Int): String =
        "${if (n == 1) "1 change" else "$n changes"} skipped because the item was removed in the file."

    fun notice(kept: Int, skipped: Int): String {
        if (skipped > 0 && kept == 0) return skipped(skipped)
        val base = "Layout updated from launcher.json." +
            when (kept) { 0 -> ""; 1 -> " Kept your change."; else -> " Kept your $kept changes." }
        return if (skipped > 0) "$base ${skipped(skipped)}" else base
    }
}
