// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.grounding

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.returngift.agent.agent.session.AppSessionManager
import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.utils.XLog

/**
 * Dynamic UI grounding: resolves a target *description* (not a volatile node ID) to a live
 * accessibility node by re-querying the current hierarchy on every call.
 *
 * This eliminates the stale-node-ID bottleneck: identifiers like "n2"/"n24" are positional
 * and only valid within a single [ClawAccessibilityService.getScreenTree] snapshot, so the
 * agent could not act across UI transitions. Instead, callers describe what they want to
 * interact with (by text, content-description, resource-id, view class, or bounding box)
 * and this resolver re-grounds it against the current screen each time.
 *
 * Architectural references (selected patterns that fit the existing ReturnGift design):
 * - Browser Use's element resolution by stable attributes (not ephemeral indices).
 * - OpenHands' "observe → resolve target → act → verify" control loop.
 * - The existing [com.returngift.agent.agent.skill.SelectorResolver] YAML-skill resolver,
 *   generalized here so tap_node / input_text / find_and_tap can use the same mechanism.
 *
 * Resolution precedence (most specific → most relaxed):
 *  1. resource-id (fully qualified `pkg:id/foo`)
 *  2. exact text
 *  3. exact content-description
 *  4. view class + bounds (for non-textual controls like icon buttons)
 *  5. relaxed text/content-description contains
 *  6. bounding-box proximity (coordinates from a previous observation)
 */
object SemanticTargetResolver {

    private const val TAG = "SemanticTargetResolver"

    /**
     * Description of a UI target the agent wants to act on. Provide whichever stable
     * properties are known; the resolver uses them in precedence order.
     *
     * @param text         exact or substring of the node's text
     * @param contentDesc  exact or substring of the node's content description
     * @param resourceId  fully-qualified resource id, e.g. "com.example:id/send"
     * @param viewClass    view class name (e.g. "android.widget.Button")
     * @param x            optional x coordinate (for bounding-box proximity fallback)
     * @param y            optional y coordinate
     * @param exactText    if true, require exact text match (default true)
     * @param exactDesc    if true, require exact content-desc match (default true)
     */
    data class TargetDescription(
        val text: String? = null,
        val contentDesc: String? = null,
        val resourceId: String? = null,
        val viewClass: String? = null,
        val x: Int? = null,
        val y: Int? = null,
        val exactText: Boolean = true,
        val exactDesc: Boolean = true
    )

    /**
     * Result of a resolution attempt.
     */
    data class ResolvedTarget(
        val node: AccessibilityNodeInfo,
        val method: ResolutionMethod,
        val bounds: Rect
    ) {
        val centerX: Int get() = bounds.centerX()
        val centerY: Int get() = bounds.centerY()
    }

    enum class ResolutionMethod {
        RESOURCE_ID,
        EXACT_TEXT,
        EXACT_DESC,
        CLASS_AND_BOUNDS,
        RELAXED_TEXT,
        RELAXED_DESC,
        BOUNDING_BOX,
        LEGACY_NODE_ID
    }

