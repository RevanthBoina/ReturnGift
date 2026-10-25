// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.routing

/**
 * Chooses *which* provider/model answers a request, and what to do when it fails.
 *
 * This is the piece a gateway like OmniRoute normally provides. Doing it in-app
 * means: no server to run, no dependency on a gateway being reachable, and the
 * choice is explainable ("why this model?") in one printf. The trade-off — we
 * support a handful of providers instead of hundreds — is deliberate.
 *
 * Nothing here performs I/O. [decide] returns an ordered chain; the caller executes
 * it and reports back through [onFailure] / [recordUsage].
 */
object ProviderRouter {

    enum class Strategy {
        /** Balanced: quality first, cost and latency as tie-breakers. */
        AUTO,

        /** Cheapest model that satisfies the request. */
        CHEAPEST,

        /** Lowest expected latency that satisfies the request. */
        FASTEST,

        /** Best model available, cost ignored. */
        QUALITY,

        /** Local model when it fits, cloud otherwise. */
        LOCAL_FIRST,
    }

    data class Request(
        val promptTokens: Int = 4_000,
        val completionTokens: Int = 1_000,
        val needsTools: Boolean = false,
        val needsVision: Boolean = false,
        val strategy: Strategy = Strategy.AUTO,
        /** Restrict to these provider ids (empty = all). */
        val providers: Set<String> = emptySet(),
        /** Explicit model wins over everything else when set. */
        val modelId: String? = null,
    ) {
        val totalTokens: Int get() = promptTokens + completionTokens
    }

    data class Candidate(
        val route: ProviderCatalog.ModelRoute,
        val score: Double,
        val estimatedCostUsd: Double,
    )

    data class Decision(
        val primary: Candidate,
        val fallbacks: List<Candidate>,
        val trace: List<String>,
    ) {
        /** Chain in execution order, primary first. */
        fun chain(): List<Candidate> = listOf(primary) + fallbacks
    }

    // ── selection ───────────────────────────────────────────────────────────

    fun decide(
        request: Request,
        ledger: TokenLedger,
        catalog: List<ProviderCatalog.ModelRoute> = ProviderCatalog.models,
    ): Decision {
        val trace = mutableListOf<String>()

        request.modelId?.let { wanted ->
            val explicit = catalog.firstOrNull { it.modelId.equals(wanted, ignoreCase = true) }
            if (explicit != null) {
                trace += "explicit model requested: ${explicit.modelId}"
                return Decision(
                    primary = Candidate(explicit, 1.0, explicit.estimateCostUsd(request.promptTokens, request.completionTokens)),
                    fallbacks = emptyList(),
                    trace = trace,
                )
            }
            trace += "requested model '$wanted' is not in the catalog — falling back to routing"
        }

        val eligible = catalog.filter { route ->
            val reason = ineligibleReason(route, request, ledger)
            if (reason != null) {
                trace += "excluded ${route.modelId} (${route.providerLabel}): $reason"
                false
            } else {
                true
            }
        }

        if (eligible.isEmpty()) {
            throw NoRouteAvailableException(
                "no provider can serve this request " +
                    "(tokens=${request.totalTokens}, tools=${request.needsTools}, " +
                    "vision=${request.needsVision}); candidates were: ${trace.joinToString("; ")}"
            )
        }

        val scored = eligible
            .map { route -> Candidate(route, score(route, request), route.estimateCostUsd(request.promptTokens, request.completionTokens)) }
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenBy { it.estimatedCostUsd }
                    .thenBy { it.route.modelId }
            )

