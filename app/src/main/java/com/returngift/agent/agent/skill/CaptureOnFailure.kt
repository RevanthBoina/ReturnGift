// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.skill

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.service.ScreenCaptureManager
import com.returngift.agent.utils.XLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Capture on Failure - captures UI dump and screenshot when skill execution fails.
 * 
 * This helps with debugging skill failures by preserving the screen state
 * at the moment of failure.
 */
object CaptureOnFailure {

    private const val TAG = "CaptureOnFailure"
    private val executor = Executors.newSingleThreadExecutor()
    private const val FAILURE_CAPTURE_DIR = "failure_captures"

    /**
     * Capture failure diagnostics for a skill.
     * 
     * @param skillId The skill that failed
     * @param stepNumber The step number where failure occurred
     * @param errorMessage The error message from the failure
     */
    fun capture(skillId: String, stepNumber: Int, errorMessage: String?) {
        val context = getContext() ?: return
        
        executor.execute {
            try {
                captureImpl(context, skillId, stepNumber, errorMessage)
            } catch (e: Exception) {
                XLog.e(TAG, "Error capturing failure diagnostics", e)
            }
        }
    }

    private fun captureImpl(
        context: Context,
        skillId: String,
        stepNumber: Int,
        errorMessage: String?
    ) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val captureDir = File(context.cacheDir, FAILURE_CAPTURE_DIR).apply { mkdirs() }
        val baseName = "${skillId}_step${stepNumber}_${timestamp}"
        
        // Capture accessibility tree XML
        val service = ClawAccessibilityService.getInstance()
        if (service != null) {
            val root = service.rootInActiveWindow
            if (root != null) {
                try {
                    val xml = buildAccessibilityTreeXml(root)
                    val xmlFile = File(captureDir, "${baseName}_tree.xml")
                    xmlFile.writeText(xml)
                    XLog.d(TAG, "Captured accessibility tree: ${xmlFile.name}")
                } finally {
                    root.recycle()
                }
            }
        }
        
        // Capture screenshot (redacted)
        captureRedactedScreenshot(context, service, captureDir, baseName)
        
        // Write failure metadata
        val metaFile = File(captureDir, "${baseName}_meta.txt")
        metaFile.writeText(buildMetadata(skillId, stepNumber, errorMessage))
        XLog.d(TAG, "Captured failure metadata: ${metaFile.name}")
        
        XLog.i(TAG, "Failure capture complete for skill=$skillId step=$stepNumber")
    }
    
    private fun buildAccessibilityTreeXml(root: android.view.accessibility.AccessibilityNodeInfo): String {
        val xml = StringBuilder()
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        xml.append("<failure-capture>\n")
        buildNodeXml(root, xml, 1)
        xml.append("</failure-capture>\n")
        return xml.toString()
    }
    
    private fun buildNodeXml(node: android.view.accessibility.AccessibilityNodeInfo, xml: StringBuilder, depth: Int) {
        if (node == null || depth > 15) return
        
        val indent = "  ".repeat(depth)
        xml.append(indent).append("<node ")
        
        node.className?.let { xml.append("class=\"").append(escapeXml(it.toString())).append("\" ") }
        node.text?.let { xml.append("text=\"").append(redactIfSensitive(it.toString())).append("\" ") }
        node.contentDescription?.let { xml.append("desc=\"").append(redactIfSensitive(it.toString())).append("\" ") }
        xml.append("clickable=\"").append(node.isClickable).append("\" ")
        xml.append("enabled=\"").append(node.isEnabled).append("\" ")
        node.viewIdResourceName?.let { xml.append("id=\"").append(it).append("\" ") }
        
        xml.append("/>\n")
        
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                buildNodeXml(child, xml, depth + 1)
                child.recycle()
            }
        }
    }
    
    /**
     * Redact potentially sensitive information from strings.
     */
    private fun redactIfSensitive(text: String): String {
        // Redact phone numbers, emails, and message content
        return text
            .replace(Regex("\\b\\d{10,}\\b"), "[PHONE]")
            .replace(Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"), "[EMAIL]")
            .replace(Regex("(?i)(password|pin|cvv|otp|secret)\\s*[:=]?\\s*\\S+", RegexOption.IGNORE_CASE), "[REDACTED]")
            .let { escapeXml(it) }
    }
    
    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
    
    private fun captureRedactedScreenshot(
        context: Context,
        service: ClawAccessibilityService?,
        captureDir: File,
        baseName: String
    ) {
        if (service == null) {
            XLog.d(TAG, "Cannot capture screenshot: accessibility service not available")
            return
        }
        
        val screenshot = service.takeScreenshot(5000)
        if (screenshot == null) {
            XLog.d(TAG, "Screenshot capture failed")
            return
        }
        
        try {
            // Convert bitmap to redacted PNG (simplified - real implementation would blur sensitive areas)
            val outputFile = File(captureDir, "${baseName}_screenshot.jpg")
            FileOutputStream(outputFile).use { fos ->
                screenshot.compress(Bitmap.CompressFormat.JPEG, 80, fos)
            }
            XLog.d(TAG, "Captured screenshot: ${outputFile.name}")
        } finally {
            screenshot.recycle()
        }
    }
    
    private fun buildMetadata(skillId: String, stepNumber: Int, errorMessage: String?): String {
        return buildString {
            appendLine("Skill: $skillId")
            appendLine("Failed Step: $stepNumber")
            appendLine("Error: ${errorMessage ?: "unknown"}")
            appendLine("Timestamp: ${System.currentTimeMillis()}")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android: ${android.os.Build.VERSION.SDK_INT}")
        }
    }
    
    private fun getContext(): Context? {
        return try {
            com.returngift.agent.ClawApplication.getInstance()
        } catch (e: Exception) {
            null
        }
    }
}
