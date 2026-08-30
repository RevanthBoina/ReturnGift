// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent

import com.returngift.agent.agent.AgentCallback
import com.returngift.agent.agent.AgentConfig
import com.returngift.agent.agent.AgentService
import com.returngift.agent.agent.AgentServiceFactory
import com.returngift.agent.agent.PipelineRouter
import com.returngift.agent.agent.Tier1Telemetry
import com.returngift.agent.agent.clarify.ClarificationManager
import com.returngift.agent.agent.exec.BoundedExecution
import com.returngift.agent.agent.skill.SkillExecutor
import com.returngift.agent.agent.skill.SkillRegistry
import com.returngift.agent.channel.Channel
import com.returngift.agent.channel.ChannelManager
import com.returngift.agent.floating.FloatingCircleManager
import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.service.ForegroundService
import com.returngift.agent.tool.ToolResult
import com.returngift.agent.utils.XLog

/**
 * Task orchestrator — manages agent lifecycle, task locking, pipeline routing, and execution.
 */
class TaskOrchestrator(
    private val agentConfigProvider: () -> AgentConfig,
    private val onTaskFinished: () -> Unit
) {
    /**
     * Typed event callback for in-app Task mode UI.
     * Called on the agent executor thread — UI must post to main thread.
     */
    var taskEventCallback: ((TaskEvent) -> Unit)? = null

    companion object {
        private const val TAG = "TaskOrchestrator"
        /** D3: 5-second auto-cancel for the Tier-1 send_message pre-send confirmation. */
        const val TIER1_SEND_CONFIRM_TIMEOUT_MS = 5_000L
        /** C4: KV key for process-death reconciliation — stores "messageID|channel" of the live task. */
        const val KEY_ACTIVE_TASK = "key_active_task"
    }

    private lateinit var agentService: AgentService
    val taskSessionStore = TaskSessionStore()

    // C4: supervisor scope for the orchestrator's background work (D4)
    private val supervisorScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    internal var appContext: android.content.Context
        get() = injectedAppContext ?: ClawApplication.instance
        set(value) { injectedAppContext = value }
    private var injectedAppContext: android.content.Context? = null

    // Router / skill executor are created lazily from ClawApplication so tests can inject
    // their own fakes via the internal *_ForTesting fields (Robolectric tests must not depend
    // on the Android singletons, and the FIX 7/8 DirectIntent/DirectTool terminal paths need
    // controllable fake returns).
    private var injectedRouter: PipelineRouter? = null
    private var injectedSkillExecutor: SkillExecutor? = null
    internal var routerForTesting: PipelineRouter?
        get() = injectedRouter
        set(value) { injectedRouter = value }
    internal var skillExecutorForTesting: SkillExecutor?
        get() = injectedSkillExecutor
        set(value) { injectedSkillExecutor = value }
    private val pipelineRouter: PipelineRouter
        get() = injectedRouter ?: PipelineRouter(appContext)
    private val skillExecutor: SkillExecutor
        get() = injectedSkillExecutor ?: SkillExecutor()

    // Observable seams for the terminal paths (DirectIntent / DirectTool / skill). Production
    // delegates to the real ChannelManager + FloatingCircleManager; tests inject fakes to
    // assert on the typed events and channel messages without Android singletons.
    internal var channelMessageSink: ((Channel, String, String) -> Unit)? = null
    internal var floatingStateSink: ((Boolean) -> Unit)? = null

    // FIX 10: wall-clock bound for Tier-1 DirectTool execution (default 60s, shared with the
    // bounded-execution helper). Tests shrink this to prove a hung tool terminates + releases
    // the session lock.
    @Volatile
    internal var directToolTimeoutMs: Long = BoundedExecution.DEFAULT_WALL_CLOCK_MS

    // D3: pre-send confirmation for Tier-1 send_message. Non-blocking confirm on the chat
    // surface (a ClarificationCard with Send/Cancel chips) with a 5-second auto-cancel as the
    // safe default — send_message is irreversible with no system confirmation surface, so it
    // must confirm BEFORE executing. The hook is injectable so pure-JVM tests can prove that a
    // declined or timed-out confirm suppresses execution and a confirmed one proceeds.
    internal var sendMessageConfirm: (() -> Boolean)? = null

    private fun confirmSendMessage(): Boolean {
        val injected = sendMessageConfirm
        if (injected != null) return injected()
        val answer = ClarificationManager.request(
            question = "Send this message? Tap Send to confirm, or Cancel.",
            choices = listOf("Send", "Cancel"),
            allowFreeText = false,
            timeoutMs = TIER1_SEND_CONFIRM_TIMEOUT_MS,
        )
        // null = timeout (5s auto-cancel) or user cancelled → safe default is NOT to send.
        return answer == "Send"
    }

    private fun sendChannelMessage(channel: Channel, content: String, messageID: String) {
        val sink = channelMessageSink
        if (sink != null) sink(channel, content, messageID) else ChannelManager.sendMessage(channel, content, messageID)
    }

    private fun setSuccessFloatingState() {
        val sink = floatingStateSink
        if (sink != null) sink(true) else FloatingCircleManager.setSuccessState()
    }

    private fun setErrorFloatingState() {
        val sink = floatingStateSink
        if (sink != null) sink(false) else FloatingCircleManager.setErrorState()
    }

    val inProgressTaskMessageId: String
        get() = taskSessionStore.snapshot().messageId
    val inProgressTaskChannel: Channel?
        get() = taskSessionStore.snapshot().channel

    // ==================== Agent Lifecycle ====================

    fun initAgent() {
        agentService = AgentServiceFactory.create()
        try {
            agentService.initialize(agentConfigProvider())
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to initialize AgentService", e)
        }
    }

    fun updateAgentConfig(): Boolean {
        return try {
            val config = agentConfigProvider()
            if (::agentService.isInitialized) {
                agentService.updateConfig(config)
                XLog.d(TAG, "Agent config updated: model=${config.modelName}, temp=${config.temperature}")
                true
            } else {
                XLog.w(TAG, "AgentService not initialized, initializing with new config")
                agentService = AgentServiceFactory.create()
                agentService.initialize(config)
                true
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to update agent config", e)
            false
        }
    }

    // ==================== Task Lock ====================

    fun tryAcquireTask(messageId: String, channel: Channel, taskText: String = ""): Boolean {
        val acquired = taskSessionStore.tryAcquire(
            messageId = messageId,
            channel = channel,
            taskText = taskText,
        )
        if (acquired) {
            // C4: record active task for process-death reconciliation
            KVUtils.putString(KEY_ACTIVE_TASK, "$messageId|$channel")
        }
        return acquired
    }

    /**
     * FIX 12: idempotent terminal cleanup. Releases the session ONLY if [messageId] still
     * owns it; returns null when it was already released by a different terminal path. Every
     * terminal handler (agent-loop onComplete/onError/onSystemDialogBlocked plus the
     * skill/DirectIntent/DirectTool paths) must route its channel confirmation + final-state
     * through this so a racing cancel can never double-run onTaskFinished or read a reset
     * session (channel/messageId already empty → silent no-feedback cancellation).
     */
    private fun casRelease(messageId: String): TaskSessionState? =
        taskSessionStore.releaseIfMatches(messageId)

    /** The live session's owner message id (empty after release). FIX 12. */
    private fun currentMessageId(): String = taskSessionStore.snapshot().messageId

    /** C1: unified terminal reporting with exactly one effect per successful release */
    private fun terminal(
        messageID: String,
        ok: Boolean,
        event: TaskEvent,
        channelMsg: String? = null
    ) {
        val s = taskSessionStore.releaseIfMatches(messageID) ?: return
        // C4: clear the active-task key so process-death reconciliation doesn't double-fire
        KVUtils.remove(KEY_ACTIVE_TASK)
        taskEventCallback?.invoke(event)
        channelMsg?.let { sendChannelMessage(s.channel ?: Channel.LOCAL, it, s.messageId) }
        ForegroundService.resetToIdle(appContext); if (ok) setSuccessFloatingState() else setErrorFloatingState(); onTaskFinished()
        // D3: only auto-dequeue on Completed. Cancelled / Failed / Blocked leave the queue
        // intact and emit a "still waiting" system line so the user decides whether to start it.
        when (event) {
            is TaskEvent.Completed -> {
                val next = taskSessionStore.tryDequeuePending()
                if (next != null) {
                    XLog.i(TAG, "Dequeued pending task ${next.messageId}; restarting")
                    taskEventCallback?.invoke(TaskEvent.Progress(0, "Resuming queued task..."))
                    startNewTask(next.channel, next.taskText, next.messageId)
                }
            }
            is TaskEvent.Cancelled, is TaskEvent.Failed, is TaskEvent.Blocked -> {
                if (taskSessionStore.pendingCount > 0) {
                    val pending = taskSessionStore.pendingFlow.value.firstOrNull()
                    if (pending != null) {
                        val snippet = pending.taskText.take(60) + if (pending.taskText.length > 60) "…" else ""
                        taskEventCallback?.invoke(TaskEvent.Queued(pending.messageId, 1))
                        ChannelManager.sendMessage(
                            s.channel ?: Channel.LOCAL,
                            "Current task ended without success — queued task \"$snippet\" is still waiting. Tap the queued card to start it, or say 'cancel queue' to drop it.",
                            pending.messageId
                        )
                    }
                }
            }
            else -> { /* not a terminal type that needs queue handling */ }
        }
    }

    fun isTaskRunning(): Boolean = taskSessionStore.isTaskRunning()

    /**
     * Cancel the pending queued task without touching the running task.
     * Idempotent — safe to call when the queue is empty.
     */
    fun cancelQueue() {
        val pending = taskSessionStore.pendingCount > 0
        if (pending) {
            taskSessionStore.clearPending()
            XLog.i(TAG, "Queued task cancelled by user")
        }
    }

    /**
     * Start the pending task immediately (card "Start now" button). Guarded by
     * isTaskRunning — a running task must be cancelled first. Returns the
     * dequeued task, or null when there is nothing queued or the lock is held.
     */
    fun startPendingNow(): PendingTask? {
        val pending = taskSessionStore.tryDequeuePending() ?: return null
        if (isTaskRunning()) {
            // Re-enqueue and let the card UI disable the button instead of
            // silently dropping the task.
            taskSessionStore.enqueuePending(pending.messageId, pending.channel, pending.taskText)
            return null
        }
        startNewTask(pending.channel, pending.taskText, pending.messageId)
        return pending
    }

    // ==================== Task Execution ====================

    fun cancelCurrentTask() {
        if (!taskSessionStore.markStopping()) return
        // Unblock a parked ask_user call immediately (belt & braces with
        // DefaultAgentService.cancel — cancelPending is idempotent).
        com.returngift.agent.agent.clarify.ClarificationManager.cancelPending()
        if (::agentService.isInitialized) {
            agentService.cancel()
        }
        if (ForegroundService.isRunning()) {
            ForegroundService.updateTaskStatus(appContext, "Stopping task...")
        }
        XLog.d(TAG, "Current task cancellation requested")
    }

    /**
     * Start a new task. Routes through the 3-tier pipeline.
     */
    fun startNewTask(
        channel: Channel,
        task: String,
        messageID: String,
        agentPromptOverride: String? = null,
        isFallback: Boolean = false,
    ) {
        // Acquire task lock if not already held
        if (!isTaskRunning()) {
            if (!tryAcquireTask(messageID, channel, task)) {
                XLog.w(TAG, "Failed to acquire task lock for: $task")
                taskEventCallback?.invoke(TaskEvent.Failed("Another task is running"))
                return
            }
        } else {
            val current = taskSessionStore.snapshot()
            if (current.messageId == messageID && current.channel == channel) {
                taskSessionStore.updateTaskText(task)
            } else {
                // D2: enqueue instead of reject — bounded FIFO (max 1 slot)
                val enqueued = taskSessionStore.enqueuePending(messageID, channel, task)
                if (enqueued) {
                    XLog.i(TAG, "Another task is running; enqueued new task for later")
                    taskEventCallback?.invoke(TaskEvent.Queued(messageID, taskSessionStore.pendingCount))
                    ChannelManager.sendMessage(
                        channel,
                        "Task queued (1 ahead). It starts when the current task SUCCEEDS. Say 'cancel queue' or tap the queued card to drop it.",
                        messageID
                    )
                } else {
                    XLog.w(TAG, "Queue full; rejecting new task")
                    taskEventCallback?.invoke(TaskEvent.Failed("Queue is full — one task can wait at a time. Stop or cancel the current task first."))
                    ChannelManager.sendMessage(
                        channel,
                        "Queue is full — one task can wait at a time. Stop or cancel the current task first.",
                        messageID
                    )
                }
                return
            }
        }

        ForegroundService.updateTaskStatus(appContext, "Preparing task...")

        // Tier 1: Deterministic routing
        val route = pipelineRouter.route(task)

        // P1.1d: emit tier event right at the routing decision, adjacent to any Tier1Telemetry
        // calls so the two never drift. Closed vocab: "tier1"/"tier2"/"tier3", route values are
        // the specific action/tool name (tier1), skill id (tier2), or "agent-loop" (tier3).
        val (tier, routeInfo) = when (route) {
            is PipelineRouter.Route.DirectIntent -> "tier1" to (route.intent.action ?: route.description)
            is PipelineRouter.Route.DirectTool -> "tier1" to route.toolName
            is PipelineRouter.Route.Skill -> "tier2" to route.skillId
            is PipelineRouter.Route.Redirect -> "tier2" to route.targetSkillId
            is PipelineRouter.Route.AgentLoop -> "tier3" to "agent-loop"
            is PipelineRouter.Route.Chat -> "tier2" to "chat"
        }
        com.returngift.agent.agent.tracker.ExecutionTracker.recordTier(messageID, tier, routeInfo)

        when (route) {
            is PipelineRouter.Route.DirectIntent -> {
                XLog.i(TAG, "Pipeline Tier 1: DirectIntent — ${route.description}")
                // FIX 7: executeIntent now reports launch failure instead of swallowing it.
                val launched = pipelineRouter.executeIntent(route.intent)
                if (launched) {
                    XLog.i(TAG, "onComplete: rounds=0, totalTokens=0, model=direct, answer=${route.description}")
                    terminal(messageID, ok = true, TaskEvent.Completed(route.description), "✓ ${route.description}")
                } else {
                    val error = "No app can handle this action"
                    XLog.e(TAG, "Tier 1 intent failed: ${route.intent.action} — $error")
                    terminal(messageID, ok = false, TaskEvent.Failed(error), "✗ ${route.description}: $error")
                }
                return
            }
            is PipelineRouter.Route.DirectTool -> {
                XLog.i(TAG, "Pipeline Tier 1: DirectTool — ${route.toolName}")
                supervisorScope.launch {
                    // FIX 9: a stop arriving while this coroutine is starting must suppress the
                    // tool call (no per-tool mid-execution hooks; tools have their own
                    // settle/timeout logic).
                    if (taskSessionStore.snapshot().stopRequested) {
                        XLog.i(TAG, "DirectTool ${route.toolName} suppressed by stop request before execution")
                        terminal(messageID, ok = false, TaskEvent.Cancelled, "Task cancelled")
                        return@launch
                    }
                    // FIX 10: a hung tool must not hold the session lock. The invocation is
                    // bounded by the shared wall-clock guard; on timeout the future is
                    // cancelled and the abandonment is reported as a typed Failed event.
                    // FIX 12: every terminal path CAS-releases once and only the owner
                    // reports — a racing cancel that already released the session makes
                    // this whole terminal block a no-op (no Completed/Failed, no message,
                    // no onTaskFinished) so we never double-report.
                    var outcomeError: String? = null
                    val bounded = BoundedExecution.runBounded(
                        wallClockMs = directToolTimeoutMs,
                    ) {
                        // D3: send_message is irreversible with no system confirmation surface,
                        // so the Tier-1 path must confirm BEFORE executing. The confirm itself is
                        // inside the bound so a stuck confirmation cannot hold the session lock.
                        if (route.toolName == "send_message" && !confirmSendMessage()) {
                            XLog.i(TAG, "Tier-1 send_message declined or timed out (5s auto-cancel) — not sending")
                            // FP proxy (spec §8): a fired Tier-1 action corrected before execution.
                            Tier1Telemetry.recordFalsePositive("send_message")
                            ToolResult.error("Send cancelled")
                        } else {
                            pipelineRouter.executeTool(route.toolName, route.params)
                        }
                    }
                    when (bounded) {
                        is BoundedExecution.Outcome.TimedOut ->
                            outcomeError = "Tier-1 tool timed out after ${directToolTimeoutMs}ms"
                        is BoundedExecution.Outcome.Failed -> {
                            XLog.e(TAG, "Tier 1 tool crashed: ${route.toolName}", bounded.error)
                            outcomeError = bounded.error.message ?: "Unknown error"
                        }
                        is BoundedExecution.Outcome.Completed -> {
                            val toolResult = bounded.value
                            if (!toolResult.isSuccess) {
                                // FIX 8: a failure is a typed Failed event, never a Completed
                                // carrying failure text — the UI matches Completed as success.
                                XLog.w(TAG, "Tier 1 tool failed: ${toolResult.error}")
                                outcomeError = toolResult.error ?: "Unknown error"
                            }
                        }
                    }
                    if (outcomeError != null) {
                        terminal(messageID, ok = false, TaskEvent.Failed(outcomeError), "✗ ${route.description}: $outcomeError")
                    } else {
                        terminal(messageID, ok = true, TaskEvent.Completed(route.description), "✓ ${route.description}")
                    }
                }
                return
            }
            is PipelineRouter.Route.Skill -> {
                if (isFallback) {
                    XLog.i(TAG, "Skipping skill route on fallback, going to agent loop: ${route.skillId}")
                } else {
                    XLog.i(TAG, "Pipeline Tier 2: Skill — ${route.skillId}")
                    val skill = SkillRegistry.findById(route.skillId)
                    if (skill != null) {
                        FloatingCircleManager.ensureShowing()
                        FloatingCircleManager.showTaskNotify(task, channel)
                        supervisorScope.launch {
                            val skillResult = skillExecutor.execute(
                                skill = skill,
                                params = route.params,
                                taskText = task,
                                routeUsed = "pipeline-tier2",
                                // FIX 9: cancellation is observed between steps via the
                                // session's stopRequested flag (set by cancelCurrentTask).
                                stopRequested = { taskSessionStore.snapshot().stopRequested },
                                onProgress = { step, total, desc ->
                                    taskEventCallback?.invoke(TaskEvent.Progress(step, "Step $step/$total: $desc"))
                                    ForegroundService.updateTaskStatus(appContext, desc)
                                }
                            )
                            if (skillResult.success) {
                                // P1.1e: clear any parked checkpoint for this task on success
                                com.returngift.agent.agent.checkpoint.TaskCheckpointStore.clearIfTaskMatches(task)
                                terminal(messageID, ok = true, TaskEvent.Completed(skillResult.message), skillResult.message)
                            } else if (skillResult.cancelled) {
                                // FIX 9: a cancelled skill must NOT respawn the agent loop.
                                XLog.i(TAG, "Skill ${skill.id} cancelled, not falling back to agent loop")
                                terminal(messageID, ok = false, TaskEvent.Cancelled, "Task cancelled")
                            } else if (skillResult.timedOut) {
                                // C3: a timed-out skill never falls back to the agent loop —
                                // the agent would re-execute the same tool sequence the wall
                                // clock already gave up on. Report a typed Failed.
                                XLog.w(TAG, "Skill ${skill.id} timed out, not falling back to agent loop")
                                terminal(
                                    messageID,
                                    ok = false,
                                    TaskEvent.Failed("Skill '${skill.name}' timed out"),
                                    "✗ ${skill.name}: timed out",
                                )
                            } else {
                                // P1.1e: write a checkpoint for a killed skill so the RESUME card
                                // can re-inject completed steps and the user can resume later.
                                val doneDesc = skill.steps.take(skillResult.stepsUsed).joinToString("\n") {
                                    "    done: ${it.description}"
                                }
                                val errorMsg = skillResult.errorMessage ?: "unknown failure"
                                val checkpointSteps = if (doneDesc.isNotEmpty()) {
                                    doneDesc.lines() + "FAILED at next step: $errorMsg"
                                } else {
                                    listOf("FAILED at next step: $errorMsg")
                                }
                                com.returngift.agent.agent.checkpoint.TaskCheckpointStore.write(
                                    taskText = task,
                                    steps = checkpointSteps,
                                )
                                val fallbackGoal = skill.fallbackGoal
                                    .let { v -> route.params.entries.fold(v) { acc, (k, v2) -> acc.replace("{$k}", v2) } }
                                XLog.i(TAG, "Skill ${skill.id} failed, falling back to agent loop: $fallbackGoal")
                                taskEventCallback?.invoke(TaskEvent.Progress(0, "Retrying with AI agent"))
                                // C3: typed escalation — pass the completed steps and the
                                // failure context via the existing agentPromptOverride seam
                                // so the LLM does not re-execute steps that already ran.
                                val override = buildString {
                                    append("GOAL: ").append(fallbackGoal).append('\n')
                                    append("RESUMING FROM SKILL '").append(skill.id)
                                    append("' — do NOT repeat completed steps:\n")
                                    append(doneDesc.ifEmpty { "    (no completed steps)" }).append('\n')
                                    append("FAILED at next step: ").append(errorMsg).append('\n')
                                    append("Continue from there.")
                                }
                                startNewTask(channel, fallbackGoal, messageID, agentPromptOverride = override, isFallback = true)
                            }
                        }
                        return
                    }
                    XLog.w(TAG, "Skill ${route.skillId} not found, falling through to agent loop")
                }
            }
            is PipelineRouter.Route.Redirect -> {
                XLog.i(TAG, "Pipeline Tier 2: Redirect — ${route.targetSkillId} (reason: ${route.reason})")
                // Recursively route to the redirected skill
                val skill = SkillRegistry.findById(route.targetSkillId)
                if (skill != null) {
                    FloatingCircleManager.ensureShowing()
                    FloatingCircleManager.showTaskNotify(task, channel)
                    supervisorScope.launch {
                        val skillResult = skillExecutor.execute(
                            skill = skill,
                            params = emptyMap(),
                            taskText = task,
                            routeUsed = "pipeline-tier2-redirect",
                            stopRequested = { taskSessionStore.snapshot().stopRequested },
                            onProgress = { step, total, desc ->
                                taskEventCallback?.invoke(TaskEvent.Progress(step, "Step $step/$total: $desc"))
                                ForegroundService.updateTaskStatus(appContext, desc)
                            }
                        )
                        if (skillResult.success) {
                            // P1.1e: clear any parked checkpoint for this task on success
                            com.returngift.agent.agent.checkpoint.TaskCheckpointStore.clearIfTaskMatches(task)
                            terminal(messageID, ok = true, TaskEvent.Completed(skillResult.message), skillResult.message)
                        } else if (skillResult.cancelled) {
                            XLog.i(TAG, "Redirected skill ${skill.id} cancelled, not falling back to agent loop")
                            terminal(messageID, ok = false, TaskEvent.Cancelled, "Task cancelled")
                        } else {
                            // P1.1e: write a checkpoint for a killed redirected skill.
                            val doneDesc = skill.steps.take(skillResult.stepsUsed).joinToString("\n") {
                                "    done: ${it.description}"
                            }
                            val errorMsg = skillResult.errorMessage ?: "unknown failure"
                            val checkpointSteps = if (doneDesc.isNotEmpty()) {
                                doneDesc.lines() + "FAILED at next step: $errorMsg"
                            } else {
                                listOf("FAILED at next step: $errorMsg")
                            }
                            com.returngift.agent.agent.checkpoint.TaskCheckpointStore.write(
                                taskText = task,
                                steps = checkpointSteps,
                            )
                            XLog.i(TAG, "Redirected skill ${skill.id} failed, falling back to agent loop")
                            // C3: typed escalation for redirected skill failures too
                            val fallbackGoal = skill.fallbackGoal
                                .let { v -> route.params.entries.fold(v) { acc, (k, v2) -> acc.replace("{$k}", v2) } }
                            taskEventCallback?.invoke(TaskEvent.Progress(0, "Retrying with AI agent"))
                            val override = buildString {
                                append("GOAL: ").append(fallbackGoal).append('\n')
                                append("RESUMING FROM SKILL '").append(skill.id)
                                append("' — do NOT repeat completed steps:\n")
                                append(doneDesc.ifEmpty { "    (no completed steps)" }).append('\n')
                                append("FAILED at next step: ").append(errorMsg).append('\n')
                                append("Continue from there.")
                            }
                            startNewTask(channel, fallbackGoal, messageID, agentPromptOverride = override, isFallback = true)
                        }
                    }
                    return
                }
                XLog.w(TAG, "Redirected skill ${route.targetSkillId} not found, falling through to agent loop")
            }
            is PipelineRouter.Route.Chat, is PipelineRouter.Route.AgentLoop -> {
                // Fall through to agent loop
            }
        }

        if (!::agentService.isInitialized) {
            XLog.e(TAG, "AgentService not initialized, attempting to initialize")
            try {
                agentService = AgentServiceFactory.create()
                agentService.initialize(agentConfigProvider())
            } catch (e: Exception) {
                XLog.e(TAG, "Failed to initialize AgentService", e)
                if (casRelease(messageID) != null) {
                    ForegroundService.resetToIdle(appContext)
                    taskEventCallback?.invoke(TaskEvent.Failed("AI service not ready"))
                    ChannelManager.sendMessage(channel, appContext.getString(R.string.channel_msg_service_not_ready), messageID)
                    onTaskFinished()
                }
                return
            }
        }

        // Per-round message buffer for channel messaging
        val roundBuffer = StringBuilder()
        fun flushRoundBuffer() {
            if (roundBuffer.isNotEmpty()) {
                ChannelManager.sendMessage(channel, roundBuffer.toString().trim(), messageID)
                roundBuffer.clear()
            }
        }

        var floatingShown = false

        val agentPrompt = agentPromptOverride?.takeIf { it.isNotBlank() } ?: task
        // If executeTask throws before the callback is wired (config error, rejected
        // executor, …), the UI must still receive a terminal event — otherwise the chat
        // FAB stays stuck in the generating state.
        try {
            agentService.executeTask(agentPrompt, messageID, object : AgentCallback {
            /** Set by onTerminalOutcome (fires before the deferred terminal callback). */
            private var terminalOutcome = com.returngift.agent.agent.TerminalOutcome.COMPLETED

            override fun onLoopStart(round: Int) {
                flushRoundBuffer()
                XLog.d(TAG, "onLoopStart: round=$round")
                if (round > 1) {
                    FloatingCircleManager.ensureShowing()
                    FloatingCircleManager.setRunningState(round, channel)
                    taskEventCallback?.invoke(TaskEvent.LoopStart(round))
                    if (ForegroundService.isRunning()) {
                        ForegroundService.updateTaskStatus(appContext, "Step $round")
                    }
                }
            }

            override fun onTokenUpdate(status: com.returngift.agent.agent.TokenMonitor.Status) {
                FloatingCircleManager.updateTokenStatus(
                    step = status.step,
                    formattedTokens = status.formattedTokens,
                    formattedCost = status.formattedCost,
                    tokenState = status.state
                )
                taskEventCallback?.invoke(TaskEvent.TokenUpdate(
                    step = status.step,
                    formattedTokens = status.formattedTokens,
                    formattedCost = status.formattedCost,
                    tokenState = status.state
                ))
            }

            override fun onContent(round: Int, content: String) {
                if (content.isNotEmpty()) {
                    roundBuffer.append(content)
                    taskEventCallback?.invoke(TaskEvent.Thinking(content))
                }
            }

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {
                XLog.d(TAG, "onToolCall: $toolId($toolName), $parameters")
                // Don't show floating circle for finish tool (it's just completion, not a real action)
                val isFinish = toolName == "finish" || toolId == "finish"
                if (!floatingShown && !isFinish) {
                    floatingShown = true
                    FloatingCircleManager.ensureShowing()
                    FloatingCircleManager.showTaskNotify(task, channel)
                    ForegroundService.updateTaskStatus(appContext, "Running task...")
                }
                if (toolName.isNotEmpty()) {
                    val displayName = com.returngift.agent.tool.ToolRegistry.getInstance().getDisplayName(toolName)
                    taskEventCallback?.invoke(TaskEvent.ToolAction(displayName))
                    ForegroundService.updateTaskStatus(appContext, "$displayName...")
                }
            }

            override fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult) {
                val app = appContext
                val success = result.isSuccess
                var data = if (success) result.data else result.error
                if (data != null && data.length > 300) data = data.substring(0, 300) + "..."
                if (!success) XLog.e(TAG, "Tool failed: $toolName $data")

                val displayName = com.returngift.agent.tool.ToolRegistry.getInstance().getDisplayName(toolName)
                taskEventCallback?.invoke(TaskEvent.ToolResult(displayName, success, data ?: ""))

                // Surface vault artifacts in chat so saved work (plans/notes) is visible,
                // not just claimed in the final summary. Use toolId — the loop passes the
                // REAL tool name there; toolName is the localized display name, which would
                // break the toolName match for save_file/take_screenshot in non-EN locales.
                kbArtifactPath(toolId, result)?.let { path ->
                    taskEventCallback?.invoke(TaskEvent.ArtifactSaved(path))
                }

                if (toolId == "finish" && result.data?.isNotEmpty() == true) {
                    flushRoundBuffer()
                    ChannelManager.sendMessage(channel, result.data, messageID)
                } else {
                    if (roundBuffer.isNotEmpty()) roundBuffer.append("\n")
                    roundBuffer.append(app.getString(R.string.channel_msg_tool_execution, toolName + parameters,
                        if (success) app.getString(R.string.channel_msg_tool_success) else app.getString(R.string.channel_msg_tool_failure)))
                }
            }

            override fun onTargetForegroundVerified(packageName: String) {
                XLog.i(TAG, "Target app verified in foreground: $packageName")
                taskEventCallback?.invoke(TaskEvent.TargetForegroundVerified(packageName))
            }

            override fun onTerminalOutcome(outcome: com.returngift.agent.agent.TerminalOutcome) {
                terminalOutcome = outcome
            }

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int, modelName: String?) {
                XLog.i(TAG, "onComplete: rounds=$round, totalTokens=$totalTokens, model=$modelName, answer=$finalAnswer")
                // Cancellation is detected from the typed terminal outcome recorded by
                // DefaultAgentService's cancel flag — never by string-matching the answer.
                if (terminalOutcome == com.returngift.agent.agent.TerminalOutcome.CANCELLED) {
                    flushRoundBuffer()
                    val cancelledSession = casRelease(currentMessageId()) ?: let {
                        XLog.d(TAG, "onComplete/cancel: session already released, skipping terminal cleanup")
                        return
                    }
                    taskEventCallback?.invoke(TaskEvent.Cancelled)
                    ForegroundService.resetToIdle(appContext)
                    if (cancelledSession.channel != null && cancelledSession.messageId.isNotEmpty()) {
                        ChannelManager.sendMessage(
                            cancelledSession.channel,
                            appContext.getString(R.string.channel_msg_task_cancelled),
                            cancelledSession.messageId
                        )
                        ChannelManager.flushMessages(cancelledSession.channel)
                    }
                    FloatingCircleManager.setErrorState()
                    onTaskFinished()
                    XLog.d(TAG, "Current task cancelled by user")
                    return
                }
                // Strip common LLM-added prefixes from the answer
                var answer = finalAnswer.ifEmpty { "Done." }
                answer = answer.removePrefix("Task completed:").removePrefix("Task completed").trim()
                if (answer.isEmpty()) answer = "Done."
                flushRoundBuffer()
                val completedSession = casRelease(currentMessageId()) ?: let {
                    XLog.d(TAG, "onComplete: session already released, skipping terminal cleanup")
                    return
                }
                taskEventCallback?.invoke(TaskEvent.Completed(answer, modelName))
                ForegroundService.resetToIdle(appContext)
                ChannelManager.flushMessages(completedSession.channel ?: channel)
                FloatingCircleManager.setSuccessState()
                // Auto-return to ReturnGift after in-app task completes
                if (completedSession.autoReturnToChat) {
                    XLog.i(TAG, "onComplete: auto-returning to ReturnGift chatroom")
                    try {
                        val context = appContext
                        val intent = android.content.Intent(context, com.returngift.agent.ui.chat.ComposeChatActivity::class.java).apply {
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        XLog.w(TAG, "onComplete: auto-return failed", e)
                    }
                }
                onTaskFinished()
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                XLog.e(TAG, "onError: ${error.message}, totalTokens=$totalTokens", error)
                flushRoundBuffer()
                val failedSession = casRelease(currentMessageId()) ?: let {
                    XLog.d(TAG, "onError: session already released, skipping terminal cleanup")
                    return
                }
                taskEventCallback?.invoke(TaskEvent.Failed(error.message ?: "Unknown error"))
                ForegroundService.resetToIdle(appContext)
                val failedChannel = failedSession.channel ?: channel
                val failedMessageId = failedSession.messageId.ifEmpty { messageID }
                ChannelManager.sendMessage(
                    failedChannel,
                    appContext.getString(R.string.channel_msg_task_error, error.message),
                    failedMessageId
                )
                ChannelManager.flushMessages(failedChannel)
                FloatingCircleManager.setErrorState()
                onTaskFinished()
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                XLog.w(TAG, "onSystemDialogBlocked: round=$round, totalTokens=$totalTokens")
                flushRoundBuffer()
                val blockedSession = casRelease(currentMessageId()) ?: let {
                    XLog.d(TAG, "onSystemDialogBlocked: session already released, skipping terminal cleanup")
                    return
                }
                taskEventCallback?.invoke(TaskEvent.Blocked)
                val blockedChannel = blockedSession.channel ?: channel
                val blockedMessageId = blockedSession.messageId.ifEmpty { messageID }
                ChannelManager.sendMessage(
                    blockedChannel,
                    appContext.getString(R.string.channel_msg_system_dialog_blocked),
                    blockedMessageId
                )
                try {
                    val service = ClawAccessibilityService.getInstance()
                    val bitmap = service?.takeScreenshot(5000)
                    if (bitmap != null) {
                        val stream = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, stream)
                        bitmap.recycle()
                        ChannelManager.sendImage(blockedChannel, stream.toByteArray(), blockedMessageId)
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to send screenshot for system dialog", e)
                }
                FloatingCircleManager.setErrorState()
                onTaskFinished()
            }
        })
        } catch (e: Exception) {
            XLog.e(TAG, "startNewTask: executeTask threw before terminal callbacks could fire", e)
            if (casRelease(messageID) != null) {
                taskEventCallback?.invoke(TaskEvent.Failed(e.message ?: "Task could not be started"))
                ChannelManager.sendMessage(
                    channel,
                    appContext.getString(R.string.channel_msg_task_error, e.message),
                    messageID
                )
                ForegroundService.resetToIdle(appContext)
                FloatingCircleManager.setErrorState()
                onTaskFinished()
            }
        }
    }

    /**
     * Extract the vault-relative path from a successful knowledge-base write tool result,
     * or null when the tool did not persist an artifact. web_fetch(save_to_vault=true)
     * counts as a persisted artifact too (its result carries "Saved to vault: <path>").
     */
    private fun kbArtifactPath(toolName: String, result: ToolResult): String? {
        if (!result.isSuccess) return null
        return com.returngift.agent.agent.artifact.ArtifactContract.extractKbPath(toolName, result.data)
            ?: com.returngift.agent.agent.artifact.ArtifactContract.extractWebFetchPath(toolName, result.data)
    }
}
