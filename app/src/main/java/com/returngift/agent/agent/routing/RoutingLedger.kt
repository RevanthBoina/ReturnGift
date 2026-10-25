// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.routing

/**
 * Process-wide routing state: the token/cost ledger plus which providers this
 * device currently has credentials for.
 *
 * Kept separate from [ProviderRouter] so the router stays a pure function and the
 * (Android) credential lookup is injected once, at startup, instead of sprinkled
 * through the decision code.
 */
object RoutingLedger {

    val ledger = TokenLedger()

    /** Installed by the app layer: true when a key for this provider id exists. */
    @Volatile
    var hasCredentials: (String) -> Boolean = { false }

    /** Provider ids this device can actually call right now. */
    fun availableProviders(): Set<String> =
        ProviderCatalog.providerIds().filter { runCatching { hasCredentials(it) }.getOrDefault(false) }.toSet()

    /**
     * Route a request across the providers this device can reach.
     *
     * When no credentials are known (fresh install, unit tests) the full catalog is
     * considered so the caller still gets a deterministic, explainable choice — the
     * eventual auth error is reported by the client, not guessed here.
     */
    fun decide(
        promptTokens: Int,
        completionTokens: Int = 1_000,
        needsTools: Boolean = false,
        needsVision: Boolean = false,
        strategy: ProviderRouter.Strategy = ProviderRouter.Strategy.AUTO,
    ): ProviderRouter.Decision {
        val available = availableProviders()
        return ProviderRouter.decide(
            ProviderRouter.Request(
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                needsTools = needsTools,
                needsVision = needsVision,
                strategy = strategy,
                providers = available,
            ),
            ledger,
        )
    }

    fun record(
        route: ProviderCatalog.ModelRoute,
        promptTokens: Int,
        completionTokens: Int,
    ) = ProviderRouter.recordUsage(ledger, route, promptTokens, completionTokens)

    /** Human-readable "why this model" line for diagnostics screens/logs. */
    fun explain(decision: ProviderRouter.Decision): String =
        decision.trace.joinToString(" | ")

    fun reset() {
        ledger.reset()
    }
}