    /**
     * Resolve a target description against the *current* screen hierarchy.
     * Re-queries the accessibility tree every call — never caches across transitions.
     * P2.3: checks per-app selector cache first; on fingerprint match returns
     * cached descriptor without re-querying the tree.
     *
     * @return a [ResolvedTarget], or null if no matching node exists on the current screen.
     */
    fun resolve(target: TargetDescription): ResolvedTarget? {
        val service = ClawAccessibilityService.getInstance() ?: run {
            XLog.w(TAG, "resolve: accessibility service not running")
            return null
        }
        val root = service.rootInActiveWindow ?: run {
            XLog.w(TAG, "resolve: no active window root")
            return null
        }
        val packageName = service.getForegroundPackage()

        // P2.3: Check selector cache before walking the tree.
        if (packageName != null) {
            val cacheKey = buildCacheKey(target)
            val fp = service.getScreenFingerprint()
            val cached = SelectorCache.get(packageName, cacheKey)
            if (cached != null && cached.fingerprint == fp) {
                // Fingerprint unchanged — try to quickly locate the node
                // using the cached resolution properties.
                val node = quickResolve(root, cached)
                if (node != null) {
                    XLog.d(TAG, "SelectorCache HIT: $packageName|$cacheKey (fp=$fp)")
                    return ResolvedTarget(node, cached.method, cached.bounds)
                }
                XLog.d(TAG, "SelectorCache STALE NODE at fp=$fp — re-resolving")
            }
            // On miss or fingerprint mismatch, fall through to full resolution
            // and cache the result on success.
            val result = try { resolveInTree(root, target) } catch (e: Exception) { null }
            result?.let { resolved ->
                SelectorCache.put(
                    packageName = packageName,
                    fingerprint = fp,
                    description = cacheKey,
                    resourceId = target.resourceId,
                    text = target.text,
                    contentDesc = target.contentDesc,
                    viewClass = target.viewClass,
                    bounds = resolved.bounds,
                    method = resolved.method,
                )
            }
            return result
        }
        return try { resolveInTree(root, target) } catch (e: Exception) { null }
    }

    /** Try to find a node quickly using cached resolution properties. */
    private fun quickResolve(root: AccessibilityNodeInfo, cached: SelectorCache.CachedEntry): AccessibilityNodeInfo? {
        if (cached.resourceId != null) {
            val nodes = root.findAccessibilityNodeInfosByViewId(cached.resourceId)
            if (!nodes.isNullOrEmpty()) {
                for (n in nodes) {
                    if (n.isVisibleToUser && n.isClickable) {
                        return AccessibilityNodeInfo.obtain(n)
                    }
                }
                nodes.forEach { it.recycle() }
            }
        }
        // Fallback: walk the tree looking for a node near the cached center.
        val nearest = findNearestToPoint(root, cached.centerX, cached.centerY)
        return nearest
    }