        val primary = scored.first()
        trace += "chose ${primary.route.modelId} via ${primary.route.providerLabel} " +
            "(score=${"%.3f".format(primary.score)}, ~$${"%.5f".format(primary.estimatedCostUsd)}, " +
            "ctx=${primary.route.contextTokens})"
        return Decision(primary, scored.drop(1), trace)
    }

    private fun ineligibleReason(
        route: ProviderCatalog.ModelRoute,
        request: Request,
        ledger: TokenLedger,
    ): String? {
        if (request.providers.isNotEmpty() &&
            request.providers.none { it.equals(route.providerId, ignoreCase = true) }
        ) {
            return "not in the allowed provider list"
        }
        if (route.contextTokens < request.totalTokens) {
            return "context ${route.contextTokens} < ${request.totalTokens}"
        }
        if (request.needsTools && !route.traits.capabilities.tools) return "no tool calling"
        if (request.needsVision && !route.traits.capabilities.vision) return "no vision"
        if (ledger.isExhausted(route.providerId)) return "quota exhausted"
        if (ledger.isCoolingDown(route.providerId)) {
            return "cooling down ${ledger.usage(route.providerId).cooldownRemainingMs}ms"
        }
        return null
    }

    /** Higher is better; all components are normalised to 0..1. */
    internal fun score(route: ProviderCatalog.ModelRoute, request: Request): Double {
        val quality = route.traits.quality / 5.0
        val cost = route.costScore(ProviderCatalog.maxInputPrice())
        val latency = route.latencyScore(ProviderCatalog.maxLatency())
        return when (request.strategy) {
            Strategy.QUALITY -> quality
            Strategy.CHEAPEST -> 1.0 - cost
            Strategy.FASTEST -> 1.0 - latency
            Strategy.LOCAL_FIRST ->
                (if (route.providerId == "LOCAL") 1.0 else 0.0) + 0.5 * quality - 0.3 * cost - 0.2 * latency
            Strategy.AUTO -> 0.5 * quality - 0.3 * cost - 0.2 * latency
        }
    }

    // ── failure handling ────────────────────────────────────────────────────

    enum class Failure {
        /** 429 / "too many requests": retry soon. */
        RATE_LIMIT,

        /** Budget or subscription exhausted: retry when the window resets. */
        QUOTA,

        /** Bad/missing key: retrying is pointless without user action. */
        AUTH,

        /** Network blip, 5xx, timeout. */
        TRANSIENT,

        /** Malformed request or unsupported feature: do not retry this route. */
        FATAL,
    }

    /** Cooldown applied per failure class. */
    fun cooldownMs(failure: Failure): Long = when (failure) {
        Failure.RATE_LIMIT -> 30_000L
        Failure.QUOTA -> 15 * 60 * 1000L
        Failure.AUTH -> 60 * 60 * 1000L
        Failure.TRANSIENT -> 5_000L
        Failure.FATAL -> 0L
    }

    fun classify(httpStatus: Int?, message: String?, exceptionName: String? = null): Failure {
        val text = message.orEmpty().lowercase()
        if (text.contains("quota") || text.contains("insufficient_quota") ||
            text.contains("credit") && text.contains("exhaust") ||
            text.contains("billing")
        ) {
            return Failure.QUOTA
        }
        when (httpStatus) {
            401, 403 -> return Failure.AUTH
            402 -> return Failure.QUOTA
            429 -> return Failure.RATE_LIMIT
            in 500..599 -> return Failure.TRANSIENT
            in 400..499 -> return Failure.FATAL
        }
        return when {
            exceptionName == null -> Failure.FATAL
            listOf("Timeout", "Socket", "UnknownHost", "Connect", "IOException", "SSL")
                .any { exceptionName.contains(it, ignoreCase = true) } -> Failure.TRANSIENT
            else -> Failure.FATAL
        }
    }

    /**
     * Move to the next candidate after [failure], cooling the failed provider down.
     * Returns null when the chain is exhausted.
     */
    fun onFailure(
        decision: Decision,
        failure: Failure,
        ledger: TokenLedger,
        failedRoute: ProviderCatalog.ModelRoute = decision.primary.route,
    ): Decision? {
        ledger.markCooldown(failedRoute.providerId, cooldownMs(failure))
        val remaining = decision.fallbacks.filterNot {
            it.route.providerId == failedRoute.providerId || ledger.isCoolingDown(it.route.providerId)
        }
        if (remaining.isEmpty()) return null
        return Decision(
            primary = remaining.first(),
            fallbacks = remaining.drop(1),
            trace = decision.trace + "${failedRoute.modelId} failed ($failure) — falling back to ${remaining.first().route.modelId}",
        )
    }

    fun recordUsage(ledger: TokenLedger, route: ProviderCatalog.ModelRoute, promptTokens: Int, completionTokens: Int) {
        val tokens = (promptTokens + completionTokens).toLong()
        ledger.record(route.providerId, tokens, route.estimateCostUsd(promptTokens, completionTokens))
    }

    class NoRouteAvailableException(message: String) : Exception(message)
}
