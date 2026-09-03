// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.llm

import com.returngift.agent.agent.AgentConfig
import com.returngift.agent.agent.langchain.http.OkHttpClientBuilderAdapter
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * OmniRoute LLM Client - Uses OpenAI-compatible API for OmniRoute gateway.
 * 
 * OmniRoute (https://omniroute.online) provides a unified API gateway that routes
 * requests to 268+ LLM providers including Claude, OpenAI, Gemini, Groq, and more.
 * 
 * Configuration:
 * - baseUrl: Your OmniRoute server URL (e.g., http://localhost:20128/v1 or your cloud instance)
 * - apiKey: Your OmniRoute API key from the dashboard
 * - modelName: Model to use (e.g., "auto" for automatic model selection, or specific models)
 * 
 * Benefits:
 * - Access to 268+ LLM providers through a single API
 * - Automatic model routing based on capability requirements
 * - Cost optimization through provider comparison
 * - Free tier available with multiple providers
 */
class OmniRouteLlmClient(
    private val config: AgentConfig,
    private val httpClientBuilder: OkHttpClientBuilderAdapter
) : LlmClient {

    companion object {
        const val DEFAULT_BASE_URL = "http://localhost:20128/v1"
        const val DEFAULT_MODEL = "auto"
        
        // Popular OmniRoute models
        const val MODEL_AUTO = "auto"
        const val MODEL_CLAUDE_OPUS = "anthropic/claude-opus-4-5"
        const val MODEL_CLAUDE_SONNET = "anthropic/claude-sonnet-4"
        const val MODEL_GPT4O = "openai/gpt-4o"
        const val MODEL_GPT4_TURBO = "openai/gpt-4-turbo"
        const val MODEL_GEMINI_PRO = "google/gemini-pro"
        const val MODEL_GROQ_LLAMA = "groq/llama-3.1-70b"
        const val MODEL_OLLAMA_70B = "ollama/llama3.1:70b"
    }

    private val baseUrl: String = config.baseUrl.ifEmpty { DEFAULT_BASE_URL }
    private val modelName: String = config.modelName.ifEmpty { DEFAULT_MODEL }

    private val chatModel: ChatModel by lazy { buildChatModel() }
    private val streamingChatModel: StreamingChatModel by lazy { buildStreamingChatModel() }

    private fun buildChatModel(): ChatModel {
        val builder = OpenAiChatModel.builder()
            .httpClientBuilder(httpClientBuilder)
            .apiKey(config.apiKey)
            .modelName(modelName)
            .temperature(config.generation.temperature ?: config.temperature)
        config.generation.topP?.let { builder.topP(it) }
        config.generation.presencePenalty?.let { builder.presencePenalty(it) }
        config.generation.frequencyPenalty?.let { builder.frequencyPenalty(it) }
        config.generation.seed?.let { builder.seed(it.toInt()) }
        config.generation.stopSequences?.let { builder.stop(it) }
        config.generation.outputTokenLimit?.let { builder.maxTokens(it) }
        builder.baseUrl(baseUrl)
        return builder.build()
    }

    private fun buildStreamingChatModel(): StreamingChatModel {
        val builder = OpenAiStreamingChatModel.builder()
            .httpClientBuilder(httpClientBuilder)
            .apiKey(config.apiKey)
            .modelName(modelName)
            .temperature(config.generation.temperature ?: config.temperature)
        config.generation.topP?.let { builder.topP(it) }
        config.generation.presencePenalty?.let { builder.presencePenalty(it) }
        config.generation.frequencyPenalty?.let { builder.frequencyPenalty(it) }
        config.generation.seed?.let { builder.seed(it.toInt()) }
        config.generation.stopSequences?.let { builder.stop(it) }
        config.generation.outputTokenLimit?.let { builder.maxTokens(it) }
        builder.baseUrl(baseUrl)
        return builder.build()
    }

    override fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>, fast: Boolean): LlmResponse {
        val request = ChatRequest.builder()
            .messages(messages)
            .toolSpecifications(toolSpecs)
            .build()
        val response = chatModel.chat(request)
        return response.toLlmResponse()
    }

    override fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener,
        fast: Boolean
    ): LlmResponse {
        val request = ChatRequest.builder()
            .messages(messages)
            .toolSpecifications(toolSpecs)
            .build()

        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<LlmResponse>()
        val errorRef = AtomicReference<Throwable>()

        streamingChatModel.chat(request, object : StreamingChatResponseHandler {
            override fun onPartialResponse(token: String) {
                listener.onPartialText(token)
            }

            override fun onCompleteResponse(response: ChatResponse) {
                val llmResponse = response.toLlmResponse()
                resultRef.set(llmResponse)
                listener.onComplete(llmResponse)
                latch.countDown()
            }

            override fun onError(error: Throwable) {
                errorRef.set(error)
                listener.onError(error)
                latch.countDown()
            }
        })

        if (!latch.await(120, TimeUnit.SECONDS)) {
            throw RuntimeException("OmniRoute streaming response timed out after 120 seconds")
        }
        errorRef.get()?.let { throw RuntimeException("OmniRoute streaming error", it) }
        return resultRef.get()
    }
}
