package com.returngift.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Roundtrip of the artifact marker line (`<!-- returngift:artifact=path|mime -->`)
 * written for SYSTEM messages with ChatMessage.artifactPath.
 */
class ChatHistoryArtifactMarkerTest {

    private fun conversationFile(dir: File, systemMarkerLine: String?): File {
        val f = File(dir, "conv.md")
        val sb = StringBuilder()
        sb.appendLine("---")
        sb.appendLine("id: test-id")
        sb.appendLine("title: Test")
        sb.appendLine("created: 2026-08-21T10:00:00")
        sb.appendLine("model: test-model")
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## System")
        sb.appendLine("<!-- returngift:timestamp=1724000000000 -->")
        if (systemMarkerLine != null) sb.appendLine(systemMarkerLine)
        sb.appendLine("Saved to vault: screenshots/shot.png")
        sb.appendLine()
        f.writeText(sb.toString())
        return f
    }

    @Test
    fun `artifact marker is parsed into ChatMessage fields`() {
        val dir = createTempDir()
        val f = conversationFile(dir, "<!-- returngift:artifact=screenshots/shot.png|image/png -->")
        val messages = ChatHistoryManager.load(f)
        assertEquals(1, messages.size)
        val msg = messages[0]
        assertEquals(ChatMessage.Role.SYSTEM, msg.role)
        assertEquals("screenshots/shot.png", msg.artifactPath)
        assertEquals("image/png", msg.artifactMime)
        assertEquals(1724000000000L, msg.timestamp)
        assertTrue(msg.content.contains("Saved to vault"))
        dir.deleteRecursively()
    }

    @Test
    fun `missing marker leaves artifact fields null (backward compatible)`() {
        val dir = createTempDir()
        val f = conversationFile(dir, null)
        val messages = ChatHistoryManager.load(f)
        assertEquals(1, messages.size)
        assertNull(messages[0].artifactPath)
        assertNull(messages[0].artifactMime)
        dir.deleteRecursively()
    }

    @Test
    fun `marker without mime yields path only`() {
        val dir = createTempDir()
        val f = conversationFile(dir, "<!-- returngift:artifact=notes/plan.md| -->")
        val messages = ChatHistoryManager.load(f)
        assertEquals("notes/plan.md", messages[0].artifactPath)
        assertNull(messages[0].artifactMime)
        dir.deleteRecursively()
    }
}
