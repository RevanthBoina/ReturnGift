// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * JVM unit tests for TargetSpecGate - pure Kotlin, no Android dependencies.
 */
class TargetSpecGateTest {

    // Common app aliases from AppCatalog seed
    private val testAliases = setOf(
        "linkedin", "linked in",
        "whatsapp", "wa",
        "chrome",
        "youtube", "yt",
        "gmail",
        "maps",
        "instagram", "ig",
        "photos",
        "settings",
        "camera",
        "messages",
        "calendar",
        "phone",
        "contacts",
        "clock",
        "calculator",
        "files",
        "drive",
        "spotify",
        "twitter", "x",
        "facebook",
        "tiktok",
        "snapchat",
        "reddit",
        "discord",
        "slack",
        "wechat",
        "line"
    )

    @Test
    fun `open 5 apps with 0 named fires gate`() {
        val task = "open 5 apps and take screenshots"
        val result = TargetSpecGate.missingTargets(task, testAliases)
        
        assertNotNull("should fire for 'open 5 apps' with 0 named", result)
        assertEquals("requestedCount", 5, result!!.requestedCount)
        assertEquals("explicitlyNamed", 0, result.explicitlyNamed)
        assertEquals("kind", "apps", result.kind)
        assertEquals("matchedApps", 0, result.matchedApps.size)
    }

    @Test
    fun `open five apps with word number fires gate`() {
        val task = "open five apps and screenshot each"
        val result = TargetSpecGate.missingTargets(task, testAliases)
        
        assertNotNull("should fire for 'open five apps' (word number)", result)
        assertEquals("requestedCount", 5, result!!.requestedCount)
        assertEquals("explicitlyNamed", 0, result.explicitlyNamed)
        assertEquals("kind", "apps", result.kind)
    }

    @Test
    fun `open chrome youtube gmail maps whatsapp - 5 named does NOT fire`() {
        val task = "open chrome, youtube, gmail, maps, whatsapp and screenshot each"
        val result = TargetSpecGate.missingTargets(task, testAliases)
        
        assertNull("should NOT fire when 5 apps explicitly named", result)
    }

    @Test
    fun `message 3 contacts fires with kind contacts`() {
        val task = "message 3 contacts"
        val result = TargetSpecGate.missingTargets(task, testAliases)
        
        assertNotNull("should fire for 'message 3 contacts'", result)
        assertEquals("requestedCount", 3, result!!.requestedCount)
        assertEquals("explicitlyNamed", 0, result.explicitlyNamed)
        assertEquals("kind", "contacts", result.kind)
    }

    @Test
    fun `launch several apps fires with count 3`() {
        val task = "launch several apps"
        val result = TargetSpecGate.missingTargets(task, testAliases)
        
        assertNotNull("should fire for 'launch several apps'", result)
        assertEquals("requestedCount", 3, result!!.requestedCount) // several = 3
        assertEquals("kind", "apps", result.kind)
    }

    @Test
    fun `open a couple of apps fires with count 2`() {
        val task = "open a couple of apps"
        val result = TargetSpecGate.missingTargets(task, testAliases)
        
        assertNotNull("should fire for 'open a couple of apps'", result)
        assertEquals("requestedCount", 2, result!!.requestedCount) // a couple = 2
        assertEquals("kind", "apps", result.kind)
    }

    @Test
    fun `open 2 apps with 1 named still fires`() {
        val task = "open chrome and one more app"
        val result = TargetSpecGate.missingTargets(task, testAliases)
        
        assertNotNull("should fire when fewer than requested are named", result)
        assertEquals("requestedCount", 2, result!!.requestedCount)
        assertEquals("explicitlyNamed", 1, result.explicitlyNamed) // chrome is named
        assertEquals("kind", "apps", result.kind)
    }

    @Test
    fun `text 4 people fires with kind contacts`() {
        val task = "text 4 people"
        val result = TargetSpecGate.missingTargets(task, testAliases)
        
        assertNotNull("should fire for 'text 4 people'", result)
        assertEquals("requestedCount", 4, result!!.requestedCount)
        assertEquals("kind", "contacts", result.kind)
    }

    @Test
    fun `call five contacts fires with word number`() {
        val task = "call five contacts"
        val result = TargetSpecGate.missingTargets(task, testAliases)
        
        assertNotNull("should fire for 'call five contacts'", result)
        assertEquals("requestedCount", 5, result!!.requestedCount)
        assertEquals("kind", "contacts", result.kind)
    }

    @Test
    fun `screenshot some apps fires`() {
        val task = "screenshot some apps"
        val result = TargetSpecGate.missingTargets(task, testAliases)
        
        assertNotNull("should fire for 'screenshot some apps'", result)
        assertEquals("requestedCount", 2, result!!.requestedCount) // some = 2
        assertEquals("kind", "apps", result.kind)
    }

    @Test
    fun `install a few apps fires`() {
        val task = "install a few apps"
        val result = TargetSpecGate.missingTargets(task, testAliases)
        
        assertNotNull("should fire for 'install a few apps'", result)
        assertEquals("requestedCount", 2, result!!.requestedCount) // a few = 2
        assertEquals("kind", "apps", result.kind)
    }
}