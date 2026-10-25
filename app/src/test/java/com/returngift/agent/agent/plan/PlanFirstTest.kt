// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.plan

import com.returngift.agent.agent.exec.micro.AppSurface
import org.junit.Assert
import org.junit.Test

/**
 * Plan-first contract.
 *
 * The headline case is the one the user wrote by hand:
 *
 * "Follow youtube on LinkedIn" →
 *   Open LinkedIn → Search "YouTube" → Filter by Companies →
 *   Select official YouTube page → Tap "Follow"
 *
 * Fixtures are built from capability rows, never from app-specific code: the same
 * assertions run against renamed apps in [plans are data driven], which is what
 * proves the planner has no built-in knowledge of any single app.
 */
object PlanFirstTest {

    private fun ctx(
        social: String = "LinkedIn",
        video: String = "YouTube",
        chat: String = "Messages",
        search: String = "Chrome",
        socialInstalled: Boolean = true,
    ): PlanContext = PlanContext(
        apps = listOf(
            AppRef(social, "com.example.${social.lowercase()}", AppSurface.SOCIAL_POST, socialInstalled),
            AppRef(video, "com.example.${video.lowercase()}", AppSurface.VIDEO),
            AppRef(chat, "com.example.${chat.lowercase()}", AppSurface.CHAT_COMPOSER),
            AppRef(search, "com.example.${search.lowercase()}", AppSurface.BROWSER_ADDRESS),
        ),
        browserLabel = "Browser",
    )

    // ------------------------------------------------------------ headline case

    @Test
    fun followEntityPlansTheWholeFlow() {
        val p = PlanFirst.plan("Follow youtube on LinkedIn", ctx())
        val details = p.blueprint.steps.map { it.detail }
        Assert.assertEquals(
            listOf(
                "Open LinkedIn",
                "YouTube",
                "Companies",
                "the official YouTube page",
                "Follow",
                "the button shows Following",
            ),
            details,
        )
        Assert.assertEquals(
            listOf(
                PlanAction.OPEN_APP,
                PlanAction.SEARCH,
                PlanAction.FILTER,
                PlanAction.OPEN_RESULT,
                PlanAction.TAP,
                PlanAction.VERIFY,
            ),
            p.blueprint.steps.map { it.action },
        )
    }

    @Test
    fun planReadsLikeTheUsersOwnSentence() {
        val p = PlanFirst.plan("follow youtube on linkedin", ctx())
        Assert.assertEquals(
            "Open LinkedIn \u2192 Search \u201cYouTube\u201d \u2192 Filter by Companies \u2192 " +
                "Open the official YouTube page \u2192 Tap Follow \u2192 Verify the button shows Following",
            p.blueprint.chain(),
        )
    }

    @Test
    fun planIsShownAndExecutionContinues() {
        val p = PlanFirst.plan("Follow youtube on LinkedIn", ctx())
        Assert.assertTrue(p.showPlan)
        Assert.assertTrue(p.executeAfter)
        Assert.assertEquals(PlanFirst.Mode.AUTO, p.mode)
        Assert.assertTrue(p.markdown.contains("1. Open LinkedIn"))
    }

    @Test
    fun numberingIsContiguous() {
        val p = PlanFirst.plan("Follow youtube on LinkedIn", ctx())
        Assert.assertEquals((1..p.blueprint.steps.size).toList(), p.blueprint.steps.map { it.number })
    }

    // ------------------------------------------------------------- entity kinds

    @Test
    fun singleWordEntityIsTreatedAsOrganisation() {
        Assert.assertEquals(EntityKind.ORGANIZATION, IntentParser.classifyEntity("YouTube"))
        Assert.assertEquals(EntityKind.ORGANIZATION, IntentParser.classifyEntity("OpenAI"))
    }

    @Test
    fun capitalisedTwoWordNameIsTreatedAsPerson() {
        Assert.assertEquals(EntityKind.PERSON, IntentParser.classifyEntity("Sundar Pichai"))
        val p = PlanFirst.plan("follow Sundar Pichai on LinkedIn", ctx())
        Assert.assertTrue(p.blueprint.steps.any { it.action == PlanAction.FILTER && it.detail == "People" })
    }

    @Test
    fun organisationMarkerWinsOverWordCount() {
        Assert.assertEquals(EntityKind.ORGANIZATION, IntentParser.classifyEntity("Acme Labs"))
        Assert.assertEquals(EntityKind.UNKNOWN, IntentParser.classifyEntity(null))
    }

    // ------------------------------------------------------------ clarification

    @Test
    fun missingPlatformBecomesAQuestion() {
        val p = PlanFirst.plan("follow youtube", ctx())
        Assert.assertTrue(p.blueprint.needsClarification)
        Assert.assertFalse(p.executeAfter)
        val q = p.blueprint.questions.first()
        Assert.assertEquals("platform", q.id)
        Assert.assertTrue(q.choices.contains("LinkedIn"))
        Assert.assertTrue(p.markdown.contains("Where should I follow YouTube?"))
    }

