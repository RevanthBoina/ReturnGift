// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blocker detection for the "open LinkedIn → search 'zip'" flow: the cases that
 * used to end in a silent failure.
 */
class SessionBlockerTest {

    private fun detect(text: String) = SessionBlockerDetector.detect(text)

    // ── login walls ─────────────────────────────────────────────────────────

    @Test
    fun `linkedin sign in screen is a login wall`() {
        val screen = """
            [n1] Text "Sign in"
            [n2] Text "Email or phone"
            [n3] Text "Password"
            [n4] Button "Sign in"
            [n5] Text "Forgot password?"
        """.trimIndent()
        assertEquals(SessionBlocker.LOGIN_REQUIRED, detect(screen))
        assertTrue(SessionBlocker.LOGIN_REQUIRED.needsUser)
        assertFalse(SessionBlocker.LOGIN_REQUIRED.retryable)
    }

    @Test
    fun `a single ambiguous word is not a login wall`() {
        // A feed post mentioning "sign in" must not stop the task.
        assertEquals(SessionBlocker.NONE, detect("""[n1] Text "How to sign in to your account — 12 likes""""))
        assertEquals(SessionBlocker.NONE, detect("""[n1] Text "password""""))
    }

    @Test
    fun `two ambiguous signals are a login wall`() {
        assertEquals(
            SessionBlocker.LOGIN_REQUIRED,
            detect("""[n1] Text "Email" [n2] Text "Password""""),
        )
    }

    @Test
    fun `continue with google alone is a login wall`() {
        assertEquals(
            SessionBlocker.LOGIN_REQUIRED,
            detect("""[n1] Button "Continue with Google""""),
        )
    }

    @Test
    fun `account chooser is its own blocker`() {
        assertEquals(
            SessionBlocker.ACCOUNT_CHOOSER,
            detect("""[n1] Text "Choose an account" [n2] Text "Use another account""""),
        )
        assertEquals(RecoveryAction.ASK_USER, SessionBlockerDetector.action(SessionBlocker.ACCOUNT_CHOOSER))
    }

    // ── captcha ─────────────────────────────────────────────────────────────

    @Test
    fun `captcha wins over everything else`() {
        val screen = "Sign in / Email or phone / Please verify you are human"
        assertEquals(SessionBlocker.CAPTCHA_REQUIRED, detect(screen))
        assertEquals(RecoveryAction.STOP_AND_REPORT, SessionBlockerDetector.action(SessionBlocker.CAPTCHA_REQUIRED))
        assertTrue(SessionBlocker.CAPTCHA_REQUIRED.needsUser)
    }

    // ── consent ─────────────────────────────────────────────────────────────

    @Test
    fun `cookie banner is consent, not a login wall`() {
        val screen = """[n1] Text "We value your privacy" [n2] Button "Accept all" [n3] Button "No thanks""""
        assertEquals(SessionBlocker.CONSENT_REQUIRED, detect(screen))
        assertTrue(SessionBlocker.CONSENT_REQUIRED.autoDismissable)
        assertEquals(RecoveryAction.TAP_CONSENT, SessionBlockerDetector.action(SessionBlocker.CONSENT_REQUIRED))
    }

    @Test
    fun `consent banner on top of a login wall is reported as a login wall`() {
        val screen = "Accept cookies. Sign in with email or phone. Forgot password?"
        assertEquals(SessionBlocker.LOGIN_REQUIRED, detect(screen))
    }

    // ── throttling / network ────────────────────────────────────────────────

    @Test
    fun `rate limiting is retryable with backoff`() {
        assertEquals(
            SessionBlocker.RATE_LIMITED,
            detect("""[n1] Text "Too many requests. Try again later.""""),
        )
        assertTrue(SessionBlocker.RATE_LIMITED.retryable)
        assertEquals(
            RecoveryAction.RETRY_WITH_BACKOFF,
            SessionBlockerDetector.action(SessionBlocker.RATE_LIMITED),
        )
    }

    @Test
    fun `network failure is recognised`() {
        assertEquals(SessionBlocker.NETWORK_ERROR, detect("Check your connection and try again"))
        assertEquals(RecoveryAction.RETRY_WITH_BACKOFF, SessionBlockerDetector.action(SessionBlocker.NETWORK_ERROR))
    }

    // ── clean screens ───────────────────────────────────────────────────────

    @Test
    fun `an ordinary screen has no blocker`() {
        val feed = """
            [n1] Text "Start a post"
            [n2] Button "Search"
            [n3] Text "Zip — fintech platform"
        """.trimIndent()
        assertEquals(SessionBlocker.NONE, detect(feed))
        assertEquals("", SessionBlockerDetector.message(SessionBlocker.NONE))
        assertEquals(RecoveryAction.NONE, SessionBlockerDetector.action(SessionBlocker.NONE))
    }

    @Test
    fun `blank screen text is not a blocker`() {
        assertEquals(SessionBlocker.NONE, detect(""))
        assertEquals(SessionBlocker.NONE, detect("   "))
    }

    // ── messages ────────────────────────────────────────────────────────────

    @Test
    fun `messages name the app, the fix and whether anything changed`() {
        val login = SessionBlockerDetector.message(SessionBlocker.LOGIN_REQUIRED, "LinkedIn")
        assertTrue(login.contains("Login required"))
        assertTrue(login.contains("LinkedIn"))
        assertTrue(login.contains("Nothing was sent or changed"))

        val captcha = SessionBlockerDetector.message(SessionBlocker.CAPTCHA_REQUIRED, "LinkedIn")
        assertTrue(captcha.contains("human verification"))

        val rate = SessionBlockerDetector.message(SessionBlocker.RATE_LIMITED, "LinkedIn")
        assertTrue(rate.contains("retry"))
    }

    @Test
    fun `isLoginWall matches detect for login screens`() {
        assertTrue(SessionBlockerDetector.isLoginWall("forgot password"))
        assertFalse(SessionBlockerDetector.isLoginWall("zip — fintech"))
        assertFalse(SessionBlockerDetector.isLoginWall(""))
    }
}
