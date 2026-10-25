// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.llm

import com.returngift.agent.agent.AgentConfig
import com.returngift.agent.agent.LlmProvider
import com.returngift.agent.agent.langchain.http.OkHttpClientBuilderAdapter
import com.returngift.agent.agent.routing.ProviderCatalog
import com.returngift.agent.agent.routing.ProviderRouter
import com.returngift.agent.agent.routing.RoutingLedger
import com.returngift.agent.agent.routing.TokenLedger
import com.returngift.agent.agent.tracker.ExecutionTracker
import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.UserMessage

/**
 * Provider failover inside the APK — the piece `OmniRoute` normally provides.
 *
 * Instead of pointing the app at a gateway (`/v1/chat/completions` with
 * `model: "auto"`), this client:
 *
 *  1. picks a (provider, model) pair with [ProviderRouter] using the *device's own*
 *     keys, the request's needs (tokens, tools, vision) and the token/cost ledger;
 *  2. executes the call against that provider directly;
 *  3. on 429/quota/5xx/timeout, cools the provider down and moves to the next
 *     candidate in the same chain — the caller never sees a transient failure;
 *  4. records tokens + cost so the next decision is informed.
 *
 * Provider coverage is intentionally small ("few important providers"): anything
 * that speaks the OpenAI chat-completions shape is served by [OpenAiLlmClient] with
 * a different base URL, Anthropic by [AnthropicLlmClient]. No vendor SDKs.
 */
