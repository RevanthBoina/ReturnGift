// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.loop

import com.returngift.agent.agent.StuckDetector
import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.utils.XLog

/**
 * Native interaction watchdog that compares relevant UI/application state before and after
 * actions and stops blindly repeating ineffective ones.
 *
 * Detects:
 *  - consecutive ineffective actions (action "succeeded" but the screen did not change
 *    and the target did not appear), default threshold 3
 *  - repeated identical states (screen signature unchanged across consecutive steps)
 *  - cyclic action sequences (A → B → A → B …)
 *  - unexpected overlays (Recents / dialog / permission screen on top of the target app)
 *  - stalled transitions (foreground package never reaches the expected target)
 *
 * After the threshold is hit it EXECUTES a recovery strategy appropriate to the situation
 * — re-query the UI, press Back, return Home, relaunch the target app, or ask the model
 * for a new plan — rather than only injecting a textual hint. The recovery preserves
 * workflow context (the original task and the target app).
 *
 * Architectural references:
 * - OpenHands' action-execution loop with state-based recovery.
 * - Browser Use's "invalid action" detection + step-back recovery.
 * - The existing [StuckDetector] (extended, not replaced — it keeps its 5-signal/3-level
 *   detection and hint injection; this watchdog adds programmatic recovery execution).
 */
