// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec.micro

import com.returngift.agent.agent.flow.SessionBlocker
import com.returngift.agent.agent.knowledge.AppUsageGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App-agnostic recovery behaviour: "open <app> → search <term>" and the ways it
 * goes wrong.
 *
 * Deliberately NOT organised per app — the same scenarios run against every
 * package the knowledge registry classifies as a search / social / chat surface,
 * so a rule that only works for one app fails here. Fixtures come from
 * `AppUsageGuide`, so a newly registered app is covered without touching this
 * file.
 */
class AppFlowRecoveryTest {

    /**
     * Fixtures are derived from the knowledge registry, never hard-coded: if the
     * registry gains a search-first or social app the suite covers it for free.
     */
    private fun packagesWith(surface: AppSurface): List<String> =
        AppUsageGuide.builtinGuides().filter { it.surface == surface }.map { it.packageName }

    private val searchApps: List<String> = packagesWith(AppSurface.SEARCH_FIELD)
    private val socialApps: List<String> = packagesWith(AppSurface.SOCIAL_POST)
    private val chatApps: List<String> = packagesWith(AppSurface.CHAT_COMPOSER)
    private val anyApps: List<String> = searchApps + socialApps + chatApps

    /** An app the guide does not know — the "unknown app" case. */
    private val unguided = "com.example.unguided"

    private fun typedSearch(
        pkg: String = unguided,
        query: String = "zip",
        label: String = "Search",
        surface: AppSurface = AppSurface.SEARCH_FIELD,
    ) = MicroStepContext(
        lastAction = LastAction.TYPED_TEXT,
        surface = surface,
        packageName = pkg,
        text = query,
        label = label,
        fieldEmpty = query.isBlank(),
        stepIndex = 2,
    )

    private fun blocked(blocker: SessionBlocker, pkg: String = unguided) = MicroStepContext(
        lastAction = LastAction.TAPPED,
        surface = AppSurface.SEARCH_FIELD,
        packageName = pkg,
        label = "Search",
        blocker = blocker,
        stepIndex = 4,
    )

    private fun bridged(
        pkg: String = unguided,
        toolName: String = "tap",
        params: Map<String, Any> = mapOf("text" to "Search"),
        screenText: String = "",
        success: Boolean = true,
        label: String? = null,
    ) = MicroStepBridge.observe(
        toolName = toolName,
        params = params,
        success = success,
        foregroundPackage = pkg,
        screenChanged = false,
        screenText = screenText,
        label = label,
        stepIndex = 3,
    )

    // ── the happy path, for every kind of search surface ────────────────────

    @Test
    fun `a typed search always submits and waits for results`() {
        (searchApps + listOf(unguided)).forEach { pkg ->
            val plan = MicroStepPlanner.plan(typedSearch(pkg))
            assertEquals("[$pkg] first step must submit", MicroStepKind.PRESS_ENTER, plan.steps.first().kind)
            assertTrue("[$pkg] must wait for results", plan.steps.any { it.kind == MicroStepKind.WAIT_FOR_RESULTS })
        }
    }

    @Test
    fun `a generic search does not open a result on its own`() {
        // Searching is not the same as opening: the model decides whether a result
        // answers the request. App-specific flows (store, video…) opt in explicitly.
        val plan = MicroStepPlanner.plan(typedSearch(unguided))
        assertFalse(
            "generic search must not auto-open a result",
            plan.steps.any { it.kind == MicroStepKind.SELECT_FIRST_RESULT },
        )
    }

    @Test
    fun `a typed search never taps a publish button`() {
        // Regression: social apps report SOCIAL_POST for the whole app, so a search
        // term used to trigger the "tap Post" follow-up.
        (socialApps + searchApps + listOf(unguided)).forEach { pkg ->
            val plan = MicroStepPlanner.plan(typedSearch(pkg, surface = AppSurface.SEARCH_FIELD))
            assertFalse(
                "[$pkg] search must not publish: ${plan.ruleId}",
                plan.steps.any {
                    it.kind == MicroStepKind.TAP_LABEL &&
                        it.label.lowercase() in setOf("post", "share", "publish", "tweet")
                },
            )
        }
    }

    @Test
    fun `a real composer still publishes`() {
        val ctx = MicroStepContext(
            lastAction = LastAction.TYPED_TEXT,
            surface = AppSurface.SOCIAL_POST,
            packageName = socialApps.first(),
            text = "We shipped it",
            label = "What do you want to talk about?",
            fieldEmpty = false,
        )
        val plan = MicroStepPlanner.plan(ctx)
        assertEquals("post_composer_publish", plan.ruleId)
        assertEquals(MicroStepKind.TAP_LABEL, plan.steps.first().kind)
    }

