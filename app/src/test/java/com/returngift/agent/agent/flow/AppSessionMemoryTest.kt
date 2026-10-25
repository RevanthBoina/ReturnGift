// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.flow

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Consent / login facts remembered per app, with TTLs. */
class AppSessionMemoryTest {

    private class FakeStorage : AppSessionMemory.Storage {
        val booleans = mutableMapOf<String, Boolean>()
        val longs = mutableMapOf<String, Long>()
        override fun getBoolean(key: String, defaultValue: Boolean) = booleans[key] ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
        override fun getLong(key: String, defaultValue: Long) = longs[key] ?: defaultValue
        override fun putLong(key: String, value: Long) { longs[key] = value }
    }

    private lateinit var storage: FakeStorage
    private val now = 1_700_000_000_000L
    private val linkedin = "com.linkedin.android"

    @Before
    fun setUp() {
        storage = FakeStorage()
        AppSessionMemory.storage = storage
    }

    @After
    fun tearDown() {
        AppSessionMemory.storage = AppSessionMemory.KVStorage
    }

    @Test
    fun `nothing is remembered for a fresh install`() {
        assertFalse(AppSessionMemory.consentAccepted(linkedin, now))
        assertFalse(AppSessionMemory.signedIn(linkedin, now))
        assertTrue(AppSessionMemory.shouldAcceptConsentSilently(linkedin, now))
    }

    @Test
    fun `consent is remembered and expires after its TTL`() {
        AppSessionMemory.markConsentAccepted(linkedin, now)
        assertTrue(AppSessionMemory.consentAccepted(linkedin, now + 1_000))
        assertFalse(AppSessionMemory.shouldAcceptConsentSilently(linkedin, now + 1_000))

        val afterTtl = now + AppSessionMemory.CONSENT_TTL_MS + 1
        assertFalse(AppSessionMemory.consentAccepted(linkedin, afterTtl))
        assertTrue(AppSessionMemory.shouldAcceptConsentSilently(linkedin, afterTtl))
    }

    @Test
    fun `login fact expires and a wall clears it`() {
        AppSessionMemory.markSignedIn(linkedin, now)
        assertTrue(AppSessionMemory.signedIn(linkedin, now + 1_000))

        AppSessionMemory.markLoginWall(linkedin)
        assertFalse("a login wall must clear a stale signed-in claim", AppSessionMemory.signedIn(linkedin, now))

        AppSessionMemory.markSignedIn(linkedin, now)
        val stale = now + AppSessionMemory.LOGIN_TTL_MS + 1
        assertFalse(AppSessionMemory.signedIn(linkedin, stale))
    }

    @Test
    fun `facts are per package and case-insensitive`() {
        AppSessionMemory.markConsentAccepted(linkedin, now)
        assertTrue(AppSessionMemory.consentAccepted("COM.LINKEDIN.ANDROID", now))
        assertFalse(AppSessionMemory.consentAccepted("com.google.android.youtube", now))
    }

    @Test
    fun `plan accepts consent once then retries`() {
        assertEquals(
            RecoveryAction.TAP_CONSENT,
            AppSessionMemory.plan(SessionBlocker.CONSENT_REQUIRED, linkedin, now),
        )
        AppSessionMemory.markConsentAccepted(linkedin, now)
        assertEquals(
            RecoveryAction.RETRY_WITH_BACKOFF,
            AppSessionMemory.plan(SessionBlocker.CONSENT_REQUIRED, linkedin, now + 1_000),
        )
    }

    @Test
    fun `plan never auto-handles a login wall`() {
        assertEquals(
            RecoveryAction.ASK_USER,
            AppSessionMemory.plan(SessionBlocker.LOGIN_REQUIRED, linkedin, now),
        )
        // …and it records the wall so a later task does not retry blindly.
        assertFalse(AppSessionMemory.signedIn(linkedin, now))
    }

    @Test
    fun `plan maps the remaining blockers`() {
        assertEquals(RecoveryAction.STOP_AND_REPORT, AppSessionMemory.plan(SessionBlocker.CAPTCHA_REQUIRED, linkedin, now))
        assertEquals(RecoveryAction.ASK_USER, AppSessionMemory.plan(SessionBlocker.ACCOUNT_CHOOSER, linkedin, now))
        assertEquals(RecoveryAction.RETRY_WITH_BACKOFF, AppSessionMemory.plan(SessionBlocker.RATE_LIMITED, linkedin, now))
        assertEquals(RecoveryAction.NONE, AppSessionMemory.plan(SessionBlocker.NONE, linkedin, now))
    }

    @Test
    fun `forget clears everything for one app only`() {
        AppSessionMemory.markConsentAccepted(linkedin, now)
        AppSessionMemory.markSignedIn(linkedin, now)
        AppSessionMemory.markConsentAccepted("com.google.android.youtube", now)

        AppSessionMemory.forget(linkedin)

        assertFalse(AppSessionMemory.consentAccepted(linkedin, now))
        assertFalse(AppSessionMemory.signedIn(linkedin, now))
        assertTrue(AppSessionMemory.consentAccepted("com.google.android.youtube", now))
    }

    @Test
    fun `blank package names are ignored instead of writing junk keys`() {
        AppSessionMemory.markConsentAccepted("", now)
        AppSessionMemory.markSignedIn("   ", now)
        AppSessionMemory.forget("")
        assertTrue(storage.longs.isEmpty())
    }
}
