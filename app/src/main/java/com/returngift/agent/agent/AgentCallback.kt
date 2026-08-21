// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

import com.returngift.agent.tool.ToolResult

/**
 * Typed terminal state of a task, delivered via [AgentCallback.onTerminalOutcome]
 * once at the terminal seam — listeners switch on this instead of string-matching
 * localized completion/cancellation messages.
 */
enum class TerminalOutcome { COMPLETED, CANCELLED, ERROR, SYSTEM_DIALOG_BLOCKED }

interface AgentCallback {
    /**
     * Callback when a new Agent Loop round starts
     * @param round current round number (starts from 1)
     */
    fun onLoopStart(round: Int)
    fun onContent(round: Int, content: String)
    fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String)
    fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult)
    fun onTokenUpdate(status: TokenMonitor.Status) {}
    fun onComplete(round: Int, finalAnswer: String, totalTokens: Int, modelName: String? = null)
    fun onError(round: Int, error: Exception, totalTokens: Int)
    fun onSystemDialogBlocked(round: Int, totalTokens: Int)
    /**
     * Part C: an undoable tool just executed successfully.
     * The UI should show a snackbar/toast with an Undo button for [UndoManager.UNDO_WINDOW_MS] ms.
     * Calling [UndoManager.executeUndo] from a background thread executes the reverse action.
     */
    fun onUndoAvailable(toolDisplayName: String) {}
    /**
     * Fired when an open_app/switch_app action is VERIFIED to have brought the target
     * package to the foreground (never fired for the assistant's own package). The UI
     * uses this to defer minimizing the chat until a target app is truly on screen.
     */
    fun onTargetForegroundVerified(packageName: String) {}
    /**
     * Fired once at the terminal seam, immediately before the deferred
     * onComplete/onError/onSystemDialogBlocked invocation, so listeners can react to a
     * typed outcome (e.g. write a checkpoint on CANCELLED, not on COMPLETED).
     */
    fun onTerminalOutcome(outcome: TerminalOutcome) {}
}
