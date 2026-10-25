// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.routing

import com.returngift.agent.agent.CloudProvider

/**
 * In-app provider catalog — the "few important providers" version of a routing
 * gateway, with no server and no SDK.
 *
 * Prices/context windows are **not** duplicated here: they are read from
 * [CloudProvider], which already drives the settings UI, so a price correction is
 * one edit. This layer adds only what routing needs and the UI does not: a quality
 * estimate, an expected latency, and capability flags.
 *
 * Cost of the whole table: a few hundred bytes of constants. Compare with running
 * OmniRoute (a Node server with a database) or bundling a provider SDK per vendor.
 */
object ProviderCatalog {

    /** Capabilities a request may require from a model. */
    data class Capabilities(
        val tools: Boolean = true,
        val vision: Boolean = false,
        val streaming: Boolean = true,
        /** True when the latency/quality numbers are measured rather than a guess. */
        val calibrated: Boolean = false,
    )

    /** Routing metadata for one model id (everything else comes from [CloudProvider]). */
    data class ModelTraits(
        val quality: Double,
        val latencyMs: Long,
        val capabilities: Capabilities = Capabilities(),
    ) {
        init {
            require(quality in 0.0..5.0) { "quality must be 0..5 (got $quality)" }
            require(latencyMs > 0) { "latencyMs must be positive" }
        }
    }

    data class ModelRoute(
        val providerId: String,
        val providerLabel: String,
        val modelId: String,
        val modelLabel: String,
        val inputPerM: Double,
        val outputPerM: Double,
        val contextTokens: Int,
        val traits: ModelTraits,
        /** False when the vendor price is unknown (gateways, custom endpoints). */
        val costKnown: Boolean,
    ) {
        /** Rough cost of a turn, in USD, assuming 3:1 prompt:completion. */
        fun estimateCostUsd(promptTokens: Int, completionTokens: Int): Double =
            (promptTokens / 1_000_000.0) * inputPerM + (completionTokens / 1_000_000.0) * outputPerM

        /** 0..1, cheaper is smaller; unknown cost is neutral (0.5). */
        fun costScore(maxInput: Double): Double {
            if (!costKnown || maxInput <= 0.0) return 0.5
            return (inputPerM / maxInput).coerceIn(0.0, 1.0)
        }

        fun latencyScore(maxLatencyMs: Long): Double {
            if (maxLatencyMs <= 0L) return 0.5
            return (traits.latencyMs.toDouble() / maxLatencyMs).coerceIn(0.0, 1.0)
        }
    }

    /**
     * Hand-tuned routing traits. Deliberately coarse: three tiers of latency and a
     * 0..5 quality band are enough to order candidates, and they are cheap to keep
     * current. Unknown model ids fall back to [DEFAULT_TRAITS].
     */
    private val TRAITS: Map<String, ModelTraits> = mapOf(
        "gpt-4.1-nano" to ModelTraits(2.6, 500, Capabilities()),
        "gpt-4.1-mini" to ModelTraits(3.6, 800, Capabilities(vision = true)),
        "gpt-4.1" to ModelTraits(4.6, 1_600, Capabilities(vision = true)),
        "gpt-4o-mini" to ModelTraits(3.1, 700, Capabilities(vision = true)),
        "gpt-4o" to ModelTraits(4.3, 1_200, Capabilities(vision = true)),
        "claude-haiku-4-5" to ModelTraits(4.0, 650),
        "claude-sonnet-4-6" to ModelTraits(4.9, 1_400),
        "gemini-2.5-flash" to ModelTraits(3.8, 600, Capabilities(vision = true)),
        "gemini-2.5-pro" to ModelTraits(4.8, 1_800, Capabilities(vision = true)),
        // Gateway: one "auto" model. Cost is decided downstream, so it stays neutral.
        "auto" to ModelTraits(3.5, 900, Capabilities(calibrated = false)),
    )

    private val DEFAULT_TRAITS = ModelTraits(3.0, 1_000, Capabilities(calibrated = false))

    /** Every routable (provider, model) pair, built from the UI catalog. */
    val models: List<ModelRoute> by lazy {
        CloudProvider.entries.flatMap { provider ->
            provider.models.map { model ->
                ModelRoute(
                    providerId = provider.name,
                    providerLabel = provider.displayName,
                    modelId = model.id,
                    modelLabel = model.displayName,
                    inputPerM = model.inputPricePerM,
                    outputPerM = model.outputPricePerM,
                    contextTokens = model.contextSize,
                    traits = TRAITS[model.id] ?: DEFAULT_TRAITS,
                    costKnown = model.inputPricePerM > 0.0 || model.outputPricePerM > 0.0,
                )
            }
        }
    }

    fun byModelId(modelId: String): ModelRoute? =
        models.firstOrNull { it.modelId.equals(modelId, ignoreCase = true) }

    fun forProvider(providerId: String): List<ModelRoute> =
        models.filter { it.providerId.equals(providerId, ignoreCase = true) }

    fun providerIds(): List<String> = models.map { it.providerId }.distinct()

    /** Highest price in the table — the denominator for cost normalisation. */
    internal fun maxInputPrice(): Double = models.filter { it.costKnown }.maxOfOrNull { it.inputPerM } ?: 0.0

    /** Slowest model in the table — the denominator for latency normalisation. */
    internal fun maxLatency(): Long = models.maxOfOrNull { it.traits.latencyMs } ?: 1L
}
