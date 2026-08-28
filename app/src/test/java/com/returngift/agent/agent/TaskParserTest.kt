package com.returngift.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the Tier-1 deterministic parser. Covers every intent with its own
 * clearly-separated block. Golden-corpus coverage (the authoritative gate) lives in
 * TaskParserGoldenCorpusTest; this file holds focused behavioral assertions including
 * the "assert on ParseResult fields, not intent internals" rule (A12) so the Intent
 * stubs from isReturnDefaultValues never mask a regression.
 */
class TaskParserTest {

    // ── send_message ────────────────────────────────────────────────────────────
    @Test
    fun `send message command routes to direct send message tool`() {
        val parsed = TaskParser.parse("send hi to Girlfriend on WhatsApp")
        assertNotNull(parsed)
        assertEquals("send_message", parsed!!.action)
        assertEquals("send_message", parsed.toolName)
        assertEquals("hi", parsed.toolParams!!["message"])
        assertEquals("Girlfriend", parsed.toolParams!!["contact"])
        assertEquals("WhatsApp", parsed.toolParams!!["app"])
    }

    @Test
    fun `send contextual message still falls through to agent`() {
        assertNull(TaskParser.parse("send that to Girlfriend on WhatsApp"))
    }

    @Test
    fun `email commands do not route to messaging app tool`() {
        assertNull(TaskParser.parse("send email to nicole@example.com"))
    }

    @Test
    fun `send text to Axel falls through (vector body, one-letter x guard)`() {
        assertNull(TaskParser.parse("send text to Axel"))
    }

    @Test
    fun `send a message to John falls through (bare vector body)`() {
        assertNull(TaskParser.parse("send a message to John"))
    }

    @Test
    fun `send numeric content falls through (would shadow as phone)`() {
        assertNull(TaskParser.parse("send 555-0100 to superscript contact"))
    }

    @Test
    fun `prefix-filler sends still route to send message`() {
        val parsed = TaskParser.parse("please send hi to Mom")
        assertEquals("send_message", parsed?.action)
        assertEquals("Mom", parsed?.toolParams?.get("contact"))
    }

    // ── call (A6/A7 + start-anchor) ─────────────────────────────────────────────
    @Test
    fun `3 digit number is tier one eligible`() {
        assertEquals("call", TaskParser.parse("call 911")?.action)
        assertEquals("call", TaskParser.parse("call 415-555-0100")?.action)
        assertEquals("call", TaskParser.parse("dial +1 (212) 555-0178")?.action)
    }

    @Test
    fun `1-2 digit numbers are not dialable`() {
        assertNull(TaskParser.parse("call 1"))
        assertNull(TaskParser.parse("call 12"))
    }

    @Test
    fun `numbers longer than 15 digits are rejected`() {
        assertNull(TaskParser.parse("call 1234567890123456"))
    }

    @Test
    fun `call verb must start the task`() {
        assertNull(TaskParser.parse("on whatsapp call mom"))
    }

    // ── sms ──────────────────────────────────────────────────────────────────────
    @Test
    fun `sms to a number opens compose`() {
        assertEquals("sms", TaskParser.parse("sms 555-0100")?.action)
        assertEquals("sms", TaskParser.parse("text 4155550100")?.action)
        assertEquals("sms", TaskParser.parse("message 555-0100")?.action)
    }

    @Test
    fun `sms trailing name is ambiguous`() {
        assertNull(TaskParser.parse("sms 555-0100 to mom"))
    }

    // ── alarm ────────────────────────────────────────────────────────────────────
    @Test
    fun `alarm at hour routes`() {
        assertEquals("alarm", TaskParser.parse("set an alarm at 7 for tomorrow")?.action)
        assertEquals("alarm", TaskParser.parse("set alarm for 7:30 am")?.action)
        assertEquals("alarm", TaskParser.parse("wake me up at 6:45 in the morning")?.action)
    }

    @Test
    fun `alarm requires a valid time`() {
        assertNull(TaskParser.parse("alarm tomorrow"))
        assertNull(TaskParser.parse("alarm at 25:99"))
    }

    @Test
    fun `reminder is not alarm`() {
        assertNull(TaskParser.parse("set a reminder for monday"))
    }

    // ── timer ────────────────────────────────────────────────────────────────────
    @Test
    fun `timer needs a duration`() {
        assertEquals("timer", TaskParser.parse("set a timer for 10 minutes")?.action)
        assertEquals("timer", TaskParser.parse("countdown 2 hours")?.action)
        assertNull(TaskParser.parse("timer please"))
        assertNull(TaskParser.parse("set a timer at 5"))
    }

