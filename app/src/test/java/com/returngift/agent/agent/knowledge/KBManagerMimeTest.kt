package com.returngift.agent.agent.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KBManagerMimeTest {

    @Test
    fun `mimeOf maps known image and text extensions`() {
        assertEquals("image/png", KBManager.mimeOf("screenshots/shot.png"))
        assertEquals("image/jpeg", KBManager.mimeOf("photos/pic.jpg"))
        assertEquals("image/jpeg", KBManager.mimeOf("a/b/c.JPEG"))
        assertEquals("text/markdown", KBManager.mimeOf("notes/plan.md"))
        assertEquals("application/json", KBManager.mimeOf("data/x.json"))
        assertEquals("text/plain", KBManager.mimeOf("notes/log.txt"))
    }

    @Test
    fun `mimeOf falls back to octet-stream for unknown or missing extension`() {
        assertEquals("application/octet-stream", KBManager.mimeOf("notes/blob.xyz"))
        assertEquals("application/octet-stream", KBManager.mimeOf("noextension"))
    }

    @Test
    fun `isImage detects image extensions only`() {
        assertTrue(KBManager.isImage("x.png"))
        assertTrue(KBManager.isImage("dir/pic.webp"))
        assertFalse(KBManager.isImage("notes/x.md"))
        assertFalse(KBManager.isImage("blob.bin"))
    }

    @Test
    fun `isTextLike covers text formats`() {
        assertTrue(KBManager.isTextLike("notes/plan.md"))
        assertTrue(KBManager.isTextLike("notes/log.txt"))
        assertTrue(KBManager.isTextLike("data/x.json"))
        assertFalse(KBManager.isTextLike("screenshots/shot.png"))
        assertFalse(KBManager.isTextLike("blob.bin"))
    }
}
