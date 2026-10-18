package dev.nasawa.configlauncher

import dev.nasawa.configlauncher.apps.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class AppEntryTest {
    @Test fun keyIsPackageSlashActivity() {
        assertEquals("a.b/a.b.Main", AppEntry("a.b", "a.b.Main", "B").key)
    }
}

/**
 * HomeScreen keys grid items by `it.key + it.userSerial` with no separator, so two different
 * entries can produce the same LazyVerticalGrid key (Compose throws on duplicate keys).
 * This documents the collision; see the PR #4 review.
 */
class GridKeyCollisionTest {
    @Test fun concatenatedKeyCanCollide() {
        val personal = AppEntry("p.q", "p.q.A1", "A", userSerial = 0)
        val work = AppEntry("p.q", "p.q.A", "A", userSerial = 10)
        assertEquals(personal.key + personal.userSerial, work.key + work.userSerial)
    }
}