class InteractionWatchdog(
    private val ineffectiveThreshold: Int = 3,
    private val stuckDetector: StuckDetector = StuckDetector()
) {

    private data class RoundRecord(
        val toolName: String,
        val argsFingerprint: String,
        val verification: ActionVerifier.VerificationResult
    )

    private val recentRounds = ArrayDeque<RoundRecord>()
    private var consecutiveIneffective = 0

    enum class RecoveryStrategy {
        NONE,                  // no recovery needed — continue normally
        RE_QUERY,             // re-query the UI (get_screen_info) and continue
        PRESS_BACK,           // dismiss overlay / go back one step
        GO_HOME,              // return to home, then relaunch target
        RELAUNCH_TARGET,      // relaunch the expected target app
        ASK_MODEL_FOR_PLAN    // inject a recovery hint asking the model for a new plan
    }

    data class Recovery(
        val strategy: RecoveryStrategy,
        val message: String,
        val modelHint: String? = null
    )

    /**
     * Record one round and decide whether recovery is needed.
     *
     * @param toolName the action tool that ran
     * @param argsFingerprint a short fingerprint of the args (for cycle detection)
     * @param verification the [ActionVerifier] result for this round
     * @param screenHash hash of current screen (fed to the underlying StuckDetector)
     * @param expectedForeground the target app the task is driving (for relaunch recovery)
     * @return a [Recovery] describing what to do; [RecoveryStrategy.NONE] means continue.
     */
    fun record(
        toolName: String,
        argsFingerprint: String,
        verification: ActionVerifier.VerificationResult,
        screenHash: Int,
        expectedForeground: String? = null,
        overlayPresent: Boolean = false
    ): Recovery {
        recentRounds.addLast(RoundRecord(toolName, argsFingerprint, verification))
        if (recentRounds.size > 8) recentRounds.removeFirst()

        // Ineffective = no state change and not verified (and the action itself didn't fail,
        // which StuckDetector handles separately).
        val ineffective = verification.outcome == ActionVerifier.VerificationOutcome.NO_CHANGE
        if (ineffective) {
            consecutiveIneffective++
        } else {
            consecutiveIneffective = 0
        }

        // 1. Threshold of consecutive ineffective actions → execute recovery.
        if (consecutiveIneffective >= ineffectiveThreshold) {
            XLog.w(TAG, "Threshold reached: $consecutiveIneffective consecutive ineffective actions")
            return chooseRecovery(verification, expectedForeground, overlayPresent)
        }

        // 2. Unexpected overlay → dismiss it.
        if (overlayPresent) {
            XLog.w(TAG, "Unexpected system overlay detected")
            return Recovery(RecoveryStrategy.PRESS_BACK,
                "A system overlay (Recents/dialog) is blocking the target app. Pressing Back to dismiss it.",
                modelHint = "[System Notice] A system overlay interrupted the task. I pressed Back to dismiss it; re-check the screen.")
        }

        // 3. Cyclic action sequence (A → B → A → B).
        val cycle = detectCycle()
        if (cycle != null) {
            XLog.w(TAG, "Cyclic action sequence detected: $cycle")
            return Recovery(RecoveryStrategy.ASK_MODEL_FOR_PLAN,
                "Cyclic action sequence detected ($cycle).",
                modelHint = "[System Warning] You are in an action cycle ($cycle). Stop repeating these actions and try a fundamentally different approach, or call finish if the task cannot be completed.")
        }

        // 4. Stalled transition: a foreground-sensitive tool ran but NO_CHANGE and the
        //    expected foreground was not reached.
        if (verification.outcome == ActionVerifier.VerificationOutcome.NO_CHANGE
            && expectedForeground != null
            && verification.foregroundPackage != expectedForeground) {
            XLog.w(TAG, "Stalled transition: expected $expectedForeground, got ${verification.foregroundPackage}")
            return Recovery(RecoveryStrategy.RELAUNCH_TARGET,
                "Transition stalled — $expectedForeground is not foreground (got ${verification.foregroundPackage}). Relaunching the target.",
                modelHint = "[System Notice] The target app did not reach the foreground. I am relaunching it; re-check the screen afterwards.")
        }

        return Recovery(RecoveryStrategy.NONE, "OK")
    }

    private fun detectCycle(): String? {
        if (recentRounds.size < 4) return null
        val fps = recentRounds.map { "${it.toolName}:${it.argsFingerprint}" }
        // A → B → A → B
        if (fps.size >= 4 && fps[fps.size - 4] == fps[fps.size - 2] && fps[fps.size - 3] == fps[fps.size - 1] && fps[fps.size - 4] != fps[fps.size - 3]) {
            return "${fps[fps.size - 4]} ⇄ ${fps[fps.size - 3]}"
        }
        return null
    }

    private fun chooseRecovery(
        verification: ActionVerifier.VerificationResult,
        expectedForeground: String?,
        overlayPresent: Boolean
    ): Recovery {
        // Overlay present → back first.
        if (overlayPresent) {
            return Recovery(RecoveryStrategy.PRESS_BACK,
                "Ineffective actions while an overlay is present — pressing Back to clear it.",
                modelHint = "[System Notice] Repeated ineffective actions with an overlay present. I pressed Back; re-check the screen.")
        }
        // If we know the expected foreground and we're not on it → relaunch.
        if (expectedForeground != null && verification.foregroundPackage != expectedForeground) {
            return Recovery(RecoveryStrategy.RELAUNCH_TARGET,
                "Repeated ineffective actions and target $expectedForeground is not foreground — relaunching.",
                modelHint = "[System Notice] Repeated ineffective actions; the target app is not foreground. I am relaunching it; re-check the screen.")
        }
        // Otherwise, ask the model for a new plan.
        return Recovery(RecoveryStrategy.ASK_MODEL_FOR_PLAN,
            "Repeated ineffective actions — asking the model for a new plan.",
            modelHint = "[System Warning] You have taken $consecutiveIneffective ineffective actions in a row with no screen change. " +
                "Stop repeating the same approach. Call get_screen_info to refresh the UI, try a completely different element or tool, " +
                "press system_key(key=\"back\") or system_key(key=\"home\"), or call finish if the task cannot be completed.")
    }

    /**
     * Execute the chosen recovery strategy against the device. Returns a short string the
     * loop can attach to the tool result / model context. Workflow context (the task and the
     * target app) is preserved across recovery.
     */
    fun executeRecovery(strategy: RecoveryStrategy, service: ClawAccessibilityService, targetPackage: String?): String {
        return try {
            when (strategy) {
                RecoveryStrategy.NONE -> ""
                RecoveryStrategy.RE_QUERY -> {
                    val screen = service.getScreenTree()
                    "Re-queried the UI (${screen?.length ?: 0} chars)."
                }
                RecoveryStrategy.PRESS_BACK -> {
                    service.pressBack()
                    "Pressed Back to recover."
                }
                RecoveryStrategy.GO_HOME -> {
                    service.pressHome()
                    "Pressed Home to recover."
                }
                RecoveryStrategy.RELAUNCH_TARGET -> {
                    if (targetPackage != null) {
                        val r = service.openAppForeground(targetPackage, 8000L)
                        if (r.success) "Relaunched target app $targetPackage (verified foreground)."
                        else "Relaunch of $targetPackage failed: ${r.error} — foreground is ${r.foregroundPackage}."
                    } else {
                        service.pressHome()
                        "Pressed Home (no target package known for relaunch)."
                    }
                }
                RecoveryStrategy.ASK_MODEL_FOR_PLAN -> {
                    // No device action; the modelHint is injected by the caller.
                    "Asked model for a new plan."
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Recovery execution failed: $strategy", e)
            "Recovery ($strategy) failed: ${e.message}"
        }
    }

    fun reset() {
        recentRounds.clear()
        consecutiveIneffective = 0
        stuckDetector.reset()
    }

    companion object {
        private const val TAG = "InteractionWatchdog"
    }
}
