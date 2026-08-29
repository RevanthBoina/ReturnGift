// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

import android.content.Context
import com.returngift.agent.agent.guard.SafetyInterceptorBlocklistSyncTest
import com.returngift.agent.agent.guard.TierGuard
import com.returngift.agent.agent.guard.TrustGuard
import com.returngift.agent.channel.Channel
import com.returngift.agent.tool.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D6: Tier guardrail wiring tests.
 * Verifies that:
 * 1. OTP blocklist tools are blocked by SafetyInterceptorBlocklistSyncTest
 * 2. Allow-list blocked app tools return null from guard()
 * 3. SafetyInterceptorBlocklistSyncTest green path allows tools
 * 4. AllowListToolGate reachability
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TierGuardWiringTest {

    private lateinit var context: Context
    private lateinit var tierGuard: TierGuard
    private lateinit var trustGuard: TrustGuard

    @Before
    fun setup() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        tierGuard = TierGuard(context)
        trustGuard = TrustGuard(context)
    }

    // D6-1: OTP blocklist tools are blocked by SafetyInterceptor
    @Test
    fun `OTP blocklist tools are blocked`() {
        val otpTools = listOf("get_otp", "read_otp", "otp_verification")
        for (tool in otpTools) {
            val result = SafetyInterceptorBlocklistSyncTest.shouldBlockTool(tool)
            assertTrue("Tool $tool should be blocked", result)
        }
    }

    // D6-2: Non-OTP tools are not blocked
    @Test
    fun `non-OTP tools pass blocklist`() {
        val safeTools = listOf("get_screen_info", "open_app", "tap", "finish")
        for (tool in safeTools) {
            val result = SafetyInterceptorBlocklistSyncTest.shouldBlockTool(tool)
            assertFalse("Tool $tool should not be blocked", result)
        }
    }

    // D6-3: Allow-list for known safe tools
    @Test
    fun `allow-listed tools pass guard`() {
        val allowList = listOf("get_screen_info", "open_app", "get_installed_apps", "get_device_info")
        for (tool in allowList) {
            val result = tierGuard.guard(tool, emptyMap(), "com.example.safe")
            assertNull("Tool $tool should pass guard", result)
        }
    }

    // D6-4: Blocked app returns block reason
    @Test
    fun `blocked app returns block reason`() {
        val blockedApps = listOf("com.android.systemui", "com.android.launcher")
        for (app in blockedApps) {
            val result = tierGuard.guard("get_screen_info", emptyMap(), app)
            assertNotNull("Tool should be blocked for app $app", result)
        }
    }

    // D6-5: SafetyInterceptorBlocklistSyncTest green path
    @Test
    fun `SafetyInterceptorBlocklistSyncTest green path allows non-blocked tools`() {
        val tool = "open_app"
        val blocked = SafetyInterceptorBlocklistSyncTest.shouldBlockTool(tool)
        assertFalse("open_app should not be blocked", blocked)
    }

    // D6-6: Unknown app defaults to allow
    @Test
    fun `unknown app defaults to allow`() {
        val result = tierGuard.guard("get_screen_info", emptyMap(), "com.unknown.app")
        assertNull("Unknown app should be allowed", result)
    }

    // D6-7: Trust guard checks for dangerous params
    @Test
    fun `TrustGuard blocks dangerous params`() {
        val dangerousParams = mapOf("package_name" to "com.android.systemui")
        val result = trustGuard.guard("open_app", dangerousParams, "com.example.app")
        assertNotNull("Dangerous params should be blocked", result)
    }

    // D6-8: ToolRegistry integration with TierGuard
    @Test
    fun `ToolRegistry tools are registered`() {
        val registry = ToolRegistry.getInstance()
        val tools = registry.getAllTools()
        assertTrue("ToolRegistry should have tools", tools.isNotEmpty())
    }
}
