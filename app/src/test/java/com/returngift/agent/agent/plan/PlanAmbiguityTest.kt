// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.plan

import com.returngift.agent.agent.exec.micro.AppSurface
import org.junit.Assert
import org.junit.Test

/**
 * The three ways a keyword parser embarrasses itself on real requests:
 *
 *  1. **quoted text** — `post "we're live on LinkedIn"` is a post *about* LinkedIn,
 *     not a post to LinkedIn; the words inside quotes are content, not targets.
 *  2. **negation** — `don't follow them` must never be planned as `follow them`.
 *  3. **multi-clause** — `open YouTube then search cats` is two instructions; the
 *     plan may only claim the first, and the entity must not swallow clause two.
 */
object PlanAmbiguityTest {

    private val ctx = PlanContext(
        apps = listOf(
            AppRef("LinkedIn", "com.example.social", AppSurface.SOCIAL_POST),
            AppRef("YouTube", "com.example.video", AppSurface.VIDEO),
            AppRef("WhatsApp", "com.example.chat", AppSurface.CHAT_COMPOSER),
        )
    )

    private fun intent(text: String) = IntentParser.parse(text, ctx)

    // ── clauses ─────────────────────────────────────────────────────────────

    @Test
    fun thenIsAClauseBoundary() {
        Assert.assertEquals(
            listOf("open YouTube", "search for cats"),
            IntentParser.clauses("open YouTube then search for cats"),
        )
    }

    @Test
    fun andIsNotAClauseBoundary() {
        // \"message John and say hi\" is one instruction; splitting it would invent work.
        Assert.assertEquals(1, IntentParser.clauses("message John and say hi").size)
    }

    @Test
    fun firstClauseDrivesThePlanWithoutSwallowingTheSecond() {
        val parsed = intent("open YouTube then search for cats")
        Assert.assertEquals(PlanCapability.OPEN_ONLY, parsed.capability)
        Assert.assertEquals("YouTube", parsed.entity)
        // The whole sentence still reaches the model.
        Assert.assertEquals("open YouTube then search for cats", parsed.rawText)
    }

    @Test
    fun separatorVariantsAllSplit() {
        for (text in listOf("open YouTube; search cats", "open YouTube, then search cats", "open YouTube and then search cats")) {
            Assert.assertEquals(text, 2, IntentParser.clauses(text).size)
        }
    }

    // ── negation ────────────────────────────────────────────────────────────

    @Test
    fun forbiddenFollowIsNotAFollow() {
        Assert.assertEquals(PlanCapability.NONE, intent("don't follow TechCrunch").capability)
    }

    @Test
    fun pleaseDontPostIsNotAPost() {
        Assert.assertEquals(PlanCapability.NONE, intent("please don't post anything on LinkedIn").capability)
    }

    @Test
    fun neverUnfollowIsNotAnUnfollow() {
        Assert.assertEquals(PlanCapability.NONE, intent("never unfollow anyone").capability)
    }

    @Test
    fun dontForgetIsAPositiveInstruction() {
        // The negation is inverted by \"forget\": this asks for the follow.
        val parsed = intent("don't forget to follow TechCrunch on LinkedIn")
        Assert.assertEquals(PlanCapability.FOLLOW_ENTITY, parsed.capability)
        Assert.assertEquals("TechCrunch", parsed.entity)
    }

    @Test
    fun aForbiddenClauseIsSkippedInFavourOfTheNextOne() {
        val parsed = intent("don't follow TechCrunch; open LinkedIn")
        Assert.assertEquals(PlanCapability.OPEN_ONLY, parsed.capability)
        Assert.assertEquals("LinkedIn", parsed.entity)
    }

    @Test
    fun isForbiddenUnderstandsTheCommonPhrasings() {
        Assert.assertTrue(IntentParser.isForbidden("don't delete it"))
        Assert.assertTrue(IntentParser.isForbidden("never post again"))
        Assert.assertTrue(IntentParser.isForbidden("no need to open it"))
        Assert.assertFalse(IntentParser.isForbidden("open it"))
        Assert.assertFalse(IntentParser.isForbidden("don't forget to open it"))
    }

    // ── quoted text ─────────────────────────────────────────────────────────

    @Test
    fun apostropheInsideAWordIsNotAQuote() {
        Assert.assertTrue(IntentParser.quotedRanges("don't follow John's page").isEmpty())
    }

    @Test
    fun quotedSpanIsFound() {
        Assert.assertEquals("hello", IntentParser.firstQuoted("post \"hello\" on LinkedIn"))
        Assert.assertEquals("hello", IntentParser.firstQuoted("post \u201chello\u201d on LinkedIn"))
    }

    @Test
    fun quotedWordsAreMaskedForTargetResolution() {
        val masked = IntentParser.unquoted("post \"hello on LinkedIn\"")
        Assert.assertFalse(masked.contains("LinkedIn"))
        Assert.assertTrue(masked.contains("post"))
    }

    @Test
    fun postBodyIsTheQuotedText() {
        val parsed = intent("post \"we're live on LinkedIn\"")
        Assert.assertEquals(PlanCapability.POST_UPDATE, parsed.capability)
        Assert.assertEquals("we're live on LinkedIn", parsed.payload)
        // LinkedIn appears only inside the quotes: that is the topic, not the target.
        Assert.assertNull(parsed.platformName)
    }

    @Test
    fun postPlatformOutsideTheQuotesStillResolves() {
        val parsed = intent("post \"hello world\" on LinkedIn")
        Assert.assertEquals(PlanCapability.POST_UPDATE, parsed.capability)
        Assert.assertEquals("hello world", parsed.payload)
        Assert.assertEquals("LinkedIn", parsed.platformName)
        Assert.assertEquals("com.example.social", parsed.platform?.packageName)
    }

    @Test
    fun messageRecipientIsNotTheQuotedBody() {
        val parsed = intent("message John \"running late\"")
        Assert.assertEquals(PlanCapability.SEND_MESSAGE, parsed.capability)
        Assert.assertEquals("John", parsed.entity)
        Assert.assertEquals("running late", parsed.payload)
    }

    @Test
    fun quotedPlatformNameIsNotResolvedFromInsideQuotes() {
        // The regex could capture `LinkedIn\"` from inside the quotes; it must not.
        val parsed = intent("follow TechCrunch on \"LinkedIn\"")
        Assert.assertEquals(PlanCapability.FOLLOW_ENTITY, parsed.capability)
        Assert.assertEquals("TechCrunch", parsed.entity)
        Assert.assertNull(parsed.platformName)
    }
}
