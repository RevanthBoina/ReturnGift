// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.prefs

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Toggles behind Settings → App permissions. */
class AgentPreferencesTest {

    private val storage = mutableMapOf<String, Boolean>()

    @Before
    fun setUp() {
        storage.clear()
        AgentPreferences.storageGet = { key, default -> storage[key] ?: default }
        AgentPreferences.storagePut = { key, value -> storage[key] = value }
    }

    @After
    fun tearDown() {
        AgentPreferences.storageGet = { key, default ->
            if (com.returngift.agent.utils.KVUtils.isInitialized) {
                com.returngift.agent.utils.KVUtils.getBoolean(key, default)
            } else default
        }
        AgentPreferences.storagePut = { key, value ->
            if (com.returngift.agent.utils.KVUtils.isInitialized) {
                com.returngift.agent.utils.KVUtils.putBoolean(key, value)
            }
        }
    }

    @Test
    fun `permission card is off by default, the rest is on`() {
        val snap = AgentPreferences.snapshot()
        assertTrue(snap.askBeforeNewApp)
        assertTrue(snap.rememberAppChoices)
        // Asks arrive as notifications; the in-app card is only the fallback for when
        // Android refuses to post them, so it must not appear uninvited.
        assertFalse(snap.openPermissionCard)
        assertTrue(snap.notifyPermission)
        assertTrue(snap.enforceMicroSteps)
        assertEquals("Permanent", snap.grantLifetimeLabel)
    }

    @Test
    fun `toggles round-trip through storage`() {
        AgentPreferences.askBeforeNewApp = false
        AgentPreferences.rememberAppChoices = false
        AgentPreferences.openPermissionCard = false
        AgentPreferences.notifyPermission = false
        AgentPreferences.enforceMicroSteps = false

        val snap = AgentPreferences.snapshot()
        assertFalse(snap.askBeforeNewApp)
        assertFalse(snap.rememberAppChoices)
        assertFalse(snap.openPermissionCard)
        assertFalse(snap.notifyPermission)
        assertFalse(snap.enforceMicroSteps)
        assertEquals("This task only", snap.grantLifetimeLabel)
        assertEquals(5, storage.size)
    }

    @Test
    fun `each toggle is independent`() {
        AgentPreferences.enforceMicroSteps = false
        assertFalse(AgentPreferences.enforceMicroSteps)
        assertTrue(AgentPreferences.askBeforeNewApp)
        assertTrue(AgentPreferences.rememberAppChoices)
    }

    @Test
    fun `reset restores the defaults`() {
        AgentPreferences.askBeforeNewApp = false
        AgentPreferences.enforceMicroSteps = false
        AgentPreferences.resetToDefaults()
        val snap = AgentPreferences.snapshot()
        assertTrue(snap.askBeforeNewApp)
        assertTrue(snap.enforceMicroSteps)
    }

    @Test
    fun `storage failures fall back to defaults instead of crashing`() {
        AgentPreferences.storageGet = { _, _ -> throw IllegalStateException("mmkv not ready") }
        // KVUtils is not initialised in unit tests, so safeGet returns the default.
        assertTrue(AgentPreferences.askBeforeNewApp)
    }
}
