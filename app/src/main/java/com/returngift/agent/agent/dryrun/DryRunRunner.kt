// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.dryrun

import com.returngift.agent.tool.ToolRegistry
import com.returngift.agent.tool.ToolResult
import com.returngift.agent.utils.XLog

/**
 * Preview / Dry-Run mode: the controller installs a stub into
 * [ToolRegistry.stubHook] before dispatching the task, so the full agent loop
 * runs its plan without touching the device. Steps are collected by the
 * TaskFlowController via normal TaskEvents; the stub is ALWAYS removed at
 * terminal cleanup before the "Execute now" plan card runs for real.
 */
object DryRunRunner {

    private const val TAG = "DryRunRunner"
    private const val KV_PREVIEW_ENABLED = "preview_mode_enabled"

    /** Whether Preview mode is currently on (persisted in KV). */
    fun isEnabled(): Boolean = try {
        com.returngift.agent.utils.KVUtils.getBoolean(KV_PREVIEW_ENABLED, false)
    } catch (_: Exception) {
        false
    }

    fun setEnabled(enabled: Boolean) {
        try {
            com.returngift.agent.utils.KVUtils.putBoolean(KV_PREVIEW_ENABLED, enabled)
        } catch (e: Exception) {
            XLog.w(TAG, "persist preview flag failed", e)
        }
        if (!enabled) removeStub()
        XLog.i(TAG, "preview mode -> $enabled")
    }

    data class PlanStep(
        val sequence: Int,
        val toolId: String,
        val displayName: String,
        val params: String,
    )

    /** Install the stub hook; called only when the preview flag is enabled. */
    fun installStub() {
        ToolRegistry.stubHook = { name, params ->
            ToolResult.success("PREVIEW (dry-run) — $name was not executed.")
        }
        XLog.i(TAG, "stub installed: no real execution")
    }

    fun removeStub() {
        ToolRegistry.stubHook = null
        XLog.i(TAG, "stub removed: real execution restored")
    }
}
