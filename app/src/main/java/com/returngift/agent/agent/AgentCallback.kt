// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

import com.returngift.agent.tool.ToolResult

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
}
