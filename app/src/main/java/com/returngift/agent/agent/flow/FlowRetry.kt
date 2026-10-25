// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.flow

/**
 * Retry budgets with exponential backoff.
 *
 * Blind retrying is what makes UI automation flaky: hammering a rate-limited
 * app makes it worse, and waiting 30 s on a login wall wastes the user's time.
 * Each flow therefore gets its own small budget:
 *
 *  - [SEARCH]  results that are slow to arrive (5 tries, 0.5 s → 8 s)
 *  - [NAVIGATION] a tap/deep link that did not change the screen (3 tries)
 *  - [NETWORK] offline / connection errors (4 tries)
 *  - [SAVE] irreversible writes (2 tries — never spam a submission)
 *
 * Delays use deterministic jitter instead of randomness so tests are stable and
 * two agents retrying in lock-step still spread out.
 *
 * Pure Kotlin — unit tested in `FlowRetryTest`.
 */
data class RetryPolicy(
    val name: String,
    val maxAttempts: Int,
    val baseDelayMs: Long,
    val maxDelayMs: Long,
    val multiplier: Double = 2.0,
    val jitterMs: Long = 250,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(baseDelayMs >= 0) { "baseDelayMs must be >= 0" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs must be >= baseDelayMs" }
        require(multiplier >= 1.0) { "multiplier must be >= 1.0" }
    }

    /** True while another attempt is still allowed. [attempt] is 1-based. */
    fun shouldRetry(attempt: Int): Boolean = attempt < maxAttempts

    /**
     * Delay before attempt [attempt] (1-based: the delay *after* attempt 1 is
     * `delayFor(2)`). Grows exponentially and is capped at [maxDelayMs].
     */
    fun delayFor(attempt: Int): Long {
        if (attempt <= 1) return 0
        val exponent = attempt - 2
        var delay = baseDelayMs
        repeat(exponent) {
            delay = (delay * multiplier).toLong().coerceAtMost(maxDelayMs)
        }
        delay = delay.coerceAtMost(maxDelayMs)
        return (delay + jitterFor(attempt)).coerceAtMost(maxDelayMs)
    }

    /** Deterministic spread so parallel retries do not collide. */
    private fun jitterFor(attempt: Int): Long =
        if (jitterMs <= 0) 0 else ((attempt * 37L) % (jitterMs + 1))

    /** Total wall-clock this policy can spend waiting. */
    fun totalBudgetMs(): Long {
        var total = 0L
        for (attempt in 2..maxAttempts) total += delayFor(attempt)
        return total
    }

    fun describe(): String = "$name: up to $maxAttempts attempts, ${baseDelayMs}ms→${maxDelayMs}ms backoff"
}

object FlowRetry {

    val SEARCH = RetryPolicy(
        name = "search results",
        maxAttempts = 5,
        baseDelayMs = 500,
        maxDelayMs = 8_000,
    )

    val NAVIGATION = RetryPolicy(
        name = "navigation",
        maxAttempts = 3,
        baseDelayMs = 800,
        maxDelayMs = 4_000,
    )

    val NETWORK = RetryPolicy(
        name = "network",
        maxAttempts = 4,
        baseDelayMs = 1_000,
        maxDelayMs = 10_000,
    )

    /** Irreversible actions get the smallest budget — never double-submit. */
    val SAVE = RetryPolicy(
        name = "save/submit",
        maxAttempts = 2,
        baseDelayMs = 1_000,
        maxDelayMs = 2_000,
    )

    val ALL: List<RetryPolicy> = listOf(SEARCH, NAVIGATION, NETWORK, SAVE)

    /** The policy a blocker should be retried with. */
    fun forBlocker(blocker: SessionBlocker): RetryPolicy = when (blocker) {
        SessionBlocker.RATE_LIMITED -> SEARCH
        SessionBlocker.NETWORK_ERROR -> NETWORK
        SessionBlocker.CONSENT_REQUIRED -> NAVIGATION
        SessionBlocker.LOGIN_REQUIRED, SessionBlocker.ACCOUNT_CHOOSER,
        SessionBlocker.CAPTCHA_REQUIRED, SessionBlocker.NONE,
        -> NAVIGATION
    }

    /** One-line hint for the model, e.g. when results are slow. */
    fun guidance(blocker: SessionBlocker): String {
        val policy = forBlocker(blocker)
        return when (blocker) {
            SessionBlocker.RATE_LIMITED ->
                "The app is throttling. Wait ~${policy.delayFor(2)}ms, then retry (max ${policy.maxAttempts} times). Do not spam the search."
            SessionBlocker.NETWORK_ERROR ->
                "Connection problem. Wait ~${policy.delayFor(2)}ms and retry; if it keeps failing, tell the user instead of looping."
            SessionBlocker.CONSENT_REQUIRED ->
                "Accept the consent prompt once, then continue — it should not reappear."
            SessionBlocker.LOGIN_REQUIRED, SessionBlocker.ACCOUNT_CHOOSER ->
                "Stop and ask the user to sign in. Never type credentials and never guess a password."
            SessionBlocker.CAPTCHA_REQUIRED ->
                "Stop. A person has to clear the verification in the app."
            SessionBlocker.NONE -> ""
        }
    }
}
