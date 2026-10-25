// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.routing

import com.returngift.agent.agent.CloudProvider
import org.junit.Assert
import org.junit.Test

/**
 * Routing contract: pick the right model for the request, explain why, and fail
 * over cleanly. All deterministic — no network, no sleeping (the ledger takes a
 * clock).
 */
object ProviderRouterTest {

    private const val T0 = 1_000_000L
    private var now = T0

    private fun ledger(): TokenLedger = TokenLedger(clock = { now }, )

    private fun route(
        providerId: String,
        modelId: String,
        input: Double = 1.0,
        output: Double = 2.0,
        context: Int = 200_000,
        quality: Double = 3.0,
        latencyMs: Long = 1_000,
        tools: Boolean = true,
        vision: Boolean = false,
    ) = ProviderCatalog.ModelRoute(
        providerId = providerId,
        providerLabel = providerId,
        modelId = modelId,
        modelLabel = modelId,
        inputPerM = input,
        outputPerM = output,
        contextTokens = context,
        traits = ProviderCatalog.ModelTraits(
            quality,
            latencyMs,
            ProviderCatalog.Capabilities(tools = tools, vision = vision),
        ),
        costKnown = input > 0.0 || output > 0.0,
    )

    private val cheap = route("P_FAST", "cheap", input = 0.1, quality = 3.0, latencyMs = 400)
    private val strong = route("P_STRONG", "strong", input = 10.0, quality = 5.0, latencyMs = 2_000)
    private val tinyContext = route("P_TINY", "tiny", context = 1_000)
    private val noTools = route("P_NOTOOL", "notool", tools = false)
    private val vision = route("P_VISION", "vision", vision = true, quality = 2.5)
    private val local = route("LOCAL", "local", input = 0.0, output = 0.0, quality = 3.2, latencyMs = 2_500)
    private val catalog = listOf(cheap, strong, tinyContext, noTools, vision, local)

    private fun resetClock() { now = T0 }

    // ── catalog ─────────────────────────────────────────────────────────────

    @Test
    fun catalogCoversEveryUiModelExactlyOnce() {
        val uiModels = CloudProvider.entries.flatMap { p -> p.models.map { p.name to it.id } }
        val routing = ProviderCatalog.models.map { it.providerId to it.modelId }
        Assert.assertEquals(uiModels.toSet(), routing.toSet())
        Assert.assertEquals(routing.size, routing.distinct().size)
    }

    @Test
    fun catalogIsSmallOnPurpose() {
        // "few important providers instead of every provider": a bound keeps the
        // artifact (and the maintenance cost) predictable.
        Assert.assertTrue("provider count ${ProviderCatalog.providerIds().size}", ProviderCatalog.providerIds().size <= 6)
        Assert.assertTrue(ProviderCatalog.models.size <= 16)
    }

    @Test
    fun catalogLookupWorksBothWays() {
        val any = ProviderCatalog.models.first()
        Assert.assertEquals(any, ProviderCatalog.byModelId(any.modelId))
        Assert.assertTrue(ProviderCatalog.forProvider(any.providerId).contains(any))
        Assert.assertNull(ProviderCatalog.byModelId("does-not-exist"))
    }

    // ── strategies ──────────────────────────────────────────────────────────

    @Test
    fun cheapestPicksTheLowestPrice() {
        resetClock()
        val d = ProviderRouter.decide(
            ProviderRouter.Request(strategy = ProviderRouter.Strategy.CHEAPEST),
            ledger(),
            catalog,
        )
        // LOCAL is free (cost unknown → neutral), so a priced cheap model wins on 1-cost.
        Assert.assertEquals("cheap", d.primary.route.modelId)
    }

    @Test
    fun qualityPicksTheBestModel() {
        val d = ProviderRouter.decide(ProviderRouter.Request(strategy = ProviderRouter.Strategy.QUALITY), ledger(), catalog)
        Assert.assertEquals("strong", d.primary.route.modelId)
    }

    @Test
    fun fastestPicksTheLowestLatency() {
        val d = ProviderRouter.decide(ProviderRouter.Request(strategy = ProviderRouter.Strategy.FASTEST), ledger(), catalog)
        Assert.assertEquals("cheap", d.primary.route.modelId)
    }

    @Test
    fun localFirstPrefersTheLocalModel() {
        val d = ProviderRouter.decide(ProviderRouter.Request(strategy = ProviderRouter.Strategy.LOCAL_FIRST), ledger(), catalog)
        Assert.assertEquals("LOCAL", d.primary.route.providerId)
    }

    @Test
    fun autoBalancesQualityAgainstCost() {
        val d = ProviderRouter.decide(ProviderRouter.Request(strategy = ProviderRouter.Strategy.AUTO), ledger(), catalog)
        // cheap: 0.5*0.6 - 0.3*small - 0.2*small beats strong: 0.5*1.0 - 0.3*1.0 - 0.2*1.0
        Assert.assertEquals("cheap", d.primary.route.modelId)
        Assert.assertTrue(d.trace.last().startsWith("chose cheap"))
    }

