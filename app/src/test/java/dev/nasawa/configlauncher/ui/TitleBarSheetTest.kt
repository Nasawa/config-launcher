package dev.nasawa.configlauncher.ui

import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.model.TitleBarModel.Slot
import org.junit.Assert.assertEquals
import org.junit.Test

class TitleBarSheetTest {
    @Test fun slotLabelsFollowTheLayoutDirection() {
        // ux.md §15.2: Left / Center / Right in LTR, Right / Center / Left in RTL; the file keeps start/end.
        assertEquals(listOf(R.string.titlebar_slot_left, R.string.titlebar_slot_center, R.string.titlebar_slot_right), Slot.entries.map { slotLabel(it, rtl = false) })
        assertEquals(listOf(R.string.titlebar_slot_right, R.string.titlebar_slot_center, R.string.titlebar_slot_left), Slot.entries.map { slotLabel(it, rtl = true) })
    }
}
