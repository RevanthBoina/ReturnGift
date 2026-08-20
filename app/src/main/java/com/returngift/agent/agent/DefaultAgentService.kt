// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.returngift.agent.ClawApplication
import com.returngift.agent.R
import com.returngift.agent.agent.langchain.LangChain4jToolBridge
import com.returngift.agent.agent.llm.LlmClient
import com.returngift.agent.agent.llm.LlmClientFactory
import com.returngift.agent.agent.llm.LlmResponse
import com.returngift.agent.agent.llm.StreamingListener
import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.tool.ToolRegistry
import com.returngift.agent.tool.impl.GetScreenInfoTool
import com.returngift.agent.tool.ToolResult
import com.returngift.agent.agent.InterruptDetector
import com.returngift.agent.agent.AllowListToolGate
import com.returngift.agent.agent.UndoManager
import com.returngift.agent.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.agent.tool.ToolExecutionRequest
import com.returngift.agent.agent.memory.SharedKnowledgeStore
import com.returngift.agent.agent.memory.LearnedProcedureStore
import com.returngift.agent.agent.tracker.ExecutionTracker
import com.returngift.agent.agent.session.AppSessionManager
import com.returngift.agent.agent.loop.ObservationPolicy
import com.returngift.agent.agent.loop.ActionVerifier
import com.returngift.agent.agent.loop.InteractionWatchdog
import java.io.File
import java.util.LinkedList
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DefaultAgentService : AgentService {

    companion object {
        private const val TAG = "AgentService"
        private val GSON = Gson()

        /**
         * Optimized system prompt for on-device LLM (Gemma / LiteRT-LM).
         * Structured with Anthropic & Cursor principles:
         * - Ultra-compact token footprint (<450 tokens)
         * - Direct action mapping & deterministic execution guide
         * - Task-scoped privacy mediation awareness
         */
        private const val LOCAL_TASK_PROMPT = """You are a phone assistant controlling an Android phone using tools. Complete the user's task accurately.

## Observe -> Decide -> Act -> Verify Protocol
1. Observe: Call get_screen_info to inspect the active foreground UI (or use attached screen).
2. Decide: Choose the most direct tool for the next step. If in a predictable flow (e.g. typing or clipboard), execute confidently.
3. Act: Call the tool.
4. Verify: Confirm the screen transitioned as expected.
5. Finish: When goal is achieved, call finish(summary="concrete data or outcome").

## Tool selection guide
- Open app (verified foreground) → open_app(package_name="com.example.app"). Returns a verified failure if the app does not reach the foreground — do NOT assume it opened on success alone.
- Switch to a running app (verified) → switch_app(package_name="com.example.app"). Handles Recents overlays.
- Check which app is foreground → get_foreground_app()
- Tap UI element → PREFER semantic: tap_node(text="Send") or tap_node(content_desc="Cancel") or tap_node(resource_id="com.app:id/btn"). Legacy node_id="n3" is re-grounded live but may be stale after a transition.
- Type text (focus verified) → input_text(text="hello") or input_text(text="hello", node_id="n5")
- System navigation → system_key(key="back"|"home"|"enter")
- Search list/page → scroll_to_find(text="Settings")
- Messaging → send_message(contact="Mom", message="hi", app="WhatsApp")
- Phone call → make_call(contact="Mom")
- Device metrics → get_device_info(category="battery"|"wifi"|"storage"|"bluetooth"|"screen")
- Notifications → get_notifications()
- Clipboard → clipboard(action="get"|"set", text="...")
- List apps → get_installed_apps()
- Settle screen → wait(duration_ms=2000)

## Core Execution Rules
- finish(summary) MUST contain the REAL DATA requested (e.g. "Battery is at 73%", not "I checked battery").
- Use direct tools (get_device_info, get_notifications, get_installed_apps, get_foreground_app) for device/system queries instead of opening Settings.
- Use input_text to type directly; do not tap autocomplete suggestions.
- Reuse existing browser tabs, authenticated sessions, and opened apps; use switch_app to resume a running app rather than open_app.
- If image generation or download encounters temporary network lag, retry within the same existing session.
- ReturnGift operates strictly in the background during automation; always inspect the active target foreground app.
- After open_app/switch_app/system_key, trust the foreground-verification note in the tool result — do not proceed if it says the target is not foreground.
- Do not re-cache node IDs ("n3") across UI transitions; re-resolve by text/content_desc/resource_id, or call get_screen_info again.
- If you see a [System Notice]/[System Warning] about ineffective actions or a recovery, change your approach — do not repeat the same action.
- Deliverables: you can only save Markdown notes via kb_write — you cannot create PDFs or binary files, never claim you did. When you save a note, name the exact vault path in finish(summary).
- Privacy & Safety: Do NOT interact with payment, checkout, UPI PIN, or CVV screens. If encountered, immediately call finish(summary="Payment required; please complete manually")."""

        /** Maximum number of retries on LLM API call failure */
        private const val MAX_API_RETRIES = 3
        /** Dead-loop detection: sliding window size */
        private const val LOOP_DETECT_WINDOW = 4

        /**
         * Opt-3: Action tools — after any of these execute we auto-attach a fresh
         * get_screen_info result so the LLM can see the updated UI without spending
         * an extra inference round (5 s) to call it manually.
         */
        private val ACTION_TOOLS = setOf(
            "phone_click_node", "phone_tap", "phone_swipe", "phone_long_press",
            "tap", "long_press", "swipe", "scroll_to_find",
            "input_text", "type_text", "system_key", "open_app", "switch_app",
            "dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center",
            "volume_up", "volume_down", "press_menu", "press_power",
            "clipboard", "send_file", "wait"
        )
        /** ms to wait for UI to settle before capturing screen after an action */
        private const val SCREEN_SETTLE_MS = 500L

        /** Whether to write raw network request/response data to sandbox cache files for debugging */
        @JvmField
        var FILE_LOGGING_ENABLED = false
        @JvmField
        var FILE_LOGGING_CACHE_DIR: File? = null
    }

    private lateinit var config: AgentConfig
    private lateinit var llmClient: LlmClient
    private lateinit var toolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>
    private var executor: ExecutorService? = null
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var taskFuture: java.util.concurrent.Future<*>? = null

    override fun initialize(config: AgentConfig) {
        this.config = config
        this.llmClient = LlmClientFactory.create(config)
        this.toolSpecs = LangChain4jToolBridge.buildToolSpecifications()
        this.executor = Executors.newSingleThreadExecutor()
        XLog.i(TAG, "Agent initialized: provider=${config.provider}, model=${config.modelName}, streaming=${config.streaming}")
    }

    override fun updateConfig(config: AgentConfig) {
        if (running.get()) {
            cancel()
            XLog.w(TAG, "Task was running during config update, cancelled")
        }
        executor?.shutdownNow()
        // Close old LlmClient before reinitializing to free engine memory
        if (::llmClient.isInitialized) {
            try {
                llmClient.close()
                XLog.i(TAG, "Old LlmClient closed before config update")
            } catch (e: Exception) {
                XLog.w(TAG, "Old LlmClient close error during config update", e)
            }
        }
        initialize(config)
        XLog.i(TAG, "Agent config updated, new model: ${config.modelName}")
    }

    override fun executeTask(userPrompt: String, callback: AgentCallback) {
        if (running.get()) {
            callback.onError(0, IllegalStateException("Agent is already running a task"), 0)
            return
        }

        running.set(true)
        cancelled.set(false)
        var terminalCallback: (() -> Unit)? = null

        val callbackProxy = object : AgentCallback {
            override fun onLoopStart(round: Int) = callback.onLoopStart(round)

            override fun onContent(round: Int, content: String) = callback.onContent(round, content)

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {
                callback.onToolCall(round, toolId, toolName, parameters)
            }

            override fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult) {
                callback.onToolResult(round, toolId, toolName, parameters, result)
            }

            override fun onTokenUpdate(status: TokenMonitor.Status) = callback.onTokenUpdate(status)

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int, modelName: String?) {
                terminalCallback = { callback.onComplete(round, finalAnswer, totalTokens, modelName) }
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                terminalCallback = { callback.onError(round, error, totalTokens) }
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                terminalCallback = { callback.onSystemDialogBlocked(round, totalTokens) }
            }
        }

        taskFuture = executor?.submit {
            try {
                runAgentLoop(userPrompt, callbackProxy)
            } catch (e: Exception) {
                if (terminalCallback == null) {
                    if (cancelled.get()) {
                        XLog.i(TAG, "Agent task cancelled (interrupted)")
                        terminalCallback = {
                            callback.onComplete(0, ClawApplication.instance.getString(R.string.agent_task_cancel), 0)
                        }
                    } else {
                        XLog.e(TAG, "Agent execution error", e)
                        terminalCallback = { callback.onError(0, e, 0) }
                    }
                }
            } finally {
                // Close local engine BEFORE clearing running flag so the chat engine
                // reload (triggered by onComplete/onError) never overlaps with task engine.
                if (::llmClient.isInitialized) {
                    try {
                        llmClient.close()
                        XLog.i(TAG, "LlmClient closed after task completion")
                    } catch (e: Exception) {
                        XLog.w(TAG, "LlmClient close error after task", e)
                    }
                }
                com.returngift.agent.agent.grounding.VisionInteractionMediator.clearTask()
                running.set(false)
                val terminal = terminalCallback
                terminalCallback = null
                terminal?.invoke()
            }
        }
    }

    // ==================== Pre-flight Check ====================

    private fun preCheck(): String? {
        if (ClawAccessibilityService.getInstance() == null) {
            return ClawApplication.instance.getString(R.string.agent_accessibility_not_enabled)
        }
        return null
    }

    // ==================== Device Context ====================

    private fun buildDeviceContext(): String {
        val app = ClawApplication.instance
        val sb = StringBuilder()
        sb.append("\n\n## Device Info\n")
        sb.append("- Brand: ").append(Build.BRAND).append("\n")
        sb.append("- Model: ").append(Build.MODEL).append("\n")
        sb.append("- Android Version: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")

        try {
            val wm = app
                .getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            sb.append("- Screen Resolution: ").append(dm.widthPixels).append("x").append(dm.heightPixels).append("\n")
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to get display metrics", e)
        }

        sb.append("- Registered Tools: ").append(ToolRegistry.getAllTools().size).append("\n")

        val appName = try {
            val appInfo = app.packageManager.getApplicationInfo(app.packageName, 0)
            app.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) { "ReturnGift" }
        sb.append("\n## This App Info\n")
        sb.append("- App Name: ").append(appName).append("\n")
        sb.append("- Package Name: ").append(app.packageName).append("\n")
        sb.append("- When the user refers to 'this app' or 'the app', they mean the app above.\n")

        return sb.toString()
    }

    // ==================== LLM Call (with retry) ====================

    private fun chatWithRetry(messages: List<ChatMessage>, callback: AgentCallback, iteration: Int): LlmResponse {
        var lastException: Exception? = null
        for (attempt in 0 until MAX_API_RETRIES) {
            if (cancelled.get()) throw RuntimeException(ClawApplication.instance.getString(R.string.agent_task_cancelled))
            try {
                return if (config.streaming) {
                    val textBuilder = StringBuilder()
                    llmClient.chatStreaming(messages, toolSpecs, object : StreamingListener {
                        override fun onPartialText(token: String) {
                            textBuilder.append(token)
                            callback.onContent(iteration, token)
                        }
                        override fun onComplete(response: LlmResponse) {}
                        override fun onError(error: Throwable) {}
                    })
                } else {
                    llmClient.chat(messages, toolSpecs)
                }
            } catch (e: Exception) {
                lastException = e
                val msg = e.message ?: ""
                // Do not retry on token exhaustion or auth failure
                if (msg.contains("401") || msg.contains("403") || msg.contains("insufficient")) {
                    throw e
                }
                val delay = (Math.pow(2.0, attempt.toDouble()) * 1000).toLong()
                XLog.w(TAG, "LLM API call failed (attempt ${attempt + 1}/$MAX_API_RETRIES), retrying in ${delay}ms: $msg")
                try {
                    Thread.sleep(delay)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        throw lastException!!
    }

    // ==================== Dead Loop Detection ====================

    private data class RoundFingerprint(val screenHash: Int, val toolCall: String)

    private fun isStuckInLoop(history: LinkedList<RoundFingerprint>): Boolean {
        if (history.size < LOOP_DETECT_WINDOW) return false
        val first = history.first()
        return history.all { it == first }
    }

    // ==================== Context Compression ====================

    /** Protected zone: keep the most recent N rounds intact */
    private val KEEP_RECENT_ROUNDS = 3

    /** Large-output observation tools → compressed placeholder */
    private val OBSERVATION_PLACEHOLDERS = mapOf(
        "get_screen_info" to "[screen info omitted]",
        "take_screenshot" to "[screenshot result omitted]",
        "get_installed_apps" to "[app list omitted]",
        "scroll_to_find" to "[scroll find result omitted]"
    )

    /**
     * Compress history messages before sending to save input tokens:
     * - get_screen_info: keep only the latest complete result globally
     * - Protected zone (most recent KEEP_RECENT_ROUNDS rounds): keep intact
     * - Outside protected zone: keep AI thinking as-is, compress tool results to a one-line summary
     */
    private fun compressHistoryForSend(messages: MutableList<ChatMessage>) {
        // Count total characters before compression
        val charsBefore = messages.sumOf { msg ->
            when (msg) {
                is AiMessage -> (msg.text()?.length ?: 0) + (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
                is ToolExecutionResultMessage -> msg.text().length
                is UserMessage -> msg.singleText().length
                is SystemMessage -> msg.text().length
                else -> 0
            }
        }
        val msgCountBefore = messages.size

        // 0. Special handling for get_screen_info: regardless of tier, keep only the latest complete result globally
        val screenPlaceholder = OBSERVATION_PLACEHOLDERS["get_screen_info"]!!
        val lastScreenIdx = messages.indexOfLast {
            it is ToolExecutionResultMessage && it.toolName() == "get_screen_info"
        }
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg is ToolExecutionResultMessage
                && msg.toolName() == "get_screen_info"
                && i != lastScreenIdx
                && msg.text() != screenPlaceholder
            ) {
                messages[i] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), screenPlaceholder)
            }
        }

        // 1. Find indices of all AiMessages; each represents one round
        val aiIndices = messages.indices.filter { messages[it] is AiMessage }
        if (aiIndices.size <= KEEP_RECENT_ROUNDS) return

        val totalRounds = aiIndices.size

        for (roundIdx in aiIndices.indices) {
            val roundFromEnd = totalRounds - roundIdx
            if (roundFromEnd <= KEEP_RECENT_ROUNDS) break // protected zone

            val aiIndex = aiIndices[roundIdx]

            // Collect ToolExecutionResultMessage indices for this round
            var j = aiIndex + 1
            while (j < messages.size && messages[j] is ToolExecutionResultMessage) {
                compressToolResultMessage(messages, j)
                j++
            }
        }

        // Count total characters after compression
        val charsAfter = messages.sumOf { msg ->
            when (msg) {
                is AiMessage -> (msg.text()?.length ?: 0) + (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
                is ToolExecutionResultMessage -> msg.text().length
                is UserMessage -> msg.singleText().length
                is SystemMessage -> msg.text().length
                else -> 0
            }
        }
        val saved = charsBefore - charsAfter
        if (saved > 0) {
            XLog.i(TAG, "Context compressed: ${charsBefore}→${charsAfter} chars, saved ${saved} chars (${saved * 100 / charsBefore}%), rounds=${aiIndices.size}")
        }
    }

    /** Compress Tool Result: use placeholder for observation tools, truncate summary for others */
    private fun compressToolResultMessage(messages: MutableList<ChatMessage>, index: Int) {
        val msg = messages[index] as ToolExecutionResultMessage
        val text = msg.text()
        if (text.length <= 100) return // already short enough, no need to compress

        val placeholder = OBSERVATION_PLACEHOLDERS[msg.toolName()]
        if (placeholder != null) {
            messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), placeholder)
            return
        }

        // Other tools: parse JSON to extract a summary
        val compressed = summarizeToolResult(text)
        messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), compressed)
    }

    /** Compress ToolResult JSON into a one-line summary */
    private fun summarizeToolResult(resultJson: String): String {
        return try {
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
            val map: Map<String, Any?> = GSON.fromJson(resultJson, mapType)
            val isSuccess = map["isSuccess"] as? Boolean ?: false
            if (isSuccess) {
                val data = map["data"]?.toString() ?: "ok"
                "✓ " + if (data.length > 80) data.take(80) + "..." else data
            } else {
                val error = map["error"]?.toString() ?: "failed"
                "✗ " + if (error.length > 80) error.take(80) + "..." else error
            }
        } catch (_: Exception) {
            if (resultJson.length > 80) resultJson.take(80) + "..." else resultJson
        }
    }

    // ==================== Main Execution Loop ====================

    private fun runAgentLoop(userPrompt: String, callback: AgentCallback) {
        // Pre-flight check
        preCheck()?.let {
            callback.onError(0, RuntimeException(it), 0)
            return
        }

        val parsedPrompt = TaskPromptEnvelope.parse(userPrompt)
        val rawUserRequest = parsedPrompt.currentRequest

        // Build System Prompt — use optimized prompt for local LLM
        val basePrompt = if (config.provider == LlmProvider.LOCAL) {
            LOCAL_TASK_PROMPT
        } else {
            config.systemPrompt
        }

        val inAppSearchGuard = InAppSearchGuard.fromTask(rawUserRequest)
        val emailComposeGuard = EmailComposeGuard.fromTask(rawUserRequest)
        val directDeviceDataGuard = DirectDeviceDataGuard.fromTask(rawUserRequest)

        // For local LLM, inject matching playbook into system prompt
        val playbookSection = if (config.provider == LlmProvider.LOCAL) {
            val matched = PlaybookManager.match(rawUserRequest)
            if (matched != null) {
                XLog.i(TAG, "Playbook matched: ${matched.id} for '$rawUserRequest'")
                "\n\n## Playbook: ${matched.name}\nFollow these steps exactly:\n\n${matched.body}"
            } else ""
        } else ""

        val taskId = UUID.randomUUID().toString()
        ExecutionTracker.beginTask(taskId, rawUserRequest, "agent_loop")
        com.returngift.agent.agent.grounding.VisionInteractionMediator.initTask(taskId, rawUserRequest)

        val sharedKnowledgeSection = SharedKnowledgeStore.getRelevantContext(rawUserRequest)
        val activeSessionsSection = AppSessionManager.getSessionSummary()
        val learnedProcedure = LearnedProcedureStore.findProcedure(rawUserRequest)
        val learnedProcedureSection = learnedProcedure?.let { LearnedProcedureStore.toProcedurePrompt(it) } ?: ""

        val fullSystemPrompt = buildString {
            append(basePrompt)
            if (sharedKnowledgeSection.isNotEmpty()) append("\n\n").append(sharedKnowledgeSection)
            if (activeSessionsSection.isNotEmpty()) append("\n\n").append(activeSessionsSection)
            if (learnedProcedureSection.isNotEmpty()) append("\n\n").append(learnedProcedureSection)
            append(playbookSection)
            append(inAppSearchGuard.buildPromptSection())
            append(emailComposeGuard.buildPromptSection())
            append(directDeviceDataGuard.buildPromptSection())
            append(buildDeviceContext())
        }

        val messages = mutableListOf<ChatMessage>()
        messages.add(SystemMessage.from(fullSystemPrompt))

        val promptForModel = if (parsedPrompt.hasChatHistory || parsedPrompt.hasBackgroundState) {
            buildString {
                append("You are continuing an existing chatroom. Use the provided context when the current request refers to earlier messages or asks about current background activity.\n\n")
                parsedPrompt.backgroundState?.trim()?.takeIf { it.isNotEmpty() }?.let { state ->
                    append("Current background status:\n")
                    append(state)
                    append("\n\n")
                }
                parsedPrompt.chatHistory?.trim()?.takeIf { it.isNotEmpty() }?.let { history ->
                    append("Chatroom so far:\n")
                    append(history)
                    append("\n\n")
                }
                append("Current user request:\n")
                append(rawUserRequest)
            }
        } else {
            rawUserRequest
        }

        // Opt-2: Pre-warm — only attach screen info for task-like prompts.
        // Chat/questions should NOT see screen data (it confuses the LLM into using tools).
        val lowerPrompt = rawUserRequest.lowercase()
        val looksLikeTask = lowerPrompt.contains("open ") || lowerPrompt.contains("send ") ||
            lowerPrompt.contains("tap ") || lowerPrompt.contains("search ") ||
            lowerPrompt.contains("play ") || lowerPrompt.contains("take ") ||
            lowerPrompt.contains("install ") || lowerPrompt.contains("click ") ||
            lowerPrompt.contains("go to ") || lowerPrompt.contains("navigate ") ||
            lowerPrompt.contains("turn on ") || lowerPrompt.contains("turn off ") ||
            lowerPrompt.contains("monitor ") || lowerPrompt.contains("close ") ||
            lowerPrompt.contains("swipe ") || lowerPrompt.contains("scroll ") ||
            lowerPrompt.contains("check ") || lowerPrompt.contains("compose ") ||
            lowerPrompt.contains("find ") || lowerPrompt.contains("screen") ||
            lowerPrompt.contains("notification") || lowerPrompt.contains("read my") ||
            lowerPrompt.contains("call ") || lowerPrompt.contains("dial ")

        val enrichedPrompt = if (looksLikeTask) {
            try {
                // Interrupt check before pre-warm screen capture
                val prewarmService = ClawAccessibilityService.getInstance()
                if (prewarmService != null) {
                    val prewarmInterrupt = InterruptDetector.inspect(prewarmService)
                    if (prewarmInterrupt is InterruptDetector.InterruptResult.PauseAndConfirm) {
                        XLog.w(TAG, "Pre-warm interrupt PAUSE_AND_CONFIRM: ${prewarmInterrupt.description}")
                        callback.onSystemDialogBlocked(0, 0)
                        return
                    } else if (prewarmInterrupt is InterruptDetector.InterruptResult.AutoDismissed) {
                        XLog.i(TAG, "Pre-warm interrupt AUTO_DISMISSED: ${prewarmInterrupt.description}")
                        try { prewarmService.pressBack() } catch (_: Exception) {}
                        Thread.sleep(SCREEN_SETTLE_MS)
                    }
                }
                val screenTool = ToolRegistry.getInstance().getTool("get_screen_info")
                if (screenTool != null) {
                    val screenResult = screenTool.execute(emptyMap())
                    if (screenResult.isSuccess && !screenResult.data.isNullOrBlank()) {
                        XLog.i(TAG, "runAgentLoop: pre-warm screen attached (${screenResult.data!!.length} chars)")
                        ExecutionTracker.recordObservation(
                            taskId = taskId,
                            stepIndex = 0,
                            screenHash = screenResult.data!!.hashCode().toString(),
                            screenSummary = screenResult.data!!
                        )
                        "$promptForModel\n\nCurrent screen:\n${screenResult.data}"
                    } else promptForModel
                } else promptForModel
            } catch (e: Exception) { promptForModel }
        } else {
            XLog.i(TAG, "runAgentLoop: chat-like prompt, skipping pre-warm screen")
            promptForModel
        }
        messages.add(UserMessage.from(enrichedPrompt))

        var iterations = 0
        var totalTokens = 0
        var actualModelName: String? = null  // Track the real model name from API response
        val maxIterations = config.maxIterations
        val loopHistory = LinkedList<RoundFingerprint>()
        var lastScreenHash = 0
        var previousScreenTexts: Set<String> = emptySet()
        val tokenMonitor = TokenMonitor(config.modelName)
        val stuckDetector = StuckDetector()
        val interactionWatchdog = InteractionWatchdog()
        var currentTargetPackage: String? = null  // target app the task is driving (for relaunch recovery)
        val taskBudget = TaskBudget.fromSettings()
        var softLimitWarned = false
        var consecutiveActionsWithoutObserve = 0
        val isFollowingProcedure = (learnedProcedure != null)
        var consecutiveNoToolCalls = 0

        while (iterations < maxIterations && !cancelled.get()) {
            iterations++
            callback.onLoopStart(iterations)

            // Compress history messages before sending to save tokens
            compressHistoryForSend(messages)

            // LLM call (with retry)
            val llmResponse: LlmResponse
            try {
                llmResponse = chatWithRetry(messages, callback, iterations)
            } catch (e: Exception) {
                XLog.e(TAG, "LLM API call failed after retries", e)
                callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_api_call_failed, e.message)), totalTokens)
                return
            }

            if (cancelled.get()) {
                callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
                return
            }

            // Capture actual model name from first API response
            if (actualModelName == null && !llmResponse.modelName.isNullOrEmpty()) {
                actualModelName = llmResponse.modelName
                XLog.d(TAG, "runAgentLoop: actual model from API = $actualModelName")
            }
            // Accumulate token usage
            llmResponse.tokenUsage?.totalTokenCount()?.let { totalTokens += it }
            tokenMonitor.record(
                step = iterations,
                inputTokens = llmResponse.tokenUsage?.inputTokenCount(),
                outputTokens = llmResponse.tokenUsage?.outputTokenCount(),
                totalTokenCount = llmResponse.tokenUsage?.totalTokenCount()
            )
            callback.onTokenUpdate(tokenMonitor.getStatus())

            // Budget check
            val tokenStatus = tokenMonitor.getStatus()
            when (taskBudget.check(tokenStatus.totalTokens, tokenStatus.estimatedCostUsd)) {
                TaskBudget.Status.HARD_LIMIT -> {
                    XLog.w(TAG, "Budget HARD LIMIT reached at step $iterations: ${tokenStatus.formattedTokens} (${tokenStatus.formattedCost})")
                    callback.onComplete(
                        iterations,
                        "Task stopped: budget limit reached (${tokenStatus.formattedTokens} tokens, ${tokenStatus.formattedCost}). " +
                        "Increase budget in Settings if needed.",
                        totalTokens,
                        actualModelName
                    )
                    return
                }
                TaskBudget.Status.SOFT_LIMIT -> {
                    if (!softLimitWarned) {
                        softLimitWarned = true
                        XLog.i(TAG, "Budget SOFT LIMIT at step $iterations: ${tokenStatus.formattedTokens}")
                        messages.add(UserMessage.from(
                            "[System Notice] You are using ${tokenStatus.formattedTokens} tokens (${tokenStatus.formattedCost}), " +
                            "approaching the budget limit. Finish the task efficiently. " +
                            "If you cannot complete it soon, call finish with a partial summary."
                        ))
                    }
                }
                TaskBudget.Status.OK -> { /* continue normally */ }
            }

            // DEBUG: log raw LLM response for tool calling diagnosis
            XLog.i(TAG, "runAgentLoop iter=$iterations response.text=${llmResponse.text?.take(500)}")
            XLog.i(TAG, "runAgentLoop iter=$iterations hasToolCalls=${llmResponse.hasToolExecutionRequests()} toolCallCount=${llmResponse.toolExecutionRequests?.size ?: 0}")

            // Add AI message to history (must construct AiMessage)
            val aiMessage = if (llmResponse.hasToolExecutionRequests()) {
                if (llmResponse.text.isNullOrEmpty()) {
                    AiMessage.from(llmResponse.toolExecutionRequests)
                } else {
                    AiMessage.from(llmResponse.text, llmResponse.toolExecutionRequests)
                }
            } else {
                AiMessage.from(llmResponse.text ?: "")
            }
            messages.add(aiMessage)
            ExecutionTracker.recordThinking(
                taskId = taskId,
                stepIndex = iterations,
                reasoning = llmResponse.text ?: "Calling ${llmResponse.toolExecutionRequests?.size ?: 0} tool(s)"
            )

            // Push thinking content in non-streaming mode
            if (!config.streaming && !llmResponse.text.isNullOrEmpty()) {
                val suppressHallucinatedCompletion =
                    !llmResponse.hasToolExecutionRequests() &&
                        (inAppSearchGuard.shouldBlockTextOnlyCompletion() ||
                            emailComposeGuard.shouldBlockTextOnlyCompletion())
                if (!suppressHallucinatedCompletion) {
                    callback.onContent(iterations, llmResponse.text)
                }
            }

            // No tool calls in this response — LLM chose to respond with text only.
            // Respect that. If there's text, it's the answer. Done.
            if (!llmResponse.hasToolExecutionRequests()) {
                val responseText = llmResponse.text ?: ""
                if (responseText.isNotEmpty()) {
                    if (inAppSearchGuard.shouldBlockTextOnlyCompletion()) {
                        val correction = inAppSearchGuard.buildCompletionCorrection()
                        XLog.i(TAG, "InAppSearchGuard blocked text-only completion for '$userPrompt'")
                        messages.add(UserMessage.from(correction))
                        continue
                    }
                    if (directDeviceDataGuard.shouldBlockTextOnlyCompletion()) {
                        val correction = directDeviceDataGuard.buildCompletionCorrection()
                        XLog.i(TAG, "DirectDeviceDataGuard blocked text-only completion for '$userPrompt'")
                        messages.add(UserMessage.from(correction))
                        continue
                    }
                    if (emailComposeGuard.shouldBlockTextOnlyCompletion()) {
                        val correction = emailComposeGuard.buildCompletionCorrection()
                        XLog.i(TAG, "EmailComposeGuard blocked text-only completion for '$userPrompt'")
                        messages.add(UserMessage.from(correction))
                        continue
                    }
                    XLog.i(TAG, "runAgentLoop: text-only response, completing")
                    ExecutionTracker.endTask(taskId, "SUCCESS", iterations, totalTokens)
                    SharedKnowledgeStore.remember(SharedKnowledgeStore.Category.TASK_FACT, rawUserRequest, responseText, sourceTask = rawUserRequest)
                    if (learnedProcedure != null) LearnedProcedureStore.recordOutcome(learnedProcedure.id, true)
                    callback.onComplete(iterations, responseText, totalTokens, actualModelName)
                    return
                }
                // Empty response with no tools — something went wrong, finish
                XLog.w(TAG, "runAgentLoop: empty response with no tools, finishing")
                ExecutionTracker.endTask(taskId, "COMPLETED", iterations, totalTokens)
                callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens, actualModelName)
                continue
            }

            // Reset counter when LLM does use tools
            consecutiveNoToolCalls = 0

            // Execute tool calls
            for (toolRequest in llmResponse.toolExecutionRequests) {
                if (cancelled.get()) {
                    ExecutionTracker.endTask(taskId, "CANCELLED", iterations, totalTokens)
                    callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
                    return
                }

                val toolName = toolRequest.name() ?: ""
                val displayName = ToolRegistry.getInstance().getDisplayName(toolName)
                val toolArgs = toolRequest.arguments() ?: "{}"

                // Parse parameters
                val mapType = object : TypeToken<Map<String, Any>>() {}.type
                var params: Map<String, Any>? = try {
                    GSON.fromJson(toolArgs, mapType)
                } catch (e: Exception) {
                    XLog.w(TAG, "Failed to parse tool args for $toolName: $toolArgs", e)
                    HashMap()
                }
                if (params == null) params = HashMap()

                val blockedFinish = if (toolName == "finish") {
                    val screenInfo = try {
                        ToolRegistry.getInstance()
                            .getTool("get_screen_info")
                            ?.execute(emptyMap())
                            ?.takeIf { it.isSuccess }
                            ?.data
                    } catch (_: Exception) {
                        null
                    }
                    directDeviceDataGuard.maybeBlockFinish()
                        ?: inAppSearchGuard.maybeBlockFinish(screenInfo)
                        ?: emailComposeGuard.maybeBlockFinish(screenInfo)
                } else null
                if (blockedFinish != null) {
                    val blockedResult = ToolResult.error(blockedFinish)
                    XLog.i(TAG, "Task guard blocked premature finish for '$userPrompt'")
                    callback.onToolCall(iterations, toolName, displayName, toolArgs)
                    callback.onToolResult(iterations, toolName, displayName, params.toString(), blockedResult)
                    messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(blockedResult)))
                    messages.add(UserMessage.from(blockedFinish))
                    continue
                }

                callback.onToolCall(iterations, toolName, displayName, toolArgs)
                directDeviceDataGuard.recordToolAttempt(toolName)
                emailComposeGuard.recordToolAttempt(toolName)

                // Track app sessions if opening an app, and remember the target package for
                // watchdog relaunch recovery + foreground verification.
                if (toolName == "open_app" || toolName == "switch_app") {
                    params["package_name"]?.toString()?.let {
                        AppSessionManager.trackAppOpen(it)
                        currentTargetPackage = it
                    }
                }

                // ── Part B: allow-list gate ─────────────────────────────────────────
                val allowListBlock = AllowListToolGate.check(
                    ClawApplication.instance, toolName, params
                )
                if (allowListBlock != null) {
                    val blockedResult = ToolResult.error(allowListBlock)
                    callback.onToolResult(iterations, toolName, displayName, params.toString(), blockedResult)
                    messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(blockedResult)))
                    messages.add(UserMessage.from(allowListBlock))
                    continue
                }
                // ── End allow-list gate ─────────────────────────────────────────────

                // Unified control loop: capture the state BEFORE the action so we can verify
                // the action's effect afterwards (observe → resolve → act → verify → recover).
                val a11ySvc = ClawAccessibilityService.getInstance()
                val beforeState = if (a11ySvc != null && toolName in ACTION_TOOLS) {
                    ActionVerifier.captureBefore(a11ySvc, toolName)
                } else null

                val actStartTime = System.currentTimeMillis()
                val result = ToolRegistry.getInstance().executeTool(toolName, params)
                val actLatency = System.currentTimeMillis() - actStartTime
                val paramsString = if (params.isEmpty()) "" else params.toString()

                // Unified control loop: verify the state change and feed the watchdog.
                // This is the programmatic verification the loop previously lacked — the AI
                // no longer continues on an unverified or stale UI state.
                var verification: ActionVerifier.VerificationResult? = null
                var recovery: InteractionWatchdog.Recovery? = null
                var recoveryExecuted: String? = null
                if (beforeState != null && a11ySvc != null) {
                    val expectedFg = if (toolName == "open_app" || toolName == "switch_app")
                        currentTargetPackage else null
                    verification = ActionVerifier.verifyAfter(
                        a11ySvc, beforeState, result.isSuccess, expectedFg
                    )
                    val overlay = a11ySvc.isSystemOverlayLikely()
                    val argsFp = (params["text"]?.toString() ?: params["node_id"]?.toString()
                        ?: params["package_name"]?.toString() ?: params["key"]?.toString() ?: "").take(40)
                    recovery = interactionWatchdog.record(
                        toolName = toolName,
                        argsFingerprint = argsFp,
                        verification = verification,
                        screenHash = (verification.afterSignature ?: "0").hashCode(),
                        expectedForeground = currentTargetPackage,
                        overlayPresent = overlay
                    )
                    if (recovery.strategy != InteractionWatchdog.RecoveryStrategy.NONE) {
                        recoveryExecuted = interactionWatchdog.executeRecovery(
                            recovery.strategy, a11ySvc, currentTargetPackage
                        )
                        XLog.i(TAG, "Watchdog recovery (${recovery.strategy}): $recoveryExecuted")
                        consecutiveActionsWithoutObserve = 0  // force a fresh observation next round
                    }
                }

                ExecutionTracker.recordVerifiedAction(
                    taskId = taskId,
                    stepIndex = iterations,
                    toolName = toolName,
                    params = paramsString,
                    resultSuccess = result.isSuccess,
                    resultSummary = result.data ?: result.error ?: "",
                    latencyMs = actLatency,
                    appPackage = verification?.foregroundPackage,
                    targetResolution = if (beforeState != null) "semantic/state-snapshot" else null,
                    verificationResult = verification?.let { "${it.outcome}: ${it.detail}" },
                    recoveryAction = recoveryExecuted
                )

                callback.onToolResult(iterations, toolName, displayName, paramsString, result)
                if (result.isSuccess) {
                    inAppSearchGuard.recordSuccessfulTool(toolName, params)
                    emailComposeGuard.recordSuccessfulTool(toolName)
                    // ── Part C: record undoable action ──────────────────────────
                    UndoManager.record(toolName, params, displayName)
                    if (UndoManager.hasPending()) {
                        callback.onUndoAvailable(displayName)
                    }
                    // ── End undo record ─────────────────────────────────────────
                }

                // System dialog blocking detected → notify user and stop task
                if (!result.isSuccess && result.error == GetScreenInfoTool.SYSTEM_DIALOG_BLOCKED) {
                    XLog.w(TAG, "System dialog blocked, notifying user and stopping task")
                    ExecutionTracker.recordError(taskId, iterations, "System dialog blocked")
                    callback.onSystemDialogBlocked(iterations, totalTokens)
                    return
                }

                // finish tool → task complete
                if (toolName == "finish" && result.isSuccess) {
                    val finishData = result.data
                    ExecutionTracker.endTask(taskId, "SUCCESS", iterations, totalTokens)
                    ExecutionTracker.getTrajectory(taskId)?.let { LearnedProcedureStore.extractAndStore(it) }
                    if (learnedProcedure != null) LearnedProcedureStore.recordOutcome(learnedProcedure.id, true)
                    SharedKnowledgeStore.remember(
                        SharedKnowledgeStore.Category.TASK_FACT,
                        rawUserRequest,
                        finishData ?: "Task completed",
                        sourceTask = rawUserRequest
                    )
                    // Periodic maintenance
                    ExecutionTracker.pruneOldTrajectories(100)
                    SharedKnowledgeStore.decay()
                    LearnedProcedureStore.prune()

                    callback.onComplete(iterations, finishData ?: ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens, actualModelName)
                    return
                }

                // Opt-3: Adaptive Observe/Act Loop.
                // Decides dynamically whether screen capture is necessary.
                val obsDecision = ObservationPolicy.evaluate(
                    lastTool = toolName,
                    lastSuccess = result.isSuccess,
                    consecutiveActionsWithoutObserve = consecutiveActionsWithoutObserve,
                    isFollowingProcedure = isFollowingProcedure,
                    screenHashChanged = (consecutiveActionsWithoutObserve > 0)
                )

                val combinedResultData: String = if (toolName in ACTION_TOOLS && obsDecision != ObservationPolicy.ObservationDecision.SKIP) {
                    try {
                        consecutiveActionsWithoutObserve = 0
                        Thread.sleep(SCREEN_SETTLE_MS) // let UI animate/settle

                        // ── Interrupt check (Part A) ────────────────────────────────────────
                        val accessibilityService = ClawAccessibilityService.getInstance()
                        if (accessibilityService != null) {
                            val interruptResult = InterruptDetector.inspect(accessibilityService)
                            when (interruptResult) {
                                is InterruptDetector.InterruptResult.AutoDismissed -> {
                                    XLog.i(TAG, "Interrupt AUTO_DISMISSED: ${interruptResult.description}")
                                    try { accessibilityService.pressBack() } catch (_: Exception) {}
                                    Thread.sleep(SCREEN_SETTLE_MS)
                                }
                                is InterruptDetector.InterruptResult.PauseAndConfirm -> {
                                    XLog.w(TAG, "Interrupt PAUSE_AND_CONFIRM: ${interruptResult.description}")
                                    callback.onSystemDialogBlocked(iterations, totalTokens)
                                    return
                                }
                                is InterruptDetector.InterruptResult.Clean -> { /* proceed */ }
                            }
                        }
                        // ── End interrupt check ─────────────────────────────────────────────

                        val screenTool = ToolRegistry.getInstance().getTool("get_screen_info")
                        val screenAfter = screenTool?.execute(emptyMap())
                        if (screenAfter != null && screenAfter.isSuccess && !screenAfter.data.isNullOrBlank()) {
                            // Update lastScreenHash for loop detection
                            lastScreenHash = screenAfter.data!!.hashCode()
                            ExecutionTracker.recordObservation(
                                taskId = taskId,
                                stepIndex = iterations,
                                screenHash = lastScreenHash.toString(),
                                screenSummary = screenAfter.data!!
                            )
                            XLog.i(TAG, "Opt3: auto-attached screen after $toolName (${screenAfter.data!!.length} chars)")
                            // Screen diff: extract text lines and compare with previous
                            val currentTexts = screenAfter.data!!.lines()
                                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                            val added = currentTexts - previousScreenTexts
                            val removed = previousScreenTexts - currentTexts
                            previousScreenTexts = currentTexts
                            val diffSection = buildString {
                                if (added.isNotEmpty()) append("\nNew on screen: ${added.take(10).joinToString(", ")}")
                                if (removed.isNotEmpty()) append("\nGone from screen: ${removed.take(10).joinToString(", ")}")
                            }
                            val baseData = result.data ?: ""
                            val enrichedData = "$baseData\n\nScreen after action:\n${screenAfter.data}$diffSection"
                            val enriched = if (result.isSuccess) ToolResult.success(enrichedData)
                                           else ToolResult.error(result.error ?: "")
                            GSON.toJson(enriched)
                        } else {
                            XLog.w(TAG, "Opt3: get_screen_info failed after $toolName: ${screenAfter?.error}")
                            GSON.toJson(result)
                        }
                    } catch (e: Exception) {
                        XLog.w(TAG, "Opt3: exception fetching screen after $toolName", e)
                        GSON.toJson(result)
                    }
                } else {
                    if (toolName in ACTION_TOOLS) {
                        consecutiveActionsWithoutObserve++
                        XLog.i(TAG, "ObservationPolicy: skipped screen attach for predictable action '$toolName' ($consecutiveActionsWithoutObserve consecutive)")
                    }
                    if (toolName == "get_screen_info" && result.isSuccess && result.data != null) {
                        lastScreenHash = result.data.hashCode()
                    }
                    GSON.toJson(result)
                }

                // For action tools the loop detection hash was already updated above;
                // for non-get_screen_info action tools also record the fingerprint.
                if (toolName in ACTION_TOOLS) {
                    loopHistory.addLast(RoundFingerprint(lastScreenHash, "$toolName:$toolArgs"))
                    if (loopHistory.size > LOOP_DETECT_WINDOW) loopHistory.removeFirst()
                } else if (toolName.isNotEmpty() && toolName != "get_screen_info") {
                    loopHistory.addLast(RoundFingerprint(lastScreenHash, "$toolName:$toolArgs"))
                    if (loopHistory.size > LOOP_DETECT_WINDOW) loopHistory.removeFirst()
                }

                // Add tool result to messages
                messages.add(ToolExecutionResultMessage.from(toolRequest, combinedResultData))
                // Unified control loop: if the watchdog executed a recovery with a model hint,
                // inject it so the model adapts its plan instead of repeating the ineffective action.
                if (recovery != null && recovery.strategy != InteractionWatchdog.RecoveryStrategy.NONE
                    && !recovery.modelHint.isNullOrBlank()) {
                    messages.add(UserMessage.from(recovery.modelHint))
                }
                XLog.d(TAG, "displayName:$displayName toolName:$toolName")
            }

            // Stuck detection (5-signal, 3-level recovery)
            val lastAction = llmResponse.toolExecutionRequests?.firstOrNull()?.let {
                "${it.name()}:${it.arguments()?.take(50)}"
            } ?: ""
            val screenDiffCount = (previousScreenTexts as? Set<*>)?.size ?: 0
            val toolError = llmResponse.toolExecutionRequests?.firstOrNull()?.let { req ->
                val result = ToolRegistry.getInstance().getTool(req.name() ?: "")
                null // error tracked per-tool above; simplified here
            }
            val detection = stuckDetector.record(lastAction, lastScreenHash, screenDiffCount, null)
            if (detection != null) {
                when (detection.level) {
                    StuckDetector.RecoveryLevel.AUTO_KILL -> {
                        XLog.w(TAG, "StuckDetector AUTO_KILL at iteration $iterations: ${detection.signal.description}")
                        val status = tokenMonitor.getStatus()
                        callback.onComplete(
                            iterations,
                            "Task stopped: agent was stuck (${detection.signal.description}). " +
                            "Used ${status.formattedTokens} tokens (${status.formattedCost}).",
                            totalTokens,
                            actualModelName
                        )
                        return
                    }
                    else -> {
                        XLog.w(TAG, "StuckDetector ${detection.level} at iteration $iterations: ${detection.signal.description}")
                        messages.add(UserMessage.from(detection.recoveryHint))
                    }
                }
            }
            XLog.d(TAG, "Round:$iterations total=$totalTokens thisRound=${llmResponse.tokenUsage?.totalTokenCount()}")
        }

        if (cancelled.get()) {
            callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
        } else {
            callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_max_iterations, maxIterations)), totalTokens)
        }
    }

    override fun cancel() {
        cancelled.set(true)
        if (config.provider == LlmProvider.LOCAL) {
            // LiteRT native sendMessage is not interrupt-safe; let the current round yield
            // naturally, then surface Task cancelled after the client closes cleanly.
            XLog.i(TAG, "cancel: LOCAL task marked cancelled; waiting for current LiteRT round to finish safely")
            return
        }
        // Cloud/network-backed tasks can be aborted safely via thread interruption.
        taskFuture?.cancel(true)
        XLog.i(TAG, "cancel: flag set + thread interrupted")
    }

    override fun shutdown() {
        cancel()
        executor?.shutdownNow()
        if (::llmClient.isInitialized) {
            try {
                llmClient.close()
                XLog.i(TAG, "LlmClient closed on shutdown")
            } catch (e: Exception) {
                XLog.w(TAG, "LlmClient close error on shutdown", e)
            }
        }
    }

    override fun isRunning(): Boolean = running.get()
}
