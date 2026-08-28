package com.returngift.agent.agent

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FIX 11 / A4: Tier-1 DirectTool execution must go through the per-app allow-list gate.
 * This pins the pure decision half of the gate (PipelineRouter.allowListBlockError) plus
 * the rule that the live parser emits send_message for the circuit that gets gated.
 *
 * Real AppAllowListGuard results are Android (PackageManager + SQLite store), so here the
 * [check] lambda is injected — exactly the seam allowListBlockError exposes. The wiring that
 * binds it to AppAllowListGuard.checkAndRecord is exercised end-to-end on device (QA T1).
 */
class PipelineRouterAllowListGateTest {

    private fun router(context: Context = org.mockito.Mockito.mock(Context::class.java)) =
        PipelineRouter(context)

    private fun blockFor(
        app: String,
        own: String = "com.returngift.agent",
        result: AppAllowListGuard.CheckResult =
            AppAllowListGuard.CheckResult.Blocked("com.whatsapp", "WhatsApp"),
    ): String? = router().allowListBlockError(app, own) { result }

    // ── the live path really emits send_message with an "app" param that gets gated ─
    @Test
    fun `send message to a contact is a Tier-1 tool call`() {
        val parsed = TaskParser.parse("send hi to Girlfriend on WhatsApp")
        assertEquals("send_message", parsed?.action)
        assertEquals("WhatsApp", parsed?.toolParams?.get("app"))
    }

    // ── blocker semantics ──────────────────────────────────────────────────────────
    @Test
    fun `blocked app returns a block message`() {
        val msg = blockFor(app = "WhatsApp", result = AppAllowListGuard.CheckResult.Blocked("com.whatsapp", "WhatsApp"))
        assertNotNull(msg)
        assertEquals(true, msg!!.contains("WhatsApp"))
        assertEquals(true, msg.contains("not allowed"))
    }

    @Test
    fun `allowed app passes the gate`() {
        assertNull(blockFor(app = "WhatsApp", result = AppAllowListGuard.CheckResult.Allowed))
    }

    @Test
    fun `first time app passes the gate (default ON)`() {
        assertNull(
            blockFor(app = "WhatsApp", result = AppAllowListGuard.CheckResult.FirstTime("com.whatsapp", "WhatsApp"))
        )
    }

    @Test
    fun `blank app name passes without gating`() {
        assertNull(blockFor(app = "   ", result = AppAllowListGuard.CheckResult.Blocked("x", "x")))
    }

    @Test
    fun `agent own package is exempt from the allow-list`() {
        assertNull(
            blockFor(
                app = "com.returngift.agent",
                own = "com.returngift.agent",
                result = AppAllowListGuard.CheckResult.Blocked("com.returngift.agent", "ReturnGift")
            )
        )
    }

    @Test
    fun `unknown app name still resolves and is gated`() {
        // resolver falls back to the raw name when uninstalled; still blocked decision applies
        val msg = blockFor(
            app = "some-uninstalled-app",
            result = AppAllowListGuard.CheckResult.Blocked("some-uninstalled-app", "some-uninstalled-app")
        )
        assertNotNull(msg)
    }

    // ── FIX 11a: global blocklist (sensitive content) on Tier-1 DirectTool ─────────
    @Test
    fun `global blocklist blocks otp in params`() {
        val msg = SafetyInterceptor.checkGlobalBlocklist("send the OTP to mom")
        assertNotNull(msg)
        assertEquals(true, msg!!.contains("otp"))
    }

    @Test
    fun `global blocklist blocks one-time password phrase`() {
        assertNotNull(SafetyInterceptor.checkGlobalBlocklist("here is my one-time password: 1234"))
    }

    @Test
    fun `global blocklist blocks cvv`() {
        assertNotNull(SafetyInterceptor.checkGlobalBlocklist("cvv 123"))
    }

    @Test
    fun `global blocklist blocks pin code`() {
        assertNotNull(SafetyInterceptor.checkGlobalBlocklist("pin code is 4321"))
    }

    @Test
    fun `global blocklist passes benign message text`() {
        assertNull(SafetyInterceptor.checkGlobalBlocklist("remind mom about dinner at 7"))
    }

    @Test
    fun `global blocklist is case-insensitive`() {
        assertNotNull(SafetyInterceptor.checkGlobalBlocklist("my OTP is 123456"))
    }

    @Test
    fun `blocklisted param text blocks the tier-1 tool call`() {
        // The blocklist gate runs BEFORE the allow-list and before ToolRegistry, so a
        // blocked phrase short-circuits without touching Android (mock context is fine).
        val r = router()
        val result = r.executeTool("send_message", mapOf("app" to "WhatsApp", "body" to "the OTP is 1234"))
        assertEquals(false, result.isSuccess)
        assertEquals(true, result.error!!.contains("blocked"))
    }

    @Test
    fun `blocklisted text blocks even an own-package-exempt call`() {
        // Blocklist is content-based; it applies even when the allow-list would skip.
        val r = router()
        val result = r.executeTool("send_message", mapOf("app" to "com.returngift.agent", "body" to "cvv 999"))
        assertEquals(false, result.isSuccess)
        assertEquals(true, result.error!!.contains("blocked"))
    }
}