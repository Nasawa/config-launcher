package dev.nasawa.configlauncher.model

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.TitleBarEdits.Choice
import dev.nasawa.configlauncher.model.TitleBarModel.Slot
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** #79 phone-side title bar edits (title-bar.md §8, ux.md §15). */
class TitleBarEditsTest {
    private fun ok(s: String) = (ConfigCodec.decode(s) as? ParseResult.Ok)?.config ?: error("invalid: ${(ConfigCodec.decode(s) as ParseResult.Invalid).errors}")
    private val empty = """{"schemaVersion":1,"pages":[{"id":"home","items":[]}]}"""
    private fun withBar(bar: String) = """{"schemaVersion":1,"pages":[{"id":"home","items":[]}],"elements":[$bar]}"""
    /** Apply, write as the phone does, and read back: every phone edit must give a valid file. */
    private fun write(c: LauncherConfig, e: Edit?): LauncherConfig {
        val r = Edits.apply(c, e ?: error("no edit"))
        assertTrue("$e not applied", r.applied)
        return ok(ConfigCodec.encode(r.config))
    }
    private fun bar(c: LauncherConfig) = c.elements!!.single()

    @Test fun turningOnWritesThePreset() {
        val c = write(ok(empty), TitleBarEdits.toggle(ok(empty), true))
        assertEquals(Json.parseToJsonElement("""{"id":"t-main","type":"titleBar","anchor":"top","content":{"slots":{"center":"{page}"}}}"""), bar(c))
        assertEquals(TitleBarEdits.State.Present("t-main", true, mapOf(Slot.START to TitleBarEdits.SlotState(Choice.NONE),
            Slot.CENTER to TitleBarEdits.SlotState(Choice.PAGE), Slot.END to TitleBarEdits.SlotState(Choice.NONE))), TitleBarEdits.state(c))
        assertNull(TitleBarEdits.toggle(ok(empty), false))
        assertNotNull(TitleBarModel.forPage(c, "home"))
    }

    @Test fun offAndOnKeepEverythingElse() {
        val custom = """{"id":"t-main","type":"titleBar","content":{"slots":{"start":"{date:EEE d MMM}"}},"style":{"size":"large"},"x-note":"mine"}"""
        val c0 = ok(withBar(custom))
        val off = write(c0, TitleBarEdits.toggle(c0, false))
        assertEquals(JsonPrimitive(false), bar(off)["enabled"])
        assertNull(TitleBarModel.forPage(off, "home"))
        val on = write(off, TitleBarEdits.toggle(off, true))
        assertEquals(Json.parseToJsonElement(custom), bar(on))   // "enabled" omitted again (default true)
        assertNull(TitleBarEdits.toggle(on, true))
    }

    @Test fun pickingASlotIsOneWriteAndCustomIsReadOnly() {
        val c0 = ok(withBar("""{"id":"t-main","type":"titleBar","content":{"slots":{"start":"{date:EEE d MMM}","center":"{page}"}}}"""))
        val p = TitleBarEdits.state(c0) as TitleBarEdits.State.Present
        assertEquals(TitleBarEdits.SlotState(Choice.CUSTOM, "{date:EEE d MMM}"), p.slots[Slot.START])
        assertNull(TitleBarEdits.pick("t-main", Slot.START, Choice.CUSTOM))
        val clock = write(c0, TitleBarEdits.pick("t-main", Slot.START, Choice.CLOCK))
        assertEquals(JsonPrimitive("{time}"), bar(clock)["content"]!!.jsonObject["slots"]!!.jsonObject["start"])
        // None removes the slot; empty objects go too (defaults are omitted).
        val c1 = write(clock, TitleBarEdits.pick("t-main", Slot.START, Choice.NONE))
        val c2 = write(c1, TitleBarEdits.pick("t-main", Slot.CENTER, Choice.NONE))
        assertNull(bar(c2)["content"])
        val c3 = write(c2, TitleBarEdits.pick("t-main", Slot.END, Choice.DATE))
        assertEquals(Json.parseToJsonElement("""{"slots":{"end":"{date}"}}"""), bar(c3)["content"])
        assertEquals(TitleBarEdits.SlotState(Choice.CLOCK), TitleBarEdits.slotState("clock"))
    }

    @Test fun replayIsByIdAndNeverCreatesASecondBar() {
        val c0 = ok(withBar("""{"id":"t-main","type":"titleBar"}"""))
        val pick = TitleBarEdits.pick("t-main", Slot.END, Choice.CLOCK)!!
        // The file dropped the element meanwhile: the phone's edit to it is skipped.
        val rep = Edits.replay(ok(empty), listOf(pick))
        assertEquals(0, rep.kept)
        // The file gained its own bar meanwhile: the phone's "turn on" doesn't add a second one.
        val add = TitleBarEdits.toggle(ok(empty), true)!!
        assertFalse(Edits.apply(c0, add).applied)
        // A bar this version skips (requires too high) is left alone.
        val newer = ok(withBar("""{"id":"t-new","type":"titleBar","requires":3}"""))
        assertEquals(TitleBarEdits.State.Unavailable, TitleBarEdits.state(newer))
        assertNull(TitleBarEdits.toggle(newer, true))
    }

    @Test fun presetIdAvoidsTakenIds() {
        val c = ok("""{"schemaVersion":1,"pages":[{"id":"t-main","items":[]}]}""")
        val e = TitleBarEdits.toggle(c, true) as Edit.AddElement
        assertEquals("t-main-2", e.element["id"]!!.jsonPrimitive.content)
        write(c, e)
    }
}
