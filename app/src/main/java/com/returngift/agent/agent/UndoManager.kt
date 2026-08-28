// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

import com.returngift.agent.core.accessibility.ScreenTreeTokenOptimizer
import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.tool.ToolRegistry
import com.returngift.agent.tool.ToolResult
import com.returngift.agent.utils.XLog
import java.util.concurrent.atomic.AtomicReference

/**
 * Part C — One-tap Undo.
 *
 * After any UNDOABLE tool executes successfully, the agent loop stores a
 * reverse-action here. The UI layer shows a snackbar/toast with an Undo button
 * for 5–8 seconds. If the user taps it, UndoManager.executeUndo() runs the
 * reverse tool call directly — NO model call, instant.
 *
 * Classification rule (undoable: true/false):
 *   ✅ undoable  — tap, tap_node, long_press, swipe, input_text, open_app,
 *                  find_and_tap, scroll_to_find, system_key (non-destructive keys),
 *                  clipboard(set)
 *   ❌ NOT undoable — send_message, auto_reply, make_call, send_file,
 *                     finish, get_*, take_screenshot, wait, repeat_actions,
 *                     kb_*, clipboard(get)
 *   NOT undoable tools already have a separate confirmation gate and must
 *   NEVER show an Undo snackbar.
 *
 * Reverse actions are direct tool calls, never model/adapter calls, so undo
 * works even when the model is busy processing the next step.
 */
object UndoManager {

    private const val TAG = "UndoManager"

    /** Snackbar display window in milliseconds (5–8 s). */
    const val UNDO_WINDOW_MS = 6_000L

    // ── Tool classification ────────────────────────────────────────────────

    /**
     * Tools that are undoable = true.
     * Any tool NOT in this set is NOT undoable.
     */
    private val UNDOABLE_TOOLS: Set<String> = setOf(
        "tap",
        "tap_node",
        "long_press",
        "swipe",
        "input_text",
        "open_app",
        "switch_app",
        "scroll_to_find",
        "system_key"
        // clipboard(set) handled via param check in buildReverseAction()
    )

    /**
     * Tools with a confirmation gate — must NEVER show Undo.
     * Listed explicitly for documentation clarity.
     */
    @Suppress("unused")
    private val CONFIRMATION_GATE_TOOLS: Set<String> = setOf(
        "send_message",
        "make_call",
        "send_file"
    )

    fun isUndoable(toolName: String, params: Map<String, Any>): Boolean {
        if (toolName == "clipboard") {
            // clipboard(action="set") is undoable; clipboard(action="get") is not
            return params["action"]?.toString() == "set"
        }
        return toolName in UNDOABLE_TOOLS
    }

    // ── Pending undo state ─────────────────────────────────────────────────

    data class PendingUndo(
        val originalTool: String,
        val originalParams: Map<String, Any>,
        val reverseTool: String,
        val reverseParams: Map<String, Any>,
        val displayLabel: String,
        val createdAt: Long = System.currentTimeMillis(),
        // C4: a cheap hash of the active screen hierarchy at registration. Visible changes
        // invalidate by design; the undo window is 5–8 s (UNDO_WINDOW_MS).
        val screenHashAtRecord: Long? = null,
    ) {
        val isExpired: Boolean
            get() = System.currentTimeMillis() - createdAt > UNDO_WINDOW_MS
    }

    private val pending = AtomicReference<PendingUndo?>(null)

    /** Listener notified when a new undoable action is available or cleared. */
    interface UndoListener {
        fun onUndoAvailable(undo: PendingUndo)
        fun onUndoExpired()
        // C4: a stale-screen refusal is an honest failure, surfaced through the same path.
        fun onUndoFailed(reason: String) {}
    }

    @Volatile
    var listener: UndoListener? = null

    /** Test seams (same lazy pattern as TaskOrchestrator.sendMessageConfirm). */
    internal var screenHashProvider: (() -> Long?)? = null
    internal var toolExecutor: (String, Map<String, Any>) -> ToolResult =
        { name, params -> ToolRegistry.getInstance().executeTool(name, params) }

    private fun captureScreenHash(): Long? {
        val provider = screenHashProvider
        if (provider != null) return provider()
        return try {
            val root = ClawAccessibilityService.getInstance()?.rootInActiveWindow
            if (root == null) null else ScreenTreeTokenOptimizer.computeHierarchyHash(root)
        } catch (e: Exception) {
            XLog.w(TAG, "screen hash unavailable, staleness check skipped", e)
            null
        }
    }

