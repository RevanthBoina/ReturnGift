// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.grounding

import android.graphics.Rect
import com.returngift.agent.utils.XLog

/**
 * In-memory per-app selector cache — memoizes SemanticTargetResolver
 * resolution results so repeated workflows don't re-walk the accessibility
 * tree for identical targets.
 *
 * key = (packageName, normalized description)
 * value = resolved descriptor (resource-id / text / content-desc /
 *         class + bounds center) + the fingerprint the entry was
 *         resolved under
 *
 * Before resolving: if the app's current fingerprint
 * (ClawAccessibilityService.getScreenFingerprint) equals the fingerprint
 * stored with the entry, return the cached descriptor directly (skip the
 * tree query).
 *
 * Any fingerprint change for that package invalidates ALL its entries
 * (AppSessionManager's hash is the hook — consult before the store,
 * update after).
 *
 * In-memory only (lazy-first) — persistence is a future option.
 *
 * Architectural references:
 * - Totoro350/Mobile-MCP — stable element addressing across steps.
 * - microsoft/playwright — selector-engine philosophy (memoize resolved
 *   locators instead of re-discovering every round).
 * - Alibaba Mobile-Agent (X-PLUG/MobileAgent) — UI-knowledge reuse across
 *   episodes.
 */
object SelectorCache {

    private const val TAG = "SelectorCache"

    /**
     * A cached resolution entry. Holds only stable attributes
     * (resource-id / text / content-desc / class + bounds center),
     * NOT volatile node IDs.
     */
    data class CachedEntry(
        val packageName: String,
        val fingerprint: Long,
        val description: String,
        val resourceId: String?,
        val text: String?,
        val contentDesc: String?,
        val viewClass: String?,
        val centerX: Int,
        val centerY: Int,
        val method: SemanticTargetResolver.ResolutionMethod,
        val resolvedAt: Long,
    )

    // key = "packageName|description"
    private val cache = mutableMapOf<String, CachedEntry>()
    // packageName -> current screen fingerprint (invalidation signal)
    private val appFingerprints = mutableMapOf<String, Long>()

    /**
     * Get the app's current fingerprint as tracked in the cache.
     * Returns null if the package has no tracked fingerprint.
     */
    fun currentFingerprint(packageName: String): Long? = appFingerprints[packageName]

    /**
     * Update the cached fingerprint for a package — called by AppSessionManager
     * after recording a new screen hash for the package.
     */
    fun updateFingerprint(packageName: String, fingerprint: Long) {
        appFingerprints[packageName] = fingerprint
    }

    /**
     * True iff the package has a tracked fingerprint AND it equals the given
     * fingerprint. Cheap O(1) read-only query used by FastRoundRouter to decide
     * whether the current round can be safely served by the small (fast) engine.
     * Returns false when the package has no tracked fingerprint yet.
     */
    fun hasValidFingerprint(packageName: String, fingerprint: Long): Boolean =
        appFingerprints[packageName]?.let { it == fingerprint } == true

    /**
     * Try to return a cached descriptor for (package, description).
     * Returns null if the fingerprint doesn't match (cache miss) or there
     * is no entry.
     */
    fun get(packageName: String, description: String): CachedEntry? {
        val key = "$packageName|$description"
        val entry = cache[key] ?: return null
        val currentFp = appFingerprints[packageName] ?: return null
        if (entry.fingerprint != currentFp) {
            // Fingerprint changed — cache miss
            return null
        }
        return entry
    }

    /**
     * Store a resolved descriptor in the cache.
     */
    fun put(
        packageName: String,
        fingerprint: Long,
        description: String,
        resourceId: String?,
        text: String?,
        contentDesc: String?,
        viewClass: String?,
        bounds: Rect,
        method: SemanticTargetResolver.ResolutionMethod,
    ) {
        val key = "$packageName|$description"
        cache[key] = CachedEntry(
            packageName = packageName,
            fingerprint = fingerprint,
            description = description,
            resourceId = resourceId,
            text = text,
            contentDesc = contentDesc,
            viewClass = viewClass,
            centerX = bounds.centerX(),
            centerY = bounds.centerY(),
            method = method,
            resolvedAt = System.currentTimeMillis(),
        )
        appFingerprints[packageName] = fingerprint
        XLog.d(TAG, "Cached descriptor for $packageName|$description (fp=$fingerprint)")
    }

    /**
     * Invalidate ALL entries for a package (fingerprint changed).
     */
    fun invalidatePackage(packageName: String) {
        val prefix = "$packageName|"
        val toRemove = cache.keys.filter { it.startsWith(prefix) }
        toRemove.forEach { cache.remove(it) }
        appFingerprints.remove(packageName)
        XLog.d(TAG, "Invalidated ${toRemove.size} cache entries for $packageName")
    }

    /**
     * Clear the entire cache (e.g. on app update or memory pressure).
     */
    fun clear() {
        cache.clear()
        appFingerprints.clear()
        XLog.d(TAG, "SelectorCache cleared")
    }

    /** Number of entries currently cached. */
    fun size(): Int = cache.size
}