    @Test
    fun missingPlatformOffersOnlyAppsThatCanSearch() {
        val p = PlanFirst.plan("follow youtube", ctx())
        val choices = p.blueprint.questions.first().choices
        Assert.assertTrue("video app has no search surface", choices.none { it == "YouTube" })
        Assert.assertTrue(choices.contains("LinkedIn"))
    }

    @Test
    fun uninstalledPlatformAsksBeforeBrowsing() {
        val p = PlanFirst.plan("follow youtube on LinkedIn", ctx(socialInstalled = false))
        val q = p.blueprint.questions.first()
        Assert.assertEquals("install", q.id)
        Assert.assertTrue(q.question.contains("not installed"))
        Assert.assertTrue(q.choices.contains("Use Browser"))
        Assert.assertFalse(p.executeAfter)
        Assert.assertTrue(p.blueprint.steps.first().detail == "Open Browser")
        Assert.assertNotNull(p.blueprint.assumptions.firstOrNull())
    }

    @Test
    fun messageWithoutAppOrTextAsksForBoth() {
        val p = PlanFirst.plan("message mom", ctx())
        val ids = p.blueprint.questions.map { it.id }.toSet()
        Assert.assertEquals(setOf("platform", "payload"), ids)
        Assert.assertFalse(p.executeAfter)
    }

    @Test
    fun messageWithEverythingIsPlanned() {
        val p = PlanFirst.plan("message mom on Messages saying dinner at 8", ctx())
        val details = p.blueprint.steps.map { it.detail }
        Assert.assertEquals(listOf("Open Messages", "mom", "the chat with mom", "dinner at 8", "Send", "the message appears in the thread"), details)
        Assert.assertTrue(p.executeAfter)
    }

    @Test
    fun sendAMessageToIsTheSameIntentAsMessage() {
        val p = PlanFirst.plan("send a message to mom on Messages saying dinner at 8", ctx())
        Assert.assertEquals(PlanCapability.SEND_MESSAGE, p.blueprint.intent.capability)
        Assert.assertEquals("mom", p.blueprint.intent.entity)
        Assert.assertEquals("dinner at 8", p.blueprint.intent.payload)
        Assert.assertEquals("Open Messages", p.blueprint.steps.first().detail)
    }

    @Test
    fun sendATextWithoutAppAsksWhichApp() {
        val p = PlanFirst.plan("send a text to Ravi", ctx())
        Assert.assertEquals(PlanCapability.SEND_MESSAGE, p.blueprint.intent.capability)
        Assert.assertTrue(p.blueprint.questions.any { it.id == "platform" })
    }

    @Test
    fun postWithoutTextAsksForText() {
        val p = PlanFirst.plan("post on LinkedIn", ctx())
        Assert.assertTrue(p.blueprint.questions.any { it.id == "payload" || it.id == "platform" })
    }

    @Test
    fun postWithTextIsPlanned() {
        val p = PlanFirst.plan("post \"shipped v2\" on LinkedIn", ctx())
        Assert.assertTrue(p.blueprint.steps.any { it.action == PlanAction.INPUT_TEXT && it.detail == "shipped v2" })
    }

    @Test
    fun openUnknownAppAsksWhichApp() {
        val p = PlanFirst.plan("open Megafon", ctx())
        Assert.assertTrue(p.blueprint.needsClarification)
        Assert.assertEquals("platform", p.blueprint.questions.first().id)
        Assert.assertTrue(p.blueprint.questions.first().choices.contains("LinkedIn"))
    }

    // ------------------------------------------------------------------- modes

    @Test
    fun previewModeShowsPlanAndStops() {
        val p = PlanFirst.plan("show me the plan to follow youtube on linkedin", ctx())
        Assert.assertTrue(p.showPlan)
        Assert.assertFalse(p.executeAfter)
        Assert.assertEquals(PlanFirst.Mode.PREVIEW, p.mode)
    }

    @Test
    fun silentModeSkipsThePlanAndActs() {
        val p = PlanFirst.plan("just do it: follow youtube on linkedin", ctx())
        Assert.assertFalse(p.showPlan)
        Assert.assertTrue(p.executeAfter)
        Assert.assertEquals(PlanFirst.Mode.SILENT, p.mode)
    }

    @Test
    fun silenceDoesNotWaiveARequiredQuestion() {
        val p = PlanFirst.plan("just do it: follow youtube", ctx())
        Assert.assertEquals(PlanFirst.Mode.SILENT, p.mode)
        Assert.assertTrue(p.blueprint.needsClarification)
        Assert.assertFalse(p.executeAfter)
    }

    @Test
    fun riskyRequestAsksForApproval() {
        val p = PlanFirst.plan("post and then delete the old post on LinkedIn", ctx())
        Assert.assertTrue(p.blueprint.questions.any { it.id == "approval" })
        Assert.assertFalse(p.executeAfter)
    }

