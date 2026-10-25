// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.mcp

import com.returngift.agent.agent.knowledge.AppGuideStore
import com.returngift.agent.utils.XLog

/**
 * Persistence for [McpToolPolicy.Settings].
 *
 * The policy decides whether a remote tool may run, so it has to survive a restart:
 * an approval the user gave once (or a tool they blocked) is a durable decision, not
 * a value that resets every launch. It is small on purpose — a flat, human-readable
 * line — because a security setting the user cannot inspect is a setting they cannot
 * trust.
 *
 * Format (order-independent, unknown keys ignored, malformed numbers default safe):
 *
 * ```
 * v1|enabled=1|reads=1|writes=0|deny=delete,wipe|allow=gmail__send_draft|trusted=srv_a|blocked=srv_b
 * ```
 */
object McpPolicyStore {

    private const val TAG = "McpPolicyStore"
    private const val KEY = "mcp_tool_policy_v1"
    private const val VERSION = "v1"

    /** Storage seam; null means "follow the app's knowledge store" (same as secrets). */
    internal var storage: AppGuideStore.Storage? = null

    private fun sink(): AppGuideStore.Storage = storage ?: AppGuideStore.storage

    // ── encode / decode (pure) ──────────────────────────────────────────────

    fun encode(settings: McpToolPolicy.Settings): String = listOf(
        VERSION,
        "enabled=" + flag(settings.enabled),
        "reads=" + flag(settings.autoApproveReads),
        "writes=" + flag(settings.autoApproveWrites),
        "deny=" + settings.denyPatterns.sorted().joinToString(","),
        "allow=" + settings.allowedTools.sorted().joinToString(","),
        "trusted=" + settings.trustedServers.sorted().joinToString(","),
        "blocked=" + settings.blockedServers.sorted().joinToString(","),
    ).joinToString("|")

    /**
     * Parse [raw]. Anything unrecognised falls back to the cautious default, so a
     * corrupt blob can never *grant* a permission.
     */
    fun decode(raw: String): McpToolPolicy.Settings {
        val defaults = McpToolPolicy.Settings()
        if (raw.isBlank()) return defaults
        val parts = raw.split('|')
        if (parts.firstOrNull() != VERSION) return defaults
        val fields = parts.drop(1)
            .mapNotNull { part ->
                val at = part.indexOf('=')
                if (at <= 0) null else part.substring(0, at) to part.substring(at + 1)
            }
            .toMap()
        fun bool(key: String, fallback: Boolean): Boolean = when (fields[key]) {
            "1", "true" -> true
            "0", "false" -> false
            else -> fallback
        }
        fun names(key: String): Set<String> = namesOf(fields[key])

        return McpToolPolicy.Settings(
            autoApproveReads = bool("reads", defaults.autoApproveReads),
            autoApproveWrites = bool("writes", defaults.autoApproveWrites),
            enabled = bool("enabled", defaults.enabled),
            denyPatterns = names("deny"),
            allowedTools = names("allow"),
            trustedServers = names("trusted"),
            blockedServers = names("blocked"),
        )
    }

    private fun flag(value: Boolean): String = if (value) "1" else "0"

    /** Tool/server names are `[a-z0-9_.:-]`; anything else in a stored blob is junk. */
    private val NAME = Regex("^[a-z0-9_.:-]+$")

    private fun namesOf(raw: String?): Set<String> = raw
        .orEmpty()
        .split(',')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() && NAME.matches(it) }
        .toSet()

    // ── load / save (effects) ───────────────────────────────────────────────

    /** Read the stored policy, or the cautious default when there is none. */
    fun load(): McpToolPolicy.Settings {
        val raw = runCatching { sink().getString(KEY, "") }.getOrDefault("")
        return decode(raw)
    }

    /** Install the stored policy as the live one. Called once at startup. */
    fun install(): McpToolPolicy.Settings {
        val settings = load()
        McpToolPolicy.settings = settings
        return settings
    }

    /** Persist [settings] and make them live in one step (the UI's save path). */
    fun save(settings: McpToolPolicy.Settings) {
        McpToolPolicy.settings = settings
        runCatching { sink().putString(KEY, encode(settings)) }
            .onFailure { XLog.w(TAG, "could not persist MCP policy", it) }
    }

    /** Convenience for the UI: flip one server's trust and save. */
    fun setServerTrusted(serverId: String, trusted: Boolean): McpToolPolicy.Settings {
        val current = McpToolPolicy.settings
        val next = current.copy(
            trustedServers = if (trusted) current.trustedServers + serverId
            else current.trustedServers - serverId,
            // Trusting and blocking at the same time is contradictory; clear the other.
            blockedServers = if (trusted) current.blockedServers - serverId else current.blockedServers,
        )
        save(next)
        return next
    }

    /** Convenience for the UI: block or unblock one server. */
    fun setServerBlocked(serverId: String, blocked: Boolean): McpToolPolicy.Settings {
        val current = McpToolPolicy.settings
        val next = current.copy(
            blockedServers = if (blocked) current.blockedServers + serverId
            else current.blockedServers - serverId,
            trustedServers = if (blocked) current.trustedServers - serverId else current.trustedServers,
        )
        save(next)
        return next
    }
}
