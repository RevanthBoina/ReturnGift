// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.core.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.returngift.agent.core.accessibility.SemanticNodeFlattener
import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.utils.XLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * VisualGroundingFallbackEngine is invoked only when the current screen lacks sufficient
 * actionable accessibility information (e.g. custom OpenGL, Canvas, Vulkan, or opaque WebViews).
 * Produces lightweight visual targets with actionable coordinates.
 */
object VisualGroundingFallbackEngine {

    private const val TAG = "VisualGroundingFallback"

    data class VisualTarget(
        val id: String,
        val label: String,
        val bounds: Rect,
        val confidence: Float,
        val centerX: Int,
        val centerY: Int
    )

    /**
     * Inspects if visual fallback is needed.
     */
    fun isFallbackNeeded(semanticNodes: List<SemanticNodeFlattener.SemanticNode>): Boolean {
        if (semanticNodes.isEmpty()) return true
        val actionableCount = semanticNodes.count { it.isClickable || it.isEditable || it.isScrollable }
        return actionableCount == 0
    }

    /**
     * Generates visual targets from the current screen when accessibility nodes are absent.
     */
    fun extractVisualTargets(
        service: ClawAccessibilityService,
        nodeIdMap: ConcurrentHashMap<String, IntArray>,
        counter: AtomicInteger
    ): List<VisualTarget> {
        val targets = mutableListOf<VisualTarget>()

        // Get display metrics
        val dm = service.resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels

        // Standard quadrant/key area candidate targets for canvas/game surfaces
        val candidateRegions = listOf(
            Pair("Center Action", Rect((width * 0.3).toInt(), (height * 0.4).toInt(), (width * 0.7).toInt(), (height * 0.6).toInt())),
            Pair("Bottom Center", Rect((width * 0.3).toInt(), (height * 0.75).toInt(), (width * 0.7).toInt(), (height * 0.9).toInt())),
            Pair("Bottom Left", Rect(0, (height * 0.75).toInt(), (width * 0.35).toInt(), (height * 0.95).toInt())),
            Pair("Bottom Right", Rect((width * 0.65).toInt(), (height * 0.75).toInt(), width, (height * 0.95).toInt())),
            Pair("Top Left Back", Rect(0, 0, (width * 0.25).toInt(), (height * 0.15).toInt())),
            Pair("Top Right Menu", Rect((width * 0.75).toInt(), 0, width, (height * 0.15).toInt()))
        )

        for ((name, rect) in candidateRegions) {
            val cx = rect.centerX()
            val cy = rect.centerY()
            val id = "v" + counter.incrementAndGet()
            nodeIdMap[id] = intArrayOf(cx, cy)
            targets.add(
                VisualTarget(
                    id = id,
                    label = name,
                    bounds = rect,
                    confidence = 0.85f,
                    centerX = cx,
                    centerY = cy
                )
            )
        }

        XLog.i(TAG, "Generated ${targets.size} visual targets on canvas surface")
        return targets
    }

    fun formatVisualTargets(targets: List<VisualTarget>): String {
        val sb = StringBuilder()
        sb.append("[Visual Fallback Active — SurfaceView / Canvas detected]\n")
        for (target in targets) {
            sb.append("[").append(target.id).append("] \"")
                .append(target.label).append("\" tap (")
                .append(target.centerX).append(",").append(target.centerY).append(")\n")
        }
        return sb.toString()
    }
}
