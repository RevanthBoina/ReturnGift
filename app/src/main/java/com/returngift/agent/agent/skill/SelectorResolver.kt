// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.skill

import android.view.accessibility.AccessibilityNodeInfo
import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.utils.XLog

/**
 * Resolves YAML selector references (e.g. "sel.wa_send_button", resource_id maps)
 * against the live accessibility tree.
 *
 * Selector formats supported:
 *  - "sel.{name}"          → text/content-desc heuristic lookup by name fragment
 *  - {by: resource_id, value: "pkg:id/foo"}  → findAccessibilityNodeInfosByViewId
 *  - {by: content_desc, matches: "regex"}    → regex on contentDescription
 *  - {by: text, value: "Send"}               → findAccessibilityNodeInfosByText
 */
object SelectorResolver {

    private const val TAG = "SelectorResolver"

    /** Returns the first matching node or null. */
    fun resolve(selector: Any?, appPackage: String = ""): AccessibilityNodeInfo? {
        val service = ClawAccessibilityService.getInstance() ?: return null
        val root = service.rootInActiveWindow ?: return null
        return try {
            when (selector) {
                is String -> resolveString(selector, root, appPackage)
                is Map<*, *> -> resolveMap(selector, root)
                else -> null
            }
        } finally {
            // root is recycled by the caller's GC; don't recycle here to avoid double-recycle
        }
    }

    private fun resolveString(sel: String, root: AccessibilityNodeInfo, appPackage: String): AccessibilityNodeInfo? {
        if (!sel.startsWith("sel.")) return null
        val name = sel.removePrefix("sel.").lowercase()
        // Heuristic: match text or content-desc containing the last segment of the name
        val fragment = name.substringAfterLast('_').ifEmpty { name }
        val byText = root.findAccessibilityNodeInfosByText(fragment)
        if (!byText.isNullOrEmpty()) return byText.first()
        XLog.d(TAG, "resolveString: no match for '$sel' (fragment='$fragment')")
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveMap(sel: Map<*, *>, root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return when (sel["by"]?.toString()) {
            "resource_id" -> {
                val id = sel["value"]?.toString() ?: return null
                root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
            }
            "content_desc" -> {
                val pattern = sel["matches"]?.toString() ?: sel["value"]?.toString() ?: return null
                val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                findByPredicate(root) { node ->
                    node.contentDescription?.let { regex.containsMatchIn(it) } == true
                }
            }
            "text" -> {
                val value = sel["value"]?.toString() ?: return null
                root.findAccessibilityNodeInfosByText(value)?.firstOrNull()
            }
            else -> null
        }
    }

    private fun findByPredicate(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (depth > 20) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findByPredicate(child, depth + 1, predicate)
            if (found != null) return found
        }
        return null
    }
}
