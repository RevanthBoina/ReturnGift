// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.llm

import com.returngift.agent.agent.AgentConfig
import com.returngift.agent.agent.LlmProvider
import com.returngift.agent.agent.langchain.http.OkHttpClientBuilderAdapter
import com.returngift.agent.agent.routing.ContextTrimmer
import com.returngift.agent.agent.routing.ProviderCatalog
import com.returngift.agent.agent.routing.ProviderRouter
import com.returngift.agent.agent.routing.RoutingLedger
import com.returngift.agent.agent.routing.TokenLedger
import com.returngift.agent.agent.tracker.ExecutionTracker
import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog
import dev.langchain4j.agent.tool.ToolSpecification
import com.returngift.agent.agent.routing.RequestProfiler
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
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

        /**
         * Rounds in one task before we stop trying to fail over.
         *
         * Bounded on purpose: an exhausted chain that keeps hopping burns quota and
         * latency. Three attempts covers "primary down, one fallback cold, one retry".
         */
        private const val MAX_ATTEMPTS = 3

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
    ): LlmResponse {
        val fitted = fitToBudget(messages, toolSpecs)
        return withFailover(fitted.second) { client, route ->
            client.chat(fitted.first, toolSpecs, fast).also { record(route, fitted.second, it) }
        }
    }

    override fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener,
        fast: Boolean,
    ): LlmResponse {
        val fitted = fitToBudget(messages, toolSpecs)
        return withFailover(fitted.second) { client, route ->
            client.chatStreaming(fitted.first, toolSpecs, listener, fast)
                .also { record(route, fitted.second, it) }
        }
    }

    override fun close() {
        delegates.values.forEach { runCatching { it.close() } }
        delegates.clear()
    }

    // ── prompt fitting ──────────────────────────────────────────────────────

    /**
     * Drop the oldest droppable messages until the prompt fits the largest context
     * we know about, and return the trimmed list with its (re-measured) facts.
     *
     * This happens *before* routing, so the router sees the prompt we will actually
     * send — not the one we wished we could. The system prompt, the newest messages
     * and the latest user turn are never dropped ([ContextTrimmer]).
     */
    private fun fitToBudget(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
    ): Pair<List<ChatMessage>, RequestProfiler.Facts> {
        val facts = factsOf(messages, toolSpecs)
        val budget = ProviderCatalog.models.maxOfOrNull { it.contextTokens } ?: return messages to facts
        if (!RequestProfiler.willOverflow(facts, budget)) return messages to facts

        val kinds = messages.map { message ->
            when (message) {
                is SystemMessage -> "system"
                is UserMessage -> "user"
                is AiMessage -> "assistant"
                is ToolExecutionResultMessage -> "tool"
                else -> "other"
            }
        }
        val sizes = messages.map { message ->
            runCatching { message.toString().length }.getOrDefault(0)
        }
        val excess = RequestProfiler.excessChars(facts, budget)
        val drop = ContextTrimmer.plan(ContextTrimmer.classify(kinds, sizes), excess)
        if (drop.isEmpty()) {
            XLog.w(TAG, "prompt exceeds $budget tokens and nothing is safe to drop (${excess} chars over)")
            return messages to facts
        }
        val kept = messages.filterIndexed { index, _ -> index !in drop }
        val trimmedFacts = factsOf(kept, toolSpecs)
        XLog.i(
            TAG,
            "trimmed ${drop.size} message(s) to fit $budget tokens " +
                "(${RequestProfiler.estimatePromptTokens(facts)} → " +
                "${RequestProfiler.estimatePromptTokens(trimmedFacts)})"
        )
        return kept to trimmedFacts
    }

    // ── chain execution ─────────────────────────────────────────────────────

    private inline fun withFailover(
        facts: RequestProfiler.Facts,
        call: (LlmClient, ProviderCatalog.ModelRoute) -> LlmResponse,
    ): LlmResponse {
        // Vision/tools/tokens all come from the real prompt (review fix): the first
        // version passed tools only, so a screenshot could be routed to a model that
        // cannot see it, and the large system prompt was missing from the estimate.
        val request = RequestProfiler.request(
            facts = facts,
            strategy = ProviderRouter.Strategy.AUTO,
            providers = usableProviders(),
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
        facts: RequestProfiler.Facts,
        response: LlmResponse,
    ) {
        val promptTokens = RequestProfiler.estimatePromptTokens(facts)
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

    /**
     * Measure everything that is actually sent: system rules, user turns, assistant
     * turns, tool results, tool schemas and images.
     *
     * Message *types* are matched on the known langchain4j classes; image detection
     * is done reflectively so an API change cannot break the build — it only means
     * images stop being counted (and we log that once).
     */
    private fun factsOf(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
    ): RequestProfiler.Facts {
        var system = 0
        var user = 0
        var assistant = 0
        var toolResults = 0
        var images = 0
        messages.forEach { message ->
            when (message) {
                is SystemMessage -> system += message.text().length
                is UserMessage -> {
                    user += textLengthSafely(message)
                    images += imageCountOf(message)
                }
                is AiMessage -> assistant += message.text().length
                is ToolExecutionResultMessage -> toolResults += message.text().length
                else -> user += message.toString().length
            }
        }
        val schemaChars = toolSpecs.sumOf { spec ->
            runCatching { spec.name().length + spec.description().length + 120 }.getOrDefault(120)
        }
        return RequestProfiler.Facts(
            systemChars = system,
            userChars = user,
            assistantChars = assistant,
            toolResultChars = toolResults,
            imageCount = images,
            messageCount = messages.size,
            toolSpecCount = toolSpecs.size,
            toolSchemaChars = schemaChars,
        )
    }

    /** `singleText()` throws when a user turn holds several parts (e.g. text + image). */
    private fun textLengthSafely(message: UserMessage): Int =
        runCatching { message.singleText().length }
            .getOrElse { runCatching { message.contents().sumOf { it.toString().length } }.getOrDefault(0) }

    /** Count image parts without binding to a specific content class name. */
    private fun imageCountOf(message: UserMessage): Int = runCatching {
        val contents = message.javaClass.getMethod("contents").invoke(message) as? List<*>
            ?: return 0
        contents.count { part ->
            val name = part?.javaClass?.simpleName.orEmpty()
            name.contains("Image", ignoreCase = true)
        }
    }.getOrDefault(0)

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