    @Test
    fun `time is not the timer verb`() {
        assertNull(TaskParser.parse("time for 5 minutes"))
    }

    // ── screenshot ───────────────────────────────────────────────────────────────
    @Test
    fun `screenshot routes`() {
        assertEquals("screenshot", TaskParser.parse("take a screenshot")?.action)
        assertEquals("screenshot", TaskParser.parse("screencap this")?.action)
    }

    @Test
    fun `capture photo are not screenshot verbs`() {
        assertNull(TaskParser.parse("capture the screen"))
        assertNull(TaskParser.parse("photo of the screen"))
    }

    // ── back/home (A1 exact + press variants) ───────────────────────────────────
    @Test
    fun `back exact phrases and press variants`() {
        assertEquals("back", TaskParser.parse("back")?.action)
        assertEquals("back", TaskParser.parse("go back please")?.action)
        assertEquals("back", TaskParser.parse("press back")?.action)
        assertEquals("back", TaskParser.parse("press the back button")?.action)
        assertNull(TaskParser.parse("back it up on youtube"))
    }

    @Test
    fun `home exact phrases and press variants`() {
        assertEquals("home", TaskParser.parse("go home")?.action)
        assertEquals("home", TaskParser.parse("press home please")?.action)
        assertEquals("home", TaskParser.parse("go to the home screen")?.action)
        assertNull(TaskParser.parse("bring me home"))
        assertNull(TaskParser.parse("home base"))
    }

    // ── open_url / settings ─────────────────────────────────────────────────────
    @Test
    fun `open url needs a scheme`() {
        assertEquals("open_url", TaskParser.parse("open https://example.com")?.action)
        assertNull(TaskParser.parse("open example.com"))
        assertNull(TaskParser.parse("look up example.com"))
    }

    @Test
    fun `open settings keyword requires settings context`() {
        assertEquals("open_settings", TaskParser.parse("open brightness settings")?.action)
        assertEquals("open_settings", TaskParser.parse("open wifi settings")?.action)
        assertNull(TaskParser.parse("change the wifi password"))
        assertNull(TaskParser.parse("open book settings"))
    }

    // ── open_app ─────────────────────────────────────────────────────────────────
    @Test
    fun `open app routes and strips politeness`() {
        assertEquals("open_app", TaskParser.parse("open youtube please")?.action)
        assertEquals("open_app", TaskParser.parse("launch spotify")?.action)
        assertEquals("open_app", TaskParser.parse("start the calendar app")?.action)
        assertEquals("Opening youtube", TaskParser.parse("open youtube please")?.description)
    }

    @Test
    fun `generic action words are not app names`() {
        assertNull(TaskParser.parse("start a countdown"))
    }

    // ── camera / flashlight (A1 live port) ──────────────────────────────────────
    @Test
    fun `camera routes to direct intent`() {
        assertEquals("camera", TaskParser.parse("open camera")?.action)
        assertEquals("camera", TaskParser.parse("camera")?.action)
        assertNull(TaskParser.parse("camera roll"))
        assertNull(TaskParser.parse("camera the document"))
    }

    @Test
    fun `flashlight on off and bare toggle`() {
        val on = TaskParser.parse("turn on the flashlight")
        assertEquals("flashlight", on?.semantic())
        val off = TaskParser.parse("turn off the torch")
        assertEquals("flashlight", off?.semantic())
        val bare = TaskParser.parse("flashlight")
        assertEquals("flashlight_toggle", bare?.action)
        assertNull(TaskParser.parse("dim the flashlight slowly"))
        assertNull(TaskParser.parse("turn on the lights"))
    }

    // ── normalization (A9) / politeness ─────────────────────────────────────────
    @Test
    fun `trailing politeness is stripped before anchoring`() {
        assertEquals("back", TaskParser.parse("go back please")?.action)
        assertEquals("open_app", TaskParser.parse("open youtube please")?.action)
    }

    // ── compound guard (A10) ────────────────────────────────────────────────────
    @Test
    fun `compound guard routes to nothing (falls through)`() {
        assertNull(TaskParser.parse("open chrome and give me the weather"))
        assertNull(TaskParser.parse("launch youtube then search"))
    }

    @Test
    fun `compound guard shares the router oracle`() {
        assertEquals(true, TaskParser.isCompound("open app and send message"))
        assertEquals(false, TaskParser.isCompound("open the books app"))
    }
}

/** Collapse flashlight sub-actions to the stable semantic token for assertions. */
private fun TaskParser.ParseResult.semantic(): String =
    if (action.startsWith("flashlight")) "flashlight" else action
