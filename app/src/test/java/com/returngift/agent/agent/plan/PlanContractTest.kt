// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.plan

import com.returngift.agent.agent.exec.micro.AppSurface
import org.junit.Assert
import org.junit.Test

/**
 * The contract the review asked for: a plan is not advice, it is state.
 *
 * Covered here: step advancement, evidence requirements, refusal to finish on a
 * promise that was never kept, fail-closed gating, and resumability.
 */
class PlanContractTest {

    private fun ctx() = PlanContext(
        apps = listOf(
            AppRef("LinkedIn", "com.example.social", AppSurface.SOCIAL_POST),
            AppRef("YouTube", "com.example.video", AppSurface.VIDEO),
        )
    )

    private fun blueprint(text: String = "follow youtube on LinkedIn") =
        PlanFirst.plan(text, ctx()).blueprint

    private fun contract(text: String = "follow youtube on LinkedIn", mode: PlanContract.Mode = PlanContract.Mode.GUARDED) =
        PlanContract.from("task-1", blueprint(text), mode)

    /** Drive the happy path: every step satisfied with evidence. */
    private fun runHappyPath(c: PlanContract) {
        c.observe("open_app", true, "LinkedIn in foreground")
        c.observe("input_text", true, "typed youtube")
        c.observe("tap_node", true, "Companies filter")
        c.observe("tap_node", true, "YouTube company page")
        c.observe("tap_node", true, "Following")
        // A VERIFY step needs the outcome, not a successful screen read.
        c.observe("get_screen_info", true, "button label: Following", verified = true)
    }

    // ── construction ────────────────────────────────────────────────────────

    @Test
    fun contractMirrorsThePlanAndStartsOnStepOne() {
        val c = contract()
        Assert.assertEquals(6, c.states().size)
        Assert.assertEquals(PlanContract.StepStatus.ACTIVE, c.states().first().status)
        Assert.assertEquals(
            PlanContract.StepStatus.PENDING,
            c.states()[1].status,
        )
        Assert.assertEquals(PlanCapability.FOLLOW_ENTITY, c.blueprint.intent.capability)
        Assert.assertEquals(PlanContract.RiskClass.SENSITIVE, c.riskClass)
    }

    @Test
    fun anEmptyPlanIsTriviallyComplete() {
        val c = PlanContract.from("t", TaskBlueprint.direct("what is a variable?"))
        Assert.assertTrue(c.states().isEmpty())
        Assert.assertTrue(c.completion().canComplete)
    }

    @Test
    fun riskClassFollowsTheCapability() {
        Assert.assertEquals(
            PlanContract.RiskClass.NORMAL,
            PlanContract.from("t", blueprint("open LinkedIn")).riskClass,
        )
        Assert.assertEquals(
            PlanContract.RiskClass.SENSITIVE,
            PlanContract.from("t", blueprint("post \"hi\" on LinkedIn")).riskClass,
        )
    }

    // ── advancement ─────────────────────────────────────────────────────────

    @Test
    fun eachSuccessfulCallAdvancesAndRecordsEvidence() {
        val c = contract()
        c.observe("open_app", true, "LinkedIn in foreground", nowMs = 10)
        Assert.assertEquals(PlanContract.StepStatus.DONE, c.states()[0].status)
        Assert.assertEquals("LinkedIn in foreground", c.states()[0].evidence)
        Assert.assertEquals(10L, c.states()[0].completedAtMs)
        Assert.assertEquals(PlanContract.StepStatus.ACTIVE, c.states()[1].status)
    }

    @Test
    fun aFailedActionStaysRetryable() {
        val c = contract()
        c.observe("open_app", false, "app not found")
        Assert.assertEquals(PlanContract.StepStatus.FAILED, c.states()[0].status)
        Assert.assertFalse(c.isComplete())
        // The same step is still the one to satisfy.
        c.observe("open_app", true, "opened")
        Assert.assertEquals(PlanContract.StepStatus.DONE, c.states()[0].status)
    }

