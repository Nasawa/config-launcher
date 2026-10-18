package dev.nasawa.configlauncher.apps

import org.junit.Assert.*
import org.junit.Test

/** #40 / OU-2: Secure Folder is never picked as the work profile. */
class WorkProfileTest {
    private val sf = listOf(WorkProfile.SECURE_FOLDER, "com.sec.android.gallery3d")
    private val work = listOf("com.microsoft.teams", "com.android.chrome")

    @Test fun realWorkProfileWinsOverSecureFolderListedFirst() {
        val apps = mapOf(150 to sf, 10 to work)
        assertEquals(10, WorkProfile.pick(listOf(150, 10)) { apps.getValue(it) })
    }

    @Test fun secureFolderAloneMeansNoWorkProfile() {
        assertNull(WorkProfile.pick(listOf(150)) { sf })
    }

    @Test fun plainWorkProfileStillPicked() {
        assertEquals(10, WorkProfile.pick(listOf(10)) { work })
        assertNull(WorkProfile.pick(emptyList<Int>()) { work })
    }
}
