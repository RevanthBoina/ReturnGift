// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.mcp

/**
 * Trust policy for remote tools.
 *
 * MCP servers are **untrusted principals**: their tool names, descriptions,
 * annotations and outputs all come from a third party. The protocol's own guidance
 * is explicit that annotations are hints, not authority — so this policy never
 * takes a server's word for whether an action is safe.
 *
 * Three outcomes:
 *  - [Decision.ALLOW]  — read-shaped tools with no write signal
 *  - [Decision.ASK]    — anything that can change remote state; the user approves
 *                        that specific call, with the arguments previewed
 *  - [Decision.DENY]   — configured off, or matching a deny pattern
 *
 * Fail-closed direction: an unknown tool is treated as a **write** (ASK), never as a
 * read. Silence is not consent.
 */
object McpToolPolicy {

    enum class Risk {
        /** Inspects state only. */
        READ,

        /** Creates or modifies remote state. */
        WRITE,

        /** Irreversible: deletes, sends, pays, publishes, transfers. */
        DESTRUCTIVE,
    }

    sealed class Decision {
        data object Allow : Decision()
        data class Ask(val reason: String) : Decision()
        data class Deny(val reason: String) : Decision()
    }

    /** User-level switches, all defaulting to the cautious side. */
    data class Settings(
        /** Auto-approve read-only tools (the only thing allowed without a prompt). */
        val autoApproveReads: Boolean = true,
        /** Auto-approve writes. Off by default: a write is remote side effect. */
        val autoApproveWrites: Boolean = false,
        /** Master switch; false disables every remote tool. */
        val enabled: Boolean = true,
        /** Tool names (suffix match, case-insensitive) that must never run. */
        val denyPatterns: Set<String> = emptySet(),
        /**
         * Tools the user explicitly allowed (tool name or `server__tool`, matched on
         * the end). Only lifts the prompt for **writes**; a destructive tool still
         * asks every time, because "I allowed it yesterday" is not approval to send
         * today's message.
         */
        val allowedTools: Set<String> = emptySet(),
        /** Server ids whose *read* tools run without a prompt. Trust is per server. */
        val trustedServers: Set<String> = emptySet(),
        /** Server ids that may not run anything at all (hard deny, all tools). */
        val blockedServers: Set<String> = emptySet(),
    )

    /** Default setting; the UI may replace it with a stored copy. */
    @Volatile
    var settings: Settings = Settings()

    private val WRITE_VERBS = listOf(
        "create", "update", "edit", "set", "add", "append", "write", "put", "patch",
        "upload", "insert", "upsert", "move", "rename", "assign", "comment", "reply",
        "label", "tag", "schedule", "invite", "share",
    )

    private val DESTRUCTIVE_VERBS = listOf(
        "delete", "remove", "destroy", "drop", "purge", "erase", "wipe", "revoke",
        "send", "post", "publish", "pay", "transfer", "purchase", "buy", "cancel",
        "archive", "close", "disable", "unsubscribe", "block", "ban", "reset",
    )

    private val READ_VERBS = listOf(
        "get", "list", "search", "find", "read", "fetch", "query", "lookup", "describe",
        "show", "view", "inspect", "count", "status", "info", "preview", "diff", "history",
    )

    /**
     * Classify a tool.
     *
     * Order of evidence: server annotations (weakest trust) are *corroborated* by the
     * name; when they disagree the higher risk wins. A name with no recognisable verb
     * is a WRITE (fail-closed), because we cannot prove it only reads.
     */
    fun riskOf(tool: McpToolDef): Risk {
        val annotationRisk = riskFromAnnotations(tool.annotations)
        val nameRisk = riskFromName(tool.name, tool.description)
        // Never let an annotation downgrade a name that reads destructive.
        return when {
            // Highest risk wins, whoever claims it.
            annotationRisk == Risk.DESTRUCTIVE || nameRisk == Risk.DESTRUCTIVE -> Risk.DESTRUCTIVE
            annotationRisk == Risk.WRITE || nameRisk == Risk.WRITE -> Risk.WRITE
            // Either source alone can establish a read; both must agree it is not.
            annotationRisk == Risk.READ || nameRisk == Risk.READ -> Risk.READ
            // Nothing recognisable: assume it can change state.
            else -> Risk.WRITE
        }
    }

