package dev.nasawa.configlauncher.store

/** §18.2 snackbar copy for an automatic merge. */
object MergeCopy {
    fun notice(kept: Int, skipped: Int): String {
        val base = "Layout updated from launcher.json." +
            if (kept > 0) " Kept your $kept change${if (kept == 1) "" else "s"}." else ""
        return if (skipped > 0) "$base $skipped change(s) skipped because the item was removed or moved in the file" else base
    }
}
