// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.loop

/**
 * Detects "observe-only" stalls: rounds where the agent neither executes an
 * action tool nor observes a screen change. This is the token-burn pattern
 * (observed at 121.8K / 74.1K tokens) where the model re-reads an unchanged
 * screen until the budget is gone.
 *
 * Architecture reference:
 * - google-research/android_world m3a.py: model-written per-step history is the
 *   progress signal; a step with no action and no state change is not progress.
 * - droidrun action_history/agent_memory: bounded history drives loop exit.
 *
 * Pure Kotlin, JVM-unit-testable. Verdicts:
 * - OK: progress (action taken or screen changed), or already-hinted idle round.
 * - HINT: at [hintThreshold] consecutive idle rounds, exactly once per streak.
 * - ABORT: at [abortThreshold] consecutive idle rounds.
 *
 * A screen hash of 0 means "unknown screen" and always counts as idle.
 */
class ObserveStallGuard(
    val hintThreshold: Int = 2,
    val abortThreshold: Int = 4
) {

    enum class Verdict { OK, HINT, ABORT }

    private var lastScreenHash: Int = 0
    private var consecutiveIdleRounds: Int = 0
    private var hintInjected: Boolean = false

    /**
     * Record one agent-loop round.
     *
     * @param madeAction true if any ACTION_TOOLS tool executed this round
     * @param screenHash hash of the current screen content; 0 = unknown
     */
    fun recordRound(madeAction: Boolean, screenHash: Int): Verdict {
        val screenChanged = screenHash != 0 && screenHash != lastScreenHash
        if (screenHash != 0) lastScreenHash = screenHash

        if (madeAction || screenChanged) {
            consecutiveIdleRounds = 0
            hintInjected = false
            return Verdict.OK
        }

        consecutiveIdleRounds++
        if (consecutiveIdleRounds >= abortThreshold) return Verdict.ABORT
        if (consecutiveIdleRounds >= hintThreshold && !hintInjected) {
            hintInjected = true
            return Verdict.HINT
        }
        return Verdict.OK
    }

    /** One-time notice telling the model to act, ask, or finish instead of re-observing. */
    fun buildHintMessage(): String =
        "[System Notice] You have spent $consecutiveIdleRounds consecutive rounds observing " +
        "an unchanged screen without acting. Do NOT re-check the screen again. Either take " +
        "a concrete action toward the goal, call ask_user if you are blocked, or call finish " +
        "with what you have so far."

    fun reset() {
        lastScreenHash = 0
        consecutiveIdleRounds = 0
        hintInjected = false
    }
}
