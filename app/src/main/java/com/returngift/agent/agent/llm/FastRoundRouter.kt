// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.llm

/**
 * Pure decision: route a single agent-loop round to the fast (small) engine or stay on main.
 *
 * Closed-vocab reasons (counters in logs only — no raw utterance text):
 *   - "unconfigured"   fast model not picked in settings
 *   - "memory"         memory gate says no (device RAM too low / over threshold)
 *   - "no-procedure"   task has no matched learned procedure
 *   - "mechanical"     selector-cache hit AND procedure step matches → safe small-model call
 *   - "planning"       everything else → main model
 */
data class RouteDecision(val useFast: Boolean, val reason: String)

object FastRoundRouter {
    fun decideRound(
        taskHasProcedure: Boolean,
        procedureStepMatch: Boolean?,
        selectorCacheHit: Boolean?,
        fastModelConfigured: Boolean,
        memoryGate: Boolean,
    ): RouteDecision {
        if (!fastModelConfigured) return RouteDecision(useFast = false, reason = "unconfigured")
        if (!memoryGate) return RouteDecision(useFast = false, reason = "memory")
        if (!taskHasProcedure) return RouteDecision(useFast = false, reason = "no-procedure")
        if (selectorCacheHit == true && procedureStepMatch == true) {
            return RouteDecision(useFast = true, reason = "mechanical")
        }
        return RouteDecision(useFast = false, reason = "planning")
    }
}