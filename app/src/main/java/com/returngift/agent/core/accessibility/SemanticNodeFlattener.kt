// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.core.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * SemanticNodeFlattener flattens deeply nested AccessibilityNodeInfo trees into
 * compact, semantic, and actionable UI element representations.
 * It collapses redundant Layout wrappers while preserving interactive and text leaves.
 */
object SemanticNodeFlattener {

    data class SemanticNode(
        val id: String,
        val text: String,
        val role: String,
        val bounds: Rect,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isScrollable: Boolean,
        val isHScroll: Boolean,
        val isCheckable: Boolean,
        val isChecked: Boolean,
        val isWebView: Boolean,
        val isOverlayWrapper: Boolean,
        val centerX: Int,
        val centerY: Int
    )

    private val CONTAINER_CLASSES = setOf(
        "android.widget.FrameLayout",
        "android.widget.LinearLayout",
        "android.widget.RelativeLayout",
        "androidx.constraintlayout.widget.ConstraintLayout",
        "androidx.appcompat.widget.ContentFrameLayout",
        "android.view.ViewGroup",
        "android.view.View"
    )

    /**
     * Flattens the accessibility node tree into a list of SemanticNode instances.
     */
    @JvmStatic
    @JvmOverloads
    fun flatten(
        root: AccessibilityNodeInfo?,
        nodeIdMap: ConcurrentHashMap<String, IntArray>,
        counter: AtomicInteger = AtomicInteger(0),
        maxNodes: Int = 500
    ): List<SemanticNode> {
        if (root == null) return emptyList()

        val results = mutableListOf<SemanticNode>()
        nodeIdMap.clear()
        counter.set(0)

        flattenRecursive(root, results, nodeIdMap, counter, maxNodes)
        return results
    }

    private fun flattenRecursive(
        node: AccessibilityNodeInfo,
        outList: MutableList<SemanticNode>,
        nodeIdMap: ConcurrentHashMap<String, IntArray>,
        counter: AtomicInteger,
        maxNodes: Int
    ) {
        if (counter.get() >= maxNodes) return

        if (!node.isVisibleToUser) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                flattenRecursive(child, outList, nodeIdMap, counter, maxNodes)
                child.recycle()
            }
            return
        }

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val displayText = if (text.isNotEmpty()) text else desc
        val hasContent = displayText.isNotEmpty()
        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val isScrollable = node.isScrollable
        val isCheckable = node.isCheckable
        val isChecked = node.isChecked
        val className = node.className?.toString().orEmpty()
        val isWebView = className.contains("WebView")
        val isSlider = className.contains("SeekBar") || className.contains("Slider")
        val isProgress = className.contains("ProgressBar")

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val cx = bounds.centerX()
        val cy = bounds.centerY()

        val isContainer = CONTAINER_CLASSES.contains(className)
        val isOverlayWrapper = isClickable && !hasContent && bounds.width() > 800 && bounds.height() > 1200

        // Decide if this node is semantically meaningful on its own
        val isMeaningful = hasContent || isEditable || (isClickable && !isContainer) ||
                isSlider || isProgress || isWebView || isCheckable || isScrollable || isOverlayWrapper

        if (isMeaningful) {
            val nodeId = "n" + counter.incrementAndGet()
            nodeIdMap[nodeId] = intArrayOf(cx, cy)

            val role = when {
                isEditable -> "edit"
                isSlider -> "slider"
                isProgress -> "progress"
                isWebView -> "webview"
                isCheckable -> "checkbox"
                isClickable -> "button"
                hasContent -> "text"
                isScrollable -> "scroll_container"
                else -> "view"
            }

            val isHScroll = isScrollable && bounds.height() > 0 && (bounds.width() > 1.5 * bounds.height())

            outList.add(
                SemanticNode(
                    id = nodeId,
                    text = displayText,
                    role = role,
                    bounds = bounds,
                    isClickable = isClickable,
                    isEditable = isEditable,
                    isScrollable = isScrollable,
                    isHScroll = isHScroll,
                    isCheckable = isCheckable,
                    isChecked = isChecked,
                    isWebView = isWebView && node.childCount == 0,
                    isOverlayWrapper = isOverlayWrapper,
                    centerX = cx,
                    centerY = cy
                )
            )
        }

        // Process children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            flattenRecursive(child, outList, nodeIdMap, counter, maxNodes)
            child.recycle()
        }
    }

    /**
     * Formats semantic nodes into the standard compact representation for AI.
     */
    @JvmStatic
    @JvmOverloads
    fun formatToString(nodes: List<SemanticNode>, maxNodes: Int = 500): String {
        val sb = StringBuilder()
        for (node in nodes) {
            sb.append("[").append(node.id).append("] ")
            if (node.text.isNotEmpty()) {
                val cleanText = if (node.text.length > 50) node.text.take(50) + ".." else node.text
                sb.append("\"").append(cleanText.replace("\n", " ")).append("\" ")
            }
            if (node.isOverlayWrapper) {
                sb.append("overlay-wrapper ")
            } else if (node.isClickable) {
                sb.append("tap ")
            }
            if (node.isEditable) sb.append("edit ")
            if (node.isHScroll) {
                sb.append("hscroll ")
            } else if (node.isScrollable) {
                sb.append("scroll ")
            }
            if (node.isCheckable) sb.append(if (node.isChecked) "on " else "off ")
            if (node.isWebView) sb.append("webview ")
            sb.append("(").append(node.centerX).append(",").append(node.centerY).append(")\n")
        }

        if (nodes.size >= maxNodes) {
            sb.append("  [...truncated at ").append(maxNodes).append(" nodes. Scroll to narrow visible content]\n")
        }

        return sb.toString()
    }
}
