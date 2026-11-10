package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.consent.Consent
import dev.nasawa.configlauncher.consent.ConsentStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** #257: release builds write a per-card "not drawn" warning and leave stored approvals alone. */
class CardPlaceholderStatusTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fixture = File("../test-fixtures/configs/valid/card-example.json").takeIf { it.isFile }
        ?: File("test-fixtures/configs/valid/card-example.json")

    private fun status(dir: File) = Json.parseToJsonElement(File(dir, ".configlauncher/status.json").readText()).jsonObject

    private fun warningsOf(dir: File) = status(dir)["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test fun releaseBuildWarnsPerCardAndKeepsApprovals() = runBlocking {
        val dir = tmp.newFolder()
        val src = LocalFileSource(dir); src.file.writeText(fixture.readText())
        val consentDir = tmp.newFolder()
        val consent = ConsentStore(consentDir, clock = { 1_700_000_000_000L })
        val debug = ConfigEngine(src, LastGoodStore(tmp.newFolder()), 0, clock = { 1_700_000_000_000L }, consent = consent, cardsLive = true)
        debug.poll(force = true)
        val lines = debug.consentReport().first { it.id == "w-anigeek" }.lines
        assertTrue(consent.allow("w-anigeek", lines, debug.state.value.config))
        debug.consentChanged()
        assertEquals(Consent.State.Allowed, consent.report(debug.state.value.config).single().state)
        val ceiling = consent.entry("w-anigeek")!!.ceiling

        val release = ConfigEngine(src, LastGoodStore(tmp.newFolder()), 0, clock = { 1_700_000_000_000L }, consent = consent, cardsLive = false)
        release.poll(force = true)
        val loaded = warningsOf(dir)
        assertEquals(release.warnings.value, loaded)
        val expected = """elements[0]: widget "w-anigeek" isn't drawn in this version of Reflow Launcher. It stays in the file."""
        assertTrue("placeholder warning listed: $loaded", expected in loaded)
        assertTrue(loaded.none { "waiting for approval" in it })
        assertEquals(Consent.State.Allowed, consent.entry("w-anigeek")!!.let { Consent.state(consent.report(release.state.value.config).single().lines, it).first })
        assertEquals(ceiling, consent.entry("w-anigeek")!!.ceiling)
        val consentObj = status(dir)["consent"]!!.jsonObject["w-anigeek"]!!.jsonObject
        assertEquals("allowed", consentObj["state"]!!.jsonPrimitive.content)
        assertFalse(consentObj.containsKey("warning"))
    }

    @Test fun debugBuildStillWritesTheConsentWarning() = runBlocking {
        val dir = tmp.newFolder()
        val src = LocalFileSource(dir); src.file.writeText(fixture.readText())
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), 0, clock = { 1_700_000_000_000L }, consent = ConsentStore(tmp.newFolder(), clock = { 1_700_000_000_000L }), cardsLive = true)
        e.poll(force = true)
        val loaded = warningsOf(dir)
        assertEquals(e.warnings.value, loaded)
        assertTrue(loaded.any { it.startsWith("elements[0]") && "waiting for approval" in it })
        assertTrue(loaded.none { "isn't drawn in this version" in it })
    }
}