    private fun riskFromAnnotations(annotations: JsonValue?): Risk? {
        val obj = annotations as? JsonValue.Obj ?: return null
        return when {
            obj.bool("destructiveHint") == true -> Risk.DESTRUCTIVE
            obj.bool("readOnlyHint") == true -> Risk.READ
            obj.bool("destructiveHint") == false && obj.bool("readOnlyHint") == false -> Risk.WRITE
            else -> null
        }
    }

    private fun riskFromName(name: String, description: String): Risk? {
        val haystack = "$name $description".lowercase()
        val tokens = haystack.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
        val snake = name.lowercase()
        fun matches(verbs: List<String>) = verbs.any { verb ->
            tokens.contains(verb) || snake.startsWith(verb) || snake.contains("_$verb") ||
                snake.contains("$verb-") || snake.endsWith(verb)
        }
        return when {
            matches(DESTRUCTIVE_VERBS) -> Risk.DESTRUCTIVE
            matches(WRITE_VERBS) -> Risk.WRITE
            matches(READ_VERBS) -> Risk.READ
            else -> null
        }
    }

    /**
     * Apply the policy (pure: same inputs, same decision).
     *
     * Precedence is deliberate, and fail-closed at every step:
     *  master switch → blocked server → deny pattern → explicit allow → risk default.
     * A deny always beats an allow, so a stale allowlist entry can never override a
     * block the user added later.
     */
    fun decide(tool: McpToolDef, current: Settings = settings): Decision {
        if (!current.enabled) return Decision.Deny("MCP tools are turned off in Settings.")
        if (current.blockedServers.any { it.equals(tool.serverId, ignoreCase = true) }) {
            return Decision.Deny("${tool.serverName} is blocked in Settings.")
        }
        val name = tool.name.lowercase()
        val qualified = tool.qualifiedName.lowercase()
        current.denyPatterns.firstOrNull { pattern ->
            pattern.isNotBlank() && (name == pattern.lowercase() || name.endsWith(pattern.lowercase()) ||
                qualified.endsWith(pattern.lowercase()))
        }?.let { pattern ->
            return Decision.Deny("'${tool.name}' matches a blocked pattern ($pattern).")
        }
        val risk = riskOf(tool)
        val explicitlyAllowed = current.allowedTools.any { pattern ->
            pattern.isNotBlank() && (name.endsWith(pattern.lowercase()) ||
                qualified.endsWith(pattern.lowercase()))
        }
        // An allowlist entry is a standing "yes" for this tool — but only where the
        // action is reversible enough to repeat without asking.
        if (explicitlyAllowed && risk != Risk.DESTRUCTIVE) return Decision.Allow
        return when (risk) {
            Risk.READ ->
                if (current.autoApproveReads || current.trustedServers.any { it.equals(tool.serverId, ignoreCase = true) }) {
                    Decision.Allow
                } else {
                    Decision.Ask("This reads data from ${tool.serverName}. Allow?")
                }
            Risk.WRITE ->
                if (current.autoApproveWrites) Decision.Allow
                else Decision.Ask("This changes data in ${tool.serverName}. Allow this call?")
            Risk.DESTRUCTIVE ->
                Decision.Ask("This can't be undone (${tool.serverName}: ${tool.name}). Allow it?")
        }
    }

    /** Question shown to the user for an ASK decision; arguments are previewed. */
    fun approvalQuestion(tool: McpToolDef, argumentsPreview: String): String {
        val args = if (argumentsPreview.isBlank() || argumentsPreview == "{}") {
            "no arguments"
        } else {
            argumentsPreview.take(300)
        }
        return "Remote tool \"${tool.name}\" (${tool.serverName}) wants to run with $args. Allow?"
    }
}
