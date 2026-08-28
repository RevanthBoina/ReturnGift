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

    /** Record a Tier-1 intent hit and the running total. */
    fun recordHit(intent: String) {
        if (!KVUtils.isInitialized) return // no-op when KV unavailable (e.g. unit tests)
        KVUtils.putInt(KEY_TOTAL, KVUtils.getInt(KEY_TOTAL) + 1)
        KVUtils.putInt("$KEY_HIT_PREFIX$intent", KVUtils.getInt("$KEY_HIT_PREFIX$intent") + 1)
        XLog.d(TAG, "tier1 hit: $intent")
    }

    /** Record a Tier-1 miss (task fell through to skills/agent loop). */
    fun recordFallback() {
        if (!KVUtils.isInitialized) return
        KVUtils.putInt(KEY_TOTAL, KVUtils.getInt(KEY_TOTAL) + 1)
        KVUtils.putInt(KEY_FALLBACK_TIER3, KVUtils.getInt(KEY_FALLBACK_TIER3) + 1)
    }

    /** Record a false-positive proxy: a fired Tier-1 action was undone/corrected quickly. */
    fun recordFalsePositive(intent: String) {
        if (!KVUtils.isInitialized) return
        KVUtils.putInt("$KEY_FP_PREFIX$intent", KVUtils.getInt("$KEY_FP_PREFIX$intent") + 1)
        XLog.d(TAG, "tier1 FP proxy: $intent")
    }
}