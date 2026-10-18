package dev.nasawa.configlauncher.ui

import dev.nasawa.configlauncher.store.ConfigEngine
import dev.nasawa.configlauncher.store.Issue
import dev.nasawa.configlauncher.store.LastGoodStore
import dev.nasawa.configlauncher.store.LocalFileSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BannerActionsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun missingInFolderOffersCreateAndReconnect() =
        assertEquals(listOf(BannerAction.CREATE, BannerAction.RECONNECT), BannerActions.forIssue(Issue.Missing, reconnectable = true))

    @Test fun missingInAppStorageHidesReconnect() =
        assertEquals(listOf(BannerAction.CREATE), BannerActions.forIssue(Issue.Missing, reconnectable = false))

    @Test fun otherIssuesHaveNoActions() =
        assertTrue(BannerActions.forIssue(Issue.Invalid(listOf("x")), true).isEmpty())

    @Test fun appStorageEngineReportsNotReconnectable() = runBlocking {
        val e = ConfigEngine(LocalFileSource(tmp.newFolder()), LastGoodStore(tmp.newFolder()), 0)
        e.poll(force = true)
        assertEquals(Issue.Missing, e.state.value.issue)
        assertFalse(e.state.value.reconnectable)
        assertEquals(listOf(BannerAction.CREATE), BannerActions.forIssue(e.state.value.issue!!, e.state.value.reconnectable))
    }
}
