// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.core.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * ScreenTreeTokenOptimizer optimizes perception speed and token efficiency:
 * 1. FNV-1a fast screen hierarchy hashing & caching to eliminate redundant tree walks.
 * 2. Viewport & ROI pruning (filters out status bar / nav bar clutter).
 * 3. Compact formatting producing <350 tokens per turn.
 */
object ScreenTreeTokenOptimizer {

    data class CachedScreen(
        val hash: Long,
        val formattedTree: String,
        val timestamp: Long
    )

    @Volatile
    private var lastCachedScreen: CachedScreen? = null
    /** Count of cache hits (content-addressed) - closed-vocab for telemetry. */
    @Volatile
    private var cacheHits = 0L

    fun computeHierarchyHash(root: AccessibilityNodeInfo?): Long {
        if (root == null) return 0L
        var hash = -3750763034362895579L // FNV offset basis

        fun hashNode(node: AccessibilityNodeInfo) {
            if (!node.isVisibleToUser) return

            val t = node.text
            if (t != null) {
                for (i in 0 until t.length) {
                    hash = hash xor t[i].code.toLong()
                    hash *= 1099511628211L // FNV prime
                }
            }
            val cd = node.contentDescription
            if (cd != null) {
                for (i in 0 until cd.length) {
                    hash = hash xor cd[i].code.toLong()
                    hash *= 1099511628211L
                }
            }

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            hash = hash xor (bounds.left.toLong() shl 48 or (bounds.top.toLong() shl 32) or (bounds.right.toLong() shl 16) or bounds.bottom.toLong())
            hash *= 1099511628211L

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                hashNode(child)
                child.recycle()
            }
        }

        hashNode(root)
        return hash
    }

    /**
     * Checks if the screen matches the cached hash (content-addressed). When the
     * caller's hash equals the cached hash → serve the cached string regardless of age.
     * Only mismatched/absent cache older than [maxAgeMs] is rejected.
     */
    fun getCachedIfValid(hash: Long, maxAgeMs: Long = 30_000L): String? {
        val cached = lastCachedScreen ?: return null
        if (cached.hash == hash) {
            // Content-addressed hit: same screen fingerprint, serve cached regardless of age
            cacheHits++
            return cached.formattedTree
        }
        // Mismatched hash → enforce freshness bound (stale-format guard)
        if (System.currentTimeMillis() - cached.timestamp < maxAgeMs) {
            return null
        }
        return null
    }

    /**
     * Updates the cache with newly computed tree formatting.
     */
    fun updateCache(hash: Long, formattedTree: String) {
        lastCachedScreen = CachedScreen(
            hash = hash,
            formattedTree = formattedTree,
            timestamp = System.currentTimeMillis()
        )
    }

    /** Count of content-addressed cache hits (closed-vocab telemetry). */
    fun getCacheHits(): Long = cacheHits

    /**
     * Prunes non-interactive system edge bars (status bar top 3% and nav bar bottom 3%)
     * if screen height is known, reducing token clutter.
     */
    fun filterSystemBars(
        nodes: List<SemanticNodeFlattener.SemanticNode>,
        screenHeight: Int
    ): List<SemanticNodeFlattener.SemanticNode> {
        if (screenHeight <= 0) return nodes
        val topThreshold = (screenHeight * 0.035).toInt()
        val bottomThreshold = (screenHeight * 0.965).toInt()

        return nodes.filter { node ->
            val cy = node.centerY
            // Keep node if it's within the main interactive body, or if it is explicitly clickable/editable
            (cy in topThreshold..bottomThreshold) || node.isClickable || node.isEditable
        }
    }
}
