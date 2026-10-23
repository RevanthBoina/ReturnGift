// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

import android.content.Context
import com.returngift.agent.agent.permission.PermissionPromptBroker
import com.returngift.agent.agent.permission.PermissionRequest
import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.utils.XLog
import java.util.Collections

/**
 * Part B — Tool-call gate for the per-app allow-list.
 *
 * Called inside DefaultAgentService.runAgentLoop() before each tool execution.
 * Resolves the foreground app package from the accessibility service and checks
 * the allow-list. Returns a non-null block message if the call should be halted.
 *
 * First encounter (and any app the user turned OFF) now RAISES A REAL PROMPT:
 * [PermissionPromptBroker] opens ReturnGift's permission card in the foreground
 * (the app's own interface — never a silent default), parks the agent thread and
 * returns the user's decision:
 *
 *  - Allow once   → this task only (in-memory grant, not persisted)
 *  - Always allow → persisted in the allow-list
 *  - Cancel/timeout → the tool call is blocked with an honest message
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
    private val onceGrants: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())

    /** Called at the start of every task run so "once" never leaks across tasks. */
    fun resetTaskGrants() {
        onceGrants.clear()
    }

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

        val store = AppAllowListStore.getInstance(context)
        val firstEncounter = store.isFirstEncounter(targetPackage)
        val allowedInStore = !firstEncounter && store.isAllowed(targetPackage)
        val grantedOnce = targetPackage in onceGrants
        if (allowedInStore || grantedOnce) return null

        // ── Ask the user — opens the APK's permission card in the foreground ──
        val label = AppAllowListGuard.resolveLabel(context, targetPackage)
        val reason = if (firstEncounter) {
            "ReturnGift is about to act inside \"$label\" for the first time. " +
                "Grant access to let it continue this task."
        } else {
            "\"$label\" is turned off in App Permissions. Grant access to let the agent act in it."
        }
        val request = PermissionRequest.forApp(targetPackage, label, reason)
        XLog.i(TAG, "permission prompt raised for $targetPackage ($label), firstEncounter=$firstEncounter")
        val decision = PermissionPromptBroker.request(request)

        return when (decision) {
            PermissionRequest.Decision.ALLOW_ONCE -> {
                // Persist the row as NOT-allowed so the next task asks again;
                // this run is covered by the in-memory grant.
                if (firstEncounter) store.touchApp(targetPackage, label, allowed = false)
                onceGrants.add(targetPackage)
                XLog.i(TAG, "allow-once granted for $targetPackage (this task only)")
                null
            }
            PermissionRequest.Decision.ALWAYS_ALLOW -> {
                store.touchApp(targetPackage, label, allowed = true)
                store.setAllowed(targetPackage, allowed = true, label = label)
                onceGrants.add(targetPackage)
                XLog.i(TAG, "always-allow granted for $targetPackage")
                null
            }
            else -> {
                XLog.w(TAG, "Tool $toolName blocked: permission for $targetPackage was not granted")
                "Action blocked: permission to act in \"$label\" was not granted. " +
                    "Tap Allow once or Always allow on the ReturnGift permission card " +
                    "(or enable the app in Settings → App Permissions), then try again."
            }
        }
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
