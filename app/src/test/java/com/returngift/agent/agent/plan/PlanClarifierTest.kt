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

    // ── the bounded ask → answer → rebuild loop (PlanQuestionFlow) ──────────

    /** Records the questions asked and answers them from a fixed script. */
    private class Scripted(vararg answers: String?) {
        val asked = mutableListOf<PlanQuestion>()
        private val queue = ArrayDeque(answers.toList())
        fun ask(q: PlanQuestion): String? {
            asked.add(q)
            return if (queue.isEmpty()) null else queue.removeFirst()
        }
    }

    private fun runLoop(text: String, scripted: Scripted) =
        PlanQuestionFlow.run(text, ctx(), plan(text), ask = scripted::ask)

    /** `unsubscribe` is both an irreversible verb for the plan and device work. */
    private val risky = "unsubscribe from youtube on LinkedIn"

    @Test
    fun anApprovedIrreversibleRequestProceedsWithoutAskingAgain() {
        // The plan that will run contains this question and nothing else.
        Assert.assertEquals(listOf(PlanQuestionFlow.APPROVAL_ID), plan(risky).blueprint.questions.map { it.id })

        val scripted = Scripted("Yes, do it")
        val outcome = runLoop(risky, scripted)
        Assert.assertTrue("expected Proceed, got $outcome", outcome is PlanQuestionFlow.Outcome.Proceed)
        // The invariant, stated once: approval is asked exactly once, and nothing is
        // left to answer before the plan can run.
        Assert.assertEquals(1, scripted.asked.count { it.id == PlanQuestionFlow.APPROVAL_ID })
        val proceed = outcome as PlanQuestionFlow.Outcome.Proceed
        Assert.assertFalse(proceed.presentation.blueprint.needsClarification)
        Assert.assertTrue(proceed.presentation.executeAfter)
    }

    @Test
    fun aQuestionIntroducedByTheFirstAnswerIsStillAsked() {
        // No app named → "Where should I unsubscribe …?", and that answer re-plans into
        // the same risky request, which must then ask the approval exactly once.
        val scripted = Scripted("LinkedIn", "Yes, do it")
        val outcome = runLoop("unsubscribe from youtube", scripted)

        Assert.assertEquals(
            listOf("platform", PlanQuestionFlow.APPROVAL_ID),
            scripted.asked.map { it.id },
        )
        Assert.assertTrue("expected Proceed, got $outcome", outcome is PlanQuestionFlow.Outcome.Proceed)
        val proceed = outcome as PlanQuestionFlow.Outcome.Proceed
        Assert.assertTrue(proceed.presentation.executeAfter)
        Assert.assertEquals("Open LinkedIn", proceed.presentation.blueprint.steps.first().detail)
    }

    @Test
    fun decliningTheApprovalStopsEverything() {
        val outcome = runLoop(risky, Scripted("Cancel"))
        Assert.assertTrue(
            "expected ApprovalDeclined, got $outcome",
            outcome is PlanQuestionFlow.Outcome.ApprovalDeclined,
        )
    }

    @Test
    fun aTimeoutOnAnyQuestionCancelsInsteadOfGuessing() {
        val scripted = Scripted("LinkedIn", null) // platform answered, approval timed out
        val outcome = runLoop("unsubscribe from youtube", scripted)
        Assert.assertTrue("expected Cancelled, got $outcome", outcome is PlanQuestionFlow.Outcome.Cancelled)
        Assert.assertTrue(scripted.asked.size <= PlanQuestionFlow.MAX_QUESTIONS)
    }

    @Test
    fun theLoopStopsAtItsBoundAndReportsWhatIsStillMissing() {
        // An answer that resolves nothing: the loop must stop at the bound, never spin,
        // and report what it still needs instead of executing on a guess.
        val asked = mutableListOf<PlanQuestion>()
        val outcome = PlanQuestionFlow.run(
            "unsubscribe from youtube", ctx(), plan("unsubscribe from youtube"),
            ask = { q -> asked.add(q); "Something" },
        )
        Assert.assertTrue(asked.size <= PlanQuestionFlow.MAX_QUESTIONS)
        Assert.assertTrue(
            "expected StillBlocked, got $outcome",
            outcome is PlanQuestionFlow.Outcome.StillBlocked,
        )
    }

    @Test
    fun aRequestWithNoQuestionsProceedsUntouched() {
        val scripted = Scripted()
        val outcome = runLoop("follow youtube on LinkedIn", scripted)
        Assert.assertTrue(scripted.asked.isEmpty())
        Assert.assertTrue(outcome is PlanQuestionFlow.Outcome.Proceed)
    }
}