class RoutedLlmClient(
    private val config: AgentConfig,
    private val httpClientBuilder: OkHttpClientBuilderAdapter,
    private val ledger: TokenLedger = RoutingLedger.ledger,
) : LlmClient {

    companion object {
        private const val TAG = "RoutedLlmClient"

        /**
         * Base URLs for OpenAI-compatible endpoints. Data, not code: adding a
         * provider means adding a line here plus a catalog row.
         */
        val OPENAI_COMPATIBLE_BASE_URLS = mapOf(
            "OPENAI" to "https://api.openai.com/v1",
            // Gemini publishes an OpenAI-compatible surface under /openai.
            "GOOGLE" to "https://generativelanguage.googleapis.com/v1beta/openai",
            "GROQ" to "https://api.groq.com/openai/v1",
            "OPENROUTER" to "https://openrouter.ai/api/v1",
        )

        /** Providers this executor knows how to talk to. */
        val SUPPORTED = OPENAI_COMPATIBLE_BASE_URLS.keys + setOf("ANTHROPIC")

        /** Keys come from the per-provider slot first, then the active config. */
        fun credentialFor(providerId: String, config: AgentConfig): String {
            val stored = runCatching { KVUtils.getApiKeyForProvider(providerId) }.getOrDefault("")
            if (stored.isNotBlank()) return stored
            return if (config.provider.name.equals(providerId, ignoreCase = true)) config.apiKey else ""
        }
    }

    private val delegates = mutableMapOf<String, LlmClient>()

    override fun chat(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        fast: Boolean,
    ): LlmResponse = withFailover(messages, toolSpecs) { client, route ->
        client.chat(messages, toolSpecs, fast).also { record(route, messages, it) }
    }

    override fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener,
        fast: Boolean,
    ): LlmResponse = withFailover(messages, toolSpecs) { client, route ->
        client.chatStreaming(messages, toolSpecs, listener, fast).also { record(route, messages, it) }
    }

    override fun close() {
        delegates.values.forEach { runCatching { it.close() } }
        delegates.clear()
    }

    // ── chain execution ─────────────────────────────────────────────────────

    private inline fun withFailover(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        call: (LlmClient, ProviderCatalog.ModelRoute) -> LlmResponse,
    ): LlmResponse {
        val promptTokens = estimatePromptTokens(messages)
        val request = ProviderRouter.Request(
            promptTokens = promptTokens,
            completionTokens = 800,
            needsTools = toolSpecs.isNotEmpty(),
            providers = usableProviders(),
            strategy = ProviderRouter.Strategy.AUTO,
        )
        var decision = try {
            ProviderRouter.decide(request, ledger)
        } catch (e: ProviderRouter.NoRouteAvailableException) {
            throw IllegalStateException(
                "No configured AI provider can serve this task. Add a key in Settings → Model. (${e.message})",
                e,
            )
        }
        XLog.i(TAG, "routing: ${RoutingLedger.explain(decision)}")

        var attempts = 0
        while (true) {
            attempts++
            val route = decision.primary.route
            try {
                return call(clientFor(route), route)
            } catch (e: Exception) {
                val failure = ProviderRouter.classify(
                    httpStatusOf(e),
                    e.message,
                    e.javaClass.name,
                )
                XLog.w(TAG, "${route.modelId} failed ($failure): ${e.message}")
                if (failure == ProviderRouter.Failure.FATAL && attempts >= MAX_ATTEMPTS) throw e
                val next = ProviderRouter.onFailure(decision, failure, ledger)
                    ?: throw e
                decision = next
                if (attempts >= MAX_ATTEMPTS) throw e
            }
        }
    }

    private fun usableProviders(): Set<String> {
        val configured = ProviderCatalog.providerIds()
            .filter { it in SUPPORTED }
            .filter { credentialFor(it, config).isNotBlank() }
            .toSet()
        // Nothing configured yet (or only the active key): let the router decide.
        return configured.ifEmpty {
            ProviderCatalog.providerIds().filter { it in SUPPORTED }.toSet()
        }
    }

    private fun clientFor(route: ProviderCatalog.ModelRoute): LlmClient {
        val cacheKey = "${route.providerId}/${route.modelId}"
        return delegates.getOrPut(cacheKey) {
            val providerConfig = config.copy(
                provider = llmProviderFor(route.providerId),
                modelName = route.modelId,
                baseUrl = baseUrlFor(route),
                apiKey = credentialFor(route.providerId, config),
            )
            when (route.providerId.uppercase()) {
                "ANTHROPIC" -> AnthropicLlmClient(providerConfig, httpClientBuilder)
                else -> OpenAiLlmClient(providerConfig, httpClientBuilder)
            }
        }
    }

    private fun baseUrlFor(route: ProviderCatalog.ModelRoute): String {
        OPENAI_COMPATIBLE_BASE_URLS[route.providerId.uppercase()]?.let { return it }
        // Anthropic keeps the configured URL (or its default).
        return config.baseUrl
    }

    private fun llmProviderFor(providerId: String): LlmProvider = when (providerId.uppercase()) {
        "ANTHROPIC" -> LlmProvider.ANTHROPIC
        else -> LlmProvider.OPENAI
    }

    private fun record(
        route: ProviderCatalog.ModelRoute,
        messages: List<ChatMessage>,
        response: LlmResponse,
    ) {
        val promptTokens = estimatePromptTokens(messages)
        val outputTokens = response.tokenCount.coerceAtLeast(0)
        RoutingLedger.record(route, promptTokens, outputTokens)
        // Data-egress ledger: one row per real cloud call, with the ACTUAL provider.
        runCatching {
            ExecutionTracker.recordLeave(
                provider = route.providerId.lowercase(),
                model = route.modelId,
                inputTokens = promptTokens,
                outputTokens = outputTokens,
            )
        }
    }

    private fun estimatePromptTokens(messages: List<ChatMessage>): Int {
        val chars = messages.sumOf { message ->
            when (message) {
                is UserMessage -> message.singleText().length
                is AiMessage -> message.text().length
                else -> 0
            }
        }
        return (chars / 4).coerceAtLeast(1)
    }

    /** Pull an HTTP status out of whatever the vendor client threw, if it has one. */
    private fun httpStatusOf(e: Throwable): Int? {
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < 4) {
            val text = current.message.orEmpty()
            Regex("\\b(4\\d{2}|5\\d{2})\\b").find(text)?.let { return it.value.toInt() }
            current = current.cause
            depth++
        }
        return null
    }
}