    @Test
    fun approvalIsNotAskedForPlainQuestions() {
        val p = PlanFirst.plan("what is a good post about payments?", ctx())
        Assert.assertTrue(p.blueprint.questions.isEmpty())
        Assert.assertEquals(PlanCapability.NONE, p.blueprint.intent.capability)
    }

    // ------------------------------------------------------------ not-a-plan

    @Test
    fun knowledgeQuestionNeedsNoPlan() {
        val p = PlanFirst.plan("what is the capital of France?", ctx())
        Assert.assertEquals(PlanCapability.NONE, p.blueprint.intent.capability)
        Assert.assertTrue(p.blueprint.steps.isEmpty())
        Assert.assertFalse(p.showPlan)
        Assert.assertTrue(p.executeAfter)
        Assert.assertEquals("", p.markdown)
    }

    @Test
    fun singleStepLaunchIsNotWorthAPlan() {
        val p = PlanFirst.plan("open LinkedIn", ctx())
        Assert.assertEquals(1, p.blueprint.steps.size)
        Assert.assertFalse(p.showPlan)
        Assert.assertTrue(p.executeAfter)
    }

    @Test
    fun freshInformationGetsAResearchPlan() {
        val p = PlanFirst.plan("what is the latest news about mars?", ctx())
        Assert.assertEquals(PlanCapability.WEB_RESEARCH, p.blueprint.intent.capability)
        Assert.assertEquals(3, p.blueprint.steps.size)
        Assert.assertTrue(p.showPlan)
    }

    @Test
    fun deviceToggleIsPlannedWithoutAnApp() {
        val p = PlanFirst.plan("turn off wifi", ctx())
        Assert.assertEquals(PlanCapability.DEVICE_TOGGLE, p.blueprint.intent.capability)
        Assert.assertEquals("wifi", p.blueprint.intent.setting)
        Assert.assertEquals(false, p.blueprint.intent.settingOn)
        Assert.assertTrue(p.blueprint.steps.last().detail.contains("off"))
    }

    // --------------------------------------------------------------- data driven

    @Test
    fun plansAreDataDriven() {
        val renamed = ctx(social = "Alpha", video = "Zeta", chat = "Beta", search = "Gamma")
        val p = PlanFirst.plan("follow zeta on alpha", renamed)
        val text = p.markdown + p.blueprint.chain()
        Assert.assertTrue(text.contains("Alpha"))
        Assert.assertTrue(text.contains("Zeta"))
        Assert.assertFalse("planner must not know LinkedIn", text.contains("LinkedIn", ignoreCase = true))
        Assert.assertFalse("planner must not know YouTube", text.contains("YouTube", ignoreCase = true))
    }

    @Test
    fun ambiguousAppNameIsDetectable() {
        val c = PlanContext(
            apps = listOf(
                AppRef("Chat", "com.a.chat", AppSurface.CHAT_COMPOSER),
                AppRef("Chat", "com.b.chat", AppSurface.CHAT_COMPOSER),
            )
        )
        Assert.assertTrue(c.resolveApp("Chat") is AppMatch.Ambiguous)
        Assert.assertTrue(c.resolveApp("nope") is AppMatch.None)
        Assert.assertTrue(c.resolveApp("com.b.chat") is AppMatch.Found)
    }

    @Test
    fun blankInputIsNotATask() {
        Assert.assertEquals(PlanCapability.NONE, IntentParser.parse("   ", ctx()).capability)
        Assert.assertEquals(PlanCapability.NONE, PlanFirst.plan("", ctx()).blueprint.intent.capability)
    }

    @Test
    fun promptLineOnlyForRealPlans() {
        Assert.assertEquals("", PlanFirst.promptLine(PlanFirst.plan("open LinkedIn", ctx()).blueprint))
        Assert.assertTrue(
            PlanFirst.promptLine(PlanFirst.plan("follow youtube on linkedin", ctx()).blueprint)
                .startsWith("Agreed plan: Open LinkedIn")
        )
    }

    // --------------------------------------------------------------- surfaces

    @Test
    fun surfaceToCapabilityMapping() {
        Assert.assertEquals(SurfaceCapability.SEARCH, AppSurface.SEARCH_FIELD.capability())
        Assert.assertEquals(SurfaceCapability.PUBLISH, AppSurface.SOCIAL_POST.capability())
        Assert.assertEquals(SurfaceCapability.MESSAGE, AppSurface.CHAT_COMPOSER.capability())
        Assert.assertEquals(SurfaceCapability.BROWSE, AppSurface.BROWSER_ADDRESS.capability())
        Assert.assertNull(AppSurface.UNKNOWN.capability())
    }

    @Test
    fun controlPhrasesAreStrippedFromThePlan() {
        Assert.assertEquals(
            "follow youtube on linkedin",
            IntentParser.stripControlPhrases("just do it: follow youtube on linkedin"),
        )
        Assert.assertTrue(IntentParser.wantsPreview("what's the plan?"))
        Assert.assertTrue(IntentParser.wantsSilence("no need to ask"))
    }
}
