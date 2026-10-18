package dev.nasawa.configlauncher.config

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ValidatorTest {
    private fun v(s: String) = Validator.validate(Json.parseToJsonElement(s))
    private fun page(items: String) = """{"schemaVersion":1,"pages":[{"id":"p","items":[$items]}]}"""
    private fun app(id: String, x: Int, y: Int) = """{"id":"$id","type":"app","package":"a.b","x":$x,"y":$y}"""

    @Test fun validMinimal() = assertEquals(emptyList<String>(), v(page(app("a", 0, 0))))

    @Test fun unknownKeyRejectedButXKeyAllowed() {
        assertTrue(v("""{"schemaVersion":1,"pages":[{"id":"p","items":[]}],"foo":1}""").single().contains("unknown key \"foo\""))
        assertEquals(emptyList<String>(), v("""{"schemaVersion":1,"pages":[{"id":"p","items":[]}],"x-foo":1}"""))
    }

    @Test fun duplicateIds() = assertTrue(v(page(app("a", 0, 0) + "," + app("a", 1, 0))).any { "duplicate id" in it })

    @Test fun overlap() = assertTrue(v(page(app("a", 0, 0) + "," + app("b", 0, 0))).any { "overlaps" in it })

    @Test fun outsideGrid() = assertTrue(v(page(app("a", 4, 0))).single().contains("outside the grid"))

    @Test fun pageItemNeedsXY() =
        assertTrue(v(page("""{"id":"a","type":"app","package":"a.b"}""")).any { "need \"x\"" in it })

    @Test fun dockForbidsXY() = assertTrue(
        v("""{"schemaVersion":1,"pages":[{"id":"p","items":[]}],"dock":[${app("a", 0, 0)}]}""").any { "not allowed in the dock" in it })

    @Test fun nestedFolderRejected() = assertTrue(v(page(
        """{"id":"f","type":"folder","x":0,"y":0,"items":[{"id":"g","type":"folder","items":[]}]}""")).any { "nested" in it })

    @Test fun badPackageAndId() {
        val e = v(page("""{"id":"A!","type":"app","package":"nodots","x":0,"y":0}"""))
        assertTrue(e.any { it.startsWith("pages[0].items[0].id") })
        assertTrue(e.any { it.startsWith("pages[0].items[0].package") })
    }

    @Test fun missingPagesAndVersion() {
        val e = v("{}")
        assertTrue(e.contains("missing required \"schemaVersion\""))
        assertTrue(e.contains("missing required \"pages\""))
    }
}
