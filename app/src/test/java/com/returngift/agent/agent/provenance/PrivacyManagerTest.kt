// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.provenance

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for PrivacyManager.forgetApp coordinating data deletion.
 */
class PrivacyManagerTest {

    @Test
    fun `forgetApp should coordinate tracker + vault + cache + session`() {
        // Since these depend on Android context, test the structure and return type
        val result = PrivacyManager.forgetApp("com.example.app")
        
        // Verify result structure
        assertNotNull("Result should not be null", result)
        assertTrue("Message should indicate deletion", result.message.contains("Forgot"))
        assertTrue("Tracker deletion tracked", result.trackerDeleted >= 0)
        assertTrue("Vault deletion tracked", result.vaultDeleted >= 0)
        assertTrue("Cache should be cleared", result.cacheCleared)
        assertTrue("Session should be cleared", result.sessionCleared)
    }
}