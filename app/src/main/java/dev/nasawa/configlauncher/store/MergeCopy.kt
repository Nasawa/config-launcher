package dev.nasawa.configlauncher.store

import android.content.res.Resources
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.model.FolderSkip

/** What a merge did, for the §9.1 snackbar. [undo] = automatic merge with something of ours written. */
data class MergeNotice(val kept: Int, val skipped: Int, val folder: FolderSkip? = null, val undo: Boolean) {
    val allSkipped get() = kept == 0 && skipped > 0
    /** Keep mine with everything kept shows nothing extra (§9.1). */
    val silent get() = !undo && skipped == 0
}

/** docs/ux.md §9.1 strings. Counts are passed as both plurals quantity and format argument. */
class MergeCopy(private val r: Resources) {
    private fun plural(id: Int, n: Int) = r.getQuantityString(id, n, n)
    private fun keptPart(k: Int) = plural(R.plurals.merge_kept_part, k)

    fun text(n: MergeNotice): String = when {
        n.allSkipped && n.skipped == 1 && n.folder != null -> folder(n.folder)
        n.allSkipped -> plural(R.plurals.merge_skipped_all, n.skipped)
        n.skipped == 0 -> "${r.getString(R.string.merge_updated)} ${plural(R.plurals.merge_kept_all, n.kept)}"
        else -> r.getString(R.string.merge_mixed, r.getString(R.string.merge_updated), keptPart(n.kept), plural(R.plurals.merge_skipped_part, n.skipped))
    }

    /** TalkBack: the full sentence for mixed; all-kept and all-skipped announce the visible text. */
    fun spoken(n: MergeNotice): String =
        if (n.kept > 0 && n.skipped > 0)
            r.getString(R.string.merge_mixed_a11y, r.getString(R.string.merge_updated), keptPart(n.kept), plural(R.plurals.merge_skipped_a11y, n.skipped))
        else text(n)

    private fun folder(f: FolderSkip) =
        if (f.placed != null) r.getString(R.string.merge_folder_one_left, f.label, f.placed)
        else r.getString(R.string.merge_folder_none_left, f.label)

    companion object {
        /** Snackbar duration: 10 s with Undo (the §18.2 Undo window), 6 s without. */
        fun durationMs(n: MergeNotice) = if (n.undo) 10_000L else 6_000L
    }
}
