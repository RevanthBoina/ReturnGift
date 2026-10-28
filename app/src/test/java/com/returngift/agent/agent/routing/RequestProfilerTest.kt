// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.routing

import org.junit.Assert
import org.junit.Test

/**
 * The profiler's contract, from the review: routing facts come from the payload we
 * are about to send — every serialised part of it — and never from an assumption.
 *
 * These tests are pure arithmetic on purpose; the interesting bug class is "a part
 * of the prompt was invisible", and that is exactly what a facts record can prove.
 */
class RequestProfilerTest {

    private const val K = 1_000

    private fun text(chars: Int) = RequestProfiler.Facts(
        systemChars = chars,
        messageCount = 1,
    )

    // ── every input counts ──────────────────────────────────────────────────

    @Test
    fun emptyFactsStillEstimateSomething() {
        // Never zero: a caller dividing by the estimate must not get a divide-by-zero,
        // and providers reject a 0-token request anyway.
        Assert.assertEquals(1, RequestProfiler.estimatePromptTokens(RequestProfiler.Facts()))
    }

    @Test
    fun systemPromptIsCountedNotIgnored() {
        // The device/game-rules system prompt is the biggest static block we send.
        val withoutSystem = RequestProfiler.Facts(userChars = 400, messageCount = 1)
        val withSystem = withoutSystem.copy(systemChars = 20_000)
        val delta = RequestProfiler.estimatePromptTokens(withSystem) -
            RequestProfiler.estimatePromptTokens(withoutSystem)
        // 20k chars ≈ 5k tokens, plus the 1.15 margin.
        Assert.assertTrue("delta=$delta", delta > 5_000)
    }

    @Test
    fun toolResultsAreCounted() {
        // A screen dump arrives as a tool result, not as user text.
        val plain = RequestProfiler.Facts(userChars = 200, messageCount = 2)
        val withDump = plain.copy(toolResultChars = 12_000, messageCount = 3)
        Assert.assertTrue(
            RequestProfiler.estimatePromptTokens(withDump) > RequestProfiler.estimatePromptTokens(plain) + 3_000
        )
    }

    @Test
    fun assistantHistoryIsCounted() {
        val one = RequestProfiler.Facts(userChars = 200, messageCount = 2)
        val many = one.copy(assistantChars = 8_000, messageCount = 6)
        Assert.assertTrue(
            RequestProfiler.estimatePromptTokens(many) > RequestProfiler.estimatePromptTokens(one)
        )
    }

    @Test
    fun toolSchemasAreCountedSeparatelyFromToolResults() {
        val bare = RequestProfiler.Facts(userChars = 200, messageCount = 1)
        val schemas = bare.copy(toolSchemaChars = 6_000, toolSpecCount = 20)
        Assert.assertTrue(
            RequestProfiler.estimatePromptTokens(schemas) > RequestProfiler.estimatePromptTokens(bare) + 1_500
        )
    }

    @Test
    fun everyCharFieldFeedsTheTotal() {
        val facts = RequestProfiler.Facts(
            systemChars = 100,
            userChars = 200,
            assistantChars = 300,
            toolResultChars = 400,
            toolSchemaChars = 500,
        )
        Assert.assertEquals(1_500, facts.totalChars)
    }

    // ── images ──────────────────────────────────────────────────────────────

    @Test
    fun imageCostIsLargeAndPerImage() {
        val noImage = RequestProfiler.Facts(userChars = 400, messageCount = 1)
        val one = noImage.copy(imageCount = 1)
        val two = noImage.copy(imageCount = 2)
        val step = RequestProfiler.estimatePromptTokens(one) - RequestProfiler.estimatePromptTokens(noImage)
        Assert.assertTrue("step=$step", step >= RequestProfiler.TOKENS_PER_IMAGE)
        Assert.assertEquals(
            step * 2,
            RequestProfiler.estimatePromptTokens(two) - RequestProfiler.estimatePromptTokens(noImage),
        )
    }

    @Test
    fun visionNeedFollowsThePayload() {
        val textOnly = RequestProfiler.Facts(userChars = 400, toolSpecCount = 3, messageCount = 1)
        Assert.assertFalse(RequestProfiler.request(textOnly).needsVision)
        Assert.assertTrue(RequestProfiler.request(textOnly.copy(imageCount = 1)).needsVision)
    }

