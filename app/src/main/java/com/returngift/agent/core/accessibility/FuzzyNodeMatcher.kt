// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.core.accessibility

import kotlin.math.max
import kotlin.math.min

/**
 * FuzzyNodeMatcher provides robust, multi-anchor fuzzy text matching across UI nodes.
 * Resolves labels with dynamic badges (e.g. "Inbox (3)"), truncation ("Settings..."),
 * and localized variations.
 */
object FuzzyNodeMatcher {

    data class MatchResult(
        val node: SemanticNodeFlattener.SemanticNode,
        val score: Float,
        val matchedText: String
    )

    /**
     * Finds the best matching SemanticNode for a given query text.
     */
    @JvmStatic
    @JvmOverloads
    fun findBestMatch(
        nodes: List<SemanticNodeFlattener.SemanticNode>,
        query: String,
        minScore: Float = 0.70f
    ): MatchResult? {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isEmpty() || nodes.isEmpty()) return null

        var bestNode: SemanticNodeFlattener.SemanticNode? = null
        var bestScore = 0.0f
        var bestMatchedText = ""

        for (node in nodes) {
            val nodeText = node.text.trim().lowercase()
            if (nodeText.isEmpty()) continue

            // 1. Exact match (Score: 1.0)
            if (nodeText == cleanQuery) {
                return MatchResult(node, 1.0f, node.text)
            }

            // 2. Starts with / Contains match (Score: 0.90 - 0.95)
            if (nodeText.startsWith(cleanQuery) || cleanQuery.startsWith(nodeText)) {
                val score = 0.95f
                if (score > bestScore) {
                    bestScore = score
                    bestNode = node
                    bestMatchedText = node.text
                }
                continue
            }

            if (nodeText.contains(cleanQuery) || cleanQuery.contains(nodeText)) {
                val score = 0.88f
                if (score > bestScore) {
                    bestScore = score
                    bestNode = node
                    bestMatchedText = node.text
                }
                continue
            }

            // 3. Levenshtein distance fuzzy score
            val levScore = calculateSimilarity(cleanQuery, nodeText)
            if (levScore > bestScore && levScore >= minScore) {
                bestScore = levScore
                bestNode = node
                bestMatchedText = node.text
            }
        }

        return if (bestNode != null && bestScore >= minScore) {
            MatchResult(bestNode, bestScore, bestMatchedText)
        } else {
            null
        }
    }

    /**
     * Calculates normalized Levenshtein similarity score between 0.0 and 1.0.
     */
    fun calculateSimilarity(s1: String, s2: String): Float {
        val maxLen = max(s1.length, s2.length)
        if (maxLen == 0) return 1.0f

        val distance = levenshteinDistance(s1, s2)
        return (1.0f - (distance.toFloat() / maxLen.toFloat())).coerceIn(0.0f, 1.0f)
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    dp[i - 1][j] + 1,
                    min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