    @Test
    fun jumpingAheadMarksBypassedStepsAsSkipped() {
        val c = contract()
        // The model never opened the app or typed the search; it tapped straight away.
        c.observe("tap_node", true, "YouTube page")
        val states = c.states()
        Assert.assertEquals(PlanContract.StepStatus.SKIPPED, states[0].status)
        Assert.assertEquals(PlanContract.StepStatus.SKIPPED, states[1].status)
        Assert.assertTrue(states[0].note!!.contains("skipped by"))
        // Ambiguous taps satisfy the EARLIEST matching step (here "Filter by
        // Companies"), never a later one: the contract must not claim progress the
        // agent cannot prove.
        Assert.assertEquals(PlanContract.StepStatus.DONE, states[2].status)
        Assert.assertEquals(PlanContract.StepStatus.ACTIVE, states[3].status)
        Assert.assertFalse(c.isComplete())
    }

    @Test
    fun anUnrelatedToolIsRecordedAsOffPlan() {
        val c = contract()
        c.observe("web_search", true, "results")
        Assert.assertEquals(listOf("web_search"), c.offPlanCalls)
        Assert.assertTrue(c.summary().contains("off-plan=web_search"))
    }

    // ── completion ──────────────────────────────────────────────────────────

    @Test
    fun finishIsRefusedBeforeThePlanIsDone() {
        val c = contract()
        val verdict = c.completion()
        Assert.assertFalse(verdict.canComplete)
        Assert.assertEquals(6, verdict.missing.size)
        Assert.assertTrue(verdict.message.contains("Not finished yet"))
        Assert.assertTrue(c.gate("finish") is PlanContract.Gate.Deny)
    }

    @Test
    fun finishIsAllowedOnceEveryPromisedStepIsDone() {
        val c = contract()
        runHappyPath(c)
        Assert.assertTrue(c.isComplete())
        Assert.assertTrue(c.completion().canComplete)
        Assert.assertTrue(c.gate("finish") is PlanContract.Gate.Allow)
    }

    @Test
    fun aSkippedVerificationStillBlocksCompletion() {
        val c = contract()
        c.observe("open_app", true, "ok")
        c.observe("input_text", true, "typed")
        c.observe("tap_node", true, "Companies")
        c.observe("tap_node", true, "page")
        c.observe("tap_node", true, "Following")
        // No observation of the button state: the verify step was bypassed.
        c.observe("tap_node", true, "somewhere else")
        val verdict = c.completion()
        Assert.assertFalse(verdict.canComplete)
        Assert.assertTrue(verdict.missing.any { it.contains("button shows Following") })
    }

    @Test
    fun aBlockedStepIsReportedByName() {
        val c = contract()
        c.observe("open_app", true, "ok")
        c.block(2, "login wall")
        val verdict = c.completion()
        Assert.assertFalse(verdict.canComplete)
        Assert.assertTrue(verdict.message.contains("blocked"))
    }

    // ── gating ──────────────────────────────────────────────────────────────

    @Test
    fun guardedModeAllowsOffPlanActionsButKeepsTheRecord() {
        val c = contract(mode = PlanContract.Mode.GUARDED)
        Assert.assertTrue(c.gate("web_search") is PlanContract.Gate.Allow)
        c.observe("web_search", true, "x")
        Assert.assertEquals(1, c.offPlanCalls.size)
    }

    @Test
    fun strictModeRefusesAnActionThatBelongsToAnotherStep() {
        val c = contract(mode = PlanContract.Mode.STRICT)
        // Step 1 is "Open LinkedIn"; typing text now is off-plan.
        val gate = c.gate("input_text", mapOf("text" to "youtube"))
        Assert.assertTrue(gate is PlanContract.Gate.Deny)
        Assert.assertTrue((gate as PlanContract.Gate.Deny).reason.contains("step 1"))
        // After opening the app, typing is exactly right.
        c.observe("open_app", true, "linkedin")
        Assert.assertTrue(c.gate("input_text") is PlanContract.Gate.Allow)
    }

    @Test
    fun strictModeStillAllowsObservationTools() {
        val c = contract(mode = PlanContract.Mode.STRICT)
        Assert.assertTrue(c.gate("get_screen_info") is PlanContract.Gate.Allow)
    }