    // ── bridge mapping ───────────────────────────────────────────────────────

    @Test
    fun `typing into an in-app search label maps to the search surface`() {
        searchApps.forEach { pkg ->
            assertEquals(
                "the guide decides the surface for $pkg",
                AppSurface.SEARCH_FIELD,
                bridged(pkg, toolName = "input_text", params = mapOf("text" to "zip"), label = "Search").surface,
            )
        }
    }

    @Test
    fun `typing into a composer keeps the app surface`() {
        val ctx = bridged(
            socialApps.first(),
            toolName = "input_text",
            params = mapOf("text" to "hello"),
            label = "Start a post",
        )
        assertEquals(AppSurface.SOCIAL_POST, ctx.surface)
    }

    @Test
    fun `a blocker is carried from the screen into the context`() {
        assertEquals(
            SessionBlocker.LOGIN_REQUIRED,
            bridged(searchApps.first(), screenText = "Sign in · Email or phone · Password · Forgot password?").blocker,
        )
        assertEquals(
            SessionBlocker.CONSENT_REQUIRED,
            bridged(chatApps.first(), screenText = "We value your privacy · Accept all").blocker,
        )
        assertEquals(SessionBlocker.NONE, bridged(unguided, screenText = "Apps · Games · Search").blocker)
    }

    // ── blockers, asserted for every app, not just one ──────────────────────

    @Test
    fun `a sign-in wall stops every app instead of typing credentials`() {
        anyApps.forEach { pkg ->
            val plan = MicroStepPlanner.plan(blocked(SessionBlocker.LOGIN_REQUIRED, pkg))
            assertEquals("[$pkg] ${plan.ruleId}", "blocker_login_wall", plan.ruleId)
            val step = plan.steps.first()
            assertEquals(MicroStepKind.STOP_AND_REPORT, step.kind)
            assertTrue("[$pkg] must tell the model not to type credentials", step.note.contains("Do not type"))
            assertFalse("[$pkg] a report must not veto finish forever", plan.hasBlockingAction)
        }
    }

    @Test
    fun `a captcha stops the flow`() {
        assertEquals(
            "blocker_captcha",
            MicroStepPlanner.plan(blocked(SessionBlocker.CAPTCHA_REQUIRED)).ruleId,
        )
        assertEquals(
            MicroStepKind.STOP_AND_REPORT,
            MicroStepPlanner.plan(blocked(SessionBlocker.CAPTCHA_REQUIRED)).steps.first().kind,
        )
    }

    @Test
    fun `an account chooser stops the flow`() {
        assertEquals(
            "blocker_account_chooser",
            MicroStepPlanner.plan(blocked(SessionBlocker.ACCOUNT_CHOOSER)).ruleId,
        )
    }

    @Test
    fun `a consent banner is accepted once`() {
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
        assertFalse("backoff is loop-owned", plan.hasBlockingAction)
    }

    @Test
    fun `network trouble re-reads the screen`() {
        val plan = MicroStepPlanner.plan(blocked(SessionBlocker.NETWORK_ERROR))
        assertEquals("blocker_network_error", plan.ruleId)
        assertTrue(plan.steps.any { it.kind == MicroStepKind.OBSERVE_SCREEN })
    }

    @Test
    fun `blockers outrank ordinary follow-ups for any app`() {
        anyApps.forEach { pkg ->
            val ctx = typedSearch(pkg).copy(blocker = SessionBlocker.LOGIN_REQUIRED)
            assertEquals("[$pkg]", "blocker_login_wall", MicroStepPlanner.plan(ctx).ruleId)
        }
    }

    // ── the tracker must never call a blocked task done ─────────────────────

    @Test
    fun `the tracker refuses to finish while a send is still owed`() {
        val tracker = MicroStepTracker()
        tracker.observe(
            typedSearch(chatApps.first(), query = "on my way", surface = AppSurface.CHAT_COMPOSER)
        )
        assertFalse("typing alone is not done", tracker.canFinish())
        assertTrue(tracker.blockFinishReason() != null)
    }

    @Test
    fun `guidance is imperative and names the blocker rule`() {
        val plan = MicroStepPlanner.plan(blocked(SessionBlocker.LOGIN_REQUIRED))
        val text = MicroStepPlanner.guidance(plan)
        assertTrue(text.contains("[Micro-step check — rule blocker_login_wall]"))
        assertTrue(text.contains("stop and report"))
    }
}
