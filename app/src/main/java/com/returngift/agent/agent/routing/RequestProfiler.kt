// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.routing

/**
 * Turns the *actual* prompt into the facts the router needs.
 *
 * Why this exists (from review): the first version asked the router only for
 * "tools, yes/no" and estimated tokens from user + assistant text. That is wrong in
 * both directions on a phone agent:
 *
 *  - the **system prompt** here is large (device rules, app guides, plan line), and
 *    tool results can be long screen dumps — both were invisible to the estimate, so
 *    a request could be routed to a model whose context is too small;
 *  - a **screenshot** in the conversation was invisible too, so a vision-less model
 *    could be chosen for an image request.
 *
 * The profiler is pure (a facts record in, a routing request out), so the policy is
 * testable; the caller is responsible only for counting its own message types.
 */
object RequestProfiler {

    /** Rough English/code ratio; providers' own tokenizers vary by ±20 %. */
    const val CHARS_PER_TOKEN = 4.0

    /** Deliberate over-estimate: under-estimating truncates a live task. */
    const val SAFETY_MARGIN = 1.15

    /** A screenshot/attachment costs far more than its byte size suggests. */
    const val TOKENS_PER_IMAGE = 800

    /** Per-message overhead (role markers, separators) in tokens. */
    const val PER_MESSAGE_TOKENS = 4

    /** Completion budget we reserve when routing. */
    const val DEFAULT_COMPLETION_TOKENS = 800

    /** What the caller measured in the conversation it is about to send. */
    data class Facts(
        val systemChars: Int = 0,
        val userChars: Int = 0,
        val assistantChars: Int = 0,
        val toolResultChars: Int = 0,
        val imageCount: Int = 0,
        val messageCount: Int = 0,
        val toolSpecCount: Int = 0,
        /** Tool schemas also occupy the prompt (name + params + description). */
        val toolSchemaChars: Int = 0,
        val completionTokens: Int = DEFAULT_COMPLETION_TOKENS,
    ) {
        val totalChars: Int
            get() = systemChars + userChars + assistantChars + toolResultChars + toolSchemaChars
    }

    /**
     * Estimate prompt tokens for [facts], including everything that is actually
     * serialised and a safety margin.
     */
    fun estimatePromptTokens(facts: Facts): Int {
        val textTokens = (facts.totalChars / CHARS_PER_TOKEN).toInt() +
            (facts.messageCount * PER_MESSAGE_TOKENS) +
            (facts.toolSpecCount * 12)
        val imageTokens = facts.imageCount * TOKENS_PER_IMAGE
        return (((textTokens + imageTokens) * SAFETY_MARGIN).toInt()).coerceAtLeast(1)
    }

    /** Build the routing request (tools/vision/distances derived, never assumed). */
    fun request(
        facts: Facts,
        strategy: ProviderRouter.Strategy = ProviderRouter.Strategy.AUTO,
        providers: Set<String> = emptySet(),
    ): ProviderRouter.Request = ProviderRouter.Request(
        promptTokens = estimatePromptTokens(facts),
        completionTokens = facts.completionTokens,
        needsTools = facts.toolSpecCount > 0,
        needsVision = facts.imageCount > 0,
        strategy = strategy,
        providers = providers,
    )

    /**
     * True when the prompt is likely to overflow [contextTokens].
     *
     * Used *before* a provider call so the agent trims instead of waiting for a
     * provider-side context error (which costs a round trip and sometimes money).
     */
    fun willOverflow(facts: Facts, contextTokens: Int, headroom: Double = 0.9): Boolean {
        if (contextTokens <= 0) return false
        val estimated = estimatePromptTokens(facts) + facts.completionTokens
        return estimated > (contextTokens * headroom)
    }

    /**
     * How many characters must be dropped to fit [contextTokens].
     * Returns 0 when no trimming is needed.
     */
    fun excessChars(facts: Facts, contextTokens: Int, headroom: Double = 0.9): Int {
        if (!willOverflow(facts, contextTokens, headroom)) return 0
        val budgetTokens = (contextTokens * headroom - facts.completionTokens).toInt().coerceAtLeast(0)
        // The budget has to pay for the parts trimming cannot shrink first: per-message
        // framing, tool schemas and images. Ignoring them (the first version did) left
        // the "trimmed" prompt still over the window by exactly that overhead.
        val fixedTokens = facts.messageCount * PER_MESSAGE_TOKENS +
            facts.toolSpecCount * 12 +
            facts.imageCount * TOKENS_PER_IMAGE
        // The margin applies to the fixed part too, so deflate the budget before
        // subtracting it; otherwise the "fitted" prompt lands a few tokens over.
        val textTokenBudget = (budgetTokens / SAFETY_MARGIN).toInt() - fixedTokens
        if (textTokenBudget <= 0) return facts.totalChars
        val budgetChars = (textTokenBudget * CHARS_PER_TOKEN).toInt()
        return (facts.totalChars - budgetChars).coerceAtLeast(0)
    }
}
