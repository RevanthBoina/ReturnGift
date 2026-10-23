// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the removed token ceiling.
 *
 * History: TokenMonitor used to hard-abort a task at 200K tokens
 * (`State.CRITICAL`) regardless of the user's TaskBudget. That ceiling is gone:
 * token usage is unbounded and [TokenMonitor.State] is now a cosmetic display
 * band only. These tests fail the moment a ceiling is reintroduced.
 */
class TokenMonitorTest {

    private fun monitor(): TokenMonitor = TokenMonitor("claude-haiku-4-5")

    @Test
    fun `small usage is NORMAL`() {
        val m = monitor()
        m.record(step = 1, inputTokens = 500, outputTokens = 500, totalTokenCount = 1_000)
        assertEquals(TokenMonitor.State.NORMAL, m.getStatus().state)
        assertEquals(1_000, m.getStatus().totalTokens)
    }

    @Test
    fun `usage above 30K is only a display band`() {
        val m = monitor()
        m.record(step = 1, inputTokens = 40_000, outputTokens = 0, totalTokenCount = 40_000)
        val status = m.getStatus()
        assertEquals(TokenMonitor.State.CAUTION, status.state)
        assertEquals(40_000, status.totalTokens)
    }

    @Test
    fun `200K no longer aborts and is not a distinct state`() {
        val m = monitor()
        m.record(step = 1, inputTokens = 150_000, outputTokens = 50_000, totalTokenCount = 200_000)
        val status = m.getStatus()
        assertEquals(200_000, status.totalTokens)
        // Highest band is WARNING; there is no CRITICAL ceiling any more.
        assertEquals(TokenMonitor.State.WARNING, status.state)
    }

    @Test
    fun `multi-million token usage is tracked without a ceiling`() {
        val m = monitor()
        repeat(10) { step ->
            m.record(
                step = step + 1,
                inputTokens = 400_000,
                outputTokens = 100_000,
                totalTokenCount = 500_000,
            )
        }
        val status = m.getStatus()
        assertEquals(5_000_000, status.totalTokens)
        assertEquals(TokenMonitor.State.WARNING, status.state)
        assertTrue("cost should still be estimated above the old ceiling", status.estimatedCostUsd > 0)
    }

    @Test
    fun `status never exposes a token limit field`() {
        // Guard rail: the status object carries counters only. If a limit/ceiling
        // field is ever added back, this reflection check fails loudly.
        val names = TokenMonitor.Status::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse("TokenMonitor.Status must not carry a limit", names.any { "limit" in it || "ceiling" in it })
    }
}