    // ── filters ─────────────────────────────────────────────────────────────

    @Test
    fun contextWindowFilterExcludesSmallModels() {
        val d = ProviderRouter.decide(ProviderRouter.Request(promptTokens = 5_000, completionTokens = 500), ledger(), catalog)
        Assert.assertTrue(d.chain().none { it.route.modelId == "tiny" })
        Assert.assertTrue(d.trace.any { it.contains("tiny") && it.contains("context") })
    }

    @Test
    fun toolRequirementExcludesTextOnlyModels() {
        val d = ProviderRouter.decide(ProviderRouter.Request(needsTools = true), ledger(), catalog)
        Assert.assertTrue(d.chain().none { it.route.modelId == "notool" })
    }

    @Test
    fun visionRequirementExcludesBlindModels() {
        val d = ProviderRouter.decide(ProviderRouter.Request(needsVision = true), ledger(), catalog)
        Assert.assertEquals(listOf("vision"), d.chain().map { it.route.modelId })
    }

    @Test
    fun providerAllowListIsHonoured() {
        val d = ProviderRouter.decide(
            ProviderRouter.Request(providers = setOf("P_STRONG")),
            ledger(),
            catalog,
        )
        Assert.assertEquals("strong", d.primary.route.modelId)
        Assert.assertEquals(1, d.chain().size)
    }

    @Test
    fun explicitModelWins() {
        val d = ProviderRouter.decide(ProviderRouter.Request(modelId = "strong"), ledger(), catalog)
        Assert.assertEquals("strong", d.primary.route.modelId)
        Assert.assertTrue(d.fallbacks.isEmpty())
        Assert.assertTrue(d.trace.first().contains("explicit"))
    }

    @Test
    fun unknownExplicitModelFallsBackToRouting() {
        val d = ProviderRouter.decide(ProviderRouter.Request(modelId = "nope"), ledger(), catalog)
        Assert.assertTrue(d.trace.any { it.contains("not in the catalog") })
        Assert.assertNotNull(d.primary)
    }

    @Test
    fun noRouteExplainsItself() {
        try {
            ProviderRouter.decide(
                ProviderRouter.Request(promptTokens = 500_000),
                ledger(),
                catalog,
            )
            Assert.fail("expected NoRouteAvailableException")
        } catch (e: ProviderRouter.NoRouteAvailableException) {
            Assert.assertTrue(e.message!!.contains("no provider can serve"))
            Assert.assertTrue(e.message!!.contains("context"))
        }
    }

    // ── ledger ──────────────────────────────────────────────────────────────

    @Test
    fun exhaustedProviderIsSkipped() {
        resetClock()
        val ledger = ledger()
        ledger.setLimits("P_FAST", TokenLedger.Limits(shortTokens = 100))
        ledger.record("P_FAST", 150)
        val d = ProviderRouter.decide(ProviderRouter.Request(), ledger, catalog)
        Assert.assertNotEquals("P_FAST", d.primary.route.providerId)
        Assert.assertTrue(d.trace.any { it.contains("quota exhausted") })
    }

    @Test
    fun coolingProviderIsSkippedTemporarily() {
        resetClock()
        val ledger = ledger()
        ledger.markCooldown("P_STRONG", 30_000)
        val d = ProviderRouter.decide(ProviderRouter.Request(strategy = ProviderRouter.Strategy.QUALITY), ledger, catalog)
        Assert.assertNotEquals("P_STRONG", d.primary.route.providerId)
        now += 31_000
        val after = ProviderRouter.decide(ProviderRouter.Request(strategy = ProviderRouter.Strategy.QUALITY), ledger, catalog)
        Assert.assertEquals("strong", after.primary.route.modelId)
    }

    @Test
    fun usageAccumulatesTokensAndCost() {
        resetClock()
        val ledger = ledger()
        ledger.record("P_FAST", 1_000, 0.02)
        ledger.record("P_FAST", 500, 0.01)
        val usage = ledger.usage("P_FAST")
        Assert.assertEquals(1_500, usage.totalTokens)
        Assert.assertEquals(0.03, usage.totalSpentUsd, )
        Assert.assertEquals(1_500, usage.shortTokens)
    }

    @Test
    fun shortWindowRollsAfterFiveHours() {
        resetClock()
        val ledger = ledger()
        ledger.record("P_FAST", 1_000)
        now += 5 * 60 * 60 * 1000L + 1
        val usage = ledger.usage("P_FAST")
        Assert.assertEquals(0, usage.shortTokens)
        Assert.assertEquals(1_000, usage.longTokens)
        Assert.assertEquals(1_000, usage.totalTokens)
    }

    @Test
    fun longWindowRollsAfterSevenDays() {
        resetClock()
        val ledger = ledger()
        ledger.record("P_FAST", 1_000)
        now += 7 * 24 * 60 * 60 * 1000L + 1
        val usage = ledger.usage("P_FAST")
        Assert.assertEquals(0, usage.shortTokens)
        Assert.assertEquals(0, usage.longTokens)
        Assert.assertEquals(1_000, usage.totalTokens)
    }

