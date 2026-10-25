// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.mcp

/**
 * Keeps MCP tools from eating the prompt.
 *
 * A handful of servers can advertise hundreds of tools; a local model has a small
 * context and degrades quickly when the tool list explodes. The index therefore:
 *
 *  1. dedupes by qualified name (server restarts must not duplicate tools),
 *  2. scores each tool against the current request (name tokens + description),
 *  3. always keeps the top [limit] and drops the rest *for this turn only* —
 *     nothing is forgotten permanently, so a later request can surface it.
 *
 * The scorer is intentionally lexical: deterministic, offline, no extra tokens.
 */
object McpToolIndex {

    /** Default ceiling for tools contributed by MCP in one prompt. */
    const val DEFAULT_LIMIT = 12

    private val STOP_WORDS = setOf(
        "the", "a", "an", "to", "on", "in", "of", "for", "and", "or", "with", "from",
        "this", "that", "it", "is", "are", "be", "my", "me", "you", "your", "please",
    )

    /** Deduplicate, preserving first-seen order. */
    fun dedupe(tools: List<McpToolDef>): List<McpToolDef> =
        tools.distinctBy { it.qualifiedName }

    /**
     * Rank tools for [task] and return at most [limit].
     *
     * A tool always keeps a small base score, so an unrelated-but-useful tool can
     * still be offered when the task names nothing.
     */
    fun select(tools: List<McpToolDef>, task: String, limit: Int = DEFAULT_LIMIT): List<McpToolDef> {
        val deduped = dedupe(tools)
        if (deduped.size <= limit) return deduped
        val taskTokens = tokens(task)
        return deduped
            .map { tool -> tool to score(tool, taskTokens) }
            .sortedWith(compareByDescending<Pair<McpToolDef, Int>> { it.second }.thenBy { it.first.qualifiedName })
            .take(limit)
            .map { it.first }
    }

    fun score(tool: McpToolDef, taskTokens: Set<String>): Int {
        if (taskTokens.isEmpty()) return 1
        val haystack = "${tool.name} ${tool.title.orEmpty()} ${tool.description}".lowercase()
        val toolTokens = tokens(haystack)
        var score = 1
        score += taskTokens.count { it in toolTokens } * 3
        // Whole-task phrase match is a much stronger signal than a token overlap.
        if (taskTokens.all { it in toolTokens }) score += 4
        return score
    }

    /** One-line-per-tool prompt section; empty when there is nothing to show. */
    fun promptSection(tools: List<McpToolDef>, limit: Int = DEFAULT_LIMIT): String {
        if (tools.isEmpty()) return ""
        val chosen = tools.take(limit)
        return buildString {
            append("\n\n## Connected tools (MCP)\n")
            append("Remote tools available for this task. Call them by their full name.\n")
            chosen.forEach { tool ->
                append("- `").append(tool.qualifiedName).append("` \u2014 ")
                append(tool.shortDescription())
                append('\n')
            }
        }
    }

    internal fun tokens(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()
}
