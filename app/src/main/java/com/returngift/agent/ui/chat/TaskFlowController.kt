// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.ui.chat

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.returngift.agent.AppCapabilityCoordinator
import com.returngift.agent.AppViewModel
import com.returngift.agent.ServiceBindingState
import com.returngift.agent.TaskEvent
import com.returngift.agent.TaskOrchestrator
import com.returngift.agent.channel.Channel
import com.returngift.agent.ClawApplication
import com.returngift.agent.agent.DirectDeviceDataGuard
import com.returngift.agent.agent.PipelineRouter
import com.returngift.agent.agent.TaskPromptEnvelope
import com.returngift.agent.agent.clarify.ClarificationManager
import com.returngift.agent.agent.llm.ModelConfigRepository
import com.returngift.agent.floating.FloatingCircleManager
import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.service.ForegroundService
import com.returngift.agent.service.AutoReplyManager
import com.returngift.agent.tool.ToolRegistry
import com.returngift.agent.ui.settings.SettingsActivity
import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog
import java.util.concurrent.ExecutorService

data class TaskFlowUiState(
    val messages: SnapshotStateList<ChatMessage>,
    val modelStatus: MutableState<String>,
    val isAwaitingReply: MutableState<Boolean>,
    val isTaskRunning: MutableState<Boolean>,
)

/**
 * Owns task-mode send flow, typed TaskEvent rendering, and monitor start wiring.
 *
 * ComposeChatActivity keeps the shell; this controller keeps task-specific behavior.
 */
