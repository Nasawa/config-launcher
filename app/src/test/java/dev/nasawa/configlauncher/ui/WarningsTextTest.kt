package dev.nasawa.configlauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WarningsTextTest {
    @Test fun copyListsEachWarningWithItsPath() {
        assertEquals(
            "launcher.json\n2 warnings\n▲ elements[0]: type \"appWidget\" isn't supported\n▲ pages[0].overrides: \"t-gone\" doesn't match any element",
            WarningsText.copy("launcher.json", "2 warnings", listOf("elements[0]: type \"appWidget\" isn't supported", "pages[0].overrides: \"t-gone\" doesn't match any element")),
        )
    }
}
