// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.llm

import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog

/**
 * Closed-vocab counter for FastRoundRouter decisions.
 *
 * Privacy stance: closed vocab only ("unconfigured" / "memory" / "no-procedure" /
 * "mechanical" / "planning") — never raw utterance text. Counters are written
 * to KV (mirroring Tier1Telemetry) and observed in-process by tests via [counterHook].
 *
 * Counts are also surfaced through FastRoundRouter's [FastRoundRouter.decideRound]
 * caller — see DefaultAgentService.runAgentLoop.
 */
object FastRoundTelemetry {

    private const val TAG = "FastRoundTelemetry"
    private const val KEY_PREFIX = "fast_round_route_"

    /** Test seam: observes every counter key. KV writes are a no-op when KV is unavailable. */
    internal var counterHook: ((String) -> Unit)? = null

    fun record(reason: String) {
        counterHook?.invoke(reason)
        if (KVUtils.isInitialized) {
            KVUtils.putInt(KEY_PREFIX + reason, KVUtils.getInt(KEY_PREFIX + reason) + 1)
        }
        XLog.d(TAG, "fast_round_route: $reason")
    }
}