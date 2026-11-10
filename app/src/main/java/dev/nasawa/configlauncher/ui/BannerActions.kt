package dev.nasawa.configlauncher.ui

import dev.nasawa.configlauncher.store.Issue

enum class BannerAction { RECONNECT, CREATE, RETRY }

/** Buttons for an issue banner (docs/ux.md §6.1). Reconnect only makes sense for a picked folder. */
object BannerActions {
    fun forIssue(issue: Issue, reconnectable: Boolean): List<BannerAction> = when (issue) {
        is Issue.AccessLost -> if (reconnectable) listOf(BannerAction.RECONNECT) else emptyList()
        Issue.Missing -> listOfNotNull(BannerAction.CREATE, BannerAction.RECONNECT.takeIf { reconnectable })
        is Issue.SaveFailed -> listOf(BannerAction.RETRY)   // B5
        else -> emptyList()
    }
}

/** #262: lines a problem banner always shows in full, whatever it's folded to. */
object BannerNotes {
    /**
     * The start sweep's kept-temp lines ([dev.nasawa.configlauncher.store.ConfigEngine.keptTemps]): in the banner
     * whenever one is up, because the Warnings list is hidden then (and status.json may not be writable). With no
     * banner they're in the Warnings list instead, so each shows in exactly one place, and only while a temp is kept.
     */
    fun keptTemps(issue: Issue?, kept: List<String>): List<String> = if (issue == null) emptyList() else kept
}
