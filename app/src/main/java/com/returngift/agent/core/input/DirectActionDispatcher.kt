// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.core.input

import android.view.accessibility.AccessibilityNodeInfo
import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.utils.XLog

/**
 * DirectActionDispatcher provides ultra-fast in-process action execution (<5ms).
 * Executes direct ACTION_CLICK when supported by the AccessibilityNodeInfo,
 * falling back to coordinate-based gesture dispatch only when required.
 */
object DirectActionDispatcher {

    private const val TAG = "DirectActionDispatcher"

    data class DispatchResult(
        val success: Boolean,
        val method: String,
        val latencyMs: Long
    )

    /**
     * Executes a fast tap on a node ID or coordinates.
     */
    @JvmStatic
    fun performFastTap(
        service: ClawAccessibilityService,
        nodeId: String?,
        x: Int,
        y: Int
    ): DispatchResult {
        val start = System.currentTimeMillis()

        // 1. Direct Node Action Fastpath (<5ms)
        val root = service.rootInActiveWindow
        if (root != null) {
            val node = findNodeAt(root, x, y)
            if (node != null) {
                if (node.isClickable) {
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        XLog.d(TAG, "Direct ACTION_CLICK succeeded on $nodeId at ($x, $y)")
                        node.recycle()
                        return DispatchResult(true, "DIRECT_NODE_CLICK", System.currentTimeMillis() - start)
                    }
                }
                // Try parent if clickable
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        val clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (clicked) {
                            XLog.d(TAG, "Parent ACTION_CLICK succeeded on $nodeId at ($x, $y)")
                            parent.recycle()
                            node.recycle()
                            return DispatchResult(true, "PARENT_NODE_CLICK", System.currentTimeMillis() - start)
                        }
                    }
                    val nextParent = parent.parent
                    parent.recycle()
                    parent = nextParent
                }
                node.recycle()
            }
        }

        // 2. Synthesized Gesture Fallpath
        val gestureSuccess = service.performTap(x, y, 60)
        return DispatchResult(
            gestureSuccess,
            "GESTURE_TAP",
            System.currentTimeMillis() - start
        )
    }

    private fun findNodeAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return null

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeAt(child, x, y)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }

        return if (node.isVisibleToUser) node else null
    }
}
