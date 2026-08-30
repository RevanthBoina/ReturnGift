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
import com.returngift.agent.agent.provenance.ProvenanceTag
import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.tool.ToolRegistry
import com.returngift.agent.tool.impl.GetScreenInfoTool
import com.returngift.agent.tool.ToolResult
import com.returngift.agent.agent.InterruptDetector
import com.returngift.agent.agent.AllowListToolGate
import com.returngift.agent.agent.UndoManager
import com.returngift.agent.agent.memory.LearnedProcedureStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.agent.tool.ToolExecutionRequest
import com.returngift.agent.agent.memory.SharedKnowledgeStore
import com.returngift.agent.agent.tracker.ExecutionTracker
import com.returngift.agent.agent.session.AppSessionManager
import com.returngift.agent.agent.loop.ObservationPolicy
import com.returngift.agent.agent.loop.ActionVerifier
import com.returngift.agent.agent.loop.InteractionWatchdog
import com.returngift.agent.agent.loop.ObserveStallGuard
import java.io.File
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
- Tap UI element → resolve semantically first: tap_node(text="Send") / tap_node(content_desc="Cancel") / tap_node(resource_id="com.app:id/btn"); use tap(x, y) at the bounding-box center ONLY as the last resort when no semantic property matches. Act immediately once the target is identified — never re-read an unchanged screen. Legacy node_id="n3" is re-grounded live but may be stale after a transition.
- Type text (focus verified) → input_text(text="hello") or input_text(text="hello", node_id="n5")
- System navigation → system_key(key="back"|"home"|"enter")
- Search list/page → scroll_to_find(text="Settings")
- Messaging → send_message(contact="Mom", message="hi", app="WhatsApp")
- Phone call → make_call(contact="Mom")
- Device metrics → get_device_info(category="battery"|"wifi"|"storage"|"bluetooth"|"screen")
- Notifications → get_notifications()
- Clipboard → clipboard(action="get"|"set", text="...")
- List apps → get_installed_apps()
- Ask the user when unsure → ask_user(question="Which app should I use?", choices="ChatGPT; Claude; Gemini") — waits for the tap/typed answer and returns it
- Query an external AI / generate an image (SUPPORTED — never refuse on capability grounds, e.g. "I can't generate images"; all other safety-scoped rules — payment/credentials, personal-content consent, deliverable honesty — still apply) → drive the installed AI app (ChatGPT, Gemini, Claude, Perplexity, …): open_app its package, input the prompt, read the answer. For generated images use the app's own Download/Save control, then import_download(name_hint="…") to bring the file into the vault. Do NOT screenshot the result unless the app offers no download control.
- Import a downloaded file into the vault → import_download(name_hint="flower") — copies the newest matching file from system Downloads into images/ and returns the vault path
- Fetch a URL/external content → web_fetch(url="https://…", save_to_vault=true) — returns readable text; never invent content you could fetch
- Look something up (no URL given) → web_search(query="…") then web_fetch the best result
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
- Deliverables: Markdown notes go to the vault via kb_write/kb_append; binary files (base64 content) via save_file; screenshots via take_screenshot(save_to_vault=true); files downloaded by other apps (e.g. an AI-generated image) via import_download. You cannot create other formats (PDF, PPT) yourself — never claim you did; images actually obtained through an AI app's download control + import_download ARE real deliverables. When you save anything, name the exact vault path in finish(summary).
- Failed taps: when tap/tap_node/long_press fails, do NOT re-tap the same coordinates — use find_and_tap with the target's visible text or description.
- Ambiguity: if the request lacks a required detail or has multiple valid targets/apps, call ask_user BEFORE acting and wait for the answer. Never guess and complete on a guess. Do not ask when the request is already clear.
- External content: when the task mentions a URL/article, web_fetch it first and answer from the fetched text; to look something up, web_search then web_fetch the best result. Never fabricate content; if search/fetch fails, say so honestly.
- Personal content: reading the user's own emails/messages/photos/files is supported when the user asks for it. A consent question is asked before the first content read — never refuse on privacy grounds after consent is granted. Use ask_user for scope (which label, how many, which item) instead of guessing.
- Privacy & Safety: Do NOT interact with payment, checkout, UPI PIN, or CVV screens. If encountered, immediately call finish(summary="Payment required; please complete manually")."""

        /** Maximum number of retries on LLM API call failure */
        private const val MAX_API_RETRIES = 3

        /**
         * Opt-3: Action tools — after any of these execute we auto-attach a fresh
         * get_screen_info result so the LLM can see the updated UI without spending
         * an extra inference round (5 s) to call it manually.
         */
        private val TAP_LIKE_TOOLS = setOf("tap_node", "tap", "long_press")

        private const val TAP_RECOVERY_HINT =
            "Recovery hint: the target may have moved or need scrolling — retry with " +
            "find_and_tap using the visible text or content description of the target, " +
            "instead of re-tapping the same coordinates or node id."

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
        executeTask(userPrompt, UUID.randomUUID().toString(), callback)
    }

    override fun executeTask(userPrompt: String, taskId: String, callback: AgentCallback) {
        if (running.get()) {
            callback.onError(0, IllegalStateException("Agent is already running a task"), 0)
            return
        }

        running.set(true)
        cancelled.set(false)
        // import_download correlates Downloads-folder files with THIS task run —
        // files added before this moment are stale and must not be imported.
        com.returngift.agent.tool.impl.ImportDownloadTool.taskStartTimestamp = System.currentTimeMillis()
        var terminalCallback: (() -> Unit)? = null
        var terminalOutcome = TerminalOutcome.COMPLETED
        // M3A-style per-step history — the resumable memory persisted as a checkpoint
        // when the task is cancelled (see TaskCheckpointStore).
        val stepHistory = mutableListOf<String>()

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

            override fun onTargetForegroundVerified(packageName: String) = callback.onTargetForegroundVerified(packageName)

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int, modelName: String?) {
                terminalOutcome = TerminalOutcome.COMPLETED
                terminalCallback = { callback.onComplete(round, finalAnswer, totalTokens, modelName) }
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                terminalOutcome = TerminalOutcome.ERROR
                terminalCallback = { callback.onError(round, error, totalTokens) }
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                terminalOutcome = TerminalOutcome.SYSTEM_DIALOG_BLOCKED
                terminalCallback = { callback.onSystemDialogBlocked(round, totalTokens) }
            }
        }

        taskFuture = executor?.submit {
            try {
                runAgentLoop(userPrompt, callbackProxy, stepHistory)
            } catch (e: Exception) {
                if (terminalCallback == null) {
                    if (cancelled.get()) {
                        XLog.i(TAG, "Agent task cancelled (interrupted)")
                        terminalOutcome = TerminalOutcome.CANCELLED
                        terminalCallback = {
                            callback.onComplete(0, ClawApplication.instance.getString(R.string.agent_task_cancel), 0)
                        }
                    } else {
                        XLog.e(TAG, "Agent execution error", e)
                        terminalOutcome = TerminalOutcome.ERROR
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
                if (terminal != null) {
                    // Typed terminal state first — listeners switch on this flag, not on
                    // localized answer strings, to detect user cancellation.
                    val outcome = if (cancelled.get()) TerminalOutcome.CANCELLED else terminalOutcome
                    // Finalizer (Design v2): the single terminal seam — checkpoint the
                    // interrupted task's step history, or retire a stale checkpoint when
                    // the matching task completed cleanly.
                    when (outcome) {
                        // A task that genuinely stopped before completion (user cancel or
                        // loop error) stays resumable; COMPLETED clears the checkpoint; a
                        // system-dialog BLOCK is a terminal state, not an interruption.
                        TerminalOutcome.CANCELLED, TerminalOutcome.ERROR ->
                            com.returngift.agent.agent.checkpoint.TaskCheckpointStore.write(userPrompt, stepHistory)
                        TerminalOutcome.COMPLETED, TerminalOutcome.SYSTEM_DIALOG_BLOCKED ->
                            com.returngift.agent.agent.checkpoint.TaskCheckpointStore.clearIfTaskMatches(userPrompt)
                    }
                    callback.onTerminalOutcome(outcome)
                    terminal.invoke()
                }
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

    private fun chatWithRetry(
        messages: List<ChatMessage>,
        callback: AgentCallback,
        iteration: Int,
        specs: List<dev.langchain4j.agent.tool.ToolSpecification>? = null,
    ): LlmResponse {
        val effectiveSpecs = specs ?: toolSpecs
        var lastException: Exception? = null
        for (attempt in 0 until MAX_API_RETRIES) {
            if (cancelled.get()) throw RuntimeException(ClawApplication.instance.getString(R.string.agent_task_cancelled))
            try {
                return if (config.streaming) {
                    val textBuilder = StringBuilder()
                    llmClient.chatStreaming(messages, effectiveSpecs, object : StreamingListener {
                        override fun onPartialText(token: String) {
                            textBuilder.append(token)
                            callback.onContent(iteration, token)
                        }
                        override fun onComplete(response: LlmResponse) {}
                        override fun onError(error: Throwable) {}
                    })
                } else {
                    llmClient.chat(messages, effectiveSpecs)
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

    private fun runAgentLoop(
        userPrompt: String,
        callback: AgentCallback,
        stepHistory: MutableList<String>? = null,
    ) {
        // Pre-flight check
        preCheck()?.let {
            callback.onError(0, RuntimeException(it), 0)
            return
        }

        val parsedPrompt = TaskPromptEnvelope.parse(userPrompt)
        val rawUserRequest = parsedPrompt.currentRequest

        // ── Intent gate (bounded executor spec) ─────────────────────────────
        // Classify BEFORE the loop starts. Knowledge/vault/research questions
        // get NO observation/device tools — the model cannot UI-scrape its own
        // chat window instead of answering (the OmniRoute Q&A token-burn bug).
        val taskIntent = com.returngift.agent.agent.exec.TaskIntentClassifier.classify(rawUserRequest)
        XLog.i(TAG, "runAgentLoop: intent=${taskIntent.intent} (${taskIntent.reason})")

        // Per-task consent grants: one "Allow once" covers ALL tool calls touching
        // that personal surface for the rest of THIS task run (never persisted).
        val taskConsentedSurfaces = mutableSetOf<String>()

        // ── Personal-content consent gate (Rule 14) ─────────────────────
        // Reading the user's own emails/messages/photos is a supported device
        // task (see classifier), but the FIRST content read is gated on
        // consent: Allow once / Allow & remember (persisted per app) / Cancel.
        // device automation intent only.
        if (taskIntent.intent == com.returngift.agent.agent.exec.TaskIntentClassifier.Intent.DEVICE_AUTOMATION) {
            val pendingApps = com.returngift.agent.agent.exec.PersonalContentConsentGuard
                .detectApps(rawUserRequest)
                .filterNot { com.returngift.agent.agent.exec.PersonalContentConsentGuard.isRemembered(it) }
            if (pendingApps.isNotEmpty()) {
                val question = com.returngift.agent.agent.exec.PersonalContentConsentGuard.buildQuestion(pendingApps)
                val answer = com.returngift.agent.agent.clarify.ClarificationManager.request(
                    question,
                    com.returngift.agent.agent.exec.PersonalContentConsentGuard.CHOICES,
                    false,
                )
                val decision = answer?.let {
                    com.returngift.agent.agent.exec.PersonalContentConsentGuard.decisionFor(it)
                }
                if (decision == null ||
                    decision == com.returngift.agent.agent.exec.PersonalContentConsentGuard.Decision.CANCEL
                ) {
                    callback.onComplete(
                        0,
                        "Stopped: I need your permission before I can read personal content " +
                            "(${pendingApps.joinToString(", ")}). Tap one of the consent options " +
                            "and I'll continue.",
                        0,
                        null,
                    )
                    return
                }
                if (decision == com.returngift.agent.agent.exec.PersonalContentConsentGuard.Decision.ALLOW_REMEMBER) {
                    pendingApps.forEach {
                        com.returngift.agent.agent.exec.PersonalContentConsentGuard.remember(it)
                    }
                }
                // "Allow once" grants live for the rest of THIS task — the
                // dispatch-site check honors them without re-asking.
                taskConsentedSurfaces.addAll(pendingApps)
                XLog.i(TAG, "Personal-content consent granted ($decision) for $pendingApps")
            }
        }

        // Two-layer path: a known structured routine runs on the deterministic
        // executor; the AI is consulted only through the escalation seam.
        if (taskIntent.intent == com.returngift.agent.agent.exec.TaskIntentClassifier.Intent.DEVICE_AUTOMATION) {
            com.returngift.agent.agent.exec.StructuredRoutineRegistry.match(rawUserRequest)?.let { match ->
                runStructuredRoutine(match, rawUserRequest, callback, stepHistory)
                return
            }
        }

        val allowedTools = com.returngift.agent.agent.exec.RunToolPolicy.allowedTools(taskIntent.intent)
        val runToolSpecs = if (allowedTools != null) {
            LangChain4jToolBridge.buildToolSpecifications(allowedTools)
        } else {
            toolSpecs
        }
        // Bounded budgets for device work: screen-read gate + action/retry budget.
        val screenReadGate = com.returngift.agent.agent.exec.ScreenReadGate()
        val execBudget = com.returngift.agent.agent.exec.ExecutionBudget(
            wallClockMs = Long.MAX_VALUE // LLM-loop wall time stays governed by token budget/maxIterations
        )
        // Build System Prompt — use optimized prompt for local LLM
        val basePrompt = if (config.provider == LlmProvider.LOCAL) {
            LOCAL_TASK_PROMPT
        } else {
            config.systemPrompt
        }

        val inAppSearchGuard = InAppSearchGuard.fromTask(rawUserRequest)
        val emailComposeGuard = EmailComposeGuard.fromTask(rawUserRequest)
        val directDeviceDataGuard = DirectDeviceDataGuard.fromTask(rawUserRequest)
        val artifactContract = com.returngift.agent.agent.artifact.ArtifactContract.fromTask(rawUserRequest)

        // For local LLM, inject matching playbook into system prompt
        val playbookSection = if (config.provider == LlmProvider.LOCAL) {
            val matched = PlaybookManager.match(rawUserRequest)
            if (matched != null) {
                XLog.i(TAG, "Playbook matched: ${matched.id} for '$rawUserRequest'")
                "\n\n## Playbook: ${matched.name}\nFollow these steps exactly:\n\n${matched.body}"
        } else ""
        }

        // taskId is either generated by the 2-arg overload or passed from the orchestrator
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
            append(artifactContract.buildPromptSection())
            if (allowedTools != null) {
                append("\n\n## Task type: ").append(taskIntent.intent.name)
                append("\nThis is NOT a device-control task. Answer directly from knowledge")
                append(" or the available lookup tools. Screen/device tools are unavailable")
                append(" for this request — do not attempt to inspect the screen.")
            }
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

        // Opt-2: Pre-warm — only attach screen info for device-automation prompts.
        // Chat/knowledge/research questions never see screen data (the intent gate
        // replaces the old keyword heuristic that misrouted Q&A into UI scraping).
        val looksLikeTask =
            taskIntent.intent == com.returngift.agent.agent.exec.TaskIntentClassifier.Intent.DEVICE_AUTOMATION ||
                taskIntent.intent == com.returngift.agent.agent.exec.TaskIntentClassifier.Intent.EXTERNAL_AI_QUERY

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
                if (screenTool != null &&
                    screenReadGate.requestRead(
                        com.returngift.agent.agent.exec.ScreenReadGate.Purpose.STATE_ENTRY
                    ) is com.returngift.agent.agent.exec.ScreenReadGate.Decision.Allow
                ) {
                    val screenResult = screenTool.execute(emptyMap())
                    if (screenResult.isSuccess && !screenResult.data.isNullOrBlank()) {
                        // C5: use screen fingerprint instead of ad-hoc string hash
                        val fp = prewarmService?.getScreenFingerprint() ?: 0L
                        screenReadGate.recordRead(fp)
                        // P3.3: stamp observation with provenance
                        val foregroundPackage = prewarmService?.getForegroundPackage()
                        val provenance = ProvenanceTag(ProvenanceTag.Kind.SCREEN, "screen:$foregroundPackage")
                        XLog.i(TAG, "runAgentLoop: pre-warm screen attached (${screenResult.data!!.length} chars)")
                        ExecutionTracker.recordObservation(
                            taskId = taskId,
                            stepIndex = 0,
                            screenHash = fp.toString(),
                            screenSummary = screenResult.data!!,
                            appPackage = foregroundPackage,
                            provenance = provenance
                        )
                        // P1.2b: wrap observation content in untrusted delimiters so the model
                        // knows observed content is data, not instructions (Rule 15).
                        val screenObservation = screenResult.data ?: ""
                        val wrapped = "$promptForModel\n\n[observed content — untrusted]\n$screenObservation\n[end observed content]"
                        // Store the observation text (between delimiters) for canary checking
                        SafetyInterceptor.lastObservations = 
                            (SafetyInterceptor.lastObservations + listOf(screenObservation)).takeLast(2)
                        $wrapped
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
        var lastScreenHash = 0
        var lastScreenDiffCount = 0  // real per-round screen text diff (added+removed lines)
        var lastToolError: String? = null  // error of the last executed tool, null on success
        var previousScreenTexts: Set<String> = emptySet()
        val tokenMonitor = TokenMonitor(config.modelName)
        val stuckDetector = StuckDetector()
        val interactionWatchdog = InteractionWatchdog()
        val observeStallGuard = ObserveStallGuard()
        var currentTargetPackage: String? = null  // target app the task is driving (for relaunch recovery)
        val taskBudget = TaskBudget.fromSettings()
        var softLimitWarned = false
        var consecutiveActionsWithoutObserve = 0
        val isFollowingProcedure = (learnedProcedure != null)

         while (iterations < maxIterations && !cancelled.get()) {
             iterations++
             callback.onLoopStart(iterations)
             // P2.6: Begin tracker batch transaction for this round.
             ExecutionTracker.beginRound()

             // Compress history messages before sending to save tokens
             compressHistoryForSend(messages)

            // LLM call (with retry) — per-run tool specs from the intent gate.
            val llmResponse: LlmResponse
            try {
                llmResponse = chatWithRetry(messages, callback, iterations, runToolSpecs)
            } catch (e: Exception) {
XLog.e(TAG, "LLM API call failed after retries", e)
                 callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_api_call_failed, e.message)), totalTokens)
                 ExecutionTracker.endRound(commit = false)
                 return
             }

             if (cancelled.get()) {
                 callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
                 ExecutionTracker.endRound(commit = false)
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

            // Absolute runaway-cost guard: abort at TokenMonitor.CRITICAL (200K+) regardless of
            // the user-configured TaskBudget — the observed 121.8K/74.1K observe-only burns prove
            // a configured budget alone is not sufficient protection.
            if (tokenStatus.state == TokenMonitor.State.CRITICAL) {
                XLog.e(TAG, "Token CRITICAL hard abort at step $iterations: ${tokenStatus.formattedTokens} (${tokenStatus.formattedCost})")
                ExecutionTracker.endTask(taskId, "BUDGET_ABORT", iterations, totalTokens)
                callback.onComplete(
                    iterations,
                    "Task stopped: token usage reached the safety ceiling (${tokenStatus.formattedTokens} tokens, " +
                    "${tokenStatus.formattedCost}). The task was aborted to prevent runaway cost. " +
                    "Please re-run with a narrower instruction.",
totalTokens,
                        actualModelName
                    )
                    ExecutionTracker.endRound(commit = false)
                    return
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
                    if (artifactContract.shouldBlockTextOnlyCompletion(responseText)) {
                        val correction = artifactContract.maybeBlockFinish(responseText)
                            ?: artifactContract.buildCompletionCorrection()
                        XLog.i(TAG, "ArtifactContract blocked text-only completion for '$userPrompt'")
                        messages.add(UserMessage.from(correction))
                        continue
                    }
                    XLog.i(TAG, "runAgentLoop: text-only response, completing")
                    ExecutionTracker.endTask(taskId, "SUCCESS", iterations, totalTokens)
                    SharedKnowledgeStore.remember(SharedKnowledgeStore.Category.TASK_FACT, rawUserRequest, responseText, sourceTask = rawUserRequest)
                    if (learnedProcedure != null) LearnedProcedureStore.recordOutcome(learnedProcedure.id, true)
callback.onComplete(iterations, responseText, totalTokens, actualModelName)
                     ExecutionTracker.endRound(commit = false)
                     return
                 }
                // Empty response with no tools — something went wrong, finish
                XLog.w(TAG, "runAgentLoop: empty response with no tools, finishing")
                ExecutionTracker.endTask(taskId, "COMPLETED", iterations, totalTokens)
                callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens, actualModelName)
                continue
            }

            // Execute tool calls
            var madeActionThisRound = false
            for (toolRequest in llmResponse.toolExecutionRequests) {
                if (cancelled.get()) {
                    ExecutionTracker.endTask(taskId, "CANCELLED", iterations, totalTokens)
callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
                     ExecutionTracker.endRound(commit = false)
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
                        ?: artifactContract.maybeBlockFinish(params["summary"]?.toString() ?: "")
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

                // ── Intent tool gate: a knowledge/vault/research task must not be able
                // to execute observation/device tools even if the model hallucinates one
                // (the spec list already hides them; this is the enforcement half).
                val policyBlock = com.returngift.agent.agent.exec.RunToolPolicy
                    .blockReason(taskIntent.intent, toolName)
                if (policyBlock != null) {
                    val blockedResult = ToolResult.error(policyBlock)
                    XLog.i(TAG, "RunToolPolicy blocked $toolName for intent ${taskIntent.intent}")
                    callback.onToolResult(iterations, toolName, displayName, params.toString(), blockedResult)
                    messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(blockedResult)))
                    messages.add(UserMessage.from(policyBlock))
                    continue
                }

                // ── Screen-read gate: every observation needs a declared purpose; a
                // passive re-read of an unchanged screen is denied with guidance
                // instead of burning tokens on an identical tree dump.
                if (toolName == "get_screen_info") {
                    val purpose = if (lastToolError != null) {
                        com.returngift.agent.agent.exec.ScreenReadGate.Purpose.ACTION_FAILURE
                    } else {
                        com.returngift.agent.agent.exec.ScreenReadGate.Purpose.STATE_ENTRY
                    }
                    val decision = screenReadGate.requestRead(purpose)
                    if (decision is com.returngift.agent.agent.exec.ScreenReadGate.Decision.Deny) {
                        val denied = ToolResult.error(decision.guidance)
                        XLog.w(TAG, "ScreenReadGate denied get_screen_info: ${decision.guidance}")
                        callback.onToolResult(iterations, toolName, displayName, params.toString(), denied)
                        messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(denied)))
                        messages.add(UserMessage.from(decision.guidance))
                        continue
                    }
                }

                // ── Foreground discipline: never capture/observe our own chat UI.
                // Screenshot and screen-read tools must look at the TARGET app; if
                // ReturnGift somehow landed in the foreground (e.g. a chat UI came up
                // between two steps), relaunch the tracked target before executing.
                val obsTools = setOf("take_screenshot", "get_screen_info")
                if (toolName in obsTools && currentTargetPackage != null
                        && ClawAccessibilityService.getInstance() != null) {
                    val svc = ClawAccessibilityService.getInstance()
                    val fg = svc.getForegroundPackage()
                    if (fg == com.returngift.agent.ClawApplication.instance.packageName) {
                        val restore = svc.openAppForeground(currentTargetPackage, 3000L)
                        if (!restore.success) {
                            val msg = "Foreground discipline: skipped $toolName — our own chat was " +
                                "in front and restoring ${currentTargetPackage} failed " +
                                "(${restore.error ?: "no error"}). Try again."
                            val blocked = ToolResult.error(msg)
                            XLog.w(TAG, msg)
                            callback.onToolResult(iterations, toolName, displayName, params.toString(), blocked)
                            messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(blocked)))
                            messages.add(UserMessage.from(msg))
                            continue
                        }
                    }
                }

                // ── Dispatch-site personal-content consent (additive to the
                // pre-loop text gate): if the dispatched tool would touch a
                // personal surface that hasn't been consented-to THIS task and
                // isn't remembered, park on the same three choices. One
                // "Allow once" covers every later call to that surface in this
                // task run (taskConsentedSurfaces).
                val dispatchSurface = com.returngift.agent.agent.exec.PersonalContentConsentGuard
                    .checkToolTarget(toolName, params, currentTargetPackage)
                if (dispatchSurface != null
                    && dispatchSurface !in taskConsentedSurfaces
                    && !com.returngift.agent.agent.exec.PersonalContentConsentGuard.isRemembered(dispatchSurface)
                ) {
                    val answer = com.returngift.agent.agent.clarify.ClarificationManager.request(
                        com.returngift.agent.agent.exec.PersonalContentConsentGuard
                            .buildQuestion(listOf(dispatchSurface)),
                        com.returngift.agent.agent.exec.PersonalContentConsentGuard.CHOICES,
                        false,
                    )
                    val decision = answer?.let {
                        com.returngift.agent.agent.exec.PersonalContentConsentGuard.decisionFor(it)
                    }
                    when (decision) {
                        com.returngift.agent.agent.exec.PersonalContentConsentGuard.Decision.ALLOW_ONCE -> {
                            taskConsentedSurfaces.add(dispatchSurface)
                            XLog.i(TAG, "Dispatch-site consent (once) for $dispatchSurface via $toolName")
                        }
                        com.returngift.agent.agent.exec.PersonalContentConsentGuard.Decision.ALLOW_REMEMBER -> {
                            com.returngift.agent.agent.exec.PersonalContentConsentGuard.remember(dispatchSurface)
                            taskConsentedSurfaces.add(dispatchSurface)
                            XLog.i(TAG, "Dispatch-site consent (remembered) for $dispatchSurface via $toolName")
                        }
                        else -> {
                            // Cancel / timeout / unrecognized — honest terminal outcome,
                            // same as the pre-loop gate.
                            ExecutionTracker.endTask(taskId, "CANCELLED", iterations, totalTokens)
                            callback.onComplete(
                                iterations,
                                "Stopped: I need your permission before I can read personal content " +
                                    "($dispatchSurface). The tool call ($toolName) was not executed.",
                                totalTokens,
                                actualModelName,
                            )
                            ExecutionTracker.endRound(commit = false)
                            return
                        }
                    }
}
                 }

                  // P1.2c: Injection canary — one new checkpoint AFTER consent/allow-list,
                  // BEFORE executeTool. One new counter: injection_canary.
                  val canaryBlock = com.returngift.agent.agent.SafetyInterceptor.checkInjectionCanary(
                      toolName, params, paramsText
                  )
                  canaryBlock?.let {
                      XLog.w(TAG, "Injection canary blocked: $canaryBlock")
                      callback.onToolResult(iterations, toolName, displayName, paramsText, ToolResult.error(canaryBlock))
                      messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(ToolResult.error(canaryBlock))))
                      messages.add(UserMessage.from(canaryBlock))
                      continue
                  }

                  // P2.5: Pre-action judge — second-opinion local LLM call for high-risk
                  // tools. Fail-open on model unavailable. Closed counters only.
                  // Fired AFTER injection canary, BEFORE executeTool (no guard reorder).
                  if (toolName in com.returngift.agent.agent.guardrail.PreActionJudge.HIGH_RISK_TOOLS) {
                      val judgeOutcome = com.returngift.agent.agent.guardrail.PreActionJudge.judgeWithTimeout(
                          client = if (::llmClient.isInitialized) llmClient else null,
                          toolName = toolName,
                          paramsText = paramsText,
                          taskSummary = userPrompt.take(200),
                      )
                      when (judgeOutcome) {
                          is PreActionJudge.Outcome.Block -> {
                              XLog.w(TAG, "PreActionJudge blocked: ${judgeOutcome.reason}")
                              callback.onToolResult(iterations, toolName, displayName, paramsText,
                                  ToolResult.error(judgeOutcome.reason))
                              messages.add(ToolExecutionResultMessage.from(toolRequest,
                                  GSON.toJson(ToolResult.error(judgeOutcome.reason))))
                              messages.add(UserMessage.from(judgeOutcome.reason))
                              continue
                          }
                          is PreActionJudge.Outcome.AskUser -> {
                              XLog.i(TAG, "PreActionJudge asking user: ${judgeOutcome.question}")
                              val clarification = com.returngift.agent.agent.clarify.ClarificationManager.request(
                                      question = judgeOutcome.question,
                                      choices = listOf("Proceed", "Cancel"),
                                      allowFreeText = false,
                              )
                              val answer = when (clarification?.trim()?.lowercase()) {
                                  "proceed" -> "yes"
                                  else -> "no"
                              }
                              if (answer != "yes") {
                                  XLog.i(TAG, "PreActionJudge: user declined, blocking")
                                  callback.onToolResult(iterations, toolName, displayName, paramsText,
                                      ToolResult.error("Action blocked by user"))
                                  messages.add(ToolExecutionResultMessage.from(toolRequest,
                                      GSON.toJson(ToolResult.error("Action blocked by user"))))
                                  messages.add(UserMessage.from("Action blocked by user"))
                                  continue
                              }
                              // User approved — proceed with execution
                          }
                          is PreActionJudge.Outcome.Allow -> { /* proceed */ }
                      }
                  }

                  // Unified control loop: capture the state BEFORE the action so we can verify
                // the action's effect afterwards (observe → resolve → act → verify → recover).
                val a11ySvc = ClawAccessibilityService.getInstance()
                val beforeState = if (a11ySvc != null && toolName in ACTION_TOOLS) {
                    ActionVerifier.captureBefore(a11ySvc, toolName)
                } else null

                val actStartTime = System.currentTimeMillis()
                var result = ToolRegistry.getInstance().executeTool(toolName, params)
                if (toolName == "get_screen_info" && result.isSuccess && !result.data.isNullOrBlank()) {
                    // C5: use screen fingerprint instead of ad-hoc string hash
                    val fp = a11ySvc?.getScreenFingerprint() ?: 0L
                    screenReadGate.recordRead(fp)
                }
                if (toolName in ACTION_TOOLS) {
                    screenReadGate.recordAction()
                    execBudget.recordAction()?.let { breach ->
                        XLog.e(TAG, "Execution budget breach: ${breach.detail}")
                        ExecutionTracker.endTask(taskId, "BUDGET_EXCEEDED", iterations, totalTokens)
                        // P1.3a: Record failure for learned procedure learning
                        ExecutionTracker.getTrajectory(taskId)?.let { LearnedProcedureStore.extractAndStore(it) }
                        callback.onComplete(
                            iterations,
                            "Task stopped: execution budget exceeded (${breach.detail}). " +
                                "The bounded executor never retries open-endedly — re-run with a narrower instruction.",
                            totalTokens,
                            actualModelName
                        )
                        ExecutionTracker.endRound(commit = false)
                        return
                    }
                }
                // Tap recovery (Design v2 Phase F): a failed tap is usually a moved or
                // off-screen target — steer the model at the existing find_and_tap
                // composite (scroll + semantic re-resolve + tap) instead of letting it
                // re-tap the same stale coordinates/node.
                if (!result.isSuccess && toolName in TAP_LIKE_TOOLS) {
                    result = ToolResult.error(
                        (result.error ?: "tap failed") + " " + TAP_RECOVERY_HINT
                    )
                }
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
                    // Typed "target app is truly on screen" signal — the UI defers
                    // minimizing the chat until this fires (never for our own package).
                    if (expectedFg != null
                        && verification?.outcome == ActionVerifier.VerificationOutcome.VERIFIED
                        && expectedFg != ClawApplication.instance.packageName
                    ) {
                        callback.onTargetForegroundVerified(expectedFg)
                    }
                    val overlay = a11ySvc.isSystemOverlayLikely()
                    val argsFp = (params["text"]?.toString() ?: params["node_id"]?.toString()
                        ?: params["package_name"]?.toString() ?: params["key"]?.toString() ?: "").take(40)
                    recovery = interactionWatchdog.record(
                        toolName = toolName,
                        argsFingerprint = argsFp,
                        verification = verification,
                        screenHash = verification.afterSignature?.toLongOrNull() ?: 0L,
                        expectedForeground = currentTargetPackage,
                        overlayPresent = overlay
                    )
                    if (recovery.strategy != InteractionWatchdog.RecoveryStrategy.NONE) {
                        // Bounded recovery: at most 2 automatic recoveries PER UI STATE
                        // (screen signature + target package — the same per-state budget
                        // philosophy as the deterministic executor's ExecutionBudget
                        // retriesPerState, reusing that very type). A stall on a NEW
                        // screen gets a fresh budget; a 3rd trigger on the SAME state
                        // STOPS and reports instead of restarting.
                        val stateKey = "${verification?.afterSignature?.toLongOrNull() ?: 0L}@$currentTargetPackage"
                        val budgetBreach = execBudget.recordRetry(stateKey)
                        if (budgetBreach?.violation == com.returngift.agent.agent.exec.ExecutionBudget.Violation.RETRY_BUDGET) {
                            XLog.e(TAG, "Watchdog recovery budget exhausted for state $stateKey (${recovery.strategy}) — stopping task")
ExecutionTracker.endTask(taskId, "FAILED_ACTION", iterations, totalTokens)
                        // P1.3a: Record failure for learned procedure learning
                        ExecutionTracker.getTrajectory(taskId)?.let { LearnedProcedureStore.extractAndStore(it) }
                        callback.onComplete(
                                iterations,
                                "Task stopped: ${recovery.message} " +
                                    "Automatic recovery was already attempted " +
                                    "${execBudget.retriesUsed(stateKey)} times for this screen state; " +
                                    "stopping instead of retrying endlessly.",
                                totalTokens,
                                actualModelName
                            )
                            ExecutionTracker.endRound(commit = false)
                            return
                        }
                        recoveryExecuted = interactionWatchdog.executeRecovery(
                            recovery.strategy, a11ySvc, currentTargetPackage
                        )
                        XLog.i(TAG, "Watchdog recovery #${execBudget.retriesUsed(stateKey)} for state $stateKey (${recovery.strategy}): $recoveryExecuted")
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
                if (toolName in ACTION_TOOLS) madeActionThisRound = true
                // M3A-style step history for the checkpoint finalizer: tool + outcome + error.
                stepHistory?.add(
                    "$iterations. $toolName — " +
                        if (result.isSuccess) "ok" else "FAILED: ${result.error ?: "unknown error"}"
                )
                lastToolError = if (result.isSuccess) null else (result.error ?: "unknown error")
                if (result.isSuccess) {
                    inAppSearchGuard.recordSuccessfulTool(toolName, params)
                    emailComposeGuard.recordSuccessfulTool(toolName)
                    artifactContract.recordKbToolResult(toolName, result.data)
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
                     ExecutionTracker.endRound(commit = false)
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
                     ExecutionTracker.endRound(commit = false)
                     return
                 }

                // Opt-3: Adaptive Observe/Act Loop.
                // Decides dynamically whether screen capture is necessary.
                val obsDecision = ObservationPolicy.evaluate(
                    lastTool = toolName,
                    lastSuccess = result.isSuccess,
                    consecutiveActionsWithoutObserve = consecutiveActionsWithoutObserve,
                    isFollowingProcedure = isFollowingProcedure,
                    screenHashChanged = (lastScreenDiffCount > 0)
                )

                // Post-action auto-attach is a VERIFY-purpose read and must pass the
                // gate too — otherwise the loop itself becomes the open-ended observer.
                val autoAttachPurpose = if (result.isSuccess) {
                    com.returngift.agent.agent.exec.ScreenReadGate.Purpose.POST_ACTION_VERIFY
                } else {
                    com.returngift.agent.agent.exec.ScreenReadGate.Purpose.ACTION_FAILURE
                }
                val autoAttachAllowed = toolName in ACTION_TOOLS &&
                    obsDecision != ObservationPolicy.ObservationDecision.SKIP &&
                    screenReadGate.requestRead(autoAttachPurpose) is com.returngift.agent.agent.exec.ScreenReadGate.Decision.Allow
                val combinedResultData: String = if (autoAttachAllowed) {
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
                                     ExecutionTracker.endRound(commit = false)
                                     return
                                 }
                                is InterruptDetector.InterruptResult.Clean -> { /* proceed */ }
                            }
                        }
                        // ── End interrupt check ─────────────────────────────────────────────

                        val screenTool = ToolRegistry.getInstance().getTool("get_screen_info")
                        val screenAfter = screenTool?.execute(emptyMap())
                        if (screenAfter != null && screenAfter.isSuccess && !screenAfter.data.isNullOrBlank()) {
                            // C5: use screen fingerprint instead of ad-hoc string hash
                            val screenFingerprint = a11ySvc?.getScreenFingerprint() ?: 0L
                            // P3.3: stamp observation with provenance (foreground package)
                            val foregroundPackage = a11ySvc?.getForegroundPackage()
                            val provenance = ProvenanceTag(ProvenanceTag.Kind.SCREEN, "screen:$foregroundPackage")
                            // P2.1: delta observation — if fingerprint is stable and
                            // at least one action already occurred since the last full
                            // send, deliver a delta line instead of the full tree.
                            val isUnchangedScreen = screenFingerprint == lastScreenHash && madeActionThisRound
                            if (isUnchangedScreen) {
                                val deltaMsg = "Screen unchanged since round $lastScreenDiffCount (fingerprint stable). " +
                                    "Do not re-read; act, or finish with a partial summary."
                                // P1.2b: wrap the observation in untrusted delimiters.
                                val observationText = deltaMsg
                                val wrapped = "[observed content — untrusted]\n$observationText\n[end observed content]"
                                // Store the observation text (between delimiters) for canary checking
                                SafetyInterceptor.lastObservations = 
                                    (SafetyInterceptor.lastObservations + listOf(observationText)).takeLast(2)
                                ExecutionTracker.recordObservation(
                                    taskId = taskId,
                                    stepIndex = iterations,
                                    screenHash = lastScreenHash.toString(),
                                    screenSummary = deltaMsg,
                                    appPackage = foregroundPackage,
                                    provenance = provenance
                                )
                                XLog.i(TAG, "Opt3: delta observation — screen unchanged (fp=$lastScreenHash)")
                                // Continue to build enriched data with delta message
                                lastScreenHash = screenFingerprint
                                val currentTexts = screenAfter.data!!.lines()
                                    .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                                previousScreenTexts = currentTexts
                                lastScreenDiffCount = 0
                                GSON.toJson(if (result.isSuccess) ToolResult.success(wrapped) else ToolResult.error(result.error ?: ""))
                            } else {
                                // Screen changed or first read — full tree with delimiters.
                                lastScreenHash = screenFingerprint
                                screenReadGate.recordRead(lastScreenHash)
                                ExecutionTracker.recordObservation(
                                    taskId = taskId,
                                    stepIndex = iterations,
                                    screenHash = lastScreenHash.toString(),
                                    screenSummary = screenAfter.data!!,
                                    appPackage = foregroundPackage,
                                    provenance = provenance
                                )
                                XLog.i(TAG, "Opt3: auto-attached screen after $toolName (${screenAfter.data!!.length} chars)")
                                // Screen diff: extract text lines and compare with previous
                                val currentTexts = screenAfter.data!!.lines()
                                    .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                                val added = currentTexts - previousScreenTexts
                                val removed = previousScreenTexts - currentTexts
                                previousScreenTexts = currentTexts
                                lastScreenDiffCount = added.size + removed.size
                                val diffSection = buildString {
                                    if (added.isNotEmpty()) append("\nNew on screen: ${added.take(10).joinToString(", ")}")
                                    if (removed.isNotEmpty()) append("\nGone from screen: ${removed.take(10).joinToString(", ")}")
                                }
                                // P1.2b: wrap the observation in untrusted delimiters.
                                val fullObservationText = screenAfter.data!!
                                val fullObservation = "[observed content — untrusted]\n$fullObservationText\n[end observed content]"
                                // Store the observation text (between delimiters) for canary checking
                                SafetyInterceptor.lastObservations = 
                                    (SafetyInterceptor.lastObservations + listOf(fullObservationText)).takeLast(2)
                                val enrichedData = "$fullObservation\n$diffSection"
                                val enriched = if (result.isSuccess) ToolResult.success(enrichedData)
                                               else ToolResult.error(result.error ?: "")
                                GSON.toJson(enriched)
                            }
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
                        // P1.2b: wrap explicit get_screen_info observation in untrusted delimiters.
                        val wrappedDataText = result.data!!
                        val wrappedData = "[observed content — untrusted]\n$wrappedDataText\n[end observed content]"
                        // Store the observation text (between delimiters) for canary checking
                        SafetyInterceptor.lastObservations = 
                            (SafetyInterceptor.lastObservations + listOf(wrappedDataText)).takeLast(2)
                        val wrappedResult = ToolResult.success(wrappedData)
                        messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(wrappedResult)))
                    } else {
                        GSON.toJson(result)
                    }
                }

                // Add tool result to messages
                if (!(toolName == "get_screen_info" && result.isSuccess && result.data != null)) {
                    messages.add(ToolExecutionResultMessage.from(toolRequest, combinedResultData))
                }
                // Unified control loop: if the watchdog executed a recovery with a model hint,
                // inject it so the model adapts its plan instead of repeating the ineffective action.
                if (recovery != null && recovery.strategy != InteractionWatchdog.RecoveryStrategy.NONE
                    && !recovery.modelHint.isNullOrBlank()) {
                    messages.add(UserMessage.from(recovery.modelHint))
                }
                XLog.d(TAG, "displayName:$displayName toolName:$toolName")
            }

            // Stuck detection (5-signal, 3-level recovery) — fed with the real per-round
            // signals so ZeroDiff (diff count == 0) and RepeatedError (real error string) can fire.
            val lastAction = llmResponse.toolExecutionRequests?.firstOrNull()?.let {
                "${it.name()}:${it.arguments()?.take(50)}"
            } ?: ""
            val detection = stuckDetector.record(lastAction, lastScreenHash, lastScreenDiffCount, lastToolError)
            if (detection != null) {
                when (detection.level) {
                    StuckDetector.RecoveryLevel.AUTO_KILL -> {
                        XLog.w(TAG, "StuckDetector AUTO_KILL at iteration $iterations: ${detection.signal.description}")
                        val status = tokenMonitor.getStatus()
                        ExecutionTracker.endTask(taskId, "AUTO_KILL", iterations, totalTokens)
                        // P1.3a: Record failure for learned procedure learning
                        ExecutionTracker.getTrajectory(taskId)?.let { LearnedProcedureStore.extractAndStore(it) }
                        callback.onComplete(
                            iterations,
                            "Task stopped: agent was stuck (${detection.signal.description}). " +
                            "Used ${status.formattedTokens} tokens (${status.formattedCost}).",
                            totalTokens,
                            actualModelName
                        )
                        ExecutionTracker.endRound(commit = false)
                        return
                    }
                    else -> {
                        XLog.w(TAG, "StuckDetector ${detection.level} at iteration $iterations: ${detection.signal.description}")
                        messages.add(UserMessage.from(detection.recoveryHint))
                    }
                }
            }

            // Observe-only stall defense: no action tool and an unchanged screen means the
            // model is burning tokens re-reading the UI. Hint once, then abort the task.
            when (observeStallGuard.recordRound(madeActionThisRound, lastScreenHash)) {
                ObserveStallGuard.Verdict.ABORT -> {
                    XLog.e(TAG, "ObserveStallGuard ABORT at iteration $iterations (observe-only stall)")
                    ExecutionTracker.endTask(taskId, "STALL_ABORT", iterations, totalTokens)
                    // P1.3a: Record failure for learned procedure learning
                    ExecutionTracker.getTrajectory(taskId)?.let { LearnedProcedureStore.extractAndStore(it) }
                    val status = tokenMonitor.getStatus()
                    callback.onComplete(
                        iterations,
                        "Task stopped: the agent was re-reading an unchanged screen without acting " +
                        "for several rounds, so it was stopped to avoid wasting tokens " +
                        "(${status.formattedTokens} used). Please re-run with a more specific instruction.",
                        totalTokens,
                        actualModelName
                    )
                    return
                }
                ObserveStallGuard.Verdict.HINT -> {
                    XLog.w(TAG, "ObserveStallGuard HINT at iteration $iterations (idle round on unchanged screen)")
                    messages.add(UserMessage.from(observeStallGuard.buildHintMessage()))
                }
                ObserveStallGuard.Verdict.OK -> { /* progress */ }
            }
            XLog.d(TAG, "Round:$iterations total=$totalTokens thisRound=${llmResponse.tokenUsage?.totalTokenCount()}")
            // P2.6: Commit the round's tracker writes atomically.
            ExecutionTracker.endRound(commit = true)
        }

        if (cancelled.get()) {
            callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
        } else {
            callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_max_iterations, maxIterations)), totalTokens)
        }
    }

    // ==================== Structured Routine Path (two-layer executor) ====================

    /**
     * Run a registered structured routine on the deterministic executor. The
     * controller owns state/selectors/actions/retries/budgets/verification; the
     * LLM is consulted ONLY through the escalation seam (bounded, no tools) when
     * deterministic target resolution fails, and its answer is parsed into a
     * selector the controller executes — the AI never drives the loop.
     */
    private fun runStructuredRoutine(
        match: com.returngift.agent.agent.exec.StructuredRoutineRegistry.Match,
        rawUserRequest: String,
        callback: AgentCallback,
        stepHistory: MutableList<String>?,
    ) {
        XLog.i(TAG, "runStructuredRoutine: ${match.routineId} for '$rawUserRequest'")
        val taskId = UUID.randomUUID().toString()
        ExecutionTracker.beginTask(taskId, rawUserRequest, "structured:${match.routineId}")

        val escalator = com.returngift.agent.agent.exec.DeterministicUiExecutor.Escalator { screenDump, hint ->
            try {
                val messages = listOf(
                    SystemMessage.from(
                        "You identify UI elements in an Android accessibility tree. " +
                            "Reply with ONLY a JSON object describing the target the controller should act on. " +
                            "Allowed keys: text, content_desc, resource_id, class, x, y. " +
                            "Prefer text, then content_desc, then resource_id; coordinates only as last resort. " +
                            "Never invent a node_id."
                    ),
                    UserMessage.from("Target needed: $hint\n\nScreen tree:\n${screenDump.take(6000)}")
                )
                val response = llmClient.chat(messages, emptyList())
                response.text?.let {
                    com.returngift.agent.agent.exec.DeterministicUiExecutor.parseEscalationResponse(GSON, it)
                }
            } catch (e: Exception) {
                XLog.w(TAG, "escalation call failed: ${e.message}")
                null
            }
        }

        val executor = com.returngift.agent.agent.exec.DeterministicUiExecutor(
            escalator = escalator,
            shouldAbort = { cancelled.get() },
        )
        callback.onLoopStart(1)
        val report = try {
            executor.execute(match.spec)
        } catch (a: com.returngift.agent.agent.exec.DeterministicUiExecutor.AbortedException) {
            ExecutionTracker.endTask(taskId, "CANCELLED", 1, 0)
            callback.onComplete(1, ClawApplication.instance.getString(R.string.agent_task_cancel), 0, null)
            return
        } catch (e: Exception) {
            XLog.e(TAG, "structured routine crashed", e)
            com.returngift.agent.agent.exec.ExecReport(
                outcome = com.returngift.agent.agent.exec.ExecOutcome.FAILED_ACTION,
                reason = "executor error: ${e.message}",
                screenReads = 0, actions = 0, escalations = 0,
                elapsedMs = 0, stateTrace = emptyList(),
            )
        }
        ExecutionTracker.endTask(taskId, report.trackerStatus(), 1, 0)
        stepHistory?.add("1. structured:${match.routineId} — ${report.outcome}: ${report.reason}")

        if (report.outcome == com.returngift.agent.agent.exec.ExecOutcome.SUCCESS) {
            callback.onComplete(1, report.toSummary(), 0, null)
        } else {
            // Genuine pre-completion stop → ERROR outcome (resumable via checkpoint).
            callback.onError(1, RuntimeException(report.toSummary()), 0)
        }
    }

    override fun cancel() {
        cancelled.set(true)
        // Unblock a parked ask_user call so the loop thread wakes and exits cleanly.
        com.returngift.agent.agent.clarify.ClarificationManager.cancelPending()
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
