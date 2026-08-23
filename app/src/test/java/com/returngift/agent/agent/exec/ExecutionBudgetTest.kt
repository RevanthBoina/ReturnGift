// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExecutionBudgetTest {

    @Test
    fun `action budget trips after the cap`() {
        val budget = ExecutionBudget(maxActions = 3)
        assertNull(budget.recordAction())
        assertNull(budget.recordAction())
        assertNull(budget.recordAction())
        val breach = budget.recordAction()
        assertEquals(ExecutionBudget.Violation.ACTION_BUDGET, breach?.violation)
    }

    @Test
    fun `screen read budget trips after the cap`() {
        val budget = ExecutionBudget(maxScreenReads = 2)
        assertNull(budget.recordScreenRead())
        assertNull(budget.recordScreenRead())
        assertEquals(ExecutionBudget.Violation.SCREEN_READ_BUDGET, budget.recordScreenRead()?.violation)
    }

    @Test
    fun `retries are bounded per state`() {
        val budget = ExecutionBudget(maxRetriesPerState = 2)
        assertNull(budget.recordRetry("FIND_TARGET"))
        assertNull(budget.recordRetry("FIND_TARGET"))
        // A different state has its own allowance.
        assertNull(budget.recordRetry("PERFORM_ACTION"))
        // Third retry of the same state breaches.
        assertEquals(ExecutionBudget.Violation.RETRY_BUDGET, budget.recordRetry("FIND_TARGET")?.violation)
    }

    @Test
    fun `watchdog stall in state B is not failed by an earlier stall in state A`() {
        // Regression: a task-global recovery counter permanently exhausted the budget
        // after two unrelated benign stalls. Per-state keys (screen hash + package)
        // give each UI state its own budget.
        val budget = ExecutionBudget(maxRetriesPerState = 2)
        val stateA = "111@com.whatsapp"
        val stateB = "222@com.whatsapp"
        // Stall in state A → first recovery consumed for A.
        assertNull(budget.recordRetry(stateA))
        assertEquals(1, budget.retriesUsed(stateA))
        // Progress happens (screen changes) → stall in state B must get a FRESH budget.
        assertNull(budget.recordRetry(stateB))
        assertEquals(1, budget.retriesUsed(stateB))
        // A's own budget is unaffected by B and still bounds A at 2.
        assertNull(budget.recordRetry(stateA))
        assertEquals(ExecutionBudget.Violation.RETRY_BUDGET, budget.recordRetry(stateA)?.violation)
        // B still has one recovery left even after A is exhausted.
        assertNull(budget.recordRetry(stateB))
        assertEquals(ExecutionBudget.Violation.RETRY_BUDGET, budget.recordRetry(stateB)?.violation)
    }

    @Test
    fun `escalations are bounded`() {
        val budget = ExecutionBudget(maxEscalations = 2)
        assertNull(budget.recordEscalation())
        assertNull(budget.recordEscalation())
        assertEquals(ExecutionBudget.Violation.ESCALATION_BUDGET, budget.recordEscalation()?.violation)
    }

    @Test
    fun `wall clock timeout is enforced`() {
        var now = 1_000L
        val budget = ExecutionBudget(wallClockMs = 60_000, nowMs = { now })
        assertNull(budget.check())
        now += 61_000
        assertEquals(ExecutionBudget.Violation.TIMEOUT, budget.check()?.violation)
    }
}
