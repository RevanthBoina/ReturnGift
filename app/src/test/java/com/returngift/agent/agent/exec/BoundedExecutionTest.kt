// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C5 / FIX 5 / FIX 10: shared bounded-execution helper. Proves a blocking body is
 * abandoned after the wall-clock bound, a fast body completes, and an exception
 * surfaces as Failed.
 */
class BoundedExecutionTest {

    @Test
    fun `fast body completes with its value`() {
        val outcome = BoundedExecution.runBounded(wallClockMs = 5_000) { "done" }
        assertTrue(outcome is BoundedExecution.Outcome.Completed)
        assertEquals("done", (outcome as BoundedExecution.Outcome.Completed).value)
    }

    @Test
    fun `never-returning body times out within the bound`() {
        val start = System.currentTimeMillis()
        val outcome = BoundedExecution.runBounded(wallClockMs = 200) {
            Thread.sleep(10_000)
            "too late"
        }
        val elapsed = System.currentTimeMillis() - start
        assertTrue("expected TimedOut, got ${outcome::class.simpleName}", outcome is BoundedExecution.Outcome.TimedOut)
        assertTrue("bound not respected: elapsed ${elapsed}ms", elapsed < 3_000)
    }

    @Test
    fun `throwing body surfaces as Failed`() {
        val outcome = BoundedExecution.runBounded(wallClockMs = 5_000) {
            throw IllegalStateException("kaboom")
        }
        assertTrue(outcome is BoundedExecution.Outcome.Failed)
        assertEquals("kaboom", (outcome as BoundedExecution.Outcome.Failed).error.message)
    }
}