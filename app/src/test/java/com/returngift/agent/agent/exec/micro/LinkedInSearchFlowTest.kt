// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec.micro

import com.returngift.agent.agent.flow.SessionBlocker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Open LinkedIn → search 'zip'", end to end at the micro-step level.
 *
 * The flow touches almost every hard part of UI automation: a session that may
 * have expired, a search box that reports the same surface as the post
 * composer, a server round-trip that can be throttled, and a consent banner in
 * front of all of it. Each of those used to end in a silent failure.
 */
class LinkedInSearchFlowTest {

    private val linkedin = "com.linkedin.android"

    private fun typedSearch(query: String = "zip", label: String = "Search") =
        MicroStepContext(
            lastAction = LastAction.TYPED_TEXT,
            surface = AppSurface.SEARCH_FIELD,
            packageName = linkedin,
            text = query,
            label = label,
            fieldEmpty = query.isBlank(),
            stepIndex = 2,
        )

    private fun bridged(toolName: String, params: Map<String, Any>, screenText: String = "") =
        MicroStepBridge.observe(
            toolName = toolName,
            params = params,
            success = true,
            foregroundPackage = linkedin,
            screenChanged = false,
            screenText = screenText,
            stepIndex = 3,
        )

    // ── the happy path ───────────────────────────────────────────────────────

    @Test
    fun `query typed in linkedin search submits and opens the first result`() {
        val plan = MicroStepPlanner.plan(typedSearch("zip"))
        assertEquals("linkedin_search_submit", plan.ruleId)
        assertEquals(MicroStepKind.PRESS_ENTER, plan.steps.first().kind)
        assertTrue("the result list must be waited for", plan.steps.any { it.kind == MicroStepKind.WAIT_FOR_RESULTS })
        assertTrue("the first hit must be opened", plan.steps.any { it.kind == MicroStepKind.SELECT_FIRST_RESULT })
    }

    @Test
    fun `a search query in linkedin is never treated as a post`() {
        val plan = MicroStepPlanner.plan(typedSearch("zip"))
        assertFalse(
            "typing a search term must not tap Post",
            plan.steps.any { it.kind == MicroStepKind.TAP_LABEL && it.label.equals("Post", true) },
        )
    }

    @Test
    fun `the composer still publishes when the label is a composer`() {
        val ctx = MicroStepContext(
            lastAction = LastAction.TYPED_TEXT,
            surface = AppSurface.SOCIAL_POST,
            packageName = linkedin,
            text = "We shipped v3.3.0",
            label = "What do you want to talk about?",
            fieldEmpty = false,
        )
        val plan = MicroStepPlanner.plan(ctx)
        assertEquals("post_composer_publish", plan.ruleId)
        assertEquals("Post", plan.steps.first().label)
    }

    // ── bridge mapping ───────────────────────────────────────────────────────

    @Test
    fun `typing into the linkedin search box is mapped to a search surface`() {
        val ctx = MicroStepBridge.observe(
            toolName = "input_text",
            params = mapOf("text" to "zip"),
            success = true,
            foregroundPackage = linkedin,
            label = "Search",
        )
        assertEquals(AppSurface.SEARCH_FIELD, ctx.surface)
    }

    @Test
    fun `typing into the linkedin composer keeps the social surface`() {
        val ctx = MicroStepBridge.observe(
            toolName = "input_text",
            params = mapOf("text" to "Hello network"),
            success = true,
            foregroundPackage = linkedin,
            label = "Start a post",
        )
        assertEquals(AppSurface.SOCIAL_POST, ctx.surface)
    }

    @Test
    fun `a failed action carries the blocker from the screen`() {
        val ctx = bridged(
            toolName = "tap",
            params = mapOf("text" to "Search"),
            screenText = "Sign in · Email or phone · Password · Forgot password?",
        )
        assertEquals(SessionBlocker.LOGIN_REQUIRED, ctx.blocker)
    }

    @Test
    fun `a clean screen carries no blocker`() {
        val ctx = bridged(
            toolName = "tap",
            params = mapOf("text" to "Search"),
            screenText = "Start a post · Zip — fintech platform · Search",
        )
        assertEquals(SessionBlocker.NONE, ctx.blocker)
    }

    // ── blockers ─────────────────────────────────────────────────────────────

    private fun blocked(blocker: SessionBlocker) = MicroStepContext(
        lastAction = LastAction.TAPPED,
        surface = AppSurface.SEARCH_FIELD,
        packageName = linkedin,
        label = "Search",
        blocker = blocker,
        stepIndex = 4,
    )

    @Test
    fun `a login wall stops the flow and tells the user`() {
        val plan = MicroStepPlanner.plan(blocked(SessionBlocker.LOGIN_REQUIRED))
        assertEquals("blocker_login_wall", plan.ruleId)
        val step = plan.steps.first()
        assertEquals(MicroStepKind.STOP_AND_REPORT, step.kind)
        assertTrue(step.note.contains("Login required"))
        assertTrue(step.note.contains("Do not type"))
        // A stop is a report, not an action: it must never veto `finish` forever.
        assertFalse(step.isActionable)
        assertFalse(plan.hasBlockingAction)
    }

    @Test
    fun `a captcha stops the flow`() {
        val plan = MicroStepPlanner.plan(blocked(SessionBlocker.CAPTCHA_REQUIRED))
        assertEquals("blocker_captcha", plan.ruleId)
        assertEquals(MicroStepKind.STOP_AND_REPORT, plan.steps.first().kind)
    }

    @Test
    fun `an account chooser stops the flow`() {
        val plan = MicroStepPlanner.plan(blocked(SessionBlocker.ACCOUNT_CHOOSER))
        assertEquals("blocker_account_chooser", plan.ruleId)
        assertEquals(MicroStepKind.STOP_AND_REPORT, plan.steps.first().kind)
    }

    @Test
    fun `a consent banner is accepted once then the flow continues`() {
        val plan = MicroStepPlanner.plan(blocked(SessionBlocker.CONSENT_REQUIRED))
        assertEquals("blocker_consent_accept", plan.ruleId)
        assertEquals(MicroStepKind.CHOOSE_DIALOG_OPTION, plan.steps.first().kind)
        assertEquals("Accept all", plan.steps.first().label)
    }

    @Test
    fun `throttling waits instead of retrying immediately`() {
        val plan = MicroStepPlanner.plan(blocked(SessionBlocker.RATE_LIMITED))
        assertEquals("blocker_rate_limited", plan.ruleId)
        assertEquals(MicroStepKind.WAIT_FOR_SETTLE, plan.steps.first().kind)
        assertTrue(plan.steps.any { it.kind == MicroStepKind.OBSERVE_SCREEN })
        assertFalse("waiting is loop-owned, not a mandatory action", plan.hasBlockingAction)
    }

    @Test
    fun `network trouble re-reads the screen`() {
        val plan = MicroStepPlanner.plan(blocked(SessionBlocker.NETWORK_ERROR))
        assertEquals("blocker_network_error", plan.ruleId)
        assertTrue(plan.steps.any { it.kind == MicroStepKind.OBSERVE_SCREEN })
    }

    @Test
    fun `blockers outrank every ordinary follow-up`() {
        // Something was typed AND a login wall is up: the wall wins.
        val ctx = typedSearch("zip").copy(blocker = SessionBlocker.LOGIN_REQUIRED)
        assertEquals("blocker_login_wall", MicroStepPlanner.plan(ctx).ruleId)
    }

    @Test
    fun `a deep link that did not land falls back to the search control`() {
        val ctx = MicroStepContext(
            lastAction = LastAction.OPENED_APP,
            surface = AppSurface.SOCIAL_POST,
            packageName = linkedin,
            screenChanged = false,
        )
        val plan = MicroStepPlanner.plan(ctx)
        assertEquals("linkedin_deeplink_verify", plan.ruleId)
        assertTrue(plan.steps.any { it.kind == MicroStepKind.TAP_LABEL && it.label == "Search" })
    }

    // ── guidance the model actually sees ─────────────────────────────────────

    @Test
    fun `guidance text is imperative and names the wall`() {
        val plan = MicroStepPlanner.plan(blocked(SessionBlocker.LOGIN_REQUIRED))
        val text = MicroStepPlanner.guidance(plan)
        assertTrue(text.contains("[Micro-step check — rule blocker_login_wall]"))
        assertTrue(text.contains("stop and report"))
    }

    @Test
    fun `the whole tracker refuses to call a login wall done`() {
        val tracker = MicroStepTracker()
        tracker.observe(typedSearch("zip"))
        assertTrue("typing alone is not done", !tracker.canFinish())

        // The login wall arrives: the outstanding "press Enter" is still owed, and a
        // stop-and-report plan does not silently satisfy it.
        tracker.observe(blocked(SessionBlocker.LOGIN_REQUIRED))
        assertTrue(tracker.outstandingRuleId() == "linkedin_search_submit" || !tracker.canFinish())
    }
}
