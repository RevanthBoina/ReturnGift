// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

import android.os.Handler
import android.os.Looper
import com.returngift.agent.agent.skill.SkillRegistry
import com.returngift.agent.utils.XLog
import com.returngift.agent.widget.ConfirmDialog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs before every tool execution to enforce YAML safety blocks:
 *  1. Blocklist pattern check — rejects tool params matching blocklist_patterns regex
 *  2. Risk-tier gate — tier ≥ 2 shows ConfirmDialog.showWarm and suspends until user responds
 *  3. never_retry_after checkpoint — prevents re-executing a tool after a terminal step
 *
 * Usage: call SafetyInterceptor.check() before ToolRegistry.executeTool().
 * Returns null if execution is allowed, or an error string to surface to the LLM.
 */
object SafetyInterceptor {

    private const val TAG = "SafetyInterceptor"
    private const val CONFIRM_TIMEOUT_SEC = 30L

    /** Per-session set of tool names that have been executed and are now terminal. */
    private val executedCheckpoints = mutableSetOf<String>()
    /** The skill id currently active (set by TaskOrchestrator before skill execution). */
    @Volatile var activeSkillId: String? = null

    fun resetSession() {
        executedCheckpoints.clear()
        activeSkillId = null
    }

    /**
     * @param toolName  the tool about to be executed
     * @param params    the tool parameters (used for blocklist matching)
     * @param context   Android context for showing dialogs (must be non-null for tier ≥ 2)
     * @return null if allowed, error message string if blocked
     */
    fun check(
        toolName: String,
        params: Map<String, Any>,
        context: android.content.Context?,
    ): String? {
        val skillId = activeSkillId ?: return null
        val yaml = SkillRegistry.getYamlMeta(skillId) ?: return null
        val safety = yaml.safety

        // 1. never_retry_after checkpoint
        if (toolName in executedCheckpoints) {
            val msg = "Safety: '$toolName' is a terminal step for skill '$skillId' and cannot be retried."
            XLog.w(TAG, msg)
            return msg
        }

        // 2. Blocklist pattern check
        val allParamText = params.values.joinToString(" ") { it.toString() }
        for (pattern in safety.blocklistPatterns) {
            try {
                if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(allParamText)) {
                    val msg = "Safety: blocked by pattern '$pattern' in skill '$skillId'."
                    XLog.w(TAG, msg)
                    return msg
                }
            } catch (e: Exception) {
                XLog.w(TAG, "Invalid blocklist pattern '$pattern': ${e.message}")
            }
        }

        // 3. Risk-tier confirmation gate (tier ≥ 2)
        if (safety.requiresConfirmation && safety.riskTier >= 2) {
            if (context == null) {
                // Fail closed: no Activity context means we cannot show a confirmation,
                // so deny rather than silently allow a tier-2+ action.
                XLog.w(TAG, "Safety: '$toolName' requires confirmation (tier ${safety.riskTier}) but no Activity context available — denying.")
                return "Safety: cannot confirm '$toolName' — no foreground Activity available."
            }
            val allowed = showConfirmationDialog(context, skillId, toolName, params, safety.confirmationMode)
            if (!allowed) {
                return "Safety: user declined confirmation for '$toolName' in skill '$skillId'."
            }
        }

        // Record terminal checkpoints after approval
        if (toolName in safety.neverRetryAfter) {
            executedCheckpoints.add(toolName)
        }

        return null
    }

    private fun showConfirmationDialog(
        context: android.content.Context,
        skillId: String,
        toolName: String,
        params: Map<String, Any>,
        mode: String,
    ): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            XLog.w(TAG, "showConfirmationDialog called on main thread — skipping (allow)")
            return true
        }

        val latch = CountDownLatch(1)
        var allowed = false

        val title = "Confirm action"
        val message = buildConfirmMessage(skillId, toolName, params, mode)

        Handler(Looper.getMainLooper()).post {
            try {
                ConfirmDialog.showWarm(
                    context = context,
                    title = title,
                    message = message,
                    actionTitle = "Allow",
                    cancelTitle = "Cancel",
                    isDismissible = false,
                    onAction = { _ -> allowed = true; latch.countDown() },
                    onCancel = { allowed = false; latch.countDown() },
                )
            } catch (e: Exception) {
                XLog.e(TAG, "Failed to show confirmation dialog", e)
                latch.countDown()
            }
        }

        val completed = latch.await(CONFIRM_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (!completed) {
            XLog.w(TAG, "Confirmation dialog timed out after ${CONFIRM_TIMEOUT_SEC}s — denying")
        }
        XLog.i(TAG, "Confirmation for '$toolName': allowed=$allowed")
        return allowed
    }

    private fun buildConfirmMessage(
        skillId: String,
        toolName: String,
        params: Map<String, Any>,
        mode: String,
    ): String = buildString {
        append("Skill: $skillId\n")
        append("Action: $toolName\n")
        if (params.isNotEmpty()) {
            append("Details:\n")
            params.forEach { (k, v) -> append("  $k: $v\n") }
        }
    }

    // Expose riskTier from yaml meta for callers that need it
    private val com.returngift.agent.agent.skill.Safety.riskTier: Int
        get() = SkillRegistry.getYamlMeta(activeSkillId ?: "")?.taxonomy?.riskTier ?: 0
}
