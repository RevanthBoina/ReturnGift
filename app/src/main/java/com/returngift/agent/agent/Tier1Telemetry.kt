// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog

/**
 * On-device, aggregate-only Tier-1 telemetry (Phase 4).
 *
 * Privacy stance: NO raw utterance text anywhere; nothing leaves the device.
 * Only monotonically-increasing aggregate counters over a FIXED closed vocabulary
 * (intent names) are written. They surface in the debug page via KVUtils.
 *
 * Counters:
 *  - `tier1_total`                  incremented for every Tier-1 decision (hit or fallback)
 *  - `tier1_hit_<intent>`          per-intent hit counters (fixed vocabulary, not raw text)
 *  - `tier3_fallback_total`        incremented when a task reaches the agent loop (fallthrough)
 *  - `tier1_fp_<intent>`           false-positive proxy: incremented when a fired Tier-1
 *                                   action is undone/corrected within 30 s (see spec §8)
 */
object Tier1Telemetry {

    private const val TAG = "Tier1Telemetry"

    /** Closed vocabulary of possible Tier-1 intent names (used for the debug surface). */
    val intents = listOf(
        "call", "send_message", "sms", "alarm", "timer", "screenshot",
        "back", "home", "open_url", "open_settings", "open_app",
        "camera", "flashlight"
    )

    const val KEY_TOTAL = "tier1_total"
    const val KEY_FALLBACK_TIER3 = "tier3_fallback_total"
    const val KEY_HIT_PREFIX = "tier1_hit_"
    const val KEY_FP_PREFIX = "tier1_fp_"

    /** Test seam: observes every counter key. KV writes are a no-op when KV is unavailable. */
    internal var counterHook: ((String) -> Unit)? = null

    @Volatile private var lastHitIntent: String? = null
    @Volatile private var lastHitAtMs: Long = 0L

    /** Record a Tier-1 intent hit and the running total. */
    fun recordHit(intent: String) {
        lastHitIntent = intent
        lastHitAtMs = System.currentTimeMillis()
        increment(KEY_TOTAL)
        increment("$KEY_HIT_PREFIX$intent")
        XLog.d(TAG, "tier1 hit: $intent")
    }

    /** Record a Tier-1 miss (task fell through to skills/agent loop). */
    fun recordFallback() {
        increment(KEY_TOTAL)
        increment(KEY_FALLBACK_TIER3)
    }

    /** Record a false-positive proxy: a fired Tier-1 action was undone/corrected quickly. */
    fun recordFalsePositive(intent: String) {
        increment("$KEY_FP_PREFIX$intent")
        XLog.d(TAG, "tier1 FP proxy: $intent")
    }

    /**
     * The last Tier-1 intent fired within [maxAgeMs] (in-memory; the recency guard for the
     * false-positive proxy producers). Returns null when nothing recent is knowable.
     */
    fun lastFiredIntent(maxAgeMs: Long = 30_000L): String? {
        val intent = lastHitIntent ?: return null
        return if (System.currentTimeMillis() - lastHitAtMs <= maxAgeMs) intent else null
    }

    private fun increment(key: String) {
        counterHook?.invoke(key)
        if (!KVUtils.isInitialized) return // no-op when KV unavailable (e.g. unit tests)
        KVUtils.putInt(key, KVUtils.getInt(key) + 1)
    }
}