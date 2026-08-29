// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.provenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for ProvenanceTag serialization/deserialization and helper functions.
 */
class ProvenanceTagTest {

    @Test
    fun `provenance tag round-trip`() {
        // Test all Kind values - origin already includes the kind prefix
        val testCases = listOf(
            ProvenanceTag(ProvenanceTag.Kind.SCREEN, "screen:com.whatsapp", 12345L),
            ProvenanceTag(ProvenanceTag.Kind.WEB, "web:example.com", 67890L),
            ProvenanceTag(ProvenanceTag.Kind.EXTERNAL_AI, "ai:gemini", 11111L),
            ProvenanceTag(ProvenanceTag.Kind.USER, "user:typing", 22222L),
            ProvenanceTag(ProvenanceTag.Kind.SYSTEM, "system:TaskParser", 33333L)
        )

        for (original in testCases) {
            // Serialize to storage string (just the origin)
            val storage = original.toStorageString()
            assertNotNull("Storage string should not be null", storage)
            assertEquals("Storage should equal origin", original.origin, storage)
            
            // Deserialize from storage string
            val restored = ProvenanceTag.fromStorageString(storage)
            assertNotNull("Restored tag should not be null for $original", restored)
            assertEquals("Kind mismatch", original.kind, restored?.kind)
            assertEquals("Origin mismatch", original.origin, restored?.origin)
        }
    }

    @Test
    fun `provenance tag invalid storage string returns null`() {
        assertNull("Empty string should return null", ProvenanceTag.fromStorageString(""))
        assertNull("Missing colon should return null", ProvenanceTag.fromStorageString("screencoomwhatsapp"))
        assertNull("Invalid kind prefix should return null", ProvenanceTag.fromStorageString("invalid:com.whatsapp"))
        assertNull("Missing origin should return null", ProvenanceTag.fromStorageString("screen:"))
    }

    @Test
    fun `provenance helper frontmatter round-trip`() {
        val frontmatter = mutableMapOf<String, Any>()
        val tag = ProvenanceTag(ProvenanceTag.Kind.SCREEN, "screen:com.example.app")
        
        // Initially empty
        assertNull("Frontmatter should initially have no provenance", ProvenanceHelper.extractFromFrontmatter(frontmatter))
        
        // Add provenance
        ProvenanceHelper.addToFrontmatter(frontmatter, tag)
        
        // Extract and verify
        val extracted = ProvenanceHelper.extractFromFrontmatter(frontmatter)
        assertNotNull("Extracted provenance should not be null", extracted)
        assertEquals("Kind should match", tag.kind, extracted?.kind)
        assertEquals("Origin should match", tag.origin, extracted?.origin)
    }
}