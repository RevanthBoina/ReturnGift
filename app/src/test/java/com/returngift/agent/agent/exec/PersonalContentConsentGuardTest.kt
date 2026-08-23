// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for [PersonalContentConsentGuard]: TTL expiry, revocation, and
 * the dispatch-site target mapping. Persistence + clock are injected — no MMKV.
 */
class PersonalContentConsentGuardTest {

    private val boolStore = mutableMapOf<String, Boolean>()
    private val longStore = mutableMapOf<String, Long>()
    private var fakeNow = 1_000_000L

    @Before
    fun setUp() {
        boolStore.clear(); longStore.clear()
        PersonalContentConsentGuard.persistenceGet = { boolStore[it] ?: false }
        PersonalContentConsentGuard.persistencePut = { k, v -> boolStore[k] = v }
        PersonalContentConsentGuard.persistenceGetLong = { longStore[it] ?: 0L }
        PersonalContentConsentGuard.persistencePutLong = { k, v -> longStore[k] = v }
        PersonalContentConsentGuard.nowMs = { fakeNow }
    }

    @After
    fun tearDown() {
        PersonalContentConsentGuard.persistenceGet = { false }
        PersonalContentConsentGuard.persistencePut = { _, _ -> }
        PersonalContentConsentGuard.persistenceGetLong = { 0L }
        PersonalContentConsentGuard.persistencePutLong = { _, _ -> }
        PersonalContentConsentGuard.nowMs = { System.currentTimeMillis() }
    }

    @Test
    fun `remembered grant is honored within TTL`() {
        PersonalContentConsentGuard.remember("Gmail")
        assertTrue(PersonalContentConsentGuard.isRemembered("Gmail"))
    }

    @Test
    fun `remembered grant expires after TTL and is dropped`() {
        PersonalContentConsentGuard.remember("Gmail")
        fakeNow += PersonalContentConsentGuard.REMEMBER_TTL_MS + 1
        assertFalse(PersonalContentConsentGuard.isRemembered("Gmail"))
        // Expiry also clears the stored grant so it doesn't linger.
        assertFalse(boolStore["personal_consent_gmail"] ?: false)
    }

    @Test
    fun `legacy grant without timestamp is still honored`() {
        boolStore["personal_consent_gmail"] = true  // no _ts key
        assertTrue(PersonalContentConsentGuard.isRemembered("Gmail"))
    }

    @Test
    fun `forget revokes a remembered grant`() {
        PersonalContentConsentGuard.remember("WhatsApp")
        PersonalContentConsentGuard.forget("WhatsApp")
        assertFalse(PersonalContentConsentGuard.isRemembered("WhatsApp"))
    }

    @Test
    fun `rememberedApps lists only active grants`() {
        PersonalContentConsentGuard.remember("Gmail")
        PersonalContentConsentGuard.remember("Photos")
        assertEquals(listOf("Gmail", "Photos").sorted(),
            PersonalContentConsentGuard.rememberedApps().sorted())
    }

    @Test
    fun `checkToolTarget maps open_app package to label`() {
        assertEquals("Gmail", PersonalContentConsentGuard.checkToolTarget(
            "open_app", mapOf("package_name" to "com.google.android.gm"), null))
        assertEquals("WhatsApp", PersonalContentConsentGuard.checkToolTarget(
            "switch_app", mapOf("package_name" to "com.whatsapp"), null))
    }

    @Test
    fun `checkToolTarget returns null for non-personal app`() {
        assertNull(PersonalContentConsentGuard.checkToolTarget(
            "open_app", mapOf("package_name" to "com.android.chrome"), null))
    }

    @Test
    fun `checkToolTarget gates content readers on the tracked target`() {
        assertEquals("Gmail", PersonalContentConsentGuard.checkToolTarget(
            "get_screen_info", emptyMap(), "com.google.android.gm"))
        assertNull(PersonalContentConsentGuard.checkToolTarget(
            "get_screen_info", emptyMap(), "com.android.chrome"))
        assertNull(PersonalContentConsentGuard.checkToolTarget(
            "take_screenshot", emptyMap(), null))
    }

    @Test
    fun `checkToolTarget ignores non-content tools`() {
        assertNull(PersonalContentConsentGuard.checkToolTarget(
            "finish", mapOf("summary" to "done"), "com.google.android.gm"))
        assertNull(PersonalContentConsentGuard.checkToolTarget(
            "kb_write", mapOf("path" to "notes/x.md"), "com.google.android.gm"))
    }
}
