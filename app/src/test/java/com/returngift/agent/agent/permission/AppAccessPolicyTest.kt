// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The toggles + per-app modes, exhaustively.
 *
 * These are the rules that decide whether the permission card appears at all —
 * a bug here either annoys the user with prompts they turned off, or lets the
 * agent act in an app they blocked.
 */
class AppAccessPolicyTest {

    // ── pre-check (do we ask?) ──────────────────────────────────────────────

    @Test
    fun `always-allowed app never asks`() {
        val r = AppAccessPolicy.preCheck(AppAccessMode.ALWAYS, grantedOnce = false, askBeforeNewApp = true)
        assertEquals(AppAccessPolicy.Step.ALLOW, r.step)
    }

    @Test
    fun `blocked app never asks and never runs`() {
        val r = AppAccessPolicy.preCheck(AppAccessMode.BLOCKED, grantedOnce = false, askBeforeNewApp = true)
        assertEquals(AppAccessPolicy.Step.BLOCK, r.step)
        assertEquals("blocked by the user", r.reason)
    }

    @Test
    fun `blocked wins over a task grant`() {
        val r = AppAccessPolicy.preCheck(AppAccessMode.BLOCKED, grantedOnce = true, askBeforeNewApp = true)
        assertEquals(AppAccessPolicy.Step.BLOCK, r.step)
    }

    @Test
    fun `allow-once grant skips the card for the rest of the task`() {
        val r = AppAccessPolicy.preCheck(AppAccessMode.ASK, grantedOnce = true, askBeforeNewApp = true)
        assertEquals(AppAccessPolicy.Step.ALLOW, r.step)
        assertTrue(r.reason.contains("this task"))
    }

    @Test
    fun `first encounter asks by default`() {
        val r = AppAccessPolicy.preCheck(mode = null, grantedOnce = false, askBeforeNewApp = true)
        assertEquals(AppAccessPolicy.Step.ASK, r.step)
        assertEquals("first encounter", r.reason)
    }

    @Test
    fun `ask mode asks every task`() {
        val r = AppAccessPolicy.preCheck(AppAccessMode.ASK, grantedOnce = false, askBeforeNewApp = true)
        assertEquals(AppAccessPolicy.Step.ASK, r.step)
        assertEquals("set to ask every task", r.reason)
    }

    @Test
    fun `turning ask-before-new-app off auto-allows`() {
        val first = AppAccessPolicy.preCheck(mode = null, grantedOnce = false, askBeforeNewApp = false)
        val ask = AppAccessPolicy.preCheck(AppAccessMode.ASK, grantedOnce = false, askBeforeNewApp = false)
        assertEquals(AppAccessPolicy.Step.ALLOW, first.step)
        assertEquals(AppAccessPolicy.Step.ALLOW, ask.step)
        assertEquals("ask-before-new-app is off", first.reason)
    }

    @Test
    fun `turning ask off does not bypass an explicit block`() {
        val r = AppAccessPolicy.preCheck(AppAccessMode.BLOCKED, grantedOnce = false, askBeforeNewApp = false)
        assertEquals(AppAccessPolicy.Step.BLOCK, r.step)
    }

    // ── answers (what gets stored) ──────────────────────────────────────────

    @Test
    fun `allow once is task-only and keeps the app on ask`() {
        val o = AppAccessPolicy.applyDecision(PermissionRequest.Decision.ALLOW_ONCE, rememberChoices = true)
        assertTrue(o.allow)
        assertTrue(o.taskOnly)
        assertEquals(AppAccessMode.ASK, o.persistMode)
    }

    @Test
    fun `always allow persists when remembering is on`() {
        val o = AppAccessPolicy.applyDecision(PermissionRequest.Decision.ALWAYS_ALLOW, rememberChoices = true)
        assertTrue(o.allow)
        assertFalse(o.taskOnly)
        assertEquals(AppAccessMode.ALWAYS, o.persistMode)
    }

    @Test
    fun `always allow is downgraded to task-only when remembering is off`() {
        val o = AppAccessPolicy.applyDecision(PermissionRequest.Decision.ALWAYS_ALLOW, rememberChoices = false)
        assertTrue(o.allow)
        assertTrue(o.taskOnly)
        assertEquals(AppAccessMode.ASK, o.persistMode)
        assertTrue(o.reason.contains("this task"))
    }

    @Test
    fun `cancel does not grant`() {
        val o = AppAccessPolicy.applyDecision(PermissionRequest.Decision.CANCEL, rememberChoices = true)
        assertFalse(o.allow)
        assertTrue(o.taskOnly)
    }

    @Test
    fun `open settings does not grant`() {
        val o = AppAccessPolicy.applyDecision(PermissionRequest.Decision.OPEN_SETTINGS, rememberChoices = true)
        assertFalse(o.allow)
    }

    // ── mode parsing ────────────────────────────────────────────────────────

    @Test
    fun `stored modes parse and unknown values fall back to ask`() {
        assertEquals(AppAccessMode.ALWAYS, AppAccessMode.fromStorage("always"))
        assertEquals(AppAccessMode.BLOCKED, AppAccessMode.fromStorage("BLOCKED"))
        assertEquals(AppAccessMode.ASK, AppAccessMode.fromStorage("ask"))
        assertEquals(AppAccessMode.ASK, AppAccessMode.fromStorage(null))
        assertEquals(AppAccessMode.ASK, AppAccessMode.fromStorage("nonsense"))
    }

    @Test
    fun `ui labels are stable`() {
        assertEquals("Always allow", AppAccessMode.ALWAYS.uiLabel)
        assertEquals("Ask", AppAccessMode.ASK.uiLabel)
        assertEquals("Blocked", AppAccessMode.BLOCKED.uiLabel)
    }
}
