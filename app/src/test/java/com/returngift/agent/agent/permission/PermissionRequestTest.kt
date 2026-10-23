// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-model tests for the permission prompt shown by the APK interface. */
class PermissionRequestTest {

    @Test
    fun `app request offers allow-once always-allow and cancel`() {
        val request = PermissionRequest.forApp("com.whatsapp", "WhatsApp")
        assertEquals(PermissionRequest.Kind.APP_ACCESS, request.kind)
        assertTrue(request.isAppAccess)
        assertEquals(
            listOf("Allow once", "Always allow", "Cancel"),
            request.choices(),
        )
        assertTrue(request.title().contains("WhatsApp"))
    }

    @Test
    fun `capability request offers open-settings and cancel`() {
        val request = PermissionRequest.forCapability("ACCESSIBILITY", "Accessibility")
        assertEquals(PermissionRequest.Kind.SYSTEM_CAPABILITY, request.kind)
        assertFalse(request.isAppAccess)
        assertEquals(listOf("Open settings", "Cancel"), request.choices())
        assertTrue(request.body().contains("system page"))
    }

    @Test
    fun `choices map to decisions case-insensitively`() {
        val request = PermissionRequest.forApp("com.example", "Example")
        assertEquals(PermissionRequest.Decision.ALLOW_ONCE, request.decisionFor("allow once"))
        assertEquals(PermissionRequest.Decision.ALLOW_ONCE, request.decisionFor("  Allow Once  "))
        assertEquals(PermissionRequest.Decision.ALWAYS_ALLOW, request.decisionFor("Always allow"))
        assertEquals(PermissionRequest.Decision.ALWAYS_ALLOW, request.decisionFor("remember"))
        assertEquals(PermissionRequest.Decision.CANCEL, request.decisionFor("Cancel"))
        assertEquals(PermissionRequest.Decision.OPEN_SETTINGS, request.decisionFor("Open settings"))
    }

    @Test
    fun `unknown or missing choice is not a grant`() {
        val request = PermissionRequest.forApp("com.example", "Example")
        assertEquals(null, request.decisionFor("maybe"))
        assertEquals(null, request.decisionFor(null))
        assertEquals(PermissionRequest.Decision.CANCEL, request.safeDefault())
    }

    @Test
    fun `dismiss and not-now are treated as cancel`() {
        val request = PermissionRequest.forCapability("OVERLAY", "Display over other apps")
        assertEquals(PermissionRequest.Decision.CANCEL, request.decisionFor("Not now"))
        assertEquals(PermissionRequest.Decision.CANCEL, request.decisionFor("deny"))
    }

    @Test
    fun `label falls back to the package name`() {
        val request = PermissionRequest.forApp("com.mystery.app", "")
        assertTrue(request.title().contains("com.mystery.app"))
        assertNotNull(request.id)
    }
}
