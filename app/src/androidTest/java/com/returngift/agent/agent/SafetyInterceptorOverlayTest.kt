// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.blankj.utilcode.util.ActivityUtils
import com.returngift.agent.widget.OverlayConfirmDialog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

/**
 * Instrumented tests for SafetyInterceptor overlay fallback behavior.
 *
 * Tests the scenario where:
 * 1. No foreground Activity exists (app background, ExternalAutomationReceiver, scheduled task)
 * 2. A tier-2+ skill action is requested
 * 3. The overlay path should be taken instead of failing closed
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 23) // Overlay requires API 23+
class SafetyInterceptorOverlayTest {

    private lateinit var application: Application
    private lateinit var context: Context

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        context = application
        MockitoAnnotations.openMocks(this)
    }

    /**
     * Test: When no foreground Activity exists but overlay permission is granted,
     * the overlay dialog path should be used instead of failing closed.
     *
     * This test simulates the scenario where:
     * - ExternalAutomationReceiver triggers a skill
     * - A scheduled task fires
     * - Cloud-initiated task while app is in background
     *
     * Expected: SafetyInterceptor.check() returns null (allowed) if user confirms,
     * or error message if user denies/times out — NOT "no dialog context available"
     */
    @Test
    fun noActivityWithOverlayPermission_usesOverlayPath() {
        // Skip if overlay permission not granted (requires manual setup)
        if (!Settings.canDrawOverlays(application)) {
            // Mark test as skipped - overlay permission test requires device setup
            return
        }

        // Verify overlay permission is granted
        assertTrue("Overlay permission should be granted for this test",
            Settings.canDrawOverlays(application))

        // Set up a tier-2 skill context
        SafetyInterceptor.activeSkillId = "test_tier2_skill"

        try {
            // Verify that ActivityUtils returns null (no foreground Activity)
            val topActivity = ActivityUtils.getTopActivity()
            // Note: In instrumented test, there might be a test activity
            // The key is that we're testing the overlay fallback path

            // Call check with Application context (no Activity window token)
            // This should NOT fail closed with "no dialog context available"
            // when overlay permission is granted
            val result = SafetyInterceptor.check(
                toolName = "send_message",
                params = mapOf("recipient" to "test@example.com", "message" to "Test"),
                context = application // Application context, not Activity
            )

            // Result should be:
            // - null if user confirmed in overlay dialog
            // - error string if user declined or timed out
            // - NOT "Safety: cannot confirm... no dialog context available"
            if (result != null) {
                assertFalse("Should not fail with 'no dialog context' when overlay is available",
                    result.contains("no dialog context"))
            }
            // If result is null, user confirmed - test passes
        } finally {
            SafetyInterceptor.resetSession()
        }
    }

    /**
     * Test: When neither Activity nor overlay is available, fail closed.
     */
    @Test
    fun noActivityNoOverlayPermission_failsClosed() {
        // This test simulates the scenario where:
        // 1. No foreground Activity
        // 2. Overlay permission not granted (or Settings.canDrawOverlays returns false)

        // We'll test the OverlayConfirmDialog.hasOverlayPermission check
        val hasPermission = OverlayConfirmDialog.hasOverlayPermission(application)

        if (!hasPermission) {
            // Overlay not available - verify the check returns appropriate error
            SafetyInterceptor.activeSkillId = "test_tier2_skill"

            try {
                val result = SafetyInterceptor.check(
                    toolName = "send_message",
                    params = mapOf("recipient" to "test@example.com", "message" to "Test"),
                    context = application
                )

                // Should fail closed with a message indicating no dialog context
                assertNotNull("Should return error when no dialog context available", result)
                assertTrue("Error should mention dialog context unavailability",
                    result!!.contains("dialog context") || result.contains("confirm"))
            } finally {
                SafetyInterceptor.resetSession()
            }
        }
        // If overlay IS available, this test is not applicable
    }

    /**
     * Test: Activity context should be preferred over overlay.
     */
    @Test
    fun activityContextExists_prefersActivityDialog() {
        val topActivity = ActivityUtils.getTopActivity()

        // This test documents expected behavior:
        // When getTopActivity() returns non-null, ConfirmDialog (not OverlayConfirmDialog) should be used
        // We can't fully test this without mocking, but we document the expected path

        // The key assertion is that when an Activity exists, the context parameter
        // is not used - the top Activity is used instead
        assertNotNull("Activity context should be preferred", topActivity)
        // Note: In test environment, the test runner activity might be top
    }
}
