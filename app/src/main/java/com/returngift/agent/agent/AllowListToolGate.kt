// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

import android.content.Context
import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.utils.XLog

/**
 * Part B — Tool-call gate for the per-app allow-list.
 *
 * Called inside DefaultAgentService.runAgentLoop() before each tool execution.
 * Resolves the foreground app package from the accessibility service and checks
 * the allow-list. Returns a non-null block message if the call should be halted.
 *
 * The decision itself lives in [com.returngift.agent.agent.permission.AppAccessGate]
 * (shared with the Tier-1 path). For an app that is set to "ask" — the default for
 * a first encounter — the gate opens ReturnGift's permission card in the
 * foreground, parks the agent thread and returns the user's decision:
 *
 *  - Allow once   → this task only (in-memory grant, not persisted)
 *  - Always allow → persisted in the allow-list (unless remembering is switched off)
 *  - Cancel/timeout → the tool call is blocked with an honest message
 *  - Blocked in Settings → blocked without a prompt
 *
 * Tools that always pass (never target a specific third-party app):
 *   finish, get_screen_info, get_device_info, get_notifications, get_installed_apps,
 *   take_screenshot, wait, clipboard, system_key, get_foreground_app, find_node_info
 */
object AllowListToolGate {

    private const val TAG = "AllowListToolGate"

    /** Tools that are own-app-only and never act inside a third-party app. */
    private val PASSTHROUGH_TOOLS = setOf(
        "finish", "get_screen_info", "get_device_info", "get_notifications",
        "get_installed_apps", "take_screenshot", "wait", "clipboard",
        "system_key", "get_foreground_app", "find_node_info",
        "kb_write", "kb_read", "kb_search", "kb_append", "kb_add_todo",
        "send_file", "web_search", "web_fetch",
    )

    /** "Allow once" grants for the current task run (process memory only). */
    fun resetTaskGrants() = com.returngift.agent.agent.permission.AppAccessGate.resetTaskGrants()

    /** Packages granted for this task by "Allow once". */
    fun taskGrants(): Set<String> = com.returngift.agent.agent.permission.AppAccessGate.taskGrants()

    /**
     * Returns null if the tool call is allowed, or a user-facing block message if not.
     *
     * @param context  app context
     * @param toolName name of the tool about to execute
     * @param params   tool parameters (used to extract explicit package for open_app)
     * @return         null = proceed; non-null string = block with this message
     */
    fun check(
        context: Context,
        toolName: String,
        params: Map<String, Any>
    ): String? {
        if (toolName in PASSTHROUGH_TOOLS) return null

        // Resolve package: explicit for open_app / switch_app, foreground window otherwise
        val targetPackage = resolveTargetPackage(toolName, params)
            ?: return null  // can't determine — allow through (don't block on ambiguity)

        // Skip ReturnGift's own package
        if (targetPackage == context.packageName) return null

        val result = com.returngift.agent.agent.permission.AppAccessGate.evaluate(context, targetPackage)
        if (!result.allowed) {
            XLog.w(TAG, "Tool $toolName blocked: ${result.blockMessage}")
            return result.blockMessage
        }
        if (!result.automatic) {
            XLog.i(TAG, "permission granted (${result.decision}) for $targetPackage")
        }
        return null
    }

    private fun resolveTargetPackage(toolName: String, params: Map<String, Any>): String? {
        // open_app / switch_app carry the package name explicitly
        if (toolName == "open_app" || toolName == "switch_app") {
            return params["package_name"]?.toString()?.takeIf { it.isNotBlank() }
        }
        // For all UI-interaction tools resolve from the current foreground window
        return try {
            ClawAccessibilityService.getInstance()
                ?.windows
                ?.firstOrNull { it.isFocused }
                ?.root
                ?.packageName
                ?.toString()
        } catch (_: Exception) {
            null
        }
    }
}