class TaskFlowController(
    private val activity: ComponentActivity,
    private val executor: ExecutorService,
    private val appViewModel: AppViewModel,
    private val chatSessionController: ChatSessionController,
    private val currentConversationId: () -> String,
    private val uiState: TaskFlowUiState,
    private val onPersistConversation: () -> Unit,
    private val onTaskSettled: (() -> Unit)? = null,
    private val onTaskTerminal: ((TaskEvent) -> Unit)? = null,
    /** Starts a fresh conversation seeded with the completed task's result summary. */
    private val onContinueNewChat: ((String) -> Unit)? = null,
) {

    companion object {
        private const val TAG = "TaskFlowController"
        private const val RUNNING_SUMMARY = "in progress…"
        /** Prefix of the checkpoint-hint SYSTEM message — ChatScreen renders a resume card for it. */
        const val RESUME_HINT_PREFIX = "An earlier task was interrupted"
        /**
         * Prefix of the post-completion SYSTEM message — ChatScreen renders a
         * "Continue in New Chat" card for it. Only posted after a genuine
         * TaskEvent.Completed; interrupted tasks get the RESUME_HINT instead.
         */
        const val CONTINUE_HINT_PREFIX = "Task completed. Continue in a new chat:"
    }

    private var sendTaskRetryCount = 0
    private var lastMonitorStatusNote: String? = null
    private val pipelineRouter = PipelineRouter(activity)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var checkpointHintShown = false

    /** A stale checkpoint awaiting the user's resume-fresh decision (see sendTask). */
    private var staleResumeCheckpoint: com.returngift.agent.agent.checkpoint.TaskCheckpointStore.Checkpoint? = null
    /** True when this task backgrounded the chat at task start — the
     *  terminal event then also posts a completion notification (Kimi-Work style). */
    private var minimizedForTask = false

    /** Live ask_user question the agent is parked on, or null. */
    val pendingClarification = androidx.compose.runtime.mutableStateOf<ClarificationManager.PendingQuestion?>(null)

    /** Pending queue state (bounded to 1) — observed from TaskSessionStore. */
    val pendingTasks = androidx.compose.runtime.mutableStateOf<List<com.returngift.agent.TaskSessionStore.PendingTask>>(emptyList())

    private val pendingFlowJob: kotlinx.coroutines.Job = kotlinx.coroutines.Dispatchers.Main.immediate.launch {
        appViewModel.taskSessionStore.pendingFlow.collect { pending ->
            pendingTasks.value = pending
        }
    }



    /** Latest Preview (dry-run) mode plan, rendered as a plan card with
     *  an "Execute now" button. */
    val previewPlan = androidx.compose.runtime.mutableStateOf<List<com.returngift.agent.agent.dryrun.DryRunRunner.PlanStep>?>(null)
    private var previewOriginalTask: String? = null
    private val previewPlanSteps = mutableListOf<com.returngift.agent.agent.dryrun.DryRunRunner.PlanStep>()

    private val clarificationListener: (ClarificationManager.PendingQuestion?) -> Unit = { q ->
        pendingClarification.value = q
        if (q != null) {
            val choices = if (q.choices.isNotEmpty())
                "\n" + q.choices.mapIndexed { i, c -> "${i + 1}. $c" }.joinToString("\n")
            else ""
            addSystem("❓ ${q.question}$choices")
        }
    }

    init {
        ClarificationManager.addListener(clarificationListener)
        // Activity recreation: a question may already be parked — restore the card
        // state without re-posting the system message (the original one persists).
        val live = ClarificationManager.snapshot()
        if (live != null) {
            pendingClarification.value = live
        } else {
            // Process death parked a question that no loop thread can consume anymore —
            // surface it once so the user knows what the agent was asking.
            ClarificationManager.consumePersisted()?.let { q ->
                addSystem("❓ ${q.question}\n(The task was interrupted before you answered — send the task again to continue.)")
            }
        }
    }

    /** Unregister UI listeners. Call from Activity.onDestroy. */
    fun release() {
        ClarificationManager.removeListener(clarificationListener)
        pendingFlowJob.cancel()
    }

    /** Submit the user's answer to a parked ask_user question (choice tap or typed reply). */
    fun submitClarificationAnswer(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (ClarificationManager.answer(trimmed)) {
            addUser(trimmed)
            onPersistConversation()
        }
    }

    /** Plan card "Execute now": disable preview and re-dispatch the original task. */
    fun executePreviewPlan() {
        val task = previewOriginalTask ?: return
        previewOriginalTask = null
        previewPlan.value = null
        com.returngift.agent.agent.dryrun.DryRunRunner.setEnabled(false)
        sendTask(task)
    }

    fun dismissPreviewPlan() {
        previewPlan.value = null
        previewOriginalTask = null
    }

    fun sendTask(text: String) {
        // Stale-bubble sweep: a previous task/chat may have left the placeholder
        // or the FAB thinking state behind (e.g. an exception killed a terminal event).
        sweepStaleTypingIndicator()
// Cancel queued task: typed "cancel queue" drops the pending slot without
        // touching the running task. Case-insensitive contains so "cancel queued task".
        if (appViewModel.pendingCount() > 0 &&
            (text.lowercase().contains("cancel queue") ||
                text.lowercase().contains("cancel queued task"))
        ) {
            appViewModel.clearPending()
            addSystem("Queued task cancelled.")
            onPersistConversation()
            return
        }
        // A pending ask_user question consumes the next outgoing message as its answer.
        if (ClarificationManager.snapshot() != null) {
            submitClarificationAnswer(text)
            return
        }
        // Stale-answer race: the UI still showed a question but the loop already
        // resolved it (timeout/cancel). Don't silently treat the text as a brand-new
        // task — acknowledge the mismatch so the user knows what just happened.
        if (ClarificationManager.resolvedRecently()) {
            addSystem("ℹ️ That question was already closed (timed out or cancelled) — your message was sent as a new task instead.")
            // FP proxy (spec §8): a fired Tier-1 action was corrected within the 30 s
            // resolved-recently window. Skipped when no Tier-1 intent is knowable.
            com.returngift.agent.agent.Tier1Telemetry.lastFiredIntent()?.let { intent ->
                com.returngift.agent.agent.Tier1Telemetry.recordFalsePositive(intent)
            }
        }

        // NOTE: the personal-content consent gate lives INSIDE the loop now
        // (DefaultAgentService.runAgentLoop → PersonalContentConsentGuard). It
        // parks the loop on ask_user-style clarification, so it composes
        // cleanly with Preview mode (the stub just feeds it back).

        // Preview (dry-run) mode: run the full loop with stubbed tools so the
        // model's plan is captured without touching the device. The steps show
        // up as a plan card with an "Execute now" option.
        val previewActive = com.returngift.agent.agent.dryrun.DryRunRunner.isEnabled()
        if (previewActive) {
            com.returngift.agent.agent.dryrun.DryRunRunner.installStub()
            previewOriginalTask = text
            previewPlanSteps.clear()
            addSystem("🔍 Preview mode: the device will NOT be touched.")
        }

        // Checkpoint resume: an explicit "resume"/"continue" restarts the interrupted
        // task with its M3A-style step history prepended; otherwise hint once.
        var text = text
        val peeked = com.returngift.agent.agent.checkpoint.TaskCheckpointStore.peekIfResumeIntent(text)
        if (peeked != null && com.returngift.agent.agent.checkpoint.TaskCheckpointStore.isStale(peeked)) {
            // Too old to resume blindly — park on a fresh-start question instead of
            // silently resuming a checkpoint whose context the user has forgotten.
            // The user's next message is consumed as the answer; "resume" re-runs
            // this branch (now fresh-checked against the same checkpoint), anything
            // else discards the checkpoint and starts a new task.
            addSystem("⏳ That interrupted task is over ${com.returngift.agent.agent.checkpoint.TaskCheckpointStore.FRESHNESS_MS / 3_600_000}h old — start fresh instead? Type \"resume\" to still resume it, or send anything else to start fresh.")
            staleResumeCheckpoint = peeked
            return
        }
        if (staleResumeCheckpoint != null && com.returngift.agent.agent.checkpoint.TaskCheckpointStore.isResumeKeyword(text)) {
            // User explicitly confirmed resuming the stale checkpoint.
            val checkpoint = com.returngift.agent.agent.checkpoint.TaskCheckpointStore.consumeIfResumeIntent(text)
            staleResumeCheckpoint = null
            if (checkpoint != null) {
                addSystem("↩️ Resuming interrupted task (${checkpoint.path})")
                text = checkpoint.taskText + "\n\n" +
                    com.returngift.agent.agent.checkpoint.TaskCheckpointStore.renderPromptContext(checkpoint)
            }
        } else if (staleResumeCheckpoint != null) {
            // User sent something else — start fresh, drop the stale checkpoint.
            val droppedTaskText = staleResumeCheckpoint?.taskText ?: ""
            staleResumeCheckpoint = null
            com.returngift.agent.agent.checkpoint.TaskCheckpointStore.clearIfTaskMatches(droppedTaskText)
        } else {
            val checkpoint = com.returngift.agent.agent.checkpoint.TaskCheckpointStore.consumeIfResumeIntent(text)
            if (checkpoint != null) {
                addSystem("↩️ Resuming interrupted task (${checkpoint.path})")
                text = checkpoint.taskText + "\n\n" +
                    com.returngift.agent.agent.checkpoint.TaskCheckpointStore.renderPromptContext(checkpoint)
            } else if (com.returngift.agent.agent.checkpoint.TaskCheckpointStore.isResumeKeyword(text)) {
                // Resume card/button tapped after the checkpoint was already consumed.
                addSystem("No interrupted task to resume.")
                return
            }
        }
        if (!checkpointHintShown && peeked == null) {
            com.returngift.agent.agent.checkpoint.TaskCheckpointStore.peek()?.let { cp ->
                checkpointHintShown = true
                addSystem("$RESUME_HINT_PREFIX — tap Resume (or type \"resume\") to continue it (${cp.path}).")
            }
        }

        if (appViewModel.isTaskRunning()) {
            addSystem("Another task is still running. Stop it first.")
            onTaskTerminal?.invoke(TaskEvent.Failed("Another task is still running. Stop it first."))
            return
        }

        if (ModelConfigRepository.snapshot().isLocalActive() && isLikelyMonitorRequest(text)) {
            addUser(text)
            addSystem("Local mode starts monitoring from the Background card. Open Background, choose the app/contact, then tap Start Monitoring.")
            onTaskTerminal?.invoke(TaskEvent.Failed("Local mode starts monitoring from the Background card."))
            return
        }

        DirectDeviceDataGuard.deterministicToolCall(text)?.let { directTool ->
            XLog.i(TAG, "sendTask: executing deterministic direct tool before LLM/accessibility gates")
            executeDirectToolTask(text, directTool)
            return
        }

        when (AppCapabilityCoordinator.accessibilityState(activity)) {
            ServiceBindingState.DISABLED -> {
                val directTool = DirectDeviceDataGuard.deterministicToolCall(text)
                if (directTool != null) {
                    XLog.i(TAG, "sendTask: executing non-interactive direct tool without Accessibility")
                    executeDirectToolTask(text, directTool)
                    return
                }
                if (canRunWithoutAccessibility(text)) {
                    XLog.i(TAG, "sendTask: allowing non-interactive task without Accessibility")
                } else {
                Toast.makeText(activity, "Enable Accessibility Service to run tasks", Toast.LENGTH_LONG).show()
                addSystem("⚠️ Task mode needs Accessibility Service enabled. Opening Settings...")
                openSettings()
                sendTaskRetryCount = 0
                onTaskTerminal?.invoke(TaskEvent.Failed("Accessibility Service is required for this task."))
                return
                }
            }
            ServiceBindingState.CONNECTING -> {
                val directTool = DirectDeviceDataGuard.deterministicToolCall(text)
                if (directTool != null) {
                    XLog.i(TAG, "sendTask: executing non-interactive direct tool while Accessibility connects")
                    executeDirectToolTask(text, directTool)
                    return
                }
                if (canRunWithoutAccessibility(text)) {
                    XLog.i(TAG, "sendTask: allowing non-interactive task while Accessibility connects")
                } else {
                if (sendTaskRetryCount >= 1) {
                    Toast.makeText(activity, "Accessibility service not connected. Try toggling it off and on.", Toast.LENGTH_LONG).show()
                    addSystem("Accessibility service didn't connect. Try toggling it off and on in Settings.")
                    openSettings()
                    sendTaskRetryCount = 0
                    onTaskTerminal?.invoke(TaskEvent.Failed("Accessibility service did not connect."))
                    return
                }
                sendTaskRetryCount++
                addSystem("Accessibility service connecting, please wait...")
                executor.submit {
                    val connected = ClawAccessibilityService.awaitRunning(5000)
                    activity.runOnUiThread {
                        if (connected) {
                            sendTask(text)
                        } else {
                            Toast.makeText(activity, "Accessibility service didn't connect", Toast.LENGTH_LONG).show()
                            addSystem("Accessibility service didn't connect. Go to Settings and toggle it off then on.")
                            sendTaskRetryCount = 0
                            onTaskTerminal?.invoke(TaskEvent.Failed("Accessibility service did not connect."))
                        }
                    }
                }
                return
                }
            }
            ServiceBindingState.DEGRADED -> {
                val directTool = DirectDeviceDataGuard.deterministicToolCall(text)
                if (directTool != null) {
                    XLog.i(TAG, "sendTask: executing non-interactive direct tool while Accessibility is degraded")
                    executeDirectToolTask(text, directTool)
                    return
                }
                if (canRunWithoutAccessibility(text)) {
                    XLog.i(TAG, "sendTask: allowing non-interactive task while Accessibility is degraded")
                } else {
                    Toast.makeText(activity, "Accessibility service disconnected. Open Settings and toggle it back on.", Toast.LENGTH_LONG).show()
                    addSystem("Accessibility service disconnected. Open Settings and toggle it off then on.")
                    openSettings()
                    sendTaskRetryCount = 0
                    onTaskTerminal?.invoke(TaskEvent.Failed("Accessibility service is disconnected."))
                    return
                }
            }
            ServiceBindingState.READY -> Unit
        }
        sendTaskRetryCount = 0

        // Preflight: tasks that read notifications need notification-listener access —
        // warn up front instead of letting the agent discover an empty notification list.
        if (text.lowercase().contains("notification")
            && AppCapabilityCoordinator.notificationAccessState(activity) != ServiceBindingState.READY
        ) {
            XLog.w(TAG, "sendTask: notification task started without notification access")
            Toast.makeText(activity, "Notification access is off", Toast.LENGTH_LONG).show()
            addSystem("⚠️ This task reads notifications, but notification access is off — the agent can't see them. Enable it in Settings → Permissions.")
        }

        ensureNotificationPermission()
        uiState.isAwaitingReply.value = false
        uiState.isTaskRunning.value = false

        if (!KVUtils.hasLlmConfig()) {
            Toast.makeText(activity, "Configure LLM in Settings first", Toast.LENGTH_LONG).show()
            onTaskTerminal?.invoke(TaskEvent.Failed("Configure LLM in Settings first."))
            return
        }

        val agentPromptOverride = buildAgentPromptOverride(text)
        addUser(text)
        uiState.isAwaitingReply.value = true
        uiState.isTaskRunning.value = false
        XLog.i(TAG, "sendTask: isProcessing=TRUE")
        uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, "..."))

        val taskId = "task_${System.currentTimeMillis()}"

        executor.submit {
            chatSessionController.prepareForTaskStart()

            activity.runOnUiThread {
                try {
                    appViewModel.startTask(text, taskId, agentPromptOverride = agentPromptOverride) { event ->
                        activity.runOnUiThread { handleTaskEvent(event) }
                    }
                    // Move ReturnGift to the background IMMEDIATELY for device-automation
                    // tasks so every observation (prewarm, screenshots, reads) sees the
                    // target app / launcher instead of our own chat UI. The old flow
                    // deferred this until TargetForegroundVerified (10s fallback), which
                    // let "open N apps + screenshot" tasks capture the chat screen. The
                    // floating pill keeps the user informed.
                    if (isDeviceAutomationTask(text)) {
                        minimizeToBackground(text)
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "sendTask failed: ${e.message}", e)
                    addSystem("Error: ${e.message}")
                    cleanupAfterTask()
                }
            }
        }
    }

    /**
     * Tasks that drive another app's UI need ReturnGift out of the foreground so the
     * AccessibilityService's active window is the target app, not this chat screen.
     * Pure info/device-data queries and chats keep the chat UI visible.
     */
    private fun isDeviceAutomationTask(text: String): Boolean {
        val p = text.lowercase()
        val automationKeywords = listOf(
            "open ", "launch ", "tap ", "click ", "type ", "send ", "search ",
            "play ", "install ", "go to ", "navigate ", "turn on ", "turn off ",
            "close ", "swipe ", "scroll ", "compose ", "find ", "call ", "dial ",
            "post ", "share ", "set an alarm", "set a reminder", "message "
        )
        if (p.contains("monitor ")) return false
        if (automationKeywords.any { p.contains(it) }) return true
        return false
    }

    private fun minimizeToBackground(taskText: String) {
        try {
            FloatingCircleManager.ensureShowing()
            FloatingCircleManager.showTaskNotify(taskText, Channel.LOCAL)
            // moveTaskToBack requires a non-finishing activity; falls back silently if it fails.
            val moved = activity.moveTaskToBack(true)
            XLog.i(TAG, "minimizeToBackground: moveTaskToBack=$moved (task started in background)")
            if (moved) minimizedForTask = true
            if (!moved) {
                XLog.w(TAG, "minimizeToBackground: moveTaskToBack returned false; task still running")
            }
        } catch (e: Exception) {
            XLog.e(TAG, "minimizeToBackground failed", e)
        }
    }


    private fun executeDirectToolTask(text: String, toolCall: DirectDeviceDataGuard.DeterministicToolCall) {
        ensureNotificationPermission()
        addUser(text)
        uiState.isAwaitingReply.value = true
        uiState.isTaskRunning.value = false
        uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, "..."))

        executor.submit {
            try {
                val result = ToolRegistry.getInstance().executeTool(toolCall.toolName, toolCall.params)
                activity.runOnUiThread {
                    val answer = result.data ?: result.error ?: "Done."
                    replaceTypingIndicator(answer)
                    onTaskTerminal?.invoke(TaskEvent.Completed(answer))
                    cleanupAfterTask()
                }
            } catch (e: Exception) {
                XLog.e(TAG, "executeDirectToolTask failed: ${e.message}", e)
                activity.runOnUiThread {
                    replaceTypingIndicator("Error: ${e.message}")
                    onTaskTerminal?.invoke(TaskEvent.Failed(e.message ?: "Direct tool failed"))
                    cleanupAfterTask()
                }
            }
        }
    }

    private fun canRunWithoutAccessibility(text: String): Boolean {
        if (DirectDeviceDataGuard.matchesNonInteractiveDeviceDataTask(text)) {
            return true
        }
        return when (pipelineRouter.route(text)) {
            is PipelineRouter.Route.DirectIntent -> true
            else -> false
        }
    }

    fun handleMonitorTask(text: String) {
        val target = MonitorTargetParser.fromTaskText(text)
        if (target == null) {
            addUser(text)
            addSystem("Could not figure out who to monitor. Try: \"Monitor Mom on WhatsApp\"")
            return
        }

        startMonitor(target, typedInput = text)
    }

    fun startMonitor(target: MonitorTargetSpec, typedInput: String? = null) {
        val trimmedLabel = target.label.trim()
        if (trimmedLabel.isEmpty()) {
            addSystem("Could not figure out who to monitor. Try: \"Monitor Mom on WhatsApp\"")
            return
        }

        typedInput?.let { addUser(it) }
        val missing = AppCapabilityCoordinator.missingMonitorRequirements(activity)
        if (missing.isNotEmpty()) {
            Toast.makeText(
                activity,
                "Enable ${missing.joinToString(" & ") { it.label }} in Settings first",
                Toast.LENGTH_LONG
            ).show()
            openSettings()
            onTaskTerminal?.invoke(TaskEvent.Failed("Missing required permissions for monitoring."))
            return
        }

        val contact = trimmedLabel
        val app = target.app
        uiState.isAwaitingReply.value = false
        uiState.isTaskRunning.value = false
        addSystem("Setting up auto-reply for $contact on $app...")

        val autoReplyManager = AutoReplyManager.getInstance()
        autoReplyManager.addTarget(contact, app)
        autoReplyManager.setEnabled(true)
        XLog.i(TAG, "startMonitor: enabled auto-reply for '${target.displayLabel}'")

        Handler(Looper.getMainLooper()).postDelayed({
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                uiState.isAwaitingReply.value = false
                uiState.isTaskRunning.value = false
                addSystem("✓ Auto-reply is now active for ${target.displayLabel}.\nMonitoring in background — you can stop anytime from the bar above.")
                XLog.i(TAG, "startMonitor: monitor active, staying in ReturnGift")
            }
        }, 1500)
    }

    // ── Live process card (G2) ────────────────────────────────────────────────
    // One TOOL_GROUP message per task, updated in place as tool events arrive;
    // finalized (and persisted) at the terminal event. Replaces the flat
    // "Tool..." / "Tool failed" system lines with a Kimi-style process trace.
    private val processSteps = mutableListOf<ToolStep>()
    private var processMsgId: String? = null
    private var pendingTaskTitle: String? = null

    private fun ensureProcessCard() {
        if (processMsgId != null) return
        val msg = ChatMessage(ChatMessage.Role.TOOL_GROUP, content = "", toolSteps = emptyList())
        uiState.messages.add(msg)
        processMsgId = msg.id
    }

    private fun updateProcessCard() {
        val id = processMsgId ?: return
        val idx = uiState.messages.indexOfFirst { it.id == id }
        if (idx >= 0) {
            uiState.messages[idx] = uiState.messages[idx].copy(toolSteps = processSteps.toList())
        }
    }

    private fun finalizeProcessCard() {
        if (processMsgId == null) return
        for (i in processSteps.indices) {
            if (processSteps[i].summary == RUNNING_SUMMARY) {
                processSteps[i] = processSteps[i].copy(summary = "interrupted", success = false)
            }
        }
        updateProcessCard()
        onPersistConversation()
        processSteps.clear()
        processMsgId = null
    }

    private fun handleTaskEvent(event: TaskEvent) {
        try {
            when (event) {
                is TaskEvent.Completed -> {
                    replaceTypingIndicator(event.answer, event.modelName)
                    notifyTaskFinishedIfBackgrounded(success = true, body = event.answer)
                    // Successful completion is NOT resumable — offer branching instead.
                    // The checkpoint for this task was already retired by the loop's
                    // terminal finalizer (clearIfTaskMatches), so no RESUME card appears.
                    if (onContinueNewChat != null) {
                        addSystem("$CONTINUE_HINT_PREFIX ${event.answer.take(200)}")
                    }
                    finalizeProcessCard()
                    onTaskTerminal?.invoke(event)
                    cleanupAfterTask()
                    checkAutoReplyConfirmation()
                }
                is TaskEvent.Failed -> {
                    replaceTypingIndicator("Error: ${event.error}")
                    notifyTaskFinishedIfBackgrounded(success = false, body = event.error)
                    finalizeProcessCard()
                    onTaskTerminal?.invoke(event)
                    cleanupAfterTask()
                }
                is TaskEvent.Cancelled -> {
                    removeTypingIndicator()
                    finalizeProcessCard()
                    onTaskTerminal?.invoke(event)
                    cleanupAfterTask()
                }
                is TaskEvent.Blocked -> {
                    replaceTypingIndicator("Blocked by system dialog.")
                    finalizeProcessCard()
                    onTaskTerminal?.invoke(event)
                    cleanupAfterTask()
                }
                is TaskEvent.ToolAction -> {
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                    if (!event.toolName.contains("Finish", ignoreCase = true)) {
                        removeTypingIndicator()
                        ensureProcessCard()
                        processSteps.add(ToolStep(event.toolName, RUNNING_SUMMARY, success = false))
                        updateProcessCard()
                    }
                }
                is TaskEvent.ToolResult -> {
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                    if (previewOriginalTask != null && event.success && !event.toolName.equals("Finish", ignoreCase = true)) {
                        previewPlanSteps.add(
                            com.returngift.agent.agent.dryrun.DryRunRunner.PlanStep(
                                sequence = previewPlanSteps.size + 1,
                                toolId = event.toolName,
                                displayName = event.toolName,
                                params = event.detail.take(120),
                            )
                        )
                    }
                    val idx = processSteps.indexOfLast { it.toolName == event.toolName && it.summary == RUNNING_SUMMARY }
                    if (idx >= 0) {
                        val detail = event.detail.trim().replace('\n', ' ')
                        val snippet = if (detail.length > 120) detail.take(117) + "…" else detail
                        processSteps[idx] = processSteps[idx].copy(
                            summary = if (event.success) snippet.ifEmpty { "done" }
                                      else "failed: ${snippet.ifEmpty { "unknown error" }}",
                            success = event.success,
                        )
                        updateProcessCard()
                    } else if (processMsgId == null && !event.success) {
                        addSystem("${event.toolName} failed")
                    }
                }
                is TaskEvent.Response -> {
                    uiState.isAwaitingReply.value = false
                    replaceTypingIndicator(event.text)
                }
                is TaskEvent.Progress -> {
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                    addSystem(event.description)
                }
                is TaskEvent.ArtifactSaved -> {
                    // Typed artifact card (clickable, correct MIME) instead of a flat string —
                    // the model's final summary alone left vault artifacts invisible to the user.
                    uiState.messages.add(
                        ChatMessage(
                            ChatMessage.Role.SYSTEM,
                            "📄 Saved to vault: ${event.path}",
                            artifactPath = event.path,
                            artifactMime = com.returngift.agent.agent.knowledge.KBManager.mimeOf(event.path),
                        )
                    )
                    onPersistConversation()
                }
                is TaskEvent.LoopStart -> {
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                }
                // Minimize now happens at task start (see sendTask); verification just
                // confirms the target app reached the foreground.
                is TaskEvent.TargetForegroundVerified -> {
                    XLog.i(TAG, "TargetForegroundVerified: ${event.packageName}")
                }
                is TaskEvent.TokenUpdate, is TaskEvent.Thinking -> Unit
            }
        } catch (e: Exception) {
            XLog.w(TAG, "handleTaskEvent error", e)
            // Guaranteed settle: a terminal event that throws halfway through
            // otherwise leaves the typing bubble and stop FAB alive forever.
            settleAfterTerminalEvent(event)
        }
    }

    /** Terminal events that must ALWAYS clear the processing/typing UI state. */
    private fun isTerminalEvent(event: TaskEvent): Boolean =
        event is TaskEvent.Completed || event is TaskEvent.Failed ||
            event is TaskEvent.Cancelled || event is TaskEvent.Blocked

    private fun settleAfterTerminalEvent(event: TaskEvent) {
        if (isTerminalEvent(event)) cleanupAfterTask()
    }

    /** Idempotent tidy-up: clear stuck processing flags and any leftover placeholder. */
    private fun sweepStaleTypingIndicator() {
        uiState.isAwaitingReply.value = false
        uiState.isTaskRunning.value = false
        removeTypingIndicator()
    }

    /** Post a completion alert only when the task backgrounded the chat — otherwise
     *  the user is already watching the answer appear. */
    private fun notifyTaskFinishedIfBackgrounded(success: Boolean, body: String) {
        if (!minimizedForTask) return
        minimizedForTask = false
        val title = pendingTaskTitle ?: "Task"
        com.returngift.agent.service.ForegroundService.notifyTaskFinished(
            ClawApplication.instance, success, title.take(60), body
        )
    }

    private fun replaceTypingIndicator(text: String, actualModelName: String? = null) {
        val modelTag = actualModelName
            ?: uiState.modelStatus.value.removePrefix("● ").split(" ·").firstOrNull()?.trim()
            ?: ""
        val idx = uiState.messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT && it.content == "..." }
        if (idx >= 0) {
            uiState.messages[idx] = ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = modelTag)
        } else {
            uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = modelTag))
        }
        onPersistConversation()
    }

    private fun removeTypingIndicator() {
        val idx = uiState.messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT && it.content == "..." }
        if (idx >= 0) uiState.messages.removeAt(idx)
    }

    private fun cleanupAfterTask() {
        XLog.i(TAG, "cleanupAfterTask: isProcessing=FALSE")
        // Preview mode: flush the collected steps into the plan card and ALWAYS
        // restore real execution — the stub must never linger after the run.
        if (previewOriginalTask != null) {
            com.returngift.agent.agent.dryrun.DryRunRunner.removeStub()
            if (previewPlanSteps.isNotEmpty()) {
                previewPlan.value = previewPlanSteps.toList()
            } else {
                addSystem("🔍 No actions were planned.")
            }
            previewPlanSteps.clear()
            // previewOriginalTask is kept alive so the plan card's 'Execute now'
            // button can re-dispatch the SAME task; cleared on execute/dismiss.
            onPersistConversation()
        }
        uiState.isAwaitingReply.value = false
        uiState.isTaskRunning.value = false
        removeTypingIndicator()
        appViewModel.clearTaskCallback()
        onTaskSettled?.invoke()
        Handler(Looper.getMainLooper()).postDelayed({
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                try {
                    chatSessionController.loadModelIfReady(
                        conversationId = currentConversationId(),
                        visibleMessages = uiState.messages.toList(),
                    )
                } catch (e: Exception) {
                    XLog.e(TAG, "cleanupAfterTask: loadModel error", e)
                }
            }
        }, 500)
    }

    private fun checkAutoReplyConfirmation() {
        val autoReplyManager = AutoReplyManager.getInstance()
        if (!autoReplyManager.isEnabled) {
            lastMonitorStatusNote = null
            return
        }
        val contacts = autoReplyManager.monitoredContacts.joinToString(", ")
        if (contacts.isBlank()) {
            lastMonitorStatusNote = null
            return
        }
        val note = "✓ Auto-reply active for $contacts.\nMonitoring in background — stop from bar above."
        if (note == lastMonitorStatusNote) return
        addSystem(note)
        lastMonitorStatusNote = note
        XLog.i(TAG, "checkAutoReplyConfirmation: monitor active, staying in ReturnGift")
    }

    private fun ensureNotificationPermission() {
        if (!AppCapabilityCoordinator.isNotificationPermissionGranted(activity)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun addUser(text: String) {
        uiState.messages.add(ChatMessage(ChatMessage.Role.USER, text))
    }

    private fun addSystem(text: String) {
        uiState.messages.add(ChatMessage(ChatMessage.Role.SYSTEM, text))
    }

    private fun openSettings() {
        activity.startActivity(Intent(activity, SettingsActivity::class.java))
    }

    private fun buildAgentPromptOverride(rawTask: String): String? {
        if (ModelConfigRepository.snapshot().isLocalActive()) {
            return null
        }

        val historyLines = CloudContextHandoffFormatter.conversationLines(uiState.messages)
        val backgroundStatus = buildBackgroundStatusContext()

        return TaskPromptEnvelope.build(
            chatHistoryLines = historyLines,
            currentRequest = rawTask,
            backgroundState = backgroundStatus,
        )
    }

    private fun buildBackgroundStatusContext(): String? {
        val autoReplyManager = AutoReplyManager.getInstance()
        if (!autoReplyManager.isEnabled) return null

        val contacts = autoReplyManager.monitoredContacts.toList()
        if (contacts.isEmpty()) return null

        return buildString {
            append("Background monitor active for: ")
            append(contacts.joinToString(", "))
            append('.')
        }
    }

    private fun isLikelyMonitorRequest(text: String): Boolean {
        val lower = text.lowercase()
        val mentionsMonitor = lower.contains("monitor") ||
            lower.contains("auto-reply") ||
            lower.contains("auto reply") ||
            lower.contains("autoreply")
        val looksLikeWatchMessages = lower.contains("watch") &&
            (lower.contains("message") || lower.contains("messages") || lower.contains("reply"))
        return mentionsMonitor || looksLikeWatchMessages
    }
}
