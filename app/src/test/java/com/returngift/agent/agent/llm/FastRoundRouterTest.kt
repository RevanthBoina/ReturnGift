// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth-table test for [FastRoundRouter.decideRound]. 16 representative rows
 * cover the 4-binary-input + 2-nullable-input truth space. Style mirrors
 * [com.returngift.agent.agent.exec.TaskIntentClassifierTest].
 */
class FastRoundRouterTest {

    private fun decide(
        fastCfg: Boolean,
        memGate: Boolean,
        hasProc: Boolean,
        cache: Boolean?,
        procMatch: Boolean?,
    ): RouteDecision = FastRoundRouter.decideRound(
        taskHasProcedure = hasProc,
        procedureStepMatch = procMatch,
        selectorCacheHit = cache,
        fastModelConfigured = fastCfg,
        memoryGate = memGate,
    )

    @Test fun `unconfigured overrides everything`() {
        val d = decide(false, true, true, true, true)
        assertFalse(d.useFast)
        assertEquals("unconfigured", d.reason)
    }

    @Test fun `memory overrides when configured but gate says no`() {
        val d = decide(true, false, true, true, true)
        assertFalse(d.useFast)
        assertEquals("memory", d.reason)
    }

    @Test fun `no-procedure when task lacks a learned procedure`() {
        val d = decide(true, true, false, true, true)
        assertFalse(d.useFast)
        assertEquals("no-procedure", d.reason)
    }

    @Test fun `planning when cache is null even with procedure match`() {
        val d = decide(true, true, true, null, true)
        assertFalse(d.useFast)
        assertEquals("planning", d.reason)
    }

    @Test fun `planning when procedure match is null even with cache hit`() {
        val d = decide(true, true, true, true, null)
        assertFalse(d.useFast)
        assertEquals("planning", d.reason)
    }

    @Test fun `planning when both signals are null`() {
        val d = decide(true, true, true, null, null)
        assertFalse(d.useFast)
        assertEquals("planning", d.reason)
    }

    @Test fun `planning when cache is false and match is true`() {
        val d = decide(true, true, true, false, true)
        assertFalse(d.useFast)
        assertEquals("planning", d.reason)
    }

    @Test fun `planning when cache is true and match is false`() {
        val d = decide(true, true, true, true, false)
        assertFalse(d.useFast)
        assertEquals("planning", d.reason)
    }

    @Test fun `mechanical happy path`() {
        val d = decide(true, true, true, true, true)
        assertTrue(d.useFast)
        assertEquals("mechanical", d.reason)
    }

    @Test fun `unconfigured beats memory and no-procedure`() {
        val d = decide(false, false, false, false, false)
        assertFalse(d.useFast)
        assertEquals("unconfigured", d.reason)
    }

    @Test fun `unconfigured beats everything even with nulls`() {
        val d = decide(false, true, true, true, null)
        assertFalse(d.useFast)
        assertEquals("unconfigured", d.reason)
    }

    @Test fun `memory beats no-procedure`() {
        val d = decide(true, false, true, null, true)
        assertFalse(d.useFast)
        assertEquals("memory", d.reason)
    }

    @Test fun `no-procedure beats planning`() {
        val d = decide(true, true, false, false, false)
        assertFalse(d.useFast)
        assertEquals("no-procedure", d.reason)
    }

    @Test fun `planning when no signals at all`() {
        val d = decide(true, true, true, false, false)
        assertFalse(d.useFast)
        assertEquals("planning", d.reason)
    }

    @Test fun `mechanical is the only useFast path`() {
        // Sanity: out of all 16 representative rows, only the one true×true×true×true×true
        // returns useFast=true. Every other row must return useFast=false.
        val rows = listOf(
            Triple(false, true, true),
            Triple(true, false, true),
            Triple(true, true, false),
            Triple(true, true, true),
        )
        for ((fastCfg, memGate, hasProc) in rows) {
            for (cache in listOf<Boolean?>(true, false, null)) {
                for (procMatch in listOf<Boolean?>(true, false, null)) {
                    val d = decide(fastCfg, memGate, hasProc, cache, procMatch)
                    val expectFast = (d.reason == "mechanical")
                    assertEquals("cfg=$fastCfg mem=$memGate proc=$hasProc cache=$cache match=$procMatch",
                        expectFast, d.useFast)
                }
            }
        }
    }

    @Test fun `reason is always a closed-vocab token`() {
        val closed = setOf("unconfigured", "memory", "no-procedure", "mechanical", "planning")
        val rows = listOf(
            Triple(false, true, true),
            Triple(true, false, true),
            Triple(true, true, false),
            Triple(true, true, true),
        )
        for ((fastCfg, memGate, hasProc) in rows) {
            for (cache in listOf<Boolean?>(true, false, null)) {
                for (procMatch in listOf<Boolean?>(true, false, null)) {
                    val d = decide(fastCfg, memGate, hasProc, cache, procMatch)
                    assertTrue("reason=${d.reason} not in closed vocab", d.reason in closed)
                }
            }
        }
    }
}
