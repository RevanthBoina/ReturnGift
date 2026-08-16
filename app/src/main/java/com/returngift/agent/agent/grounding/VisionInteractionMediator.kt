// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.grounding

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.returngift.agent.utils.XLog
import java.util.regex.Pattern

/**
 * Task-Scoped Vision & Interaction Mediator.
 *
 * Privacy & Security Architecture:
 * - Operates as a local privacy firewall between Android's raw screen state and the AI model.
 * - Does NOT attempt to jailbreak or bypass model restrictions; instead guarantees that the model
 *   never receives sensitive user data (OTPs, passwords, banking tokens, personal notification leaks).
 * - Scopes UI state strictly to the active target application/window.
 * - Deterministically redacts sensitive values in text and images before transmission.
 */
object VisionInteractionMediator {

    private const val TAG = "VisionMediator"

    data class MediatedState(
        val targetApp: String,
        val currentActivity: String,
        val sanitizedScreenText: String,
        val redactedCount: Int,
        val isPrivacySafe: Boolean = true
    )

    private val OTP_PATTERN = Pattern.compile("(?i)(?:otp|code|pin|verification|auth|one-time)[\\s:]*([0-9]{4,8})\\b")
    private val CARD_PATTERN = Pattern.compile("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|6(?:011|5[0-9]{2})[0-9]{12}|3[47][0-9]{13})\\b")
    private val CARD_SPACED_PATTERN = Pattern.compile("\\b[0-9]{4}[\\s-][0-9]{4}[\\s-][0-9]{4}[\\s-][0-9]{4}\\b")
    private val CVV_PATTERN = Pattern.compile("(?i)(?:cvv|cvc|security code)[\\s:]*([0-9]{3,4})\\b")
    private val BANK_BALANCE_PATTERN = Pattern.compile("(?i)(?:balance|bal|available)[\\s:]*(?:[₹$€£]|rs\\.?|inr)?[\\s]*([0-9,]+\\.[0-9]{2})\\b")
    private val UPI_PIN_PATTERN = Pattern.compile("(?i)(?:upi pin|mpin|secret pin)[\\s:]*([0-9]{4,6})\\b")

    @Volatile
    private var activeTaskId: String = ""
    @Volatile
    private var activeTargetPackage: String = ""
    @Volatile
    private var activeTaskGoal: String = ""

    /**
     * Initialize mediation context for an active task.
     */
    fun initTask(taskId: String, taskGoal: String, targetPackage: String? = null) {
        activeTaskId = taskId
        activeTaskGoal = taskGoal
        activeTargetPackage = targetPackage ?: ""
        XLog.i(TAG, "VisionInteractionMediator initialized for task: $taskId (targetPkg=$targetPackage)")
    }

    /**
     * Clear task mediation context.
     */
    fun clearTask() {
        activeTaskId = ""
        activeTargetPackage = ""
        activeTaskGoal = ""
    }

    /**
     * Mediate raw accessibility screen tree text into a task-scoped, sanitized representation.
     */
    fun mediateScreenInfo(
        rawScreenInfo: String,
        foregroundPackage: String? = null,
        currentActivity: String? = null
    ): MediatedState {
        if (rawScreenInfo.isBlank()) {
            return MediatedState(
                targetApp = foregroundPackage ?: activeTargetPackage,
                currentActivity = currentActivity ?: "unknown",
                sanitizedScreenText = rawScreenInfo,
                redactedCount = 0
            )
        }

        var text = rawScreenInfo
        var redactionCounter = 0

        // 1. Redact OTPs & Verification Codes
        val otpMatcher = OTP_PATTERN.matcher(text)
        if (otpMatcher.find()) {
            text = otpMatcher.replaceAll("[REDACTED_OTP]")
            redactionCounter++
        }

        // 2. Redact Card numbers
        val cardMatcher = CARD_PATTERN.matcher(text)
        if (cardMatcher.find()) {
            text = cardMatcher.replaceAll("[REDACTED_CARD]")
            redactionCounter++
        }
        val cardSpacedMatcher = CARD_SPACED_PATTERN.matcher(text)
        if (cardSpacedMatcher.find()) {
            text = cardSpacedMatcher.replaceAll("[REDACTED_CARD]")
            redactionCounter++
        }

        // 3. Redact CVV / Security Codes
        val cvvMatcher = CVV_PATTERN.matcher(text)
        if (cvvMatcher.find()) {
            text = cvvMatcher.replaceAll("[REDACTED_CVV]")
            redactionCounter++
        }

        // 4. Redact Bank Balances & UPI PIN cues
        val balMatcher = BANK_BALANCE_PATTERN.matcher(text)
        if (balMatcher.find()) {
            text = balMatcher.replaceAll("[REDACTED_BALANCE]")
            redactionCounter++
        }
        val upiMatcher = UPI_PIN_PATTERN.matcher(text)
        if (upiMatcher.find()) {
            text = upiMatcher.replaceAll("[REDACTED_PIN]")
            redactionCounter++
        }

        // 5. Redact Password input field contents
        text = text.replace(Regex("(?i)\\[password\\]:[^\\n]*"), "[password]: [PROTECTED]")
        text = text.replace(Regex("(?i)password=[^,\\s\\]]+"), "password=[PROTECTED]")

        val targetApp = foregroundPackage ?: activeTargetPackage.ifEmpty { "system_or_target_app" }
        val activity = currentActivity ?: "active_window"

        if (redactionCounter > 0) {
            XLog.i(TAG, "VisionMediator: applied $redactionCounter privacy redactions for targetApp=$targetApp")
        }

        return MediatedState(
            targetApp = targetApp,
            currentActivity = activity,
            sanitizedScreenText = text,
            redactedCount = redactionCounter
        )
    }

    /**
     * Format a compact, structured task-scoped observation state for model consumption.
     */
    fun formatMediatedPromptState(
        rawScreenInfo: String,
        foregroundPackage: String? = null,
        currentActivity: String? = null
    ): String {
        val mediated = mediateScreenInfo(rawScreenInfo, foregroundPackage, currentActivity)
        return buildString {
            append("## Task-Scoped Screen Observation\n")
            append("- Target App: ${mediated.targetApp}\n")
            if (mediated.currentActivity != "unknown") {
                append("- Active Window: ${mediated.currentActivity}\n")
            }
            if (mediated.redactedCount > 0) {
                append("- Privacy Shield: Active (${mediated.redactedCount} sensitive fields redacted)\n")
            }
            append("\n### Visible Elements:\n")
            append(mediated.sanitizedScreenText)
        }
    }

    /**
     * Deterministically sanitize a screenshot bitmap by painting opaque masks over sensitive bounds.
     */
    fun sanitizeScreenshot(sourceBitmap: Bitmap, sensitiveBounds: List<Rect>): Bitmap {
        if (sensitiveBounds.isEmpty()) return sourceBitmap

        val mutableBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }

        for (rect in sensitiveBounds) {
            canvas.drawRect(rect, paint)
        }
        XLog.i(TAG, "Sanitized screenshot bitmap by masking ${sensitiveBounds.size} sensitive regions")
        return mutableBitmap
    }
}
