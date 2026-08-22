// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec

/**
 * Global execution budgets (pure Kotlin, JVM-unit-testable).
 *
 * Hard bounds for any device-automation run, from the bounded-executor spec:
 * 60 seconds wall clock, 8 screen reads, 15 actions, 2 retries per state,
 * 2 consecutive passive screen reads. When a budget is exceeded the task
 * terminates — it never loops open-endedly.
 */
class ExecutionBudget(
    private val wallClockMs: Long = DEFAULT_WALL_CLOCK_MS,
    private val maxActions: Int = DEFAULT_MAX_ACTIONS,
    private val maxScreenReads: Int = DEFAULT_MAX_SCREEN_READS,
    private val maxRetriesPerState: Int = DEFAULT_MAX_RETRIES_PER_STATE,
    private val maxEscalations: Int = DEFAULT_MAX_ESCALATIONS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    enum class Violation { TIMEOUT, ACTION_BUDGET, SCREEN_READ_BUDGET, RETRY_BUDGET, ESCALATION_BUDGET }

    data class Breach(val violation: Violation, val detail: String)

    private val startMs = nowMs()
    var actions = 0
        private set
    var screenReads = 0
        private set
    var escalations = 0
        private set
    private val retriesPerState = mutableMapOf<String, Int>()

    fun elapsedMs(): Long = nowMs() - startMs

    fun recordAction(): Breach? {
        actions++
        return check()
    }

    fun recordScreenRead(): Breach? {
        screenReads++
        return check()
    }

    /** @return Breach when this state already used all its retries. */
    fun recordRetry(stateName: String): Breach? {
        val n = (retriesPerState[stateName] ?: 0) + 1
        retriesPerState[stateName] = n
        if (n > maxRetriesPerState) {
            return Breach(Violation.RETRY_BUDGET, "state $stateName exceeded $maxRetriesPerState retries")
        }
        return check()
    }

    fun retriesUsed(stateName: String): Int = retriesPerState[stateName] ?: 0

    fun recordEscalation(): Breach? {
        escalations++
        if (escalations > maxEscalations) {
            return Breach(Violation.ESCALATION_BUDGET, "AI escalation budget exceeded ($maxEscalations)")
        }
        return check()
    }

    fun check(): Breach? {
        if (elapsedMs() > wallClockMs) {
            return Breach(Violation.TIMEOUT, "wall clock ${elapsedMs()}ms > ${wallClockMs}ms")
        }
        if (actions > maxActions) {
            return Breach(Violation.ACTION_BUDGET, "actions $actions > $maxActions")
        }
        if (screenReads > maxScreenReads) {
            return Breach(Violation.SCREEN_READ_BUDGET, "screen reads $screenReads > $maxScreenReads")
        }
        return null
    }

    companion object {
        const val DEFAULT_WALL_CLOCK_MS = 60_000L
        const val DEFAULT_MAX_ACTIONS = 15
        const val DEFAULT_MAX_SCREEN_READS = 8
        const val DEFAULT_MAX_RETRIES_PER_STATE = 2
        const val DEFAULT_MAX_CONSECUTIVE_PASSIVE_READS = 2
        const val DEFAULT_MAX_ESCALATIONS = 2
    }
}
