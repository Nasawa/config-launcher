package dev.nasawa.configlauncher.model

import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Replaces the scaffold's GridKeyCollisionTest: keys are "profile:package/activity", separators on both sides. */
class InstalledAppKeyTest {
    @Test fun keysDoNotCollideAcrossProfilesOrActivities() {
        assertNotEquals(InstalledApp("p.q", "p.q.A1", "A").key, InstalledApp("p.q", "p.q.A", "A", Profile.WORK).key)
        assertNotEquals(InstalledApp("p.q", "p.q.A", "A").key, InstalledApp("p.q", "p.q.A", "A", Profile.WORK).key)
    }
}
