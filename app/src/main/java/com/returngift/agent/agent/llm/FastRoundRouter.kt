// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.llm

/**
 * Represents the decision to use either the main (slower, more capable) model
 * or a fast (smaller, specialized) model for a given reasoning round.
 */
data class RouteDecision(
    val useFast: Boolean,
    val reason: String
) {
    companion object {
        const val REASON_UNCONFIGURED = "unconfigured"
        const val REASON_MEMORY = "memory"
        const val REASON_NO_PROCEDURE = "no-procedure"
        const val REASON_MECHANICAL = "mechanical"
        const val REASON_PLANNING = "planning"
    }
}

/**
 * Makes routing decisions for LLM inference rounds based on task characteristics
 * and system constraints.
 *
 * The decision process follows this priority:
 * 1. If fast model not configured OR memory gate fails → MAIN (unconfigured/memory)
 * 2. If task has no learned procedure → MAIN (no-procedure)
 * 3. If selector cache hit AND procedure step match → FAST (mechanical)
 * 4. Otherwise → MAIN (planning)
 */
object FastRoundRouter {
    /**
     * Determines whether to use the fast model for the current inference round.
     *
     * @param taskHasProcedure Whether the current task has an associated learned procedure
     * @param procedureStepMatch Whether the current step matches the procedure's expected step
     * @param selectorCacheHit Whether the selector cache has a valid entry for current context
     * @param fastModelConfigured Whether a fast model is explicitly configured by user
     * @param memoryGate Whether system has sufficient resources to load fast engine
     * @return RouteDecision indicating whether to use fast model and reason
     */
    fun decideRound(
        taskHasProcedure: Boolean,
        procedureStepMatch: Boolean?,
        selectorCacheHit: Boolean?,
        fastModelConfigured: Boolean,
        memoryGate: Boolean
    ): RouteDecision {
        // Rule 1: Fast model not available or insufficient memory
        if (!fastModelConfigured || !memoryGate) {
            return RouteDecision(
                useFast = false,
                reason = if (!fastModelConfigured) REASON_UNCONFIGURED else REASON_MEMORY
            )
        }

        // Rule 2: No procedure means no mechanical steps to optimize
        if (!taskHasProcedure) {
            return RouteDecision(useFast = false, reason = REASON_NO_PROCEDURE)
        }

        // Rule 3: Both conditions met - we can use fast model for mechanical execution
        val cacheHit = selectorCacheHit ?: false
        val stepMatch = procedureStepMatch ?: false
        if (cacheHit && stepMatch) {
            return RouteDecision(useFast = true, reason = REASON_MECHANICAL)
        }

        // Rule 4: Default to main model for planning/ambiguous steps
        return RouteDecision(useFast = false, reason = REASON_PLANNING)
    }
}