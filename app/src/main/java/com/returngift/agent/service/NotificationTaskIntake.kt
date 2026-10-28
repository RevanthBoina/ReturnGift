// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.service

import android.content.Context
import com.returngift.agent.ReturnGiftApplication
import com.returngift.agent.TaskEvent
import com.returngift.agent.utils.XLog
import java.util.UUID

/**
 * Task intake for anything that is not the chat screen.
 *
 * The notification reply, and an answer that arrives after the process was killed, both
 * land here — the same [com.returngift.agent.AppViewModel.startTask] the chat uses, so a
 * task started from the shade is routed, planned and budgeted exactly like one typed in
 * the app. Progress and the result go back to the notification the owner is holding.
 */
object NotificationTaskIntake {

    private const val TAG = "NotificationTaskIntake"

    /**
     * @param answerContext the owner's answer to a question the parked task asked; when
     *        present it is folded into the task text so the rerun does not ask again.
     */
    fun submit(context: Context, task: String, answerContext: String? = null): Boolean {
        val trimmed = task.trim()
        if (trimmed.isEmpty()) return false
        val vm = runCatching { ReturnGiftApplication.appViewModelInstance }.getOrNull()
        if (vm == null) {
            XLog.w(TAG, "app is not initialised yet; dropping the task")
            return false
        }
        if (vm.isTaskRunning()) {
            ForegroundService.notifyTaskFinished(
                context,
                success = false,
                taskTitle = "Busy",
                body = "A task is already running. Stop it first.",
            )
            return false
        }
        val prompt = if (answerContext.isNullOrBlank()) trimmed else {
            "$trimmed\n\nThe owner answered your question: $answerContext"
        }
        ForegroundService.updateTaskStatus(context, "Starting…")
        runCatching {
            vm.startTask(prompt, UUID.randomUUID().toString()) { event ->
                when (event) {
                    is TaskEvent.Progress -> ForegroundService.updateTaskStatus(context, event.description)
                    is TaskEvent.ToolAction -> ForegroundService.updateTaskStatus(context, "$task · ${event.toolName}")
                    is TaskEvent.Completed -> ForegroundService.notifyTaskFinished(context, true, task, event.answer)
                    is TaskEvent.Failed -> ForegroundService.notifyTaskFinished(context, false, task, event.error)
                    TaskEvent.Cancelled -> ForegroundService.notifyTaskFinished(context, false, task, "Stopped.")
                    TaskEvent.Blocked ->
                        ForegroundService.notifyTaskFinished(context, false, task, "Blocked by a system dialog.")
                    else -> Unit
                }
                if (event is TaskEvent.Completed || event is TaskEvent.Failed ||
                    event is TaskEvent.Cancelled || event is TaskEvent.Blocked
                ) {
                    // The run is over: drop the callback so a chat screen that comes back
                    // to the front owns the next task's events again.
                    runCatching { vm.clearTaskCallback() }
                }
            }
        }.onFailure {
            XLog.w(TAG, "could not start the task from the notification", it)
            ForegroundService.notifyTaskFinished(context, false, task, it.message ?: "Could not start.")
            return false
        }
        XLog.i(TAG, "task submitted from a notification: ${trimmed.take(60)}")
        return true
    }
}
