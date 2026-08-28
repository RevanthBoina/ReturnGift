// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool.impl

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C5 / FIX 5: pins the pre-existing `MAX_TOTAL_STEPS` cap in [RepeatActionsTool].
 * The cap is a pure step-count bound (repeat_count × actions.size()) enforced BEFORE any
 * action executes, so a huge loop is rejected up-front. This is the deterministic bound
 * that stays in place — no token/cost budget is wired into this non-LLM path (TaskBudget
 * remains exclusive to `DefaultAgentService.runAgentLoop`).
 */
class RepeatActionsToolCapTest {

    private val tool = RepeatActionsTool()

    @Test
    fun `total steps exceeding the cap is rejected before any action runs`() {
        val actions = "[{\"tool\":\"tap\",\"params\":{\"x\":1,\"y\":1}}]"
        // 1 action × repeat_count=99999 → way over MAX_TOTAL_STEPS
        val result = tool.execute(
            mapOf("actions" to actions, "repeat_count" to "99999")
        )
        assertTrue("expected the cap error, got: ${result.data} / ${result.error}", !result.isSuccess)
        assertTrue(result.error!!.contains("exceeds max"))
    }

    @Test
    fun `a within-cap repeat is not rejected by the cap gate`() {
        val actions = "[{\"tool\":\"tap\",\"params\":{\"x\":1,\"y\":2}},{\"tool\":\"swipe\",\"params\":{\"x\":1,\"y\":1,\"x2\":2,\"y2\":2}}]"
        val result = tool.execute(
            mapOf("actions" to actions, "repeat_count" to "2")
        )
        // 2 actions × 2 = 4 steps, well under the cap; it must NOT be the cap error.
        // (Full execution touches accessibility services, so we only assert the cap gate
        // did not short-circuit — i.e. the error is not the cap message.)
        if (!result.isSuccess && result.error != null) {
            assertTrue("cap gate should not fire: ${result.error}", !result.error.contains("exceeds max"))
        }
    }
}