// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.loop

import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.utils.XLog

/**
 * The "verify state change" step of the unified control loop
 * (observe → resolve target → act → **verify state change** → continue/recover).
 *
 * Compares relevant UI/application state before and after an action and decides whether
 * the action had its intended effect. This is the programmatic verification the loop
 * previously lacked — the only post-action signal was an optional screen snapshot fed to
 * the LLM, which left the agent free to continue on an unverified or stale state.
 *
 * Architectural references:
 * - Browser Use's "AgentHistoryList": state before/after each action.
 * - OpenHands' verification of action outcomes before continuing.
 *
 * Verification inputs (whichever are relevant to the action):
 * - foreground package (for open_app / switch_app / system_key navigation)
 * - screen-state signature (for tap / input / scroll)
 * - target presence (was the tapped element still there / did the expected text appear)
 *
 * The result tells the loop whether to continue, re-observe, or recover.
 */
object ActionVerifier {

    private const val TAG = "ActionVerifier"

    enum class VerificationOutcome {
        /** The action had its intended, verifiable effect — continue. */
        VERIFIED,
        /** The screen changed but the specific target/effect could not be confirmed — re-observe. */
        CHANGED_STATE,
        /** No detectable state change — the action was likely ineffective → recover. */
        NO_CHANGE,
        /** The action failed outright — recover / ask for a new plan. */
        FAILED
    }

    data class VerificationResult(
        val outcome: VerificationOutcome,
        val foregroundPackage: String?,
        val beforeSignature: String?,
        val afterSignature: String?,
        val detail: String
    )

    /**
     * Capture the state snapshot BEFORE an action executes. Pass the returned token to
     * [verifyAfter] along with the action's success flag and any target expectation.
     */
    fun captureBefore(service: ClawAccessibilityService, toolName: String): BeforeState {
        val foreground = try { service.getForegroundPackage() } catch (_: Exception) { null }
        val signature = if (toolName in FOREGROUND_SENSITIVE_TOOLS) {
            // For navigation/launch tools, the screen signature is volatile during the
            // transition; capture it but weight the foreground-package check more heavily.
            try { service.getScreenStateSignature() } catch (_: Exception) { null }
        } else {
            try { service.getScreenStateSignature() } catch (_: Exception) { null }
        }
        return BeforeState(foreground, signature, toolName)
    }

    /**
     * Verify the state AFTER an action executed, given the before-state token and any
     * expectation (e.g. the package that should now be foreground, or the text that should
     * now be present). Returns a [VerificationResult] the loop uses to continue or recover.
     */
    fun verifyAfter(
        service: ClawAccessibilityService,
        before: BeforeState,
        actionSucceeded: Boolean,
        expectedForeground: String? = null,
        expectedTextPresent: String? = null
    ): VerificationResult {
        val afterForeground = try { service.getForegroundPackage() } catch (_: Exception) { null }
        val afterSignature = try { service.getScreenStateSignature() } catch (_: Exception) { null }

        // 1. Failed action → FAILED.
        if (!actionSucceeded) {
            return VerificationResult(
                VerificationOutcome.FAILED, afterForeground, before.signature, afterSignature,
                "Action reported failure"
            )
        }

        // 2. Foreground expectation (open_app / switch_app / system_key navigation).
        if (expectedForeground != null) {
            // Own-package guard: the assistant driving its own UI to the foreground is not an
            // automation outcome. Verifying it would mask the real target app's state, so skip
            // the foreground verdict and let the caller observe instead.
            if (expectedForeground == service.packageName) {
                return VerificationResult(
                    VerificationOutcome.CHANGED_STATE, afterForeground, before.signature, afterSignature,
                    "Expected foreground is the assistant's own package (${service.packageName}) — foreground verification skipped"
                )
            }
            val match = expectedForeground.equals(afterForeground, ignoreCase = false)
            return VerificationResult(
                if (match) VerificationOutcome.VERIFIED else VerificationOutcome.NO_CHANGE,
                afterForeground, before.signature, afterSignature,
                if (match) "Foreground is $expectedForeground as expected"
                else "Expected foreground $expectedForeground but got $afterForeground"
            )
        }

        // 3. Expected text present (find_and_tap / input_text on a specific label).
        if (expectedTextPresent != null) {
            val present = textPresentOnScreen(service, expectedTextPresent)
            return VerificationResult(
                if (present) VerificationOutcome.VERIFIED else VerificationOutcome.CHANGED_STATE,
                afterForeground, before.signature, afterSignature,
                if (present) "Expected text '$expectedTextPresent' is on screen"
                else "Expected text '$expectedTextPresent' not found; screen may have changed"
            )
        }

        // 4. Generic state-change check.
        val changed = before.signature != null && before.signature != afterSignature
        return VerificationResult(
            if (changed) VerificationOutcome.CHANGED_STATE else VerificationOutcome.NO_CHANGE,
            afterForeground, before.signature, afterSignature,
            if (changed) "Screen state changed (signature $before.signature → $afterSignature)"
            else "Screen state unchanged ($afterSignature) — action may have been ineffective"
        )
    }

    private fun textPresentOnScreen(service: ClawAccessibilityService, text: String): Boolean {
        return try {
            val nodes = service.findNodesByText(text)
            val present = nodes.any { it.isVisibleToUser }
            ClawAccessibilityService.recycleNodes(nodes)
            present
        } catch (_: Exception) { false }
    }

    data class BeforeState(
        val foregroundPackage: String?,
        val signature: String?,
        val toolName: String
    )

    /** Tools whose effect is best verified by the foreground package, not screen text. */
    private val FOREGROUND_SENSITIVE_TOOLS = setOf(
        "open_app", "switch_app", "system_key"
    )
}
