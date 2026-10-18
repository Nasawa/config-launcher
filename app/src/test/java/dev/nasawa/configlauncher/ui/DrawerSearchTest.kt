package dev.nasawa.configlauncher.ui

import dev.nasawa.configlauncher.model.InstalledApp
import org.junit.Assert.*
import org.junit.Test

class DrawerSearchTest {
    private fun app(l: String) = InstalledApp("p.${l.hashCode()}", "A", l)
    private val apps = listOf("Calculator", "Google Calendar", "Café Finder", "Camera", "Messages", "Pocket Casts").map(::app)
    private fun labels(q: String) = DrawerSearch.filter(apps, q).map { it.label }

    @Test fun emptyQueryShowsAll() = assertEquals(apps, DrawerSearch.filter(apps, "  "))
    @Test fun labelStartThenWordStartThenAnywhere() =
        assertEquals(listOf("Calculator", "Café Finder", "Camera", "Google Calendar", "Pocket Casts"), labels("ca"))
    @Test fun caseAndAccentInsensitive() {
        assertEquals(listOf("Café Finder"), labels("CAFE"))
        assertEquals(listOf("Café Finder"), labels("café"))
    }
    @Test fun anywhereMatch() = assertEquals(listOf("Messages"), labels("ssag"))
    @Test fun noMatch() = assertTrue(labels("xyz").isEmpty())
}
