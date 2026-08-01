// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent

import android.os.PowerManager
import androidx.lifecycle.ViewModel
import com.returngift.agent.ClawApplication.Companion.appViewModelInstance
import com.returngift.agent.TaskEvent
import com.returngift.agent.agent.AgentConfig
import com.returngift.agent.agent.llm.ModelConfigRepository
import com.returngift.agent.channel.Channel
import com.returngift.agent.channel.ChannelManager
import com.returngift.agent.channel.ChannelSetup
import com.returngift.agent.service.ForegroundService
import com.returngift.agent.floating.FloatingCircleManager
import com.returngift.agent.server.ConfigServerManager
import com.returngift.agent.service.KeepAliveJobService
import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog

class AppViewModel : ViewModel() {

    companion object {
        private const val TAG = "AppViewModel"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private var _commonInitialized = false

    val taskOrchestrator = TaskOrchestrator(
        agentConfigProvider = { getAgentConfig() },
        onTaskFinished = { /* refresh */ }
    )

    private val channelSetup = ChannelSetup(taskOrchestrator = taskOrchestrator)

    val taskSessionStore: TaskSessionStore
        get() = taskOrchestrator.taskSessionStore
    val inProgressTaskMessageId: String get() = taskSessionStore.snapshot().messageId
    val inProgressTaskChannel: Channel? get() = taskSessionStore.snapshot().channel

    // ==================== Task API (clean interface for Activity) ====================

    /**
     * Called before a task starts — allows the chat UI to release its local LLM conversation
     * so the task agent can use the same LiteRT-LM engine (only 1 session supported).
     */
    var onBeforeTask: (() -> Unit)? = null

    fun startTask(
        task: String,
        taskId: String,
        agentPromptOverride: String? = null,
        onEvent: (TaskEvent) -> Unit,
    ) {
        onBeforeTask?.invoke()
        taskOrchestrator.taskEventCallback = onEvent
        if (!updateAgentConfig()) {
            onEvent(TaskEvent.Failed("AI service not ready"))
            return
        }
        taskOrchestrator.startNewTask(Channel.LOCAL, task, taskId, agentPromptOverride = agentPromptOverride)
    }

    fun stopTask() {
        taskOrchestrator.cancelCurrentTask()
    }

    fun isTaskRunning(): Boolean = taskSessionStore.isTaskRunning()

    fun clearTaskCallback() {
        taskOrchestrator.taskEventCallback = null
    }

    fun init() {
        initCommon()
        initAgent()
    }

    fun initCommon() {
        if (_commonInitialized) return
        _commonInitialized = true
    }

    fun initAgent() {
        if (!KVUtils.hasLlmConfig()) return
        taskOrchestrator.initAgent()
    }

    fun getAgentConfig(): AgentConfig =
        ModelConfigRepository.snapshot().toAgentConfig(
            temperature = 0.1,
            maxIterations = 60
        )

    fun updateAgentConfig(): Boolean = taskOrchestrator.updateAgentConfig()

    fun afterInit() {
        acquireScreenWakeLock()
        KeepAliveJobService.cancel(ClawApplication.instance)
        ForegroundService.syncToBackgroundState(ClawApplication.instance)
        ConfigServerManager.autoStartIfNeeded(ClawApplication.instance)
        channelSetup.setup()
    }


    /**
     * Acquire a wake lock to prevent the screen from turning off during accessibility operations
     */
    private fun acquireScreenWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = ClawApplication.instance.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
            ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "ReturnGift::ScreenWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minute timeout to prevent battery drain
        }
        XLog.i(TAG, "Wake lock acquired")
    }

    /**
     * Release the wake lock
     */
    private fun releaseScreenWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                XLog.i(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    /**
     * Show the circular floating window
     */
    fun showFloatingCircle() {
        try {
            FloatingCircleManager.show(ClawApplication.instance)
            FloatingCircleManager.onFloatClick = {
                XLog.d(TAG, "Floating circle clicked")
                bringAppToForeground()
            }
            FloatingCircleManager.onStopTask = {
                XLog.i(TAG, "Stop task requested from floating pill")
                stopTask()
                bringAppToForeground()
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to show floating circle: ${e.message}")
        }
    }

    /**
     * Bring the app to the foreground
     */
    private fun bringAppToForeground() {
        val context = ClawApplication.instance
        val intent = android.content.Intent(context, com.returngift.agent.ui.chat.ComposeChatActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(intent)
    }

    // Old pass-through methods removed — use startTask/stopTask/isTaskRunning/clearTaskCallback instead

    private fun trySendScreenshot(channel: Channel, filePath: String, messageID: String) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                XLog.w(TAG, "Screenshot file does not exist: $filePath")
                return
            }
            val imageBytes = file.readBytes()
            ChannelManager.sendImage(channel, imageBytes, messageID)
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to send screenshot", e)
        }
    }
}
