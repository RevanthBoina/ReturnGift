// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.returngift.agent.ReturnGiftApplication
import com.returngift.agent.agent.UndoManager
import com.returngift.agent.utils.XLog

/**
 * Everything the shade can do to the agent, completed here rather than in an activity.
 *
 * Android 12+ refuses activity launches from notification actions, so "type a task",
 * "Stop" and "Undo" all arrive as explicit broadcasts to this non-exported receiver.
 */
class CommandReplyReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CommandReplyReceiver"
        const val ACTION_TASK = "com.returngift.agent.action.NOTIFICATION_TASK"
        const val ACTION_STOP = "com.returngift.agent.action.NOTIFICATION_STOP"
        const val ACTION_UNDO = "com.returngift.agent.action.NOTIFICATION_UNDO"
        const val KEY_TEXT_REPLY = "command_text_reply"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TASK -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim()
                if (text.isNullOrEmpty()) {
                    XLog.w(TAG, "empty task from the notification, dropping")
                    return
                }
                NotificationTaskIntake.submit(context, text)
            }
            ACTION_STOP -> {
                XLog.i(TAG, "Stop tapped in the notification")
                runCatching {
                    ReturnGiftApplication.appViewModelInstance.taskOrchestrator.cancelCurrentTask()
                }.onFailure { XLog.w(TAG, "nothing to stop", it) }
            }
            ACTION_UNDO -> {
                XLog.i(TAG, "Undo tapped in the notification")
                val undone = runCatching { UndoManager.executeUndo() }.getOrDefault(false)
                if (undone) ForegroundService.cancelResult(context)
            }
            else -> XLog.w(TAG, "unknown action ${intent.action}")
        }
    }
}
