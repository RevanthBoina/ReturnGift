// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.llm

import com.returngift.agent.agent.AgentConfig
import com.returngift.agent.agent.DefaultAgentService
import com.returngift.agent.agent.LlmProvider
import com.returngift.agent.agent.langchain.http.OkHttpClientBuilderAdapter
import com.returngift.agent.agent.tracker.ExecutionTracker
import com.returngift.agent.utils.XLog

object LlmClientFactory {

    private const val TAG = "LlmClientFactory"

    fun create(config: AgentConfig): LlmClient {
        val httpClientBuilder = OkHttpClientBuilderAdapter().apply {
            if (DefaultAgentService.FILE_LOGGING_ENABLED && DefaultAgentService.FILE_LOGGING_CACHE_DIR != null) {
                setFileLoggingEnabled(true, DefaultAgentService.FILE_LOGGING_CACHE_DIR)
            }
        }
        return when (config.provider) {
            LlmProvider.OPENAI -> OpenAiLlmClient(config, httpClientBuilder)
            LlmProvider.ANTHROPIC -> AnthropicLlmClient(config, httpClientBuilder)
            LlmProvider.LOCAL -> LocalLlmClient(config)
            LlmProvider.OMNIROUTE -> OmniRouteLlmClient(config, httpClientBuilder)
        }.also { client ->
            // P3.4: Wrap the client to log LEAVE events (data egress to cloud providers)
            // at the ONE site per AGENTS.md — all providers' response path.
            wrapWithLeaveTracking(client, config)
        }
    }

    /**
     * P3.4: Wrap an LlmClient to record LEAVE events for data-outgoing requests.
     *
     * Per the AGENTS.md mandate, LlmClientFactory is the single choke point for
     * all cloud calls (OpenAI, Anthropic, OmniRoute). Local model calls do NOT
     * generate LEAVE events.
     *
     * We wrap the client by creating a tracking proxy that records:
     * - provider: OPENAI / ANTHROPIC / OMNIROUTE / LOCAL
     * - model: the model name (e.g. "gpt-4o-mini", "claude-3-5-sonnet")
     * - tokenUsage: approximate output tokens (from LlmResponse.tokenCount)
     *
     * The zero-row test asserts: for LOCAL provider, NO LEAVE events are recorded.
     */
    private fun wrapWithLeaveTracking(client: LlmClient, config: AgentConfig): LlmClient {
        // LOCAL model never generates LEAVE events — do not wrap.
        if (config.provider == LlmProvider.LOCAL) return client

        val providerName = when (config.provider) {
            LlmProvider.OPENAI -> "openai"
            LlmProvider.ANTHROPIC -> "anthropic"
            LlmProvider.OMNIROUTE -> "omniroute"
            LlmProvider.LOCAL -> "local" // unreachable, guarded above
        }
        val modelName = config.modelName

        return LlmClientLeaveTracker(client, providerName, modelName)
    }
}

/**
 * P3.4: Proxy wrapper that records a LEAVE event on each chat completion.
 *
 * Data egress ledger: every cloud LLM response (not just local) produces ONE row.
 * The zero-row test asserts LOCAL calls produce zero rows.
 */
class LlmClientLeaveTracker(
    private val delegate: LlmClient,
    private val provider: String,
    private val model: String
) : LlmClient by delegate {

    override fun chat(messages: List<dev.langchain4j.data.message.ChatMessage>,
                      toolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>,
                      fast: Boolean): LlmResponse {
        val response = delegate.chat(messages, toolSpecs, fast)

        // Record LEAVE event: one row per response
        ExecutionTracker.recordLeave(
            provider = provider,
            model = model,
            inputTokens = messages.sumOf { msg ->
                // Approximate input tokens by message text length
                when (msg) {
                    is dev.langchain4j.data.message.UserMessage ->
                        msg.singleText().length / 4
                    is dev.langchain4j.data.message.AiMessage ->
                        msg.text().length / 4
                    else -> 0
                }
            },
            outputTokens = response.tokenCount
        )

        XLog.d("LlmClientLeaveTracker", "LEAVE recorded: provider=$provider model=$model outputTokens=${response.tokenCount}")
        return response
    }

    override fun chatStreaming(
        messages: List<dev.langchain4j.data.message.ChatMessage>,
        toolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>,
        listener: StreamingListener,
        fast: Boolean
    ): LlmResponse {
        val response = delegate.chatStreaming(messages, toolSpecs, listener, fast)

        // Record LEAVE event after streaming completes
        ExecutionTracker.recordLeave(
            provider = provider,
            model = model,
            inputTokens = messages.sumOf { msg ->
                when (msg) {
                    is dev.langchain4j.data.message.UserMessage ->
                        msg.singleText().length / 4
                    is dev.langchain4j.data.message.AiMessage ->
                        msg.text().length / 4
                    else -> 0
                }
            },
            outputTokens = response.tokenCount
        )

        XLog.d("LlmClientLeaveTracker", "LEAVE (streaming) recorded: provider=$provider model=$model outputTokens=${response.tokenCount}")
        return response
    }
}
