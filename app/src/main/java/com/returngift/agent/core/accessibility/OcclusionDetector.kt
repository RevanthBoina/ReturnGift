// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.core.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import com.returngift.agent.service.ClawAccessibilityService

/**
 * OcclusionDetector detects when UI elements are obscured by soft keyboards,
 * floating windows, mini-players, or dialog overlays.
 */
object OcclusionDetector {

    private const val OCCLUSION_AREA_THRESHOLD = 0.50f // 50% covered

    data class OcclusionResult(
        val isOccluded: Boolean,
        val occludedBy: String? = null,
        val visibleAreaRatio: Float = 1.0f
    )

    /**
     * Inspects a list of SemanticNodes against overlay windows on screen.
     * Returns a filtered list where substantially occluded nodes are excluded or marked.
     */
    @JvmStatic
    fun filterOccludedNodes(
        nodes: List<SemanticNodeFlattener.SemanticNode>,
        service: ClawAccessibilityService?
    ): List<SemanticNodeFlattener.SemanticNode> {
        if (service == null) return nodes
        val overlayBoundsList = getOverlayBounds(service)
        if (overlayBoundsList.isEmpty()) return nodes

        return nodes.filter { node ->
            val result = checkOcclusion(node.bounds, overlayBoundsList)
            !result.isOccluded
        }
    }

    /**
     * Checks whether a single bounding box is occluded by any known overlay window.
     */
    @JvmStatic
    fun checkOcclusion(targetBounds: Rect, overlayBoundsList: List<Rect>): OcclusionResult {
        val targetArea = targetBounds.width() * targetBounds.height()
        if (targetArea <= 0) return OcclusionResult(isOccluded = false)

        for (overlay in overlayBoundsList) {
            val intersection = Rect()
            if (intersection.setIntersect(targetBounds, overlay)) {
                val overlapArea = intersection.width() * intersection.height()
                val ratio = overlapArea.toFloat() / targetArea.toFloat()
                if (ratio >= OCCLUSION_AREA_THRESHOLD) {
                    return OcclusionResult(
                        isOccluded = true,
                        occludedBy = "Overlay at $overlay",
                        visibleAreaRatio = 1.0f - ratio
                    )
                }
            }
        }

        return OcclusionResult(isOccluded = false)
    }

    private fun getOverlayBounds(service: ClawAccessibilityService): List<Rect> {
        val overlays = mutableListOf<Rect>()
        try {
            val windows = service.windows
            if (windows != null) {
                for (window in windows) {
                    val type = window.type
                    // TYPE_INPUT_METHOD (keyboard), TYPE_APPLICATION_OVERLAY, TYPE_SYSTEM_ALERT, TYPE_ACCESSIBILITY_OVERLAY
                    if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                        type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                        type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                        val bounds = Rect()
                        window.getBoundsInScreen(bounds)
                        if (bounds.width() > 0 && bounds.height() > 0) {
                            overlays.add(bounds)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return overlays
    }
}
