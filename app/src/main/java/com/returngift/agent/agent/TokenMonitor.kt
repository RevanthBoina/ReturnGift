// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

import com.returngift.agent.utils.XLog

/**
 * Tracks token usage and estimated cost during agent task execution.
 * Updated after each LLM call in the agent loop.
 *
 * There is NO token ceiling here. Usage is reported for display/telemetry only;
 * the only thing that can stop a task on cost is the user-configured
 * [TaskBudget] (Settings → task budget), and it is unlimited unless the user
 * sets it. The previous absolute 200K "CRITICAL" hard abort was removed on
 * request: it silently killed long-but-legitimate tasks regardless of the
 * user's own budget.
 */
class TokenMonitor(private val modelName: String) {

    private var totalInputTokens: Int = 0
    private var totalOutputTokens: Int = 0
    private var totalTokens: Int = 0
    private var currentStep: Int = 0

    /**
     * Cosmetic display bands only — they never abort a task and carry no limit.
     * There is deliberately no upper band: token use is unbounded.
     */
    enum class State {
        NORMAL,     // < 30K
        CAUTION,    // >= 30K
        WARNING,    // >= 100K
    }

    data class Status(
        val step: Int,
        val totalTokens: Int,
        val inputTokens: Int,
        val outputTokens: Int,
        val estimatedCostUsd: Double,
        val state: State,
        val formattedTokens: String,
        val formattedCost: String
    )

    /**
     * Record token usage from one LLM call.
     * Call this after each agent loop iteration.
     */
    fun record(step: Int, inputTokens: Int?, outputTokens: Int?, totalTokenCount: Int?) {
        currentStep = step
        if (inputTokens != null) totalInputTokens += inputTokens
        if (outputTokens != null) totalOutputTokens += outputTokens
        if (totalTokenCount != null) {
            totalTokens += totalTokenCount
        } else {
            totalTokens = totalInputTokens + totalOutputTokens
        }

        val status = getStatus()
        XLog.i(TAG, "Step $step: ${status.formattedTokens} tokens, ${status.formattedCost} [${status.state}]")
    }

    /**
     * Get current token status snapshot.
     */
    fun getStatus(): Status {
        val cost = ModelPricing.estimateCost(modelName, totalInputTokens, totalOutputTokens)
        val state = when {
            totalTokens >= 100_000 -> State.WARNING
            totalTokens >= 30_000 -> State.CAUTION
            else -> State.NORMAL
        }
        return Status(
            step = currentStep,
            totalTokens = totalTokens,
            inputTokens = totalInputTokens,
            outputTokens = totalOutputTokens,
            estimatedCostUsd = cost,
            state = state,
            formattedTokens = ModelPricing.formatTokens(totalTokens),
            formattedCost = ModelPricing.formatCost(cost)
        )
    }

    fun reset() {
        totalInputTokens = 0
        totalOutputTokens = 0
        totalTokens = 0
        currentStep = 0
    }

    companion object {
        private const val TAG = "TokenMonitor"
    }
}