    /**
     * Record a successful tool execution. If undoable, builds the reverse action
     * and notifies the UI listener to show the snackbar.
     */
    fun record(toolName: String, params: Map<String, Any>, displayLabel: String) {
        if (!isUndoable(toolName, params)) return

        val (reverseTool, reverseParams) = buildReverseAction(toolName, params) ?: return

        val undo = PendingUndo(
            originalTool   = toolName,
            originalParams = params,
            reverseTool    = reverseTool,
            reverseParams  = reverseParams,
            displayLabel   = displayLabel,
            screenHashAtRecord = captureScreenHash(),
        )
        pending.set(undo)
        XLog.i(TAG, "Undo recorded: $toolName → $reverseTool $reverseParams")
        listener?.onUndoAvailable(undo)
    }

    /**
     * Execute the pending undo action directly via ToolRegistry.
     * Must be called from a background thread (ToolRegistry.executeTool is blocking).
     * Returns true if undo executed, false if nothing was pending or it expired.
     */
    fun executeUndo(): Boolean {
        val undo = pending.getAndSet(null) ?: return false
        if (undo.isExpired) {
            XLog.i(TAG, "Undo expired, not executing")
            listener?.onUndoExpired()
            return false
        }
        // C4: strict screen-hash comparison — a visible change since registration means
        // the inverse action is no longer safe (e.g. Back would leave the wrong screen).
        val nowHash = captureScreenHash()
        if (undo.screenHashAtRecord != null && nowHash != null && nowHash != undo.screenHashAtRecord) {
            XLog.i(TAG, "Undo not executed — screen changed since registration")
            listener?.onUndoFailed("Can't undo — screen has changed")
            return false
        }
        if (undo.screenHashAtRecord == null || nowHash == null) {
            XLog.d(TAG, "screen staleness check skipped (hash unavailable on one side)")
        }
        XLog.i(TAG, "Executing undo: ${undo.reverseTool} ${undo.reverseParams}")
        val result = toolExecutor(undo.reverseTool, undo.reverseParams)
        XLog.i(TAG, "Undo result: success=${result.isSuccess} err=${result.error}")
        return result.isSuccess
    }

    /** Discard the pending undo (e.g. when snackbar times out or user taps elsewhere). */
    fun clear() {
        pending.set(null)
        listener?.onUndoExpired()
    }

    fun hasPending(): Boolean = pending.get()?.isExpired == false

    // ── Reverse action builder ─────────────────────────────────────────────

    /**
     * Maps a forward tool + params to its direct reverse tool + params.
     * Returns null if no sensible reverse exists (fall through → not undoable).
     */
    private fun buildReverseAction(
        toolName: String,
        params: Map<String, Any>
    ): Pair<String, Map<String, Any>>? = when (toolName) {

        "open_app", "switch_app" ->
            // Close the app by pressing home (takes user back to launcher)
            "system_key" to mapOf("key" to "home")

        "input_text" -> {
            // Clear the focused field by setting empty text
            val nodeId = params["node_id"]?.toString()
            if (nodeId != null) {
                "input_text" to mapOf("text" to "", "node_id" to nodeId)
            } else {
                "input_text" to mapOf("text" to "")
            }
        }

        "tap", "tap_node", "long_press" ->
            // Go back to undo a navigation tap
            "system_key" to mapOf("key" to "back")

        "swipe" ->
            // Reverse the swipe direction
            reverseSwipe(params)

        "scroll_to_find" ->
            // Scroll back in the opposite direction
            "system_key" to mapOf("key" to "back")

        "system_key" -> {
            val key = params["key"]?.toString() ?: ""
            // Only back/home/enter are undoable; others (power, volume) are not
            when (key) {
                "back", "home", "enter" -> "system_key" to mapOf("key" to "back")
                else -> null
            }
        }

        "clipboard" ->
            // clipboard(set) → clear clipboard
            "clipboard" to mapOf("action" to "set", "text" to "")

        else -> null
    }

    private fun reverseSwipe(params: Map<String, Any>): Pair<String, Map<String, Any>>? {
        val reversed = mutableMap(params)
        return when (params["direction"]?.toString()?.lowercase()) {
            "up"    -> { reversed["direction"] = "down"; "swipe" to reversed }
            "down"  -> { reversed["direction"] = "up";   "swipe" to reversed }
            "left"  -> { reversed["direction"] = "right"; "swipe" to reversed }
            "right" -> { reversed["direction"] = "left";  "swipe" to reversed }
            else -> null
        }
    }

    private fun mutableMap(params: Map<String, Any>): MutableMap<String, Any> =
        params.toMutableMap()
}
