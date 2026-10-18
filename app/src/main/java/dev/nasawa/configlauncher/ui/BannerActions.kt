package dev.nasawa.configlauncher.ui

import dev.nasawa.configlauncher.store.Issue

enum class BannerAction { RECONNECT, CREATE }

/** Buttons for an issue banner (docs/ux.md §6.1). Reconnect only makes sense for a picked folder. */
object BannerActions {
    fun forIssue(issue: Issue, reconnectable: Boolean): List<BannerAction> = when (issue) {
        is Issue.AccessLost -> if (reconnectable) listOf(BannerAction.RECONNECT) else emptyList()
        Issue.Missing -> listOfNotNull(BannerAction.CREATE, BannerAction.RECONNECT.takeIf { reconnectable })
        else -> emptyList()
    }
}
