package com.returngift.agent.agent.checkpoint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCheckpointStoreTest {

    @Test
    fun `slugify lowercases and hyphenates`() {
        assertEquals("send-hi-to-mom-on-whatsapp", TaskCheckpointStore.slugify("Send hi to Mom on WhatsApp!"))
    }

    @Test
    fun `slugify strips non-ascii and collapses separators`() {
        assertEquals("notes", TaskCheckpointStore.slugify("  ——Notes——  "))
    }

    @Test
    fun `slugify caps at 48 chars and trims trailing hyphen`() {
        val slug = TaskCheckpointStore.slugify("a".repeat(60) + " task")
        assertTrue(slug.length <= 48)
        assertFalse(slug.endsWith("-"))
    }

    @Test
    fun `slugify falls back for empty input`() {
        assertEquals("task", TaskCheckpointStore.slugify("!!!"))
    }

    @Test
    fun `resume intent matches bare resume words`() {
        assertTrue(TaskCheckpointStore.isResumeIntent("resume"))
        assertTrue(TaskCheckpointStore.isResumeIntent("Resume the task"))
        assertTrue(TaskCheckpointStore.isResumeIntent("  continue  "))
        assertTrue(TaskCheckpointStore.isResumeIntent("weiter"))
    }

    @Test
    fun `resume intent does not hijack unrelated sentences`() {
        assertFalse(TaskCheckpointStore.isResumeIntent("resumable downloads are great"))
        assertFalse(TaskCheckpointStore.isResumeIntent("please write my resume"))
        assertFalse(TaskCheckpointStore.isResumeIntent("open WhatsApp"))
        assertFalse(TaskCheckpointStore.isResumeIntent(""))
    }

    @Test
    fun `renderMarkdown includes task and steps`() {
        val md = TaskCheckpointStore.renderMarkdown(
            "Open WhatsApp and send hi",
            1724000000000L,
            listOf("1. open_app — ok", "2. tap_node — FAILED: node not found"),
        )
        assertTrue(md.contains("**Task**: Open WhatsApp and send hi"))
        assertTrue(md.contains("## Step history"))
        assertTrue(md.contains("- 1. open_app — ok"))
        assertTrue(md.contains("- 2. tap_node — FAILED: node not found"))
    }

    @Test
    fun `renderMarkdown handles empty steps`() {
        val md = TaskCheckpointStore.renderMarkdown("t", 1724000000000L, emptyList())
        assertTrue(md.contains("(no steps executed)"))
    }

    @Test
    fun `renderPromptContext contains history and no-redo guidance`() {
        val ctx = TaskCheckpointStore.renderPromptContext(
            TaskCheckpointStore.Checkpoint(
                path = "notes/x-draft.md",
                taskText = "do thing",
                timestamp = 1724000000000L,
                steps = listOf("1. open_app — ok"),
            )
        )
        assertTrue(ctx.contains("RESUME CONTEXT"))
        assertTrue(ctx.contains("Do NOT redo steps"))
        assertTrue(ctx.contains("- 1. open_app — ok"))
    }
}
