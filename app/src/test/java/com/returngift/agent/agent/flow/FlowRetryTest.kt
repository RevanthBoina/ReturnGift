// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Retry budgets: bounded, growing, and never spammy. */
class FlowRetryTest {

    @Test
    fun `delays grow exponentially and stay capped`() {
        val policy = RetryPolicy("test", maxAttempts = 5, baseDelayMs = 500, maxDelayMs = 4_000, jitterMs = 0)
        assertEquals(0L, policy.delayFor(1))
        assertEquals(500L, policy.delayFor(2))
        assertEquals(1_000L, policy.delayFor(3))
        assertEquals(2_000L, policy.delayFor(4))
        assertEquals(4_000L, policy.delayFor(5))
        // Beyond the cap it never grows further.
        assertTrue(policy.delayFor(9) <= 4_000L)
    }

    @Test
    fun `jitter is bounded and deterministic`() {
        val policy = RetryPolicy("test", maxAttempts = 4, baseDelayMs = 500, maxDelayMs = 8_000, jitterMs = 250)
        val a = policy.delayFor(2)
        val b = policy.delayFor(2)
        assertEquals("jitter must be deterministic for tests", a, b)
        assertTrue(a >= 500L)
        assertTrue(a <= 500L + 250L)
    }

    @Test
    fun `attempt budgets stop at the configured count`() {
        val policy = FlowRetry.SEARCH
        assertTrue(policy.shouldRetry(1))
        assertTrue(policy.shouldRetry(4))
        assertFalse(policy.shouldRetry(5))
    }

    @Test
    fun `irreversible saves get the smallest budget`() {
        assertEquals(2, FlowRetry.SAVE.maxAttempts)
        assertFalse(FlowRetry.SAVE.shouldRetry(2))
    }

    @Test
    fun `blockers map onto the right policy`() {
        assertEquals(FlowRetry.SEARCH, FlowRetry.forBlocker(SessionBlocker.RATE_LIMITED))
        assertEquals(FlowRetry.NETWORK, FlowRetry.forBlocker(SessionBlocker.NETWORK_ERROR))
        assertEquals(FlowRetry.NAVIGATION, FlowRetry.forBlocker(SessionBlocker.CONSENT_REQUIRED))
    }

    @Test
    fun `total budget is the sum of the waits and stays small`() {
        val policy = RetryPolicy("test", maxAttempts = 4, baseDelayMs = 1_000, maxDelayMs = 4_000, jitterMs = 0)
        assertEquals(1_000L + 2_000L + 4_000L, policy.totalBudgetMs())
        // A single search may not hang the task for minutes.
        assertTrue(FlowRetry.SEARCH.totalBudgetMs() < 30_000L)
    }

    @Test
    fun `guidance tells the model to back off rather than spam`() {
        val rate = FlowRetry.guidance(SessionBlocker.RATE_LIMITED)
        assertTrue(rate.contains("retry"))
        assertTrue(rate.contains("Do not spam"))

        val login = FlowRetry.guidance(SessionBlocker.LOGIN_REQUIRED)
        assertTrue(login.contains("Never type credentials"))

        assertEquals("", FlowRetry.guidance(SessionBlocker.NONE))
    }

    @Test
    fun `every policy is valid and described`() {
        FlowRetry.ALL.forEach { policy ->
            assertTrue(policy.maxAttempts >= 1)
            assertTrue(policy.maxDelayMs >= policy.baseDelayMs)
            assertTrue(policy.describe().contains(policy.name))
            assertTrue("${policy.name} should not exceed 1 min", policy.totalBudgetMs() <= 60_000L)
        }
    }
}
