// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.core.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.utils.XLog

/**
 * ModalInterceptor detects benign transient popups (rate us, promo sheets, updates)
 * and safely dismisses them, while protecting sensitive confirmation / payment / auth dialogs.
 */
object ModalInterceptor {

    private const val TAG = "ModalInterceptor"

    // Safe transient dismiss button keywords
    private val TRANSIENT_DISMISS_KEYWORDS = setOf(
        "dismiss", "close", "got it", "not now", "skip", "later", "no thanks", "maybe later", "remind me later"
    )

    // Security/Safety sensitive keywords — NEVER auto-dismiss
    private val SENSITIVE_KEYWORDS = setOf(
        "payment", "pay", "checkout", "upi", "pin", "cvv", "password", "authenticate",
        "fingerprint", "biometric", "delete", "erase", "format", "remove account",
        "transfer", "subscribe", "purchase", "authorize", "grant admin"
    )

    data class ModalCheckResult(
        val isModalDetected: Boolean,
        val isBenignTransient: Boolean,
        val isSecuritySensitive: Boolean,
        val dismissNode: AccessibilityNodeInfo? = null,
        val title: String? = null
    )

    /**
     * Inspects the current window hierarchy for modal dialogs.
     */
    @JvmStatic
    fun checkModal(root: AccessibilityNodeInfo?): ModalCheckResult {
        if (root == null) return ModalCheckResult(false, false, false)

        val fullText = StringBuilder()
        val dismissCandidates = mutableListOf<AccessibilityNodeInfo>()
        val isExplicitDialogContainer = booleanArrayOf(false)

        collectDialogInfo(root, fullText, dismissCandidates, isExplicitDialogContainer)

        val textString = fullText.toString().lowercase()

        // Check if sensitive
        for (sensitive in SENSITIVE_KEYWORDS) {
            if (textString.contains(sensitive)) {
                return ModalCheckResult(
                    isModalDetected = true,
                    isBenignTransient = false,
                    isSecuritySensitive = true,
                    title = "Security Sensitive Dialog ($sensitive)"
                )
            }
        }

        // Check if benign transient dismissible
        if (dismissCandidates.isNotEmpty() && (isExplicitDialogContainer[0] || dismissCandidates.size <= 2)) {
            val candidate = dismissCandidates.first()
            val text = candidate.text?.toString() ?: candidate.contentDescription?.toString() ?: "Dismiss"
            return ModalCheckResult(
                isModalDetected = true,
                isBenignTransient = true,
                isSecuritySensitive = false,
                dismissNode = candidate,
                title = "Transient Dismissible Dialog ($text)"
            )
        }

        return ModalCheckResult(false, false, false)
    }

    /**
     * Tries to auto-dismiss a benign modal dialog. Returns true if dismissed.
     */
    @JvmStatic
    fun tryAutoDismiss(service: ClawAccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val check = checkModal(root)
        if (check.isBenignTransient && check.dismissNode != null) {
            XLog.i(TAG, "Auto-dismissing benign transient dialog: ${check.title}")
            val bounds = Rect()
            check.dismissNode.getBoundsInScreen(bounds)
            val success = service.performTap(bounds.centerX(), bounds.centerY())
            if (success) {
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
                return true
            }
        }
        return false
    }

    private fun collectDialogInfo(
        node: AccessibilityNodeInfo,
        textCollector: StringBuilder,
        dismissCandidates: MutableList<AccessibilityNodeInfo>,
        isExplicitDialogContainer: BooleanArray
    ) {
        val cn = node.className?.toString().orEmpty()
        if (cn.contains("Dialog", ignoreCase = true) ||
            cn.contains("BottomSheet", ignoreCase = true) ||
            cn.contains("PopupWindow", ignoreCase = true) ||
            cn.contains("Popup", ignoreCase = true)) {
            isExplicitDialogContainer[0] = true
        }

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val label = (if (text.isNotEmpty()) text else desc).lowercase()

        if (label.isNotEmpty()) {
            textCollector.append(" ").append(label)
        }

        if (node.isClickable && label.isNotEmpty()) {
            if (TRANSIENT_DISMISS_KEYWORDS.contains(label)) {
                dismissCandidates.add(node)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectDialogInfo(child, textCollector, dismissCandidates, isExplicitDialogContainer)
        }
    }
}
