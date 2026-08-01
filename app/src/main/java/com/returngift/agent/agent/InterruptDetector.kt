// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.utils.AppLogStore
import com.returngift.agent.utils.XLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mid-task popup / notification interrupt detector.
 *
 * Called immediately before any screen_state snapshot is passed to the model,
 * and before each tool call that modifies the screen.
 *
 * Classification:
 *   AUTO_DISMISSABLE  — dismiss silently, re-capture screen, continue task.
 *   PAUSE_AND_CONFIRM — halt task, surface user notice, wait for resume tap.
 *
 * All events are logged to AppLogStore for later debug-report inclusion.
 *
 * Usage:
 *   val result = InterruptDetector.inspect(service)
 *   when (result) {
 *     is InterruptResult.Clean             -> proceed normally
 *     is InterruptResult.AutoDismissed     -> re-capture screen, proceed
 *     is InterruptResult.PauseAndConfirm   -> halt task, surface callback
 *   }
 */
object InterruptDetector {

    private const val TAG = "InterruptDetector"

    sealed class InterruptResult {
        /** No interrupt detected — screen is the expected target. */
        object Clean : InterruptResult()

        /**
         * A known-benign overlay was detected and dismissed.
         * The caller should re-capture screen_state before continuing.
         */
        data class AutoDismissed(
            val source: String,
            val description: String
        ) : InterruptResult()

        /**
         * A potentially significant interrupt was detected.
         * The caller must pause the task and ask the user to confirm before resuming.
         */
        data class PauseAndConfirm(
            val source: String,
            val description: String
        ) : InterruptResult()
    }

    /**
     * Inspect the current accessibility window stack for interrupt signatures.
     *
     * @param service  live ClawAccessibilityService instance
     * @return         classification result
     */
    fun inspect(service: ClawAccessibilityService): InterruptResult {
        // ── 1. Check accessibility windows ──────────────────────────────────
        val windows = try {
            service.windows ?: emptyList()
        } catch (e: Exception) {
            XLog.w(TAG, "Could not retrieve window list", e)
            emptyList()
        }

        for (window in windows) {
            val pkg = window.root?.packageName?.toString() ?: continue
            val windowType = window.type

            // Input method windows (keyboards) — auto-dismiss category
            if (windowType == InterruptConfig.WINDOW_TYPE_INPUT_METHOD) {
                // Keyboard suggestion bar is normal and benign — not an interrupt
                continue
            }

            // System windows from known-benign packages
            if (windowType == InterruptConfig.WINDOW_TYPE_SYSTEM &&
                pkg in InterruptConfig.AUTO_DISMISSABLE_PACKAGES
            ) {
                val desc = "System overlay from $pkg (window type $windowType)"
                logEvent("AUTO_DISMISSABLE", pkg, desc, "dismiss_and_continue")
                return InterruptResult.AutoDismissed(pkg, desc)
            }

            // System windows from known-pause packages
            if (pkg in InterruptConfig.PAUSE_AND_CONFIRM_PACKAGES) {
                val desc = "Blocking window from $pkg (window type $windowType)"
                logEvent("PAUSE_AND_CONFIRM", pkg, desc, "halt_task")
                return InterruptResult.PauseAndConfirm(pkg, desc)
            }
        }

        // ── 2. Inspect node text in the active window ────────────────────────
        val screenTree = try {
            service.getScreenTree() ?: ""
        } catch (e: Exception) {
            XLog.w(TAG, "Could not get screen tree for interrupt check", e)
            ""
        }

        if (screenTree.isNotEmpty()) {
            // Check for pause-worthy text patterns
            for (pattern in InterruptConfig.PAUSE_TEXT_PATTERNS) {
                val match = pattern.find(screenTree)
                if (match != null) {
                    val desc = "Permission/call dialog detected: \"${match.value.take(60)}\""
                    val activePackage = windows.firstOrNull { it.isFocused }
                        ?.root?.packageName?.toString() ?: "unknown"
                    logEvent("PAUSE_AND_CONFIRM", activePackage, desc, "halt_task")
                    return InterruptResult.PauseAndConfirm(activePackage, desc)
                }
            }

            // Check for auto-dismissable toast text
            for (pattern in InterruptConfig.AUTO_DISMISS_TEXT_PATTERNS) {
                val match = pattern.find(screenTree)
                if (match != null) {
                    val desc = "Transient toast: \"${match.value.take(60)}\""
                    logEvent("AUTO_DISMISSABLE", "toast", desc, "ignore_and_continue")
                    return InterruptResult.AutoDismissed("toast", desc)
                }
            }
        }

        return InterruptResult.Clean
    }

    /**
     * Log an interrupt event to AppLogStore (surfaces in debug-report.zip).
     */
    private fun logEvent(type: String, source: String, description: String, action: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = "INTERRUPT [$ts] type=$type source=$source action=$action desc=$description"
        XLog.i(TAG, entry)
        AppLogStore.log("I", TAG, entry, null)
    }
}
