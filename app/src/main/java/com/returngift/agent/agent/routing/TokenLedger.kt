// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.routing

/**
 * Token/cost accounting per provider, with the two quota windows a subscription
 * gateway actually enforces (the model OmniRoute adopts for Antigravity):
 *
 *  - a **short window** (default 5 h) that resets after first use — "sprint",
 *  - a **long window** (default 7 d) that overrides the short one when exhausted.
 *
 * The ledger never blocks work by itself: it only tells the router which providers
 * are out of budget and which are cooling down after an error. Pure Kotlin with an
 * injectable clock, so window rollover is testable without sleeping.
 */
class TokenLedger(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val shortWindowMs: Long = 5 * 60 * 60 * 1000L,
    private val longWindowMs: Long = 7 * 24 * 60 * 60 * 1000L,
) {

    /** Everything recorded for one provider across both windows. */
    data class Usage(
        val shortTokens: Long,
        val shortSpentUsd: Double,
        val longTokens: Long,
        val longSpentUsd: Double,
        val totalTokens: Long,
        val totalSpentUsd: Double,
        val shortResetsInMs: Long,
        val longResetsInMs: Long,
        val cooldownRemainingMs: Long,
    )

    /** Per-provider budgets; `0` means "no limit known". */
    data class Limits(val shortTokens: Long = 0L, val longTokens: Long = 0L, val shortUsd: Double = 0.0)

    private class Window(var startMs: Long, var tokens: Long, var spentUsd: Double)

    private class Entry {
        val short = Window(0L, 0L, 0.0)
        val long = Window(0L, 0L, 0.0)
        var totalTokens: Long = 0L
        var totalSpentUsd: Double = 0.0
        var cooldownUntilMs: Long = 0L
        var limits: Limits = Limits()
    }

    private val entries = LinkedHashMap<String, Entry>()

    fun setLimits(providerId: String, limits: Limits) {
        entry(providerId).limits = limits
    }

    fun limits(providerId: String): Limits = entry(providerId).limits

    /** Record a completed call. Negative or zero tokens are ignored (no-op success). */
    @Synchronized
    fun record(providerId: String, tokens: Long, costUsd: Double = 0.0) {
        if (tokens <= 0L) return
        val now = clock()
        val entry = entry(providerId)
        rollWindows(entry, now)
        entry.short.tokens += tokens
        entry.short.spentUsd += costUsd
        entry.long.tokens += tokens
        entry.long.spentUsd += costUsd
        entry.totalTokens += tokens
        entry.totalSpentUsd += costUsd
    }

    @Synchronized
    fun usage(providerId: String): Usage {
        val now = clock()
        val entry = entry(providerId)
        rollWindows(entry, now)
        return Usage(
            shortTokens = entry.short.tokens,
            shortSpentUsd = entry.short.spentUsd,
            longTokens = entry.long.tokens,
            longSpentUsd = entry.long.spentUsd,
            totalTokens = entry.totalTokens,
            totalSpentUsd = entry.totalSpentUsd,
            shortResetsInMs = (entry.short.startMs + shortWindowMs - now).coerceAtLeast(0L),
            longResetsInMs = (entry.long.startMs + longWindowMs - now).coerceAtLeast(0L),
            cooldownRemainingMs = (entry.cooldownUntilMs - now).coerceAtLeast(0L),
        )
    }

    /** True when either window's budget is used up. */
    @Synchronized
    fun isExhausted(providerId: String): Boolean {
        val now = clock()
        val entry = entry(providerId)
        rollWindows(entry, now)
        val limits = entry.limits
        if (limits.shortTokens > 0 && entry.short.tokens >= limits.shortTokens) return true
        if (limits.longTokens > 0 && entry.long.tokens >= limits.longTokens) return true
        if (limits.shortUsd > 0.0 && entry.short.spentUsd >= limits.shortUsd) return true
        return false
    }

    @Synchronized
    fun isCoolingDown(providerId: String): Boolean = usage(providerId).cooldownRemainingMs > 0L

    /** Put a provider in the penalty box after a failure. */
    @Synchronized
    fun markCooldown(providerId: String, durationMs: Long, nowMs: Long = clock()) {
        if (durationMs <= 0L) return
        val entry = entry(providerId)
        entry.cooldownUntilMs = maxOf(entry.cooldownUntilMs, nowMs + durationMs)
    }

    @Synchronized
    fun reset() {
        entries.clear()
    }

    /** Snapshot for diagnostics / the "why did it pick that" panel. */
    @Synchronized
    fun describe(): List<String> = entries.map { (id, entry) ->
        val usage = usage(id)
        buildString {
            append(id).append(": ").append(entry.totalTokens).append(" tokens, $")
            append("%.4f".format(entry.totalSpentUsd))
            if (usage.shortTokens > 0) append(" (short ${usage.shortTokens})")
            if (usage.longTokens > 0) append(" (long ${usage.longTokens})")
            if (usage.cooldownRemainingMs > 0) append(" cooling ${usage.cooldownRemainingMs}ms")
        }
    }

    private fun entry(providerId: String): Entry = entries.getOrPut(providerId) { Entry() }

    /** Roll a window forward the first time it is touched after it expired. */
    private fun rollWindows(entry: Entry, now: Long) {
        if (entry.short.startMs == 0L) entry.short.startMs = now
        if (entry.long.startMs == 0L) entry.long.startMs = now
        if (now - entry.short.startMs >= shortWindowMs) {
            entry.short.startMs = now
            entry.short.tokens = 0L
            entry.short.spentUsd = 0.0
        }
        if (now - entry.long.startMs >= longWindowMs) {
            entry.long.startMs = now
            entry.long.tokens = 0L
            entry.long.spentUsd = 0.0
        }
    }
}
