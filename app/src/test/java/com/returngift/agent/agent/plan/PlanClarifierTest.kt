// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.plan

import com.returngift.agent.agent.exec.micro.AppSurface
import org.junit.Assert
import org.junit.Test

/**
 * Answers must change the plan. This is the regression that matters: ask "where?",
 * get "LinkedIn", and the second plan has to open LinkedIn — not the old guess.
 */
class PlanClarifierTest {

    private fun ctx() = PlanContext(
        apps = listOf(
            AppRef("LinkedIn", "com.example.social", AppSurface.SOCIAL_POST),
            AppRef("X", "com.example.x", AppSurface.SOCIAL_POST),
            AppRef("Messages", "com.example.chat", AppSurface.CHAT_COMPOSER),
        )
    )

    private fun plan(text: String) = PlanFirst.plan(text, ctx())

    @Test
    fun platformAnswerBecomesTheOpenStep() {
        val first = plan("follow youtube")
        Assert.assertTrue(first.blueprint.needsClarification)
        Assert.assertTrue(first.blueprint.steps.isEmpty())

        val clarified = PlanClarifier.applyAnswers("follow youtube", first.blueprint, mapOf("platform" to "LinkedIn"))
        Assert.assertEquals("follow youtube on LinkedIn", clarified)
        val second = PlanFirst.plan(clarified, ctx())
        Assert.assertFalse(second.blueprint.needsClarification)
        Assert.assertEquals("Open LinkedIn", second.blueprint.steps.first().detail)
    }

    @Test
    fun platformAnswerReplacesAWrongAppInsteadOfAppending() {
        val first = plan("follow youtube on Megafon")
        val clarified = PlanClarifier.applyAnswers(
            "follow youtube on Megafon",
            first.blueprint,
            mapOf("platform" to "LinkedIn"),
        )
        Assert.assertEquals("follow youtube on LinkedIn", clarified)
        Assert.assertEquals("Open LinkedIn", PlanFirst.plan(clarified, ctx()).blueprint.steps.first().detail)
    }

    @Test
    fun installAnswerUsesTheBrowserFallback() {
        val first = plan("follow youtube on Snapchat")
        val clarified = PlanClarifier.applyAnswers(
            "follow youtube on Snapchat",
            first.blueprint,
            mapOf("install" to "Use Browser"),
        )
        Assert.assertTrue(clarified.endsWith("on Browser"))
    }

    @Test
    fun payloadAnswerCompletesTheMessage() {
        val first = plan("message mom on Messages")
        Assert.assertTrue(first.blueprint.questions.any { it.id == "payload" })
        val clarified = PlanClarifier.applyAnswers(
            "message mom on Messages",
            first.blueprint,
            mapOf("payload" to "dinner at 8"),
        )
        val second = PlanFirst.plan(clarified, ctx())
        Assert.assertFalse(second.blueprint.needsClarification)
        Assert.assertTrue(second.blueprint.steps.any { it.action == PlanAction.INPUT_TEXT && it.detail == "dinner at 8" })
    }

    @Test
    fun recipientAnswerIsInsertedAfterTheVerb() {
        val first = plan("message on Messages")
        val clarified = PlanClarifier.applyAnswers(
            "message on Messages",
            first.blueprint,
            mapOf("recipient" to "Ravi", "payload" to "hi"),
        )
        Assert.assertTrue(clarified.startsWith("message Ravi"))
        val second = PlanFirst.plan(clarified, ctx())
        Assert.assertTrue(second.blueprint.steps.any { it.detail == "the chat with Ravi" })
    }

    @Test
    fun answeredReportsMissingBlockingAnswers() {
        val first = plan("message mom")
        Assert.assertFalse(PlanClarifier.isAnswered(first.blueprint, mapOf("platform" to "Messages")))
        Assert.assertTrue(
            PlanClarifier.isAnswered(
                first.blueprint,
                mapOf("platform" to "Messages", "payload" to "hello"),
            )
        )
    }

    @Test
    fun noAnswersLeavesTheRequestUntouched() {
        val first = plan("follow youtube")
        Assert.assertEquals("follow youtube", PlanClarifier.applyAnswers("follow youtube", first.blueprint, emptyMap()))
    }
}
