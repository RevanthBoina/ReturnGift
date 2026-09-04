// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.ui.chat

import com.returngift.agent.AppCapabilityCoordinator
import com.returngift.agent.ServiceBindingState
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import com.returngift.agent.TaskEvent
import com.returngift.agent.agent.llm.ModelConfigRepository
import com.returngift.agent.automation.ExternalAutomationContract
import com.returngift.agent.automation.ExternalAutomationEntrypoint
import com.returngift.agent.appViewModel
import com.returngift.agent.floating.FloatingCircleManager
import com.returngift.agent.ui.settings.LlmConfigActivity
import com.returngift.agent.ui.settings.SettingsActivity
import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog
import java.util.concurrent.Executors

/**
 * ReturnGift Chat Activity — Compose shell for the chat screen.
 *
 * Chat runtime ownership lives in [ChatSessionController].
 * This activity keeps lifecycle wiring, task flows, and sidebar/history UI state.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class ComposeChatActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ComposeChatActivity"
        private const val EXTRA_TASK = "task"
        private const val EXTRA_CHAT = "chat"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val conversationStore by lazy { ConversationStore(this) }

    // Compose state — observed by ChatScreen
    private val _messages = mutableStateListOf<ChatMessage>()
    private val _modelStatus = mutableStateOf("No model loaded")
    private val _isLocalModelActive = mutableStateOf(ModelConfigRepository.isLocalActive())
    private val _needsPermission = mutableStateOf(false)
    private val _isAwaitingReply = mutableStateOf(false)
    private val _isTaskRunning = mutableStateOf(false)
    private val _inputEnabled = mutableStateOf(true)    // False when model not ready (no task running)
    private val _conversations = mutableStateListOf<ChatHistoryManager.ConversationSummary>()
    private val _isDownloading = mutableStateOf(false)
    private val _downloadProgress = mutableStateOf(0)

    // Session-level token tracking for chat mode
    private val _sessionTokens = mutableStateOf(0)
    private val _sessionCost = mutableStateOf(0.0)
    private var deferLocalChatBootstrapForAutoTask = false
    private var pendingExternalRequestId: String? = null
    private var pendingExternalReturnAction: String? = null
    private var pendingExternalReturnPackage: String? = null

    private val chatSessionController by lazy {
        ChatSessionController(
            activity = this,
            executor = executor,
            uiState = ChatSessionUiState(
                messages = _messages,
                modelStatus = _modelStatus,
                isAwaitingReply = _isAwaitingReply,
                inputEnabled = _inputEnabled,
                isDownloading = _isDownloading,
                downloadProgress = _downloadProgress,
                sessionTokens = _sessionTokens,
                sessionCost = _sessionCost,
            ),
            onPersistConversation = { saveChat() },
            onRefreshSidebarHistory = { refreshSidebarHistory() },
            isTaskRunning = { appViewModel.isTaskRunning() },
        )
    }

    private val taskFlowControllerLazy = lazy {
        TaskFlowController(
            activity = this,
            executor = executor,
            appViewModel = appViewModel,
            chatSessionController = chatSessionController,
            currentConversationId = { conversationStore.currentConversationId },
            uiState = TaskFlowUiState(
                messages = _messages,
                modelStatus = _modelStatus,
                isAwaitingReply = _isAwaitingReply,
                isTaskRunning = _isTaskRunning,
            ),
            onPersistConversation = { saveChat() },
            onTaskSettled = { deferLocalChatBootstrapForAutoTask = false },
            onTaskTerminal = { sendExternalAutomationTerminalCallback(it) },
            onContinueNewChat = { summary -> continueInNewChat(summary) },
        )
    }
    private val taskFlowController: TaskFlowController get() = taskFlowControllerLazy.value

    private val activeTaskShellController by lazy {
        ActiveTaskShellController(appViewModel = appViewModel)
    }

    // Permission polling
    private val permHandler = Handler(Looper.getMainLooper())
    private val permPoller = object : Runnable {
        override fun run() {
            _needsPermission.value =
                AppCapabilityCoordinator.accessibilityState(this@ComposeChatActivity) != ServiceBindingState.READY
            permHandler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // Hide floating circle only when no task is running
        // (task running = keep floating pill visible for step/token status)
        try {
            if (!appViewModel.isTaskRunning()) {
                FloatingCircleManager.hide()
            } else {
                XLog.d(TAG, "onCreate: task running, keeping floating circle visible")
            }
        } catch (_: Exception) {}

        // Check for updates (stable release) + dev-channel self-update if enabled in
        // Settings → Developer (fetches the rolling dev-latest prerelease via the PAT).
        com.returngift.agent.utils.UpdateChecker.checkForUpdate(this)
        if (com.returngift.agent.dev.DevConfig.isDevChannelEnabled()) {
            com.returngift.agent.utils.AppUpdateManager.checkForDevUpdate(force = false) { state ->
                if (state is com.returngift.agent.utils.AppUpdateManager.UpdateState.UpdateAvailable) {
                    val info = (state as com.returngift.agent.utils.AppUpdateManager.UpdateState.UpdateAvailable).releaseInfo
                    com.returngift.agent.utils.UpdateChecker.showUpdateForRelease(this, info)
                }
                null
            }
        }

        // Status bar color
        val themeColors = ThemeManager.getColors()
        window.statusBarColor = themeColors.toolbarBg

        // Build Compose colors from ThemeManager
        val composeColors = with(ThemeManager) { themeColors.toComposeColors() }

        setContent {
            val activeTasks by activeTaskShellController.activeTasks.collectAsState()

ChatScreen(
                messages = _messages,
                modelStatus = _modelStatus.value,
                needsPermission = _needsPermission.value,
  isAwaitingReply = _isAwaitingReply.value,
  inputEnabled = _inputEnabled.value,

                isTaskRunning = _isTaskRunning.value,
                isDownloading = _isDownloading.value,
                downloadProgress = _downloadProgress.value,
                isLocalModel = _isLocalModelActive.value,
                wsc = calculateWindowSizeClass(this),
                sessionTokens = _sessionTokens.value,
                sessionCost = _sessionCost.value,
                onSendChat = { sendChat(it) },
                onSendTask = { taskFlowController.sendTask(it) },
                onStartMonitor = { target -> taskFlowController.startMonitor(target) },
                onSendDirectMessage = { contact, app, message ->
                    taskFlowController.sendTask("send \"$message\" to $contact on $app")
                },
                onNewChat = { newChat() },
                onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onOpenModels = { startActivity(Intent(this, LlmConfigActivity::class.java)) },
                onOpenVault = {
                    startActivity(Intent(this, com.returngift.agent.ui.vault.VaultActivity::class.java))
                },
                onContinueNewChat = { summary -> continueInNewChat(summary) },
                onFixPermissions = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onAttach = { Toast.makeText(this, "Image upload coming soon", Toast.LENGTH_SHORT).show() },
                conversations = _conversations,
                onSelectConversation = { loadConversation(it) },
                onDeleteConversation = { conv ->
                    lifecycleScope.launch {
                        val deleted = conversationStore.deleteConversation(conv)
                        XLog.i(TAG, "Delete conversation: ${conv.file.absolutePath} deleted=$deleted")
                        refreshSidebarHistory()
                    }
                },
                onRenameConversation = { conv, newName ->
                    lifecycleScope.launch {
                        val renamed = conversationStore.renameConversation(conv, newName)
                        XLog.i(TAG, "Rename conversation: '${conv.title}' → '$newName' renamed=$renamed")
                        refreshSidebarHistory()
                    }
                },
                onEditMessage = { index, newContent ->
                    // Edit-and-resend: rewind both the visible list and the model's
                    // history to the edited message, then resubmit the new content so
                    // the model actually reads the edit instead of continuing with
                    // the stale original.
                    chatSessionController.editAndResend(
                        index,
                        newContent,
                        conversationStore.currentConversationId,
                    )
                },
                activeTasks = activeTasks,
                onStopTask = { contact ->
                    _isTaskRunning.value = appViewModel.isTaskRunning()
                    Toast.makeText(
                        this,
                        activeTaskShellController.stopTask(contact),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onStopAllTasks = {
                    _isAwaitingReply.value = false
                    _isTaskRunning.value = false
                    Toast.makeText(
                        this,
                        activeTaskShellController.stopAllTasks(),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onModelSwitch = { modelId, displayName -> switchModel(modelId, displayName) },
                pendingClarification = taskFlowController.pendingClarification.value,
                onClarificationAnswer = { taskFlowController.submitClarificationAnswer(it) },
                previewPlan = taskFlowController.previewPlan.value,
                onExecutePreviewPlan = { taskFlowController.executePreviewPlan() },
                onDismissPreviewPlan = { taskFlowController.dismissPreviewPlan() },
                colors = composeColors,
                onCancelQueue = { appViewModel.clearPending() },
                onStartNow = { pending -> appViewModel.startPendingNow() },
            )
        }

        refreshSidebarHistory()

        lifecycleScope.launch {
            if (_messages.isEmpty()) {
                conversationStore.restoreLastConversation()?.let { restored ->
                    syncSidebar(restored.conversations)
                    if (restored.messages.isNotEmpty()) {
                        _messages.addAll(restored.messages)
                        XLog.i(
                            TAG,
                            "Restored ${restored.messages.size} messages from conversation ${restored.conversationId}"
                        )
                    }
                }
            }
        }

        deferLocalChatBootstrapForAutoTask = shouldDeferLocalChatBootstrap(intent)
        if (!deferLocalChatBootstrapForAutoTask) {
            chatSessionController.loadModelIfReady(
                conversationId = conversationStore.currentConversationId,
                visibleMessages = _messages.toList(),
            )
        }
        _isLocalModelActive.value = ModelConfigRepository.isLocalActive()

        // Release local LLM conversation before task starts so the agent can use the engine
        // (LiteRT-LM only supports 1 session at a time)
        appViewModel.onBeforeTask = {
            chatSessionController.releaseForTask()
        }

        // Debug: auto-trigger task from ADB intent
        // Usage: adb shell am start -n com.returngift.agent/.ui.chat.ComposeChatActivity --es task "open my camera"
        handleIntentAutomation(intent, initialDelayMs = 2000)

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deferLocalChatBootstrapForAutoTask = shouldDeferLocalChatBootstrap(intent)
        handleIntentAutomation(intent, initialDelayMs = 1000)
    }

    override fun onResume() {
        super.onResume()
        _needsPermission.value =
            AppCapabilityCoordinator.accessibilityState(this) != ServiceBindingState.READY
        _isLocalModelActive.value = ModelConfigRepository.isLocalActive()
        _isTaskRunning.value = appViewModel.isTaskRunning()
        refreshSidebarHistory()
        permHandler.removeCallbacks(permPoller)
        permHandler.postDelayed(permPoller, 3000)
        activeTaskShellController.onResume()
        if (!deferLocalChatBootstrapForAutoTask) {
            chatSessionController.onResume(
                conversationId = conversationStore.currentConversationId,
                visibleMessages = _messages.toList(),
            )
        }
    }

    override fun onPause() {
        super.onPause()
        saveChat()
        permHandler.removeCallbacks(permPoller)
        activeTaskShellController.onPause()
        chatSessionController.onPause(conversationStore.currentConversationId)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (taskFlowControllerLazy.isInitialized()) taskFlowControllerLazy.value.release()
        chatSessionController.onDestroy()
        executor.shutdown()
    }

    // ==================== CHAT ====================

    private fun sendChat(text: String) {
        chatSessionController.sendChat(text)
    }

    private fun handleIntentAutomation(intent: Intent?, initialDelayMs: Long) {
        val taskText = intent?.getStringExtra(EXTRA_TASK)?.takeIf { it.isNotBlank() }
        val chatText = intent?.getStringExtra(EXTRA_CHAT)?.takeIf { it.isNotBlank() }
        val automationText = taskText ?: chatText ?: return
        val isTask = taskText != null
        captureExternalAutomationCallback(intent, isTask)

        XLog.i(
            TAG,
            if (isTask) "Auto-task from intent: $automationText" else "Auto-chat from intent: $automationText"
        )

        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isTask) {
                    taskFlowController.sendTask(automationText)
                    return
                }
                if (!KVUtils.hasLlmConfig()) {
                    _messages.add(ChatMessage(ChatMessage.Role.USER, automationText))
                    _messages.add(ChatMessage(ChatMessage.Role.SYSTEM, "Configure LLM in Settings first."))
                    saveChat()
                    return
                }
                if (chatSessionController.isModelReady()) {
                    sendChat(automationText)
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }, initialDelayMs)
    }

    private fun shouldDeferLocalChatBootstrap(intent: Intent?): Boolean {
        val taskText = intent?.getStringExtra(EXTRA_TASK)?.takeIf { it.isNotBlank() } ?: return false
        return taskText.isNotBlank() && ModelConfigRepository.isLocalActive()
    }

    private fun captureExternalAutomationCallback(intent: Intent?, isTask: Boolean) {
        pendingExternalRequestId = null
        pendingExternalReturnAction = null
        pendingExternalReturnPackage = null
        if (!isTask) return
        pendingExternalRequestId = intent?.getStringExtra(ExternalAutomationEntrypoint.EXTRA_EXTERNAL_REQUEST_ID)
        pendingExternalReturnAction = intent?.getStringExtra(ExternalAutomationEntrypoint.EXTRA_EXTERNAL_RETURN_ACTION)
        pendingExternalReturnPackage = intent?.getStringExtra(ExternalAutomationEntrypoint.EXTRA_EXTERNAL_RETURN_PACKAGE)
    }

    private fun sendExternalAutomationTerminalCallback(event: TaskEvent) {
        val returnAction = pendingExternalReturnAction ?: return
        val requestId = pendingExternalRequestId
        val returnPackage = pendingExternalReturnPackage
        val status: String
        var result: String? = null
        var error: String? = null
        when (event) {
            is TaskEvent.Completed -> {
                status = ExternalAutomationContract.STATUS_COMPLETED
                result = event.answer
            }
            is TaskEvent.Failed -> {
                status = ExternalAutomationContract.STATUS_FAILED
                error = event.error
            }
            is TaskEvent.Cancelled -> {
                status = ExternalAutomationContract.STATUS_CANCELLED
                error = "Task cancelled."
            }
            is TaskEvent.Blocked -> {
                status = ExternalAutomationContract.STATUS_BLOCKED
                error = "Task blocked by a system dialog."
            }
            else -> return
        }
        ExternalAutomationContract.sendCallback(
            context = this,
            returnAction = returnAction,
            requestId = requestId,
            status = status,
            result = result,
            error = error,
            returnPackage = returnPackage,
            mode = ExternalAutomationContract.Mode.TASK,
        )
        pendingExternalRequestId = null
        pendingExternalReturnAction = null
        pendingExternalReturnPackage = null
    }

    private fun syncTaskAgentConfig() {
        if (!appViewModel.updateAgentConfig()) {
            XLog.w(TAG, "syncTaskAgentConfig: failed to update task agent config")
        }
    }

    private fun switchModel(modelId: String, displayName: String) {
        chatSessionController.switchModel(modelId, displayName)
        _isLocalModelActive.value = ModelConfigRepository.isLocalActive()
        if (modelId != "NONE") {
            syncTaskAgentConfig()
        }
        XLog.i(TAG, "Model switched to: $modelId ($displayName)")
    }

    private fun newChat() {
        lifecycleScope.launch {
            val session = conversationStore.startNewConversation(_messages, currentConversationModelName())
            syncSidebar(session.conversations)
            _messages.clear()
            _sessionTokens.value = 0
            _sessionCost.value = 0.0
            _isAwaitingReply.value = false
            _isTaskRunning.value = false
            chatSessionController.startNewConversationRuntime()
        }
    }

    /**
     * "Continue in New Chat" after a successfully completed task: opens a fresh
     * conversation seeded with the result summary as context (ASSISTANT role so the
     * cloud history rebuild includes it). Successful tasks are NOT resumable — the
     * RESUME card only appears for genuinely interrupted tasks.
     */
    private fun continueInNewChat(summary: String) {
        lifecycleScope.launch {
            val session = conversationStore.startNewConversation(_messages, currentConversationModelName())
            syncSidebar(session.conversations)
            _messages.clear()
            _sessionTokens.value = 0
            _sessionCost.value = 0.0
            _isAwaitingReply.value = false
            _isTaskRunning.value = false
            chatSessionController.startNewConversationRuntime()
            _messages.add(
                ChatMessage(
                    ChatMessage.Role.ASSISTANT,
                    "Continuing in a new chat. Context from the previous task:\n\n$summary"
                )
            )
            saveChat()
        }
    }

    private fun loadConversation(conv: ChatHistoryManager.ConversationSummary) {
        lifecycleScope.launch {
            val session = conversationStore.openConversation(conv, _messages, currentConversationModelName())
            syncSidebar(session.conversations)
            _messages.clear()
            _messages.addAll(session.messages)
            _isAwaitingReply.value = false
            _isTaskRunning.value = false
            chatSessionController.restoreConversationRuntime(session.conversationId, session.messages)
        }
    }

    private fun saveChat() {
        lifecycleScope.launch {
            syncSidebar(conversationStore.saveCurrent(_messages, currentConversationModelName()))
        }
    }

    private fun refreshSidebarHistory() {
        lifecycleScope.launch {
            syncSidebar(conversationStore.refreshSidebar())
        }
    }

    private fun syncSidebar(convos: List<ChatHistoryManager.ConversationSummary>) {
        _conversations.clear()
        _conversations.addAll(convos)
    }

    private fun currentConversationModelName(): String {
        val config = ModelConfigRepository.snapshot()
        return if (config.isLocalActive()) {
            config.local.displayName.ifBlank {
                KVUtils.getLocalModelPath().substringAfterLast('/').substringBeforeLast('.')
            }
        } else {
            config.activeCloud.modelName
        }
    }
}