    @Test
    fun toolNeedFollowsTheAdvertisedSchemas() {
        val noTools = RequestProfiler.Facts(userChars = 400, messageCount = 1)
        Assert.assertFalse(RequestProfiler.request(noTools).needsTools)
        Assert.assertTrue(RequestProfiler.request(noTools.copy(toolSpecCount = 1)).needsTools)
    }

    // ── estimate shape ──────────────────────────────────────────────────────

    @Test
    fun estimateIsMonotonic() {
        var previous = 0
        for (chars in listOf(0, 1_000, 4_000, 40_000, 400_000)) {
            val estimate = RequestProfiler.estimatePromptTokens(text(chars))
            Assert.assertTrue("chars=$chars estimate=$estimate previous=$previous", estimate >= previous)
            previous = estimate
        }
    }

    @Test
    fun estimateOverestimatesNeverUnderestimates() {
        // Under-estimating truncates a live task; over-estimating only costs a cheaper
        // model. 4 chars/token is the nominal ratio, so the estimate must exceed it.
        val facts = RequestProfiler.Facts(userChars = 40_000, messageCount = 10)
        val nominal = facts.totalChars / 4
        Assert.assertTrue(
            "estimate=${RequestProfiler.estimatePromptTokens(facts)} nominal=$nominal",
            RequestProfiler.estimatePromptTokens(facts) > nominal,
        )
    }

    @Test
    fun estimateGrowsLinearlyEnoughForTrimMath() {
        val a = RequestProfiler.estimatePromptTokens(text(10_000))
        val b = RequestProfiler.estimatePromptTokens(text(20_000))
        Assert.assertTrue("a=$a b=$b", b in (a * 2 - 50)..(a * 2 + 50))
    }

    @Test
    fun completionReserveIsCarriedIntoTheRequest() {
        val facts = RequestProfiler.Facts(userChars = 400, messageCount = 1, completionTokens = 4_096)
        Assert.assertEquals(4_096, RequestProfiler.request(facts).completionTokens)
        Assert.assertEquals(
            RequestProfiler.estimatePromptTokens(facts) + 4_096,
            RequestProfiler.request(facts).totalTokens,
        )
    }

    @Test
    fun strategyAndAllowlistArePassedThrough() {
        val request = RequestProfiler.request(
            RequestProfiler.Facts(userChars = 400, messageCount = 1),
            strategy = ProviderRouter.Strategy.CHEAPEST,
            providers = setOf("P_FAST", "LOCAL"),
        )
        Assert.assertEquals(ProviderRouter.Strategy.CHEAPEST, request.strategy)
        Assert.assertEquals(setOf("P_FAST", "LOCAL"), request.providers)
    }

    // ── overflow pre-flight ─────────────────────────────────────────────────

    @Test
    fun smallPromptDoesNotOverflow() {
        val facts = RequestProfiler.Facts(userChars = 2_000, messageCount = 2)
        Assert.assertFalse(RequestProfiler.willOverflow(facts, 200_000))
        Assert.assertEquals(0, RequestProfiler.excessChars(facts, 200_000))
    }

    @Test
    fun hugePromptOverflowsSmallContext() {
        val facts = RequestProfiler.Facts(toolResultChars = 400_000, messageCount = 8)
        Assert.assertTrue(RequestProfiler.willOverflow(facts, 32_000))
        Assert.assertTrue(RequestProfiler.excessChars(facts, 32_000) > 0)
    }

    @Test
    fun unknownContextNeverOverflows() {
        val facts = RequestProfiler.Facts(toolResultChars = 400_000, messageCount = 8)
        Assert.assertFalse(RequestProfiler.willOverflow(facts, 0))
        Assert.assertEquals(0, RequestProfiler.excessChars(facts, 0))
    }

    @Test
    fun completionReserveCanBeWhatPushesItOver() {
        val base = RequestProfiler.Facts(userChars = 60_000, messageCount = 4)
        val roomy = base.copy(completionTokens = 200)
        val greedy = base.copy(completionTokens = 8_000)
        val context = 24_000
        Assert.assertFalse(RequestProfiler.willOverflow(roomy, context))
        Assert.assertTrue(RequestProfiler.willOverflow(greedy, context))
    }

    @Test
    fun excessCharsIsEnoughToFit() {
        // The trim loop drops `excessChars` characters; after that the prompt must fit.
        val facts = RequestProfiler.Facts(toolResultChars = 300_000, messageCount = 10)
        val context = 40_000
        val excess = RequestProfiler.excessChars(facts, context)
        Assert.assertTrue("excess=$excess", excess > 0)
        val trimmed = facts.copy(toolResultChars = (facts.toolResultChars - excess).coerceAtLeast(0))
        Assert.assertFalse(RequestProfiler.willOverflow(trimmed, context))
    }

