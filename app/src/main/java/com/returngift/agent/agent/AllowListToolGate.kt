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
 * Tools that always pass (never target a specific third-party app):
 *   finish, get_screen_info, get_device_info, get_notifications, get_installed_apps,
 *   take_screenshot, wait, clipboard, system_key, kb_*, find_node_info
 */
object AllowListToolGate {

    private const val TAG = "AllowListToolGate"

    // Tools that are own-app-only and never act inside a third-party app
    private val PASSTHROUGH_TOOLS = setOf(
        "finish", "get_screen_info", "get_device_info", "get_notifications",
        "get_installed_apps", "take_screenshot", "wait", "clipboard",
        "system_key", "find_node_info", "kb_write", "kb_read", "kb_search",
        "kb_append", "kb_add_todo", "send_file"
    )

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

        // Resolve package: explicit for open_app / send_message, foreground window otherwise
        val targetPackage = resolveTargetPackage(toolName, params)
            ?: return null  // can't determine — allow through (don't block on ambiguity)

        // Skip ReturnGift's own package
        if (targetPackage == context.packageName) return null

        val store = AppAllowListStore.getInstance(context)

        return if (store.isFirstEncounter(targetPackage)) {
            // Insert with default ON — the UI layer will surface the first-time prompt
            val label = AppAllowListGuard.resolveLabel(context, targetPackage)
            store.touchApp(targetPackage, label, allowed = true)
            XLog.i(TAG, "First encounter recorded: $targetPackage — allow-list prompt should surface in UI")
            // Return special sentinel so caller knows to show the prompt (but still allows this call)
            null  // default ON means this specific execution proceeds; prompt shown by UI layer
        } else if (!store.isAllowed(targetPackage)) {
            val label = AppAllowListGuard.resolveLabel(context, targetPackage)
            XLog.w(TAG, "Tool $toolName blocked by allow-list for $targetPackage")
            "Action blocked: the agent is not allowed to act in \"$label\". " +
            "Enable it in Settings → App Permissions to allow this."
        } else {
            null
        }
    }

    private fun resolveTargetPackage(toolName: String, params: Map<String, Any>): String? {
        // open_app carries the package name explicitly
        if (toolName == "open_app") {
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
