// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.core.input

import android.content.Context
import android.content.ClipboardManager
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.returngift.agent.service.ClawAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.any
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Privacy tests for the clipboard-paste fallback: sensitive fields (password /
 * credential / OTP) must NEVER receive text via the shared clipboard, which is
 * readable by other apps and clipboard managers.
 *
 * The target node is a Mockito mock (per task spec); Robolectric supplies the
 * main Looper + a real shadow ClipboardManager so "clipboard never written" is
 * asserted against actual clipboard state, not just mock interactions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DynamicIMEInjectorTest {

    private val defaultPoster = DynamicIMEInjector.mainThreadPoster

    @org.junit.Before
    fun setUp() {
        // Run the clipboard write synchronously (production posts to the main looper,
        // which is paused under Robolectric and would starve the latch).
        DynamicIMEInjector.mainThreadPoster = { it.run() }
    }

    @org.junit.After
    fun tearDown() {
        DynamicIMEInjector.mainThreadPoster = defaultPoster
    }

    private fun clipboardManager(): ClipboardManager =
        org.robolectric.RuntimeEnvironment.getApplication()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** Mock service wired to the REAL shadow ClipboardManager. */
    private fun serviceWithClipboard(): ClawAccessibilityService {
        val service = mock(ClawAccessibilityService::class.java)
        `when`(service.getSystemService(Context.CLIPBOARD_SERVICE)).thenReturn(
            org.robolectric.RuntimeEnvironment.getApplication()
                .getSystemService(Context.CLIPBOARD_SERVICE)
        )
        return service
    }

    private fun failingNode(password: Boolean, hintText: String? = null): AccessibilityNodeInfo {
        val node = mock(AccessibilityNodeInfo::class.java)
        `when`(node.isPassword).thenReturn(password)
        `when`(node.hintText).thenReturn(hintText)
        `when`(node.text).thenReturn(null)
        // Every action fails → ACTION_SET_TEXT retries exhaust, forcing the fallback decision.
        `when`(node.performAction(eq(AccessibilityNodeInfo.ACTION_SET_TEXT), any(Bundle::class.java)))
            .thenReturn(false)
        return node
    }

    @Test
    fun `clipboard fallback is never invoked when isPassword is true`() {
        val service = serviceWithClipboard()
        val node = failingNode(password = true)

        val result = DynamicIMEInjector.injectText(service, "s3cret", node, clearFirst = true)

        assertFalse(result.success)
        assertEquals(DynamicIMEInjector.METHOD_SENSITIVE_FIELD_INPUT_FAILED, result.method)
        // The strong assertion: the system clipboard was never written.
        assertNull(clipboardManager().primaryClip)
        // And no paste action was ever dispatched to the node.
        verify(node, never()).performAction(eq(AccessibilityNodeInfo.ACTION_PASTE), any(Bundle::class.java))
    }

    @Test
    fun `credential hint text marks the field sensitive`() {
        assertTrue(DynamicIMEInjector.isSensitiveField(failingNode(false, "Enter your password")))
        assertTrue(DynamicIMEInjector.isSensitiveField(failingNode(false, "One-time code")))
        assertTrue(DynamicIMEInjector.isSensitiveField(failingNode(false, "Enter OTP")))
        assertTrue(DynamicIMEInjector.isSensitiveField(failingNode(false, "CVV")))
        // Mixed-case still matches.
        assertTrue(DynamicIMEInjector.isSensitiveField(failingNode(false, "Type your PaSsWoRd")))
    }

    @Test
    fun `non-sensitive field still uses the clipboard fallback path`() {
        val service = serviceWithClipboard()
        val node = failingNode(password = false, hintText = null)

        val result = DynamicIMEInjector.injectText(service, "hello", node, clearFirst = true)

        assertFalse(result.success)
        assertEquals("all_failed", result.method)
        // Behavior unchanged for normal fields: the clipboard WAS populated as before.
        assertEquals("hello", clipboardManager().primaryClip?.getItemAt(0)?.text?.toString())
        verify(node).performAction(eq(AccessibilityNodeInfo.ACTION_PASTE), any(Bundle::class.java))
    }

    @Test
    fun `isSensitiveField handles nulls and ordinary fields`() {
        assertFalse(DynamicIMEInjector.isSensitiveField(null))
        assertFalse(DynamicIMEInjector.isSensitiveField(failingNode(false, null)))
        assertFalse(DynamicIMEInjector.isSensitiveField(failingNode(false, "Search")))
        assertFalse(DynamicIMEInjector.isSensitiveField(failingNode(false, "Email address")))
    }
}