    private fun findNearestToPoint(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestDist = Int.MAX_VALUE
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.isVisibleToUser) {
                val b = Rect()
                node.getBoundsInScreen(b)
                val dx = b.centerX() - x
                val dy = b.centerY() - y
                val d = dx * dx + dy * dy
                if (d < bestDist) {
                    bestDist = d
                    best?.let { runCatching { it.recycle() } }
                    best = AccessibilityNodeInfo.obtain(node)
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return best
    }

    private fun buildCacheKey(target: TargetDescription): String {
        val parts = mutableListOf<String>()
        if (!target.resourceId.isNullOrEmpty()) parts.add("rid:${target.resourceId}")
        if (!target.text.isNullOrEmpty()) parts.add("txt:${target.text}")
        if (!target.contentDesc.isNullOrEmpty()) parts.add("desc:${target.contentDesc}")
        if (!target.viewClass.isNullOrEmpty()) parts.add("cls:${target.viewClass}")
        if (target.x != null && target.y != null) parts.add("pos:${target.x},${target.y}")
        return parts.joinToString(";").ifEmpty { "fallback" }
    }

    /**
     * Resolve by a legacy node ID ("n3") for backwards compatibility. This re-grounds the
     * *coordinates* the ID pointed to (if still present) rather than trusting the ID as a
     * persistent reference. Prefer [resolve] with a [TargetDescription].
     */
    fun resolveByLegacyNodeId(nodeId: String): ResolvedTarget? {
        val service = ClawAccessibilityService.getInstance() ?: return null
        val coords = service.getNodeCoordinates(nodeId.replace("[", "").replace("]", "").trim())
            ?: return null
        // Re-query the hierarchy to find what is actually at those coordinates now.
        val target = TargetDescription(x = coords[0], y = coords[1])
        val resolved = resolve(target)
        if (resolved != null) {
            return ResolvedTarget(resolved.node, ResolutionMethod.LEGACY_NODE_ID, resolved.bounds)
        }
        return null
    }

    private fun resolveInTree(root: AccessibilityNodeInfo, target: TargetDescription): ResolvedTarget? {
        // 1. Resource id (most specific)
        if (!target.resourceId.isNullOrEmpty()) {
            val nodes = root.findAccessibilityNodeInfosByViewId(target.resourceId)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (node.isVisibleToUser) {
                        val b = Rect()
                        node.getBoundsInScreen(b)
                        val obtained = AccessibilityNodeInfo.obtain(node)
                        nodes.forEach { it.recycle() }
                        return ResolvedTarget(obtained, ResolutionMethod.RESOURCE_ID, b)
                    }
                }
                nodes.forEach { it.recycle() }
            }
        }
        // 2 & 5. Text (exact, then relaxed)
        if (!target.text.isNullOrEmpty()) {
            val exact = findFirstMatching(root) { node ->
                node.isVisibleToUser && node.text?.toString()?.let {
                    if (target.exactText) it == target.text else it.contains(target.text!!, ignoreCase = true)
                } == true
            }
            if (exact != null) {
                val b = Rect(); exact.getBoundsInScreen(b)
                return ResolvedTarget(exact, if (target.exactText) ResolutionMethod.EXACT_TEXT else ResolutionMethod.RELAXED_TEXT, b)
            }
        }
        // 3 & 6. Content description (exact, then relaxed)
        if (!target.contentDesc.isNullOrEmpty()) {
            val exact = findFirstMatching(root) { node ->
                node.isVisibleToUser && node.contentDescription?.toString()?.let {
                    if (target.exactDesc) it == target.contentDesc else it.contains(target.contentDesc!!, ignoreCase = true)
                } == true
            }
            if (exact != null) {
                val b = Rect(); exact.getBoundsInScreen(b)
                return ResolvedTarget(exact, if (target.exactDesc) ResolutionMethod.EXACT_DESC else ResolutionMethod.RELAXED_DESC, b)
            }
        }
        // 4. View class + proximity (for icon buttons with no text/desc)
        if (!target.viewClass.isNullOrEmpty()) {
            val x = target.x ?: -1
            val y = target.y ?: -1
            val match = findFirstMatching(root) { node ->
                if (!node.isVisibleToUser) return@findFirstMatching false
                val cls = node.className?.toString() ?: return@findFirstMatching false
                if (!cls.contains(target.viewClass!!)) return@findFirstMatching false
                if (x < 0 || y < 0) return@findFirstMatching true
                val b = Rect(); node.getBoundsInScreen(b)
                b.contains(x, y)
            }
            if (match != null) {
                val b = Rect(); match.getBoundsInScreen(b)
                return ResolvedTarget(match, ResolutionMethod.CLASS_AND_BOUNDS, b)
            }
        }
        // 7. Bounding-box proximity fallback (coordinates from a previous observation)
        if (target.x != null && target.y != null) {
            val nearest = findNearestToPoint(root, target.x!!, target.y!!)
            if (nearest != null) {
                val b = Rect(); nearest.getBoundsInScreen(b)
                return ResolvedTarget(nearest, ResolutionMethod.BOUNDING_BOX, b)
            }
        }
        return null
    }

    private fun findFirstMatching(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (predicate(node)) {
                // Recycle remaining stack nodes not part of result path
                stack.forEach { runCatching { it.recycle() } }
                return AccessibilityNodeInfo.obtain(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    private fun findNearestToPoint(
        root: AccessibilityNodeInfo,
        x: Int,
        y: Int
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestDist = Int.MAX_VALUE
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.isVisibleToUser && (node.isClickable || node.isEditable || node.isScrollable
                        || node.text?.isNotEmpty() == true || node.contentDescription?.isNotEmpty() == true)) {
                val b = Rect()
                node.getBoundsInScreen(b)
                val dx = b.centerX() - x
                val dy = b.centerY() - y
                val d = dx * dx + dy * dy
                if (d < bestDist) {
                    bestDist = d
                    best?.let { runCatching { it.recycle() } }
                    best = AccessibilityNodeInfo.obtain(node)
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return best
    }
}
