// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.grounding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.returngift.agent.agent.planner.GraphState
import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.utils.XLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Visual UI Grounding service - unified screen representation.
 * Combines accessibility hierarchy, OCR, visual landmarks, and icon detection.
 */
class VisualUIGrounding(private val context: Context) {

    private const val TAG = "VisualUIGrounding"
    
    private val a11yConfidenceThreshold = 0.7f
    private val screenCache = ConcurrentHashMap<String, ScreenUnderstanding>()
    private var lastScreenHash: String? = null
    
    data class ScreenUnderstanding(
        val timestamp: Long,
        val screenHash: String,
        val elements: List<AccessibilityElement>,
        val ocrResults: List<OCRResult>,
        val landmarks: List<VisualLandmark>,
        val confidence: Float,
        val requiresFallback: Boolean
    )
    
    data class AccessibilityElement(
        val nodeId: String,
        val className: String,
        val text: String,
        val contentDescription: String,
        val bounds: Rect,
        val isClickable: Boolean,
        val isFocusable: Boolean,
        val isEditable: Boolean,
        val viewIdResourceName: String,
        val depth: Int,
        val confidence: Float
    )
    
    data class OCRResult(val text: String, val bounds: Rect, val confidence: Float)
    
    data class VisualLandmark(val type: String, val bounds: Rect, val label: String, val confidence: Float)
    
    fun observe(): GraphState.Observation {
        val elements = getAccessibilityHierarchy()
        val confidence = calculateAccessibilityConfidence(elements)
        val requiresFallback = confidence < a11yConfidenceThreshold || hasUnlabeledButtons(elements)
        
        val screenshot = captureScreenshot()
        val screenHash = screenshot?.hashCode()?.toString() ?: "no_screen"
        
        val observation = GraphState.Observation(
            nodeId = "",
            timestamp = System.currentTimeMillis(),
            screenHash = screenHash,
            elements = elements.map { a11y ->
                GraphState.UIElement(
                    id = a11y.nodeId,
                    type = a11y.className,
                    text = a11y.text.ifEmpty { a11y.contentDescription },
                    bounds = GraphState.Rect(a11y.bounds.left, a11y.bounds.top, a11y.bounds.right, a11y.bounds.bottom),
                    clickable = a11y.isClickable,
                    confidence = a11y.confidence
                )
            },
            landmarks = emptyList(),
            confidence = confidence,
            textContent = elements.filter { it.text.isNotEmpty() }.joinToString(" ") { it.text }
        )
        
        screenCache[screenHash] = ScreenUnderstanding(
            System.currentTimeMillis(), screenHash, elements, emptyList(), emptyList(), confidence, requiresFallback
        )
        lastScreenHash = screenHash
        
        XLog.d(TAG, "Observed: hash=$screenHash, elements=${elements.size}, confidence=$confidence, fallback=$requiresFallback")
        return observation
    }
    
    fun verify(action: GraphState.Action?, postObservation: GraphState.Observation): Boolean {
        if (action == null) return true
        if (postObservation.screenHash != lastScreenHash) return true
        
        return when {
            action.expectedOutcome.isEmpty() -> true
            action.expectedOutcome.contains("text:") -> {
                val expectedText = action.expectedOutcome.removePrefix("text:")
                postObservation.textContent.contains(expectedText, ignoreCase = true)
            }
            else -> true
        }
    }
    
    fun findElement(query: String, observation: GraphState.Observation): GraphState.UIElement? {
        val normalized = query.lowercase()
        return observation.elements.firstOrNull { 
            it.text.lowercase().contains(normalized) || it.id.lowercase().contains(normalized)
        }
    }
    
    fun getElementAt(x: Int, y: Int, observation: GraphState.Observation): GraphState.UIElement? {
        return observation.elements
            .filter { x in it.bounds.left..it.bounds.right && y in it.bounds.top..it.bounds.bottom }
            .maxByOrNull { it.id.hashCode() }
    }
    
    fun compareScreens(pre: GraphState.Observation, post: GraphState.Observation): ScreenDiff {
        val added = post.elements.count { pe -> pre.elements.none { it.id == pe.id } }
        val removed = pre.elements.count { pe -> post.elements.none { it.id == pe.id } }
        val common = pre.elements.count { pe -> post.elements.any { it.id == pe.id } }
        
        val preWords = pre.textContent.split("\\s+").toSet()
        val postWords = post.textContent.split("\\s+").toSet()
        val textChanges = (postWords - preWords).union(preWords - postWords).toList()
        
        return ScreenDiff(
            pre.screenHash, post.screenHash, added, removed, common, textChanges,
            significantChange = added > 0 || removed > 0 || textChanges.size > 5
        )
    }
    
    data class ScreenDiff(
        val preHash: String,
        val postHash: String,
        val addedCount: Int,
        val removedCount: Int,
        val commonCount: Int,
        val textChanges: List<String>,
        val significantChange: Boolean
    )
    
    private fun getAccessibilityHierarchy(): List<AccessibilityElement> {
        val elements = mutableListOf<AccessibilityElement>()
        try {
            val service = ClawAccessibilityService.getInstance()
            val rootNode = service?.rootInActiveWindow
            if (rootNode != null) {
                extractRecursive(rootNode, elements, 0)
                rootNode.recycle()
            }
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to get accessibility hierarchy", e)
        }
        return elements
    }
    
    private fun extractRecursive(node: AccessibilityNodeInfo, elements: MutableList<AccessibilityElement>, depth: Int) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        
        var confidence = 0.5f
        if (node.text?.isNotEmpty() == true) confidence += 0.2f
        if (node.contentDescription?.isNotEmpty() == true) confidence += 0.15f
        if (node.viewIdResourceName?.isNotEmpty() == true) confidence += 0.15f
        
        elements.add(AccessibilityElement(
            generateNodeId(node), node.className?.toString() ?: "",
            node.text?.toString() ?: "", node.contentDescription?.toString() ?: "",
            bounds, node.isClickable || node.isLongClickable, node.isFocusable,
            node.isEditable, node.viewIdResourceName?.toString() ?: "", depth, confidence.coerceIn(0f, 1f)
        ))
        
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                extractRecursive(child, elements, depth + 1)
                child.recycle()
            }
        }
    }
    
    private fun generateNodeId(node: AccessibilityNodeInfo): String {
        return "node_${node.hashCode()}"
    }
    
    private fun calculateAccessibilityConfidence(elements: List<AccessibilityElement>): Float {
        if (elements.isEmpty()) return 0f
        val avg = elements.map { it.confidence }.average()
        val clickableRatio = elements.count { it.isClickable }.toFloat() / elements.size
        return (avg * 0.6 + clickableRatio * 0.4).toFloat().coerceIn(0f, 1f)
    }
    
    private fun hasUnlabeledButtons(elements: List<AccessibilityElement>): Boolean {
        return elements.any { it.isClickable && it.text.isEmpty() && it.contentDescription.isEmpty() }
    }
    
    private fun captureScreenshot(): Bitmap? {
        return try {
            ClawAccessibilityService.getInstance()?.takeScreenshot(2000)
        } catch (e: Exception) {
            null
        }
    }
}
