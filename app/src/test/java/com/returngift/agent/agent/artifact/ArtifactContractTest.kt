package com.returngift.agent.agent.artifact

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactContractTest {

    // ── Inference ────────────────────────────────────────────────────────────

    @Test
    fun binaryDeliverableTask_isEnforced() {
        assertTrue(ArtifactContract.fromTask("prepare a PDF plan for my week").buildPromptSection().isNotEmpty())
        assertTrue(ArtifactContract.fromTask("create a presentation about climate change").buildPromptSection().isNotEmpty())
        assertTrue(ArtifactContract.fromTask("make me a website for my bakery").buildPromptSection().isNotEmpty())
        assertTrue(ArtifactContract.fromTask("generate an excel spreadsheet of expenses").buildPromptSection().isNotEmpty())
    }

    @Test
    fun saveNoteTask_isEnforced() {
        assertTrue(ArtifactContract.fromTask("save a note about the meeting").buildPromptSection().isNotEmpty())
        assertTrue(ArtifactContract.fromTask("save the plan to my notes").buildPromptSection().isNotEmpty())
        assertTrue(ArtifactContract.fromTask("add a todo to buy milk").buildPromptSection().isNotEmpty())
    }

    @Test
    fun unrelatedTask_isNotEnforced() {
        assertTrue(ArtifactContract.fromTask("open WhatsApp").buildPromptSection().isEmpty())
        assertTrue(ArtifactContract.fromTask("what is the battery level").buildPromptSection().isEmpty())
        assertTrue(ArtifactContract.fromTask("send good morning to Mom on WhatsApp").buildPromptSection().isEmpty())
    }

    @Test
    fun unenforcedTask_neverBlocksFinish() {
        val c = ArtifactContract.fromTask("open YouTube")
        assertNull(c.maybeBlockFinish("Opened YouTube"))
        assertFalse(c.shouldBlockTextOnlyCompletion("Here you go"))
    }

    // ── MARKDOWN_NOTE gate ───────────────────────────────────────────────────

    @Test
    fun noteTask_blocksFinishUntilArtifactSaved() {
        val c = ArtifactContract.fromTask("save a note about the meeting")
        assertNotNull(c.maybeBlockFinish("Done."))
        c.recordKbToolResult("kb_write", "Written: notes/meeting.md")
        assertNull(c.maybeBlockFinish("Saved to notes/meeting.md"))
    }

    @Test
    fun noteTask_ignoresFailedOrUnrelatedToolResults() {
        val c = ArtifactContract.fromTask("save a note about the meeting")
        c.recordKbToolResult("kb_write", "Error: storage unavailable")
        c.recordKbToolResult("kb_read", "Read: notes/old.md")
        c.recordKbToolResult("tap", "ok")
        assertNotNull(c.maybeBlockFinish("Done."))
    }

    @Test
    fun noteTask_blocksTextOnlyCompletionUntilSaved() {
        val c = ArtifactContract.fromTask("save a note about the meeting")
        assertTrue(c.shouldBlockTextOnlyCompletion("Sure, noted."))
        c.recordKbToolResult("kb_append", "Appended to: notes/meeting.md")
        assertFalse(c.shouldBlockTextOnlyCompletion("Saved it to notes/meeting.md"))
    }

    // ── EXTERNAL_ARTIFACT gate ───────────────────────────────────────────────

    @Test
    fun pdfTask_blocksHallucinatedClaim() {
        val c = ArtifactContract.fromTask("prepare a PDF plan for my week")
        assertNotNull(c.maybeBlockFinish("I've prepared the PDF plan for you."))
        assertNotNull(c.maybeBlockFinish("Your presentation has been created successfully."))
        assertNotNull(c.maybeBlockFinish("The website is ready."))
    }

    @Test
    fun pdfTask_acceptsHonestFinish() {
        val c = ArtifactContract.fromTask("prepare a PDF plan for my week")
        assertNull(c.maybeBlockFinish("I cannot create PDF files on-device."))
        assertNull(c.maybeBlockFinish("Binary deliverables are not supported here."))
    }

    @Test
    fun pdfTask_withSavedMarkdown_allowsExportGuidance() {
        val c = ArtifactContract.fromTask("prepare a PDF plan for my week")
        c.recordKbToolResult("kb_write", "Written: notes/week-plan.md")
        assertNull(c.maybeBlockFinish("Saved the plan to notes/week-plan.md — export it as a PDF from the Vault."))
    }

    @Test
    fun pdfTask_blocksTextOnlyHallucination() {
        val c = ArtifactContract.fromTask("prepare a PDF plan for my week")
        assertTrue(c.shouldBlockTextOnlyCompletion("I've prepared the PDF for you."))
        assertFalse(c.shouldBlockTextOnlyCompletion("I cannot produce PDFs on-device."))
    }

    // ── extractKbPath ────────────────────────────────────────────────────────

    @Test
    fun extractKbPath_parsesKnownPrefixes() {
        assertTrue(ArtifactContract.extractKbPath("kb_write", "Written: notes/a.md") == "notes/a.md")
        assertTrue(ArtifactContract.extractKbPath("kb_append", "Appended to: todos/2026-08-20.md") == "todos/2026-08-20.md")
        assertNull(ArtifactContract.extractKbPath("kb_read", "Read: notes/a.md"))
        assertNull(ArtifactContract.extractKbPath("kb_write", null))
        assertNull(ArtifactContract.extractKbPath("kb_write", "Error: disk full"))
    }
}