    @Test
    fun zeroTokenRecordsAreIgnored() {
        resetClock()
        val ledger = ledger()
        ledger.record("P_FAST", 0)
        Assert.assertEquals(0, ledger.usage("P_FAST").totalTokens)
    }

    @Test
    fun spendingLimitCountsAsExhausted() {
        resetClock()
        val ledger = ledger()
        ledger.setLimits("P_FAST", TokenLedger.Limits(shortUsd = 0.01))
        ledger.record("P_FAST", 10, 0.02)
        Assert.assertTrue(ledger.isExhausted("P_FAST"))
        Assert.assertFalse(ledger.isExhausted("P_STRONG"))
    }

    @Test
    fun ledgerDescribeIsReadable() {
        resetClock()
        val ledger = ledger()
        ledger.record("P_FAST", 2_000, 0.05)
        val line = ledger.describe().first()
        Assert.assertTrue(line.contains("P_FAST"))
        Assert.assertTrue(line.contains("2000"))
    }

    // ── failures ────────────────────────────────────────────────────────────

    @Test
    fun failureClassification() {
        Assert.assertEquals(ProviderRouter.Failure.RATE_LIMIT, ProviderRouter.classify(429, "slow down"))
        Assert.assertEquals(ProviderRouter.Failure.AUTH, ProviderRouter.classify(401, "bad key"))
        Assert.assertEquals(ProviderRouter.Failure.AUTH, ProviderRouter.classify(403, "forbidden"))
        Assert.assertEquals(ProviderRouter.Failure.QUOTA, ProviderRouter.classify(402, "pay up"))
        Assert.assertEquals(
            ProviderRouter.Failure.QUOTA,
            ProviderRouter.classify(400, "insufficient_quota: you exceeded your current quota"),
        )
        Assert.assertEquals(ProviderRouter.Failure.TRANSIENT, ProviderRouter.classify(503, "unavailable"))
        Assert.assertEquals(ProviderRouter.Failure.FATAL, ProviderRouter.classify(400, "bad request"))
        Assert.assertEquals(
            ProviderRouter.Failure.TRANSIENT,
            ProviderRouter.classify(null, "boom", "java.net.SocketTimeoutException"),
        )
        Assert.assertEquals(ProviderRouter.Failure.FATAL, ProviderRouter.classify(null, "boom", "IllegalArgumentException"))
    }

    @Test
    fun cooldownsMatchFailureSeverity() {
        Assert.assertTrue(ProviderRouter.cooldownMs(ProviderRouter.Failure.QUOTA) > ProviderRouter.cooldownMs(ProviderRouter.Failure.RATE_LIMIT))
        Assert.assertTrue(ProviderRouter.cooldownMs(ProviderRouter.Failure.AUTH) >= ProviderRouter.cooldownMs(ProviderRouter.Failure.QUOTA))
        Assert.assertTrue(ProviderRouter.cooldownMs(ProviderRouter.Failure.TRANSIENT) < ProviderRouter.cooldownMs(ProviderRouter.Failure.RATE_LIMIT))
        Assert.assertEquals(0L, ProviderRouter.cooldownMs(ProviderRouter.Failure.FATAL))
    }

    @Test
    fun failureMovesToTheNextCandidate() {
        resetClock()
        val ledger = ledger()
        val d = ProviderRouter.decide(ProviderRouter.Request(strategy = ProviderRouter.Strategy.QUALITY), ledger, catalog)
        Assert.assertEquals("strong", d.primary.route.modelId)
        val next = ProviderRouter.onFailure(d, ProviderRouter.Failure.RATE_LIMIT, ledger)
        Assert.assertNotNull(next)
        Assert.assertNotEquals("strong", next!!.primary.route.modelId)
        Assert.assertTrue(ledger.isCoolingDown("P_STRONG"))
        Assert.assertTrue(next.trace.last().contains("falling back"))
    }

    @Test
    fun chainExhaustionReturnsNull() {
        resetClock()
        val ledger = ledger()
        val single = ProviderRouter.decide(
            ProviderRouter.Request(providers = setOf("P_STRONG")),
            ledger,
            catalog,
        )
        Assert.assertNull(ProviderRouter.onFailure(single, ProviderRouter.Failure.QUOTA, ledger))
    }

    @Test
    fun recordingUsageAppliesPricing() {
        resetClock()
        val ledger = ledger()
        val r = route("P_FAST", "m", input = 2.0, output = 8.0)
        ProviderRouter.recordUsage(ledger, r, promptTokens = 1_000_000, completionTokens = 500_000)
        Assert.assertEquals(1_500_000, ledger.usage("P_FAST").totalTokens)
        Assert.assertEquals(6.0, ledger.usage("P_FAST").totalSpentUsd)
    }
}
