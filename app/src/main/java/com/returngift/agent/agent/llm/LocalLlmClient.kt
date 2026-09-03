// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.llm

import com.returngift.agent.ClawApplication
import com.returngift.agent.agent.AgentConfig
import com.returngift.agent.utils.XLog
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import com.google.gson.Gson
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
     * LlmClient implementation using Google LiteRT-LM SDK for on-device inference.
     *
     * Bridges the stateless LangChain4j chat interface (full message list per call)
     * to LiteRT-LM's stateful Conversation API (incremental messages).
     *
     * config.baseUrl is repurposed to hold the local model file path.
     */
    class LocalLlmClient(private val config: AgentConfig) : LlmClient {

        // Separate conversation state for fast engine (used when fast=true)
        private var fastConversation: Conversation? = null
        private var fastProcessedMessageCount = 0

    private val GSON = Gson()

    // Engine is owned by the shared local runtime.
    // We keep a local reference only for null-check convenience.
    private var engine: Engine? = null
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    private var processedMessageCount = 0

    private var gpuFailed = false

    private fun ensureEngine() {
        val modelPath = config.baseUrl
        val context = ClawApplication.instance
        try {
            val shared = LocalModelRuntime.acquireSharedEngine(
                context = context,
                modelPath = modelPath,
                preferCpu = gpuFailed,
            ).engine
            if (engine !== shared) {
                XLog.i(TAG, "ensureEngine: obtained shared engine for $modelPath")
                engine = shared
            }
        } catch (e: Exception) {
            if (gpuFailed || !LocalModelRuntime.isGpuBackendFailure(e)) {
                XLog.e(TAG, "ensureEngine: failed to get engine from shared runtime", e)
                throw e
            }

            XLog.w(TAG, "ensureEngine: GPU engine init failed, retrying on CPU: ${e.message}")
            gpuFailed = true
            val cpuShared = LocalModelRuntime.forceCpuEngine(context, modelPath).engine
            if (engine !== cpuShared) {
                XLog.i(TAG, "ensureEngine: obtained shared CPU engine for $modelPath")
                engine = cpuShared
            }
        }
    }

    /**
     * Force engine to recreate with CPU backend. Called when GPU inference fails
     * (e.g. OpenCL library not found).
     */
    private fun fallbackToCpu() {
        XLog.w(TAG, "fallbackToCpu: GPU inference failed, switching to CPU")
        gpuFailed = true
        try { conversation?.close() } catch (_: Exception) {}
        conversation = null
        processedMessageCount = 0
        sendCount = 0
        engine = LocalModelRuntime.forceCpuEngine(ClawApplication.instance, config.baseUrl).engine
    }

    /**
     * Create a new conversation with system prompt and tool declarations.
     */
    private fun createConversation(systemPrompt: String, toolSpecs: List<ToolSpecification>) {
        // LiteRT-LM only supports one session at a time — close existing first
        try { conversation?.close() } catch (_: Exception) {}
        conversation = null

        // Convert tool specs to native LiteRT-LM tools
        val nativeTools = toolSpecs.mapNotNull { spec ->
            try {
                val paramsJson = try { GSON.toJson(spec.parameters()) } catch (_: Exception) { "{}" }
                com.google.ai.edge.litertlm.tool(object : com.google.ai.edge.litertlm.OpenApiTool {
                    override fun getToolDescriptionJsonString(): String = GSON.toJson(mapOf(
                        "name" to spec.name(),
                        "description" to (spec.description() ?: ""),
                        "parameters" to try { GSON.fromJson(paramsJson, Any::class.java) } catch (_: Exception) { emptyMap<String, Any>() }
                    ))
                    override fun execute(params: String): String = "{}" // Execution handled by DefaultAgentService
                })
            } catch (e: Exception) {
                XLog.w(TAG, "Failed to wrap tool: ${spec.name()}", e)
                null
            }
        }

        XLog.i(TAG, "createConversation: ${nativeTools.size} native tools")

        val convConfig = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            tools = nativeTools,
            samplerConfig = SamplerConfig(
                // Fast/mechanical calls intentionally keep temperature = 0.3 for stable tool playback.
                topK = config.generation.topK ?: 64,
                topP = config.generation.topP ?: 0.95,
                temperature = config.generation.temperature ?: config.temperature
            ),
            automaticToolCalling = false  // We handle execution in DefaultAgentService
        )

        val lease = LocalModelRuntime.openConversation(
            context = ClawApplication.instance,
            modelPath = config.baseUrl,
            conversationConfig = convConfig,
            preferCpu = gpuFailed,
        )
        engine = lease.engine
        conversation = lease.conversation
        processedMessageCount = 0
    }

    private var sendCount = 0

    override fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>, fast: Boolean): LlmResponse {
        return try {
            if (fast) chatFastInternal(messages, toolSpecs) else chatInternal(messages, toolSpecs)
        } catch (e: Exception) {
            if (fast) {
                XLog.w(TAG, "chat(fast=true) failed, retrying on main engine")
                return chatInternal(messages, toolSpecs)
            }
            if (!gpuFailed && LocalModelRuntime.isGpuBackendFailure(e)) {
                XLog.w(TAG, "chat: GPU inference failed, retrying with CPU: ${e.message}")
                fallbackToCpu()
                chatInternal(messages, toolSpecs)
            } else {
                throw e
            }
        }
    }

    private fun chatInternal(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse {
        ensureEngine()

        // Detect new task or recreate needed
        if (processedMessageCount == 0 || messages.size < processedMessageCount || sendCount >= 8) {
            val systemPrompt = messages.filterIsInstance<SystemMessage>().firstOrNull()?.text()
                ?: config.systemPrompt.ifEmpty { LOCAL_SYSTEM_PROMPT }
            createConversation(systemPrompt, toolSpecs)
            sendCount = 0
            processedMessageCount = 0
        }

        // Find new messages to send
        val newMessages = messages.subList(
            processedMessageCount.coerceAtMost(messages.size),
            messages.size
        )

        var lastResponse: Any? = null

        for (msg in newMessages) {
            when (msg) {
                is SystemMessage -> { /* handled in createConversation */ }
                is UserMessage -> {
                    val conv = conversation ?: throw RuntimeException("LiteRT-LM conversation not initialized — engine may have failed to load the model")
                    XLog.d(TAG, "chat: sendMessage user (${msg.singleText().take(80)}...) sendCount=$sendCount")
                    lastResponse = sendAndRecover(conv, msg.singleText())
                    sendCount++
                }
                is AiMessage -> { /* already in conversation state */ }
                is ToolExecutionResultMessage -> {
                    // Truncate tool results to prevent token overflow + reduce crash risk
                    val truncatedResult = msg.text().take(400)
                    val toolResultText = "[Tool ${msg.toolName()} result]: $truncatedResult"
                    val conv = conversation ?: throw RuntimeException("LiteRT-LM conversation not initialized — engine may have failed to load the model")
                    XLog.d(TAG, "chat: sendMessage toolResult (${toolResultText.take(80)}...) sendCount=$sendCount")
                    lastResponse = sendAndRecover(conv, toolResultText)
                    sendCount++
                }
            }
        }

processedMessageCount = messages.size
         return parseResponse(lastResponse)
     }

     /**
      * Chat using the fast (mechanical-step) engine for procedure-based tasks.
      * Constructs a minimal prompt: trimmed system prompt + last 2 observations +
      * procedure remaining steps + "output next tool call as JSON".
      */
     private fun chatFastInternal(
         messages: List<ChatMessage>,
         toolSpecs: List<ToolSpecification>
     ): LlmResponse {
         // Acquire fast engine - if not available, fall back to main
         val modelPath = config.baseUrl
         val context = ClawApplication.instance
         val fastEngineLease = EngineHolder.acquireFast(modelPath)
         val fastEngine = fastEngineLease?.engine
         val isFastAvailable = fastEngine != null
         var fastConversation: Conversation? = null
         var fastProcessed = 0
         var sendCount = 0
         var gpuFailedFast = false

         if (!isFastAvailable) {
             XLog.w(TAG, "chatFastInternal: fast engine not available, falling back to main")
             return chatInternal(messages, toolSpecs)
         }

         return try {
             ensureFastEngineCreated(context, modelPath, fastEngineLease)
             
             // Recreate conversation if needed (new task or reset condition)
             if (fastProcessed == 0 || messages.size < fastProcessed || sendCount >= 8) {
                 val systemPrompt = buildFastSystemPrompt(messages)
                 fastConversation = createFastConversation(systemPrompt, toolSpecs)
                 sendCount = 0
                 fastProcessed = 0
             }

             // Find new messages to send
             val newMessages = messages.subList(
                 fastProcessed.coerceAtMost(messages.size),
                 messages.size
             )

             var lastResponse: Any? = null

             for (msg in newMessages) {
                 when (msg) {
                     is SystemMessage -> { /* handled in createFastConversation */ }
                     is UserMessage -> {
                         val conv = fastConversation ?: throw RuntimeException("Fast conversation not initialized")
                         XLog.d(TAG, "chatFast: sendMessage user (${msg.singleText().take(80)}...) sendCount=$sendCount")
                         lastResponse = sendAndRecoverFast(conv, msg.singleText(), gpuFailedFast)
                         sendCount++
                     }
                     is AiMessage -> { /* already in conversation state */ }
                     is ToolExecutionResultMessage -> {
                         val truncatedResult = msg.text().take(400)
                         val toolResultText = "[Tool ${msg.toolName()} result]: $truncatedResult"
                         val conv = fastConversation ?: throw RuntimeException("Fast conversation not initialized")
                         XLog.d(TAG, "chatFast: sendMessage toolResult (${toolResultText.take(80)}...) sendCount=$sendCount")
                         lastResponse = sendAndRecoverFast(conv, toolResultText, gpuFailedFast)
                         sendCount++
                     }
                 }
             }

             fastProcessed = messages.size
             return parseResponse(lastResponse)
         } catch (e: Exception) {
             // On any fast-engine failure, retry on main engine once
             XLog.w(TAG, "chatFastInternal: fast engine failed, retrying on main: ${e.message}")
             if (fastConversation != null) {
                 try { fastConversation?.close() } catch (_: Exception) {}
                 fastConversation = null
             }
             return chatInternal(messages, toolSpecs)
         } finally {
             // Clean up fast conversation (but keep engine for potential reuse in same task)
             // Engine will be released by DefaultAgentService at task end
             if (fastConversation != null) {
                 try { fastConversation?.close() } catch (_: Exception) {}
                 fastConversation = null
             }
             fastProcessed = 0
         }
     }

     private fun ensureFastEngineCreated(
         context: Context,
         modelPath: String,
         lease: LocalEngineLease?
     ) {
         // Fast engine is already acquired and held by EngineHolder
         // We just need to ensure our local reference is set
         if (lease != null) {
             // Engine is already initialized in EngineHolder.acquireFast
         }
     }

     private fun buildFastSystemPrompt(messages: List<ChatMessage>): String {
         // Extract the trimmed always-section from PROMPT 2.5 (from AgentConfig.LOCAL_TASK_PROMPT)
         // This is simplified - in reality we'd need access to the full prompt structure
         val alwaysSection = config.systemPrompt.takeWhile { line ->
             !line.contains("::") && !line.contains("OUTPUT FORMAT")
         }.joinToString("\n")
         
         // Get last 2 user/tool messages as observations
         val recentMessages = messages.takeLast(2)
         val observations = recentMessages.map { msg ->
             when (msg) {
                 is UserMessage -> "User: ${msg.singleText()}"
                 is ToolExecutionResultMessage -> "Tool ${msg.toolName()}: ${msg.text().take(100)}"
                 else -> ""
             }
         }.filter { it.isNotBlank() }.joinToString("\n")
         
         return """
         $alwaysSection

         Recent observations:
         $observations

         Output the next tool call as JSON in the format: 
         {"name": "tool_name", "arguments": {"arg1": "value1"}}
         """.trimIndent()
     }

     private fun createFastConversation(
         systemPrompt: String,
         toolSpecs: List<ToolSpecification>
     ): Conversation {
         // LiteRT-LM only supports one session at a time — close existing first
         try { fastConversation?.close() } catch (_: Exception) {}
         fastConversation = null

         // Convert tool specs to native LiteRT-LM tools
         val nativeTools = toolSpecs.mapNotNull { spec ->
             try {
                 val paramsJson = try { GSON.toJson(spec.parameters()) } catch (_: Exception) { "{}" }
                 com.google.ai.edge.litertlm.tool(object : com.google.ai.edge.litertlm.OpenApiTool {
                     override fun getToolDescriptionJsonString(): String = GSON.toJson(mapOf(
                         "name" to spec.name(),
                         "description" to (spec.description() ?: ""),
                         "parameters" to try { GSON.fromJson(paramsJson, Any::class.java) } catch (_: Exception) { emptyMap<String, Any>() }
                     ))
                     override fun execute(params: String): String = "{}" // Execution handled by DefaultAgentService
                 })
             } catch (e: Exception) {
                 XLog.w(TAG, "Failed to wrap tool for fast engine: ${spec.name()}", e)
                 null
             }
         }

         val convConfig = ConversationConfig(
             systemInstruction = Contents.of(systemPrompt),
             tools = nativeTools,
            samplerConfig = SamplerConfig(
                topK = config.generation.topK ?: 64,
                topP = config.generation.topP ?: 0.95,
                temperature = 0.3  // Fixed for deterministic mechanical-step playback.
            ),
             automaticToolCalling = false  // We handle execution in DefaultAgentService
         )

         val context = ClawApplication.instance
         val modelPath = config.baseUrl
         val lease = LocalModelRuntime.openConversation(
             context = context,
             modelPath = modelPath,
             conversationConfig = convConfig,
             preferCpu = false  // Fast engine prefers GPU if available
         )
         // Note: We don't store the engine here as it's managed by EngineHolder
         return lease.conversation
     }

     private fun sendAndRecover(
         conv: Conversation,
         text: String
     ): Any {
         return try {
             conv.sendMessage(text) ?: ""
         } catch (e: Exception) {
             val recovered = recoverRawOutput(e)
             if (recovered != null) {
                 return recovered
             }
             throw e
         }
     }

     private fun sendAndRecoverFast(
         conv: com.google.ai.edge.litertlm.Conversation,
         text: String,
         gpuFailedRef: Boolean
     ): Any {
         return try {
             conv.sendMessage(text) ?: ""
         } catch (e: Exception) {
             val recovered = recoverRawOutput(e)
             if (recovered != null) {
                 return recovered
             }
             // Check if it's a GPU failure that we should retry on CPU
             if (!gpuFailedRef && LocalModelRuntime.isGpuBackendFailure(e)) {
                 XLog.w(TAG, "sendAndRecoverFast: GPU inference failed, retrying on CPU: ${e.message}")
                 // For fast engine, we just fall back to main engine via caller
                 throw e
             }
             throw e
         }
     }

    private fun recoverRawOutput(e: Exception): String? {
        val errorMsg = e.message ?: return null
        if (!errorMsg.contains("Failed to parse tool calls") || !errorMsg.contains("tool_call")) return null
        XLog.w(TAG, "SDK tool call parse failed, extracting raw output: ${errorMsg.take(200)}")
        return errorMsg.substringAfter("from response: ").substringBefore("code block:")
            .ifEmpty { errorMsg.substringAfter("from response: ") }
            .trim()
    }

    /**
     * True streaming send via sendMessageAsync + MessageCallback. The calling thread blocks on a
     * latch while partial deltas are forwarded to the listener from the SDK inference thread.
     */
    private fun sendStreaming(
        conv: com.google.ai.edge.litertlm.Conversation,
        text: String,
        listener: StreamingListener
    ): Any {
        val done = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>()
        val accumulated = StringBuilder()
        conv.sendMessageAsync(text, object : MessageCallback {
            override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                val part = message.toString()
                if (part.isNotEmpty()) {
                    accumulated.append(part)
                    listener.onPartialText(part)
                }
            }
            override fun onDone() {
                done.countDown()
            }
            override fun onError(throwable: Throwable) {
                errorRef.set(throwable)
                done.countDown()
            }
        })
        done.await()
        val error = errorRef.get()
        if (error != null) {
            val recovered = if (error is Exception) recoverRawOutput(error) else null
            if (recovered != null) return recovered
            listener.onError(error)
            throw RuntimeException(error.message ?: "LiteRT-LM streaming failed", error)
        }
        return accumulated.toString()
    }

    override fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener,
        fast: Boolean
    ): LlmResponse {
        return try {
            chatStreamingInternal(messages, toolSpecs, listener)
        } catch (e: Exception) {
            if (!gpuFailed && LocalModelRuntime.isGpuBackendFailure(e)) {
                XLog.w(TAG, "chatStreaming: GPU inference failed, retrying with CPU: ${e.message}")
                fallbackToCpu()
                chatStreamingInternal(messages, toolSpecs, listener)
            } else {
                throw e
            }
        }
    }

    private fun chatStreamingInternal(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener
    ): LlmResponse {
        ensureEngine()

        if (processedMessageCount == 0 || messages.size < processedMessageCount || sendCount >= 8) {
            val systemPrompt = messages.filterIsInstance<SystemMessage>().firstOrNull()?.text()
                ?: config.systemPrompt.ifEmpty { LOCAL_SYSTEM_PROMPT }
            createConversation(systemPrompt, toolSpecs)
            sendCount = 0
            processedMessageCount = 0
        }

        val newMessages = messages.subList(
            processedMessageCount.coerceAtMost(messages.size),
            messages.size
        )

        var lastResponse: Any? = null

        for ((index, msg) in newMessages.withIndex()) {
            val isLast = index == newMessages.lastIndex
            when (msg) {
                is SystemMessage -> { /* handled in createConversation */ }
                is UserMessage -> {
                    val conv = conversation ?: throw RuntimeException("LiteRT-LM conversation not initialized — engine may have failed to load the model")
                    XLog.d(TAG, "chatStreaming: send user (${msg.singleText().take(80)}...) sendCount=$sendCount last=$isLast")
                    lastResponse = if (isLast) sendStreaming(conv, msg.singleText(), listener)
                        else sendAndRecover(conv, msg.singleText())
                    sendCount++
                }
                is AiMessage -> { /* already in conversation state */ }
                is ToolExecutionResultMessage -> {
                    val truncatedResult = msg.text().take(400)
                    val toolResultText = "[Tool ${msg.toolName()} result]: $truncatedResult"
                    val conv = conversation ?: throw RuntimeException("LiteRT-LM conversation not initialized — engine may have failed to load the model")
                    XLog.d(TAG, "chatStreaming: send toolResult (${toolResultText.take(80)}...) sendCount=$sendCount last=$isLast")
                    lastResponse = if (isLast) sendStreaming(conv, toolResultText, listener)
                        else sendAndRecover(conv, toolResultText)
                    sendCount++
                }
            }
        }

        processedMessageCount = messages.size
        val response = parseResponse(lastResponse)
        listener.onComplete(response)
        return response
    }

    /**
     * Parse LiteRT-LM response into LlmResponse.
     *
     * The response text may contain tool calls in Gemma's function calling format:
     * <tool_call>{"name": "tap", "arguments": {"x": 100, "y": 200}}</tool_call>
     *
     * Or it may be plain text (thinking + final answer).
     */
    private fun parseResponse(response: Any?): LlmResponse {
        // Check for native LiteRT-LM Message with structured tool calls
        if (response is com.google.ai.edge.litertlm.Message) {
            val nativeToolCalls = response.toolCalls
            if (!nativeToolCalls.isNullOrEmpty()) {
                val converted = nativeToolCalls.mapNotNull { tc ->
                    try {
                        ToolExecutionRequest.builder()
                            .id("native_${System.currentTimeMillis()}")
                            .name(tc.name)
                            .arguments(GSON.toJson(tc.arguments))
                            .build()
                    } catch (e: Exception) {
                        XLog.w(TAG, "Failed to convert native ToolCall: ${tc.name}", e)
                        null
                    }
                }
                if (converted.isNotEmpty()) {
                    XLog.i(TAG, "parseResponse: ${converted.size} native tool calls from SDK")
                    val text = response.contents?.toString()?.trim()?.ifEmpty { null }
                    return LlmResponse(text = text, toolExecutionRequests = converted)
                }
            }
        }

        val responseText = response?.toString() ?: ""

        // Fallback: extract tool calls from text (for prompt-based tool calling)
        val toolCalls = extractToolCalls(responseText)

        if (toolCalls.isNotEmpty()) {
            // Remove all tool call markup from text to extract the thinking portion
            val thinkingText = responseText
                .replace(TOOL_CALL_PATTERN, "")
                .replace(GEMMA4_NATIVE_PATTERN, "")
                .replace(TOOL_CALL_BLOCK_PATTERN, "")
                .trim()
                .ifEmpty { null }

            return LlmResponse(
                text = thinkingText,
                toolExecutionRequests = toolCalls
            )
        }

        return LlmResponse(
            text = responseText,
            toolExecutionRequests = emptyList()
        )
    }

    /**
     * Extract tool calls from model output.
     *
     * Gemma 4 uses special tokens for function calling. The format may be:
     * - <tool_call>{"name":"tap","arguments":{"x":100,"y":200}}</tool_call>
     * - ```tool_call\n{"name":"tap","arguments":{"x":100,"y":200}}\n```
     * - Or JSON objects with "name" and "arguments" fields
     *
     * This parser tries multiple patterns.
     */
    private fun extractToolCalls(text: String): List<ToolExecutionRequest> {
        val calls = mutableListOf<ToolExecutionRequest>()

        // Pattern 1: Standard <tool_call>{"name":...,"arguments":{...}}</tool_call>
        // Also handles: <tool_call>tool_name{"key":"value",...}</tool_call>
        TOOL_CALL_PATTERN.findAll(text).forEach { match ->
            val content = match.groupValues[1].trim()
            if (content.startsWith("{")) {
                // Standard JSON format
                parseToolCallJson(content)?.let { calls.add(it) }
            } else {
                // tool_name{...} format — extract name and treat rest as arguments
                val nameEnd = content.indexOf('{')
                if (nameEnd > 0) {
                    val name = content.substring(0, nameEnd).trim()
                    val argsJson = content.substring(nameEnd)
                    // Parse the JSON as arguments directly
                    try {
                        var fixed = argsJson
                        val open = fixed.count { it == '{' }
                        val close = fixed.count { it == '}' }
                        repeat(open - close) { fixed += "}" }
                        val args = GSON.fromJson(fixed, Map::class.java) as Map<*, *>
                        val argsStr = GSON.toJson(args)
                        XLog.d(TAG, "extractToolCalls: parsed name=$name args=$argsStr from tool_name{} format")
                        calls.add(ToolExecutionRequest.builder()
                            .id("local_${System.currentTimeMillis()}")
                            .name(name)
                            .arguments(argsStr)
                            .build())
                    } catch (e: Exception) {
                        XLog.w(TAG, "extractToolCalls: failed to parse tool_name{} format: $content", e)
                    }
                }
            }
        }
        if (calls.isNotEmpty()) {
            XLog.d(TAG, "extractToolCalls: matched ${calls.size} calls via TOOL_CALL_PATTERN")
            return calls
        }

        // Pattern 2: Gemma 4 native token format <|tool_call>call:name{key:<|"|>value<|"|>}<tool_call|>
        // Gemma 4 E2B may emit its built-in token format instead of plain JSON tags
        GEMMA4_NATIVE_PATTERN.findAll(text).forEach { match ->
            parseGemma4NativeCall(match.groupValues[1])?.let { calls.add(it) }
        }
        if (calls.isNotEmpty()) {
            XLog.d(TAG, "extractToolCalls: matched ${calls.size} calls via GEMMA4_NATIVE_PATTERN")
            return calls
        }

        // Pattern 2b: Gemma 4 native WITHOUT closing tag: <|tool_call>call:name(...)
        val gemmaNoClose = Regex("""<\|tool_call>(call:\w+[\(\{].*)""")
        gemmaNoClose.findAll(text).forEach { match ->
            parseGemma4NativeCall(match.groupValues[1].trim())?.let { calls.add(it) }
        }
        if (calls.isNotEmpty()) {
            XLog.d(TAG, "extractToolCalls: matched ${calls.size} calls via GEMMA4_NO_CLOSE")
            return calls
        }

        // Pattern 3: ```tool_call\n...\n``` fenced blocks
        TOOL_CALL_BLOCK_PATTERN.findAll(text).forEach { match ->
            parseToolCallJson(match.groupValues[1])?.let { calls.add(it) }
        }
        if (calls.isNotEmpty()) {
            XLog.d(TAG, "extractToolCalls: matched ${calls.size} calls via TOOL_CALL_BLOCK_PATTERN")
            return calls
        }

        // Pattern 4: Legacy functioncall/function_call prefix format
        // e.g. functioncall: {"name": "tap", "args": {"x": 100, "y": 200}}
        FUNCTION_CALL_PATTERN.findAll(text).forEach { match ->
            parseToolCallJson(match.groupValues[1], argsKey = "args")?.let { calls.add(it) }
        }
        if (calls.isNotEmpty()) {
            XLog.d(TAG, "extractToolCalls: matched ${calls.size} calls via FUNCTION_CALL_PATTERN")
        }

        return calls
    }

    /**
     * Parse Gemma 4's native token format into a ToolExecutionRequest.
     *
     * Gemma 4 emits: call:tool_name{key:<|"|>value<|"|>,key2:<|"|>value2<|"|>}
     * The <|"|> tokens are Gemma's quote markers. We strip them and reconstruct JSON.
     *
     * Example input: "call:tap{x:<|"|>540<|"|>,y:<|"|>960<|"|>}"
     * Parsed as: name="tap", arguments={"x":"540","y":"960"}
     */
    private fun parseGemma4NativeCall(rawContent: String): ToolExecutionRequest? {
        return try {
            val content = rawContent.trim()
            XLog.d(TAG, "parseGemma4NativeCall: raw=$content")

            // Extract name and params — supports both call:name{...} and call:name("...")
            val nameMatch = Regex("""^call:(\w+)[\(\{]""").find(content) ?: run {
                return parseToolCallJson(content)
            }
            val name = nameMatch.groupValues[1]

            // Extract params — could be {key:value} or ("value") or (key=value)
            val openChar = content[nameMatch.range.last]
            val closeChar = if (openChar == '{') '}' else ')'
            val paramsStart = content.indexOf(openChar)
            val paramsEnd = content.lastIndexOf(closeChar)
            if (paramsStart < 0 || paramsEnd <= paramsStart) return null
            val paramsRaw = content.substring(paramsStart + 1, paramsEnd)

            // If simple string arg like ("WhatsApp"), convert to first param of tool
            if (openChar == '(' && !paramsRaw.contains(':') && !paramsRaw.contains('=')) {
                val cleanVal = paramsRaw.trim().removeSurrounding("\"").removeSurrounding("<|\"", "\"|>")
                val argsJson = GSON.toJson(mapOf("app_name" to cleanVal, "package_name" to cleanVal, "text" to cleanVal, "key" to cleanVal, "summary" to cleanVal))
                XLog.d(TAG, "parseGemma4NativeCall: name=$name simpleArg=$cleanVal args=$argsJson")
                return ToolExecutionRequest.builder()
                    .id("local_${System.currentTimeMillis()}")
                    .name(name)
                    .arguments(argsJson)
                    .build()
            }

            // Parse key-value pairs from multiple possible formats
            val argsMap = mutableMapOf<String, String>()

            // Format 1: key:<|"|>value<|"|> (Gemma native tokens)
            val gemmaKv = Regex("""(\w+):<\|"\|>(.*?)<\|"\|>""")
            gemmaKv.findAll(paramsRaw).forEach { kv ->
                argsMap[kv.groupValues[1]] = kv.groupValues[2]
            }
            // Format 2: key="value" or key:"value" (equals or colon with quotes)
            val quotedKv = Regex("""(\w+)[=:]"([^"]*?)"""")
            quotedKv.findAll(paramsRaw).forEach { kv ->
                val key = kv.groupValues[1]
                if (!argsMap.containsKey(key)) {
                    argsMap[key] = kv.groupValues[2]
                }
            }
            // Format 3: key:value (bare numeric/boolean)
            val bareKv = Regex("""(\w+):([^,<}"=\s]+)""")
            bareKv.findAll(paramsRaw).forEach { kv ->
                val key = kv.groupValues[1]
                if (!argsMap.containsKey(key)) {
                    argsMap[key] = kv.groupValues[2]
                }
            }

            val argsJson = GSON.toJson(argsMap)
            XLog.d(TAG, "parseGemma4NativeCall: name=$name args=$argsJson")

            ToolExecutionRequest.builder()
                .id("local_${System.currentTimeMillis()}")
                .name(name)
                .arguments(argsJson)
                .build()
        } catch (e: Exception) {
            XLog.w(TAG, "parseGemma4NativeCall failed: $rawContent", e)
            null
        }
    }

    private fun parseToolCallJson(json: String, argsKey: String = "arguments"): ToolExecutionRequest? {
        return try {
            val trimmed = json.trim()
            // Handle multiple tool calls separated by commas: {...},{...}
            // Take only the FIRST one (one tool per turn rule)
            // We need to find the matching closing brace for the first object
            val firstJson = if (trimmed.startsWith("{") && trimmed.contains("},{")) {
                // Find balanced braces for first JSON object
                var depth = 0
                var endIdx = 0
                for (i in trimmed.indices) {
                    when (trimmed[i]) {
                        '{' -> depth++
                        '}' -> { depth--; if (depth == 0) { endIdx = i; break } }
                    }
                }
                trimmed.substring(0, endIdx + 1)
            } else {
                trimmed
            }

            // Fix malformed JSON from LLM
            var fixedJson = firstJson
            // Auto-close missing braces
            val openBraces = fixedJson.count { it == '{' }
            val closeBraces = fixedJson.count { it == '}' }
            repeat(openBraces - closeBraces) { fixedJson += "}" }

            val map = try {
                GSON.fromJson(fixedJson, Map::class.java) as Map<*, *>
            } catch (e: Exception) {
                // Fallback: extract name and arguments with regex
                XLog.w(TAG, "JSON parse failed, trying regex fallback: $fixedJson")
                val nameRegex = Regex(""""name"\s*:\s*"(\w+)"""")
                val argsRegex = Regex(""""arguments"\s*:\s*\{([^}]*)\}""")
                val n = nameRegex.find(fixedJson)?.groupValues?.get(1) ?: return null
                val argsRaw = argsRegex.find(fixedJson)?.groupValues?.get(1) ?: ""
                // Parse key-value pairs from arguments
                val argsMap = mutableMapOf<String, Any>()
                Regex(""""(\w+)"\s*:\s*"([^"]*?)"""").findAll(argsRaw).forEach {
                    argsMap[it.groupValues[1]] = it.groupValues[2]
                }
                mapOf("name" to n, "arguments" to argsMap)
            }
            val name = map["name"]?.toString() ?: return null
            val args = map[argsKey]
            val argsJson = if (args is Map<*, *>) GSON.toJson(args) else args?.toString() ?: "{}"

            ToolExecutionRequest.builder()
                .id("local_${System.currentTimeMillis()}")
                .name(name)
                .arguments(argsJson)
                .build()
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to parse tool call JSON: $json", e)
            null
        }
    }

    override fun close() {
        XLog.i(TAG, "close() — closing conversation only (engine stays in EngineHolder)")
        try { conversation?.close() } catch (e: Exception) { XLog.w(TAG, "close conversation error", e) }
        conversation = null
        engine = null
        processedMessageCount = 0
        XLog.i(TAG, "close() — done")
    }

    companion object {
        private const val TAG = "LocalLlmClient"

        private const val LOCAL_SYSTEM_PROMPT = """You control an Android phone via tools. Screen shows elements as: [n1] "text" [flags] (x,y) where n1 is the node ID and (x,y) is the tap target.

Rules:
- Use open_app(package_name) to open apps, e.g. open_app("com.whatsapp"). It verifies the app is foreground and returns a verified failure if not.
- Use tap_node(text="Send") / tap_node(content_desc="...") / tap_node(resource_id="pkg:id/btn") to tap by stable semantic properties (preferred); node_id="n3" works but is re-resolved live and may be stale after a transition.
- Use tap(x,y) only when you know exact coordinates and no node ID is available
- Use input_text(text) to type into focused editable fields (focus and text entry are verified)
- Use system_key(key) with key="back","home","enter" for navigation
- Use finish(summary) when task is complete
- One tool per turn. Read screen after each action. Do not re-cache node IDs across UI transitions.
- To message someone: use send_message(contact="Name", message="text", app="WhatsApp"). This handles everything automatically.
- Do NOT try to navigate messaging apps manually — always use send_message tool instead."""

        // Pattern 1: Standard <tool_call>...</tool_call> tags (preferred format)
        private val TOOL_CALL_PATTERN = Regex("""<tool_call>(.*?)</tool_call>""", RegexOption.DOT_MATCHES_ALL)

        // Pattern 2: Gemma 4 native trained token format: <|tool_call>call:name{key:<|"|>value<|"|>}<tool_call|>
        // This is the format Gemma 4 E2B emits when using its built-in function calling tokens
        private val GEMMA4_NATIVE_PATTERN = Regex("""<\|tool_call>(.*?)<tool_call\|>""", RegexOption.DOT_MATCHES_ALL)

        // Pattern 3: Fenced code block format
        private val TOOL_CALL_BLOCK_PATTERN = Regex("""```tool_call\s*\n(.*?)\n\s*```""", RegexOption.DOT_MATCHES_ALL)

        // Pattern 4: Legacy functioncall/function_call prefix format
        private val FUNCTION_CALL_PATTERN = Regex("""(?:functioncall|function_call|tool_call)\s*:\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL)
    }
}

/**
 * Wraps a LangChain4j ToolSpecification as a LiteRT-LM OpenApiTool.
 * Only declares the schema — execution is handled by the agent loop.
 */
private class DynamicOpenApiTool(private val spec: ToolSpecification) : OpenApiTool {

    override fun getToolDescriptionJsonString(): String {
        val json = buildMap {
            put("name", spec.name())
            put("description", spec.description() ?: "")
            spec.parameters()?.let { params ->
                put("parameters", buildMap {
                    put("type", "object")
                    val properties = mutableMapOf<String, Any>()
                    val required = mutableListOf<String>()

                    // Extract properties from JsonObjectSchema
                    params.properties()?.forEach { (name, schema) ->
                        val prop = mutableMapOf<String, Any>()
                        prop["description"] = schema.description() ?: ""
                        prop["type"] = when (schema.javaClass.simpleName) {
                            "JsonIntegerSchema" -> "integer"
                            "JsonNumberSchema" -> "number"
                            "JsonBooleanSchema" -> "boolean"
                            else -> "string"
                        }
                        properties[name] = prop
                    }
                    put("properties", properties)

                    params.required()?.let { put("required", it) }
                })
            }
        }
        return Gson().toJson(json)
    }

    override fun execute(paramsJsonString: String): String {
        // Not called with automaticToolCalling = false
        return """{"result": "ok"}"""
    }
}
