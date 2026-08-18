// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.loop

import com.returngift.agent.utils.XLog

/**
 * Experienced-Agent Adaptive Observation Policy.
 *
 * Inspired by Stagehand's observe -> act separation:
 * - Replaces blind "check screen -> touch -> check screen -> touch" loops.
 * - Dynamic decision engine: observe only when needed (state transitions, errors, navigation),
 *   execute actions in predictable bursts when confident, and verify only at critical milestones.
 */
object ObservationPolicy {

    private const val TAG = "ObservationPolicy"

    enum class ObservationDecision {
        ALWAYS,          // Must re-observe (errors, navigation, app launch, critical milestones)
        ON_STATE_CHANGE, // Observe if state hash changed or diff detected
        SKIP             // Skip full screen re-read; proceed with predicted state
    }

    /**
     * Tools that always require full visual observation afterwards.
     */
    private val MANDATORY_OBSERVE_TOOLS = setOf(
        "open_app", "switch_app", "system_key", "phone_long_press", "scroll_to_find"
    )

    /**
     * Predictable action tools where observation can be batched/skipped during confident flows.
     */
    private val PREDICTABLE_TOOLS = setOf(
        "input_text", "type_text", "clipboard"
    )

    /**
     * Determine whether full screen observation is necessary for the next round.
     */
    fun evaluate(
        lastTool: String,
        lastSuccess: Boolean,
        consecutiveActionsWithoutObserve: Int,
        isFollowingProcedure: Boolean,
        screenHashChanged: Boolean
    ): ObservationDecision {
        // 1. Failure or error -> ALWAYS observe immediately
        if (!lastSuccess) {
            XLog.d(TAG, "ObservationDecision: ALWAYS (last action failed)")
            return ObservationDecision.ALWAYS
        }

        // 2. Navigation or app-switch tools -> ALWAYS observe
        if (lastTool in MANDATORY_OBSERVE_TOOLS) {
            XLog.d(TAG, "ObservationDecision: ALWAYS (mandatory observe tool: $lastTool)")
            return ObservationDecision.ALWAYS
        }

        // 3. Max consecutive actions reached -> Force observation for grounding
        if (consecutiveActionsWithoutObserve >= 3) {
            XLog.d(TAG, "ObservationDecision: ALWAYS (reached $consecutiveActionsWithoutObserve actions without observation)")
            return ObservationDecision.ALWAYS
        }

        // 4. Following a known successful procedure with predictable inputs -> SKIP
        if (isFollowingProcedure && lastTool in PREDICTABLE_TOOLS && consecutiveActionsWithoutObserve < 2) {
            XLog.d(TAG, "ObservationDecision: SKIP (predictable tool in learned procedure)")
            return ObservationDecision.SKIP
        }

        // 5. Default: observe if screen state actually changed
        return if (screenHashChanged) {
            ObservationDecision.ON_STATE_CHANGE
        } else {
            ObservationDecision.SKIP
        }
    }
}
