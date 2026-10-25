// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.plan

import com.returngift.agent.agent.knowledge.AppGuideStore
import com.returngift.agent.utils.XLog

/**
 * Durable checkpoint for the plan the user is currently watching.
 *
 * Why: the contract's value collapses if it dies with the process. Android kills
 * backgrounded apps, the user comes back, and a plan that was "step 3 of 6, tapped
 * Follow, not verified" has to be either resumed or honestly abandoned — never
 * restarted from zero while the app reports success.
 *
 * One slot on purpose: one device task runs at a time, and the slot is keyed by a
 * fingerprint of the request so an unrelated new task cannot resume an old plan.
 * The payload is a plain one-line string ([PlanContract.checkpoint]) so it survives
 * a schema change by simply failing to parse (→ fresh plan, no half-state).
 */
object PlanCheckpointStore {

    private const val TAG = "PlanCheckpointStore"
    private const val KEY = "agent_plan_checkpoint_v1"
    private const val VERSION = "v1"

    /** How long a plan may be resumed. Beyond this the screen has surely changed. */
    const val DEFAULT_TTL_MS = 30 * 60 * 1000L

    /** Storage seam; null follows the app's knowledge store (same as MCP). */
    internal var storage: AppGuideStore.Storage? = null

    private fun sink(): AppGuideStore.Storage = storage ?: AppGuideStore.storage

    /** Stable fingerprint of a request: same words → same plan slot. */
    fun keyFor(request: String): String {
        val normalised = request.lowercase()
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return Integer.toHexString(normalised.hashCode())
    }

    fun encode(key: String, savedAtMs: Long, contract: PlanContract): String =
        listOf(VERSION, key, savedAtMs.toString(), contract.checkpoint()).joinToString("|")

    private data class Slot(val key: String, val savedAtMs: Long, val body: String)

    private fun parse(raw: String): Slot? {
        val parts = raw.split('|', limit = 4)
        if (parts.size != 4 || parts[0] != VERSION) return null
        val savedAt = parts[2].toLongOrNull() ?: return null
        if (parts[3].isBlank()) return null
        return Slot(parts[1], savedAt, parts[3])
    }

    /** Persist the current state of [contract]. Best-effort: a failed write is logged. */
    fun save(requestKey: String, contract: PlanContract, nowMs: Long = System.currentTimeMillis()) {
        runCatching { sink().putString(KEY, encode(requestKey, nowMs, contract)) }
            .onFailure { XLog.w(TAG, "could not persist plan checkpoint", it) }
    }

    /**
     * Restore a contract for [requestKey] against the (re)built [blueprint].
     *
     * Returns null when there is nothing to resume, the slot belongs to a different
     * request, it is older than [ttlMs], or the payload no longer parses.
     */
    fun load(
        requestKey: String,
        blueprint: TaskBlueprint,
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = DEFAULT_TTL_MS,
    ): PlanContract? {
        val raw = runCatching { sink().getString(KEY, "") }.getOrDefault("")
        if (raw.isBlank()) return null
        val slot = parse(raw) ?: run {
            XLog.w(TAG, "unreadable plan checkpoint — starting fresh")
            null
        } ?: return null
        if (slot.key != requestKey) return null
        if (nowMs - slot.savedAtMs > ttlMs || slot.savedAtMs > nowMs + 60_000) return null
        val contract = PlanContract.fromCheckpoint(slot.body, blueprint)
        if (contract == null) {
            XLog.w(TAG, "checkpoint did not match the rebuilt plan — starting fresh")
            return null
        }
        // A fully finished plan is history, not a resume point.
        if (contract.isComplete()) {
            clear()
            return null
        }
        return contract
    }

    fun clear() {
        runCatching { sink().remove(KEY) }
    }
}