    @Test
    fun anApprovalStepMustBeApprovedBeforeTheGuardedAction() {
        val p = PlanFirst.plan("follow youtube", ctx())
        Assert.assertTrue(p.blueprint.needsClarification)
        val withApproval = PlanContract.from(
            "t",
            p.blueprint.copy(
                steps = listOf(
                    PlanStep(1, PlanAction.OPEN_APP, "Open LinkedIn"),
                    PlanStep(2, PlanAction.ASK_USER, "Confirm you want to follow"),
                    PlanStep(3, PlanAction.TAP, "Follow"),
                )
            ),
        )
        withApproval.observe("open_app", true, "ok")
        val gate = withApproval.gate("tap_node")
        Assert.assertTrue(gate is PlanContract.Gate.Ask)
        withApproval.recordApproval(2)
        Assert.assertTrue(withApproval.gate("tap_node") is PlanContract.Gate.Allow)
    }

    // ── checkpoint / resume ─────────────────────────────────────────────────

    @Test
    fun checkpointRoundTripsProgress() {
        val c = contract()
        c.observe("open_app", true, "opened | with ; odd : chars")
        c.observe("input_text", true, "typed")

        val restored = PlanContract.fromCheckpoint(c.checkpoint(), c.blueprint)
        Assert.assertNotNull(restored)
        Assert.assertEquals(c.states().map { it.status }, restored!!.states().map { it.status })
        Assert.assertEquals("opened | with ; odd : chars", restored.states()[0].evidence)
        Assert.assertEquals(c.taskId, restored.taskId)
        Assert.assertEquals(c.riskClass, restored.riskClass)
    }

    @Test
    fun restoredContractStillRefusesPrematureFinish() {
        val c = contract()
        c.observe("open_app", true, "opened")
        val restored = PlanContract.fromCheckpoint(c.checkpoint(), c.blueprint)!!
        Assert.assertTrue(restored.gate("finish") is PlanContract.Gate.Deny)
    }

    @Test
    fun aCorruptCheckpointIsRejectedInsteadOfGuessed() {
        Assert.assertNull(PlanContract.fromCheckpoint("", blueprint()))
        Assert.assertNull(PlanContract.fromCheckpoint("v1|only-two-parts", blueprint()))
        Assert.assertNull(PlanContract.fromCheckpoint("v9|t|GUARDED|NORMAL||1:DONE", blueprint()))
    }

    @Test
    fun approvalsAreNotReusedAcrossARestart() {
        // OWNER DECISION (applied): a resumed plan asks again. The checkpoint is
        // minutes-to-hours old and may describe a different screen than the one the
        // user approved, so carrying the "yes" forward would be consent by staleness.
        val c = contract()
        c.recordApproval(2)
        Assert.assertTrue(c.approvals.contains("2"))
        val restored = PlanContract.fromCheckpoint(c.checkpoint(), c.blueprint)!!
        Assert.assertFalse(restored.approvals.contains("2"))
    }

    // ── action mapping ──────────────────────────────────────────────────────

    @Test
    fun toolMappingIsCapabilityBased() {
        Assert.assertEquals(setOf(PlanAction.OPEN_APP), PlanContract.actionsFor("open_app"))
        Assert.assertEquals(setOf(PlanAction.INPUT_TEXT, PlanAction.SEARCH), PlanContract.actionsFor("input_text"))
        Assert.assertTrue(PlanContract.actionsFor("tap_node").contains(PlanAction.SEND))
        Assert.assertTrue(PlanContract.actionsFor("get_screen_info").contains(PlanAction.VERIFY))
        Assert.assertTrue(PlanContract.actionsFor("no_such_tool").isEmpty())
    }

    @Test
    fun finishIsNeverAnObservableStep() {
        val c = contract()
        Assert.assertNull(c.observe("finish", true, "done"))
        Assert.assertEquals(0, c.completedCount())
    }

    @Test
    fun evidenceIsBounded() {
        val c = contract()
        c.observe("open_app", true, "x".repeat(5_000))
        Assert.assertTrue(c.states()[0].evidence!!.length <= 160)
    }

    // ── strict mode: what it actually refuses (opt-in, off by default) ───────