    @Test
    fun excessCharsNeverExceedsWhatWeHave() {
        val facts = RequestProfiler.Facts(userChars = 1_000, toolResultChars = 1_000, messageCount = 1)
        val excess = RequestProfiler.excessChars(facts, 1_000)
        Assert.assertTrue("excess=$excess total=${facts.totalChars}", excess <= facts.totalChars)
    }

    @Test
    fun headroomIsRespected() {
        // At 90 % headroom a prompt that exactly fills the window must already overflow.
        val context = 10_000
        val facts = RequestProfiler.Facts(userChars = 34_000, messageCount = 0, completionTokens = 0)
        Assert.assertTrue(RequestProfiler.estimatePromptTokens(facts) >= context * 0.9)
        Assert.assertTrue(RequestProfiler.willOverflow(facts, context))
        Assert.assertFalse(RequestProfiler.willOverflow(facts, context, headroom = 1.5))
    }

    // ── end to end with the router ──────────────────────────────────────────

    @Test
    fun profiledRequestNeverRoutesToAModelThatCannotHoldIt() {
        // A long screen dump must not land on the small-context model, and the trace
        // must say why it was skipped — the user can see this, so it cannot be silent.
        val small = route("P_SMALL", "small", context = 8_000)
        val roomy = route("P_ROOMY", "roomy", context = 400_000)
        val payload = RequestProfiler.Facts(toolResultChars = 120_000, messageCount = 12)
        val request = RequestProfiler.request(payload)
        val decision = ProviderRouter.decide(request, TokenLedger(clock = { 0L }), listOf(small, roomy))
        Assert.assertEquals(roomy, decision.primary.route)
        Assert.assertTrue(
            "picked ${decision.primary.route.modelId} (${decision.primary.route.contextTokens}) " +
                "for ${request.totalTokens} tokens",
            decision.primary.route.contextTokens >= request.totalTokens,
        )
        Assert.assertTrue(
            "trace=${decision.trace}",
            decision.trace.any { it.contains("small") && it.contains("excluded") },
        )
    }

    @Test
    fun impossiblePayloadFailsLoudlyInsteadOfTruncatingSilently() {
        val payload = RequestProfiler.Facts(toolResultChars = 4_000_000, messageCount = 50)
        var message: String? = null
        try {
            ProviderRouter.decide(
                RequestProfiler.request(payload),
                TokenLedger(clock = { 0L }),
                listOf(route("P_SMALL", "small", context = 8_000)),
            )
        } catch (e: Exception) {
            message = e.message
        }
        Assert.assertNotNull("expected a NoRouteAvailable failure", message)
        Assert.assertTrue("message=$message", message!!.contains("no provider can serve this request"))
    }

    private fun route(providerId: String, modelId: String, context: Int) = ProviderCatalog.ModelRoute(
        providerId = providerId,
        providerLabel = providerId,
        modelId = modelId,
        modelLabel = modelId,
        inputPerM = 1.0,
        outputPerM = 2.0,
        contextTokens = context,
        traits = ProviderCatalog.ModelTraits(
            quality = 3.0,
            latencyMs = 1_000,
            capabilities = ProviderCatalog.Capabilities(tools = true, vision = false),
        ),
        costKnown = true,
    )

    @Test
    fun visionPayloadExcludesModelsWithoutVision() {
        val facts = RequestProfiler.Facts(userChars = 400, imageCount = 1, messageCount = 1)
        val decision = ProviderRouter.decide(RequestProfiler.request(facts), TokenLedger(clock = { 0L }))
        Assert.assertTrue(
            "picked ${decision.primary.route.modelId}",
            decision.primary.route.traits.capabilities.vision,
        )
    }

    @Test
    fun privacyAllowlistIsHonouredThroughTheProfiler() {
        val allowed = ProviderCatalog.models.first().providerId
        val request = RequestProfiler.request(
            RequestProfiler.Facts(userChars = 400, messageCount = 1),
            providers = setOf(allowed),
        )
        val decision = ProviderRouter.decide(request, TokenLedger(clock = { 0L }))
        Assert.assertEquals(allowed, decision.primary.route.providerId)
        Assert.assertTrue(decision.chain().all { it.route.providerId.equals(allowed, ignoreCase = true) })
    }
}