    @Test
    fun strictModeRefusesAToolNoStepCanMatch() {
        // "Never run an action this contract cannot match" — MCP and other tools with
        // no capability mapping are refused, not waved through on their own name.
        val c = contract(mode = PlanContract.Mode.STRICT)
        Assert.assertTrue(c.gate("mcp__srv__delete_record") is PlanContract.Gate.Deny)
        Assert.assertTrue(c.gate("frobnicate") is PlanContract.Gate.Deny)
        // Observation and loop tools are how the agent notices the UI differs.
        Assert.assertTrue(c.gate("get_screen_info") is PlanContract.Gate.Allow)
        Assert.assertTrue(c.gate("ask_user") is PlanContract.Gate.Allow)
    }

    @Test
    fun guardedModeStillAllowsAnUnmappedTool() {
        val c = contract(mode = PlanContract.Mode.GUARDED)
        Assert.assertTrue(c.gate("mcp__srv__search") is PlanContract.Gate.Allow)
        c.observe("mcp__srv__search", true, "rows")
        Assert.assertEquals(listOf("mcp__srv__search"), c.offPlanCalls)
    }

    // ── a step is about a target, not only a verb ───────────────────────────

    @Test
    fun anOpenOfADifferentAppNeverCompletesThePlanStep() {
        val c = contract() // step 1: Open LinkedIn
        val state = c.observe(
            "open_app", true, "opened",
            params = mapOf("package_name" to "com.example.video"),
        )
        Assert.assertNull("a different app must not satisfy the step", state)
        Assert.assertEquals(PlanContract.StepStatus.ACTIVE, c.states()[0].status)
        // Recorded — never silently dropped — and named with the reason.
        Assert.assertTrue(c.offPlanCalls.single().startsWith("open_app"))
        Assert.assertTrue(c.offPlanCalls.single().contains("LinkedIn"))
    }

    @Test
    fun anOpenOfThePlannedAppCompletesIt() {
        val c = contract()
        val state = c.observe(
            "open_app", true, "opened",
            params = mapOf("package_name" to "com.example.social"),
        )
        Assert.assertEquals(PlanContract.StepStatus.DONE, state?.status)
    }

    @Test
    fun strictModeRefusesAnActionOnADifferentApp() {
        val c = contract(mode = PlanContract.Mode.STRICT)
        val gate = c.gate("open_app", mapOf("package_name" to "com.example.video"))
        Assert.assertTrue(gate is PlanContract.Gate.Deny)
        Assert.assertTrue((gate as PlanContract.Gate.Deny).reason.contains("LinkedIn"))
        Assert.assertTrue(c.gate("open_app", mapOf("package_name" to "com.example.social")) is PlanContract.Gate.Allow)
    }

    // ── completion needs the promised outcome ───────────────────────────────

    @Test
    fun aSuccessfulScreenReadAloneDoesNotCompleteTheVerifyStep() {
        val c = contract()
        c.observe("open_app", true, "ok")
        c.observe("input_text", true, "typed")
        c.observe("tap_node", true, "Companies")
        c.observe("tap_node", true, "page")
        c.observe("tap_node", true, "Follow")
        val state = c.observe("get_screen_info", true, "LinkedIn feed, no button")
        Assert.assertNotEquals(PlanContract.StepStatus.DONE, state?.status)
        Assert.assertEquals(PlanContract.StepStatus.ACTIVE, c.states().last().status)
        Assert.assertTrue(c.states().last().note!!.contains("not confirmed"))
        Assert.assertFalse(c.completion().canComplete)
        Assert.assertTrue(c.gate("finish") is PlanContract.Gate.Deny)
    }

    @Test
    fun theVerifyStepCompletesWhenTheOutcomeIsObserved() {
        val c = contract()
        c.observe("open_app", true, "ok")
        c.observe("input_text", true, "typed")
        c.observe("tap_node", true, "Companies")
        c.observe("tap_node", true, "page")
        c.observe("tap_node", true, "Follow")
        c.observe("get_screen_info", true, "button: Following", verified = true)
        Assert.assertTrue(c.isComplete())
        Assert.assertTrue(c.gate("finish") is PlanContract.Gate.Allow)
    }
}
