// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.permission

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * JVM tests for [PermissionPromptBroker]: the real latch/timeout machinery runs
 * on background threads; the UI/notification hooks are replaced so no Android
 * runtime is needed (android.os.Handler/Looper are stubbed by
 * unitTests.isReturnDefaultValues, and [PermissionPromptBroker.isMainThread] is
 * overridden like ClarificationManagerTest does).
 */
class PermissionPromptBrokerTest {

    private lateinit var openedUi: AtomicInteger
    private lateinit var postedNotification: AtomicBoolean

    @Before
    fun setUp() {
        PermissionPromptBroker.isMainThread = { false }
        PermissionPromptBroker.openUiHook = { openedUi.incrementAndGet() }
        PermissionPromptBroker.notifyHook = { postedNotification.set(true) }
        // Notifications are the prompt surface; the card is only the blocked-notification fallback.
        PermissionPromptBroker.notificationsAvailableHook = { true }
        PermissionPromptBroker.dismissHook = { }
        PermissionPromptBroker.cancelPending()
        openedUi = AtomicInteger(0)
        postedNotification = AtomicBoolean(false)
    }

    @After
    fun tearDown() {
        PermissionPromptBroker.cancelPending()
        PermissionPromptBroker.isMainThread = { false }
    }

    private fun requestAsync(
        request: PermissionRequest = PermissionRequest.forApp("com.whatsapp", "WhatsApp"),
        timeoutMs: Long = 5_000,
    ): Pair<AtomicReference<PermissionRequest.Decision?>, Thread> {
        val result = AtomicReference<PermissionRequest.Decision?>(null)
        val thread = Thread {
            result.set(PermissionPromptBroker.request(request, timeoutMs = timeoutMs))
        }
        thread.start()
        waitUntilPending()
        return result to thread
    }

    private fun waitUntilPending() {
        repeat(50) {
            if (PermissionPromptBroker.isPending()) return
            Thread.sleep(20)
        }
    }

    @Test
    fun `request asks in a notification instead of opening the app`() {
        val (_, thread) = requestAsync()
        assertTrue("the request must be answered in the shade", postedNotification.get())
        assertEquals("the app must not be pulled to the front for a question", 0, openedUi.get())
        assertNotNull(PermissionPromptBroker.snapshot())
        PermissionPromptBroker.answer("Allow once")
        thread.join(2_000)
    }

    @Test
    fun `allow once resolves to ALLOW_ONCE`() {
        val (result, thread) = requestAsync()
        PermissionPromptBroker.answer("Allow once")
        thread.join(2_000)
        assertEquals(PermissionRequest.Decision.ALLOW_ONCE, result.get())
        assertNull(PermissionPromptBroker.snapshot())
    }

    @Test
    fun `always allow resolves to ALWAYS_ALLOW`() {
        val (result, thread) = requestAsync()
        PermissionPromptBroker.answer("Always allow")
        thread.join(2_000)
        assertEquals(PermissionRequest.Decision.ALWAYS_ALLOW, result.get())
    }

    @Test
    fun `cancel choice resolves to CANCEL`() {
        val (result, thread) = requestAsync()
        PermissionPromptBroker.answer("Cancel")
        thread.join(2_000)
        assertEquals(PermissionRequest.Decision.CANCEL, result.get())
    }

    @Test
    fun `timeout resolves to the safe default`() {
        val (result, thread) = requestAsync(timeoutMs = 150)
        thread.join(3_000)
        assertEquals(PermissionRequest.Decision.CANCEL, result.get())
        assertNull(PermissionPromptBroker.snapshot())
    }

    @Test
    fun `task cancel unblocks the parked agent thread`() {
        val (result, thread) = requestAsync()
        PermissionPromptBroker.cancelPending()
        thread.join(2_000)
        assertEquals(PermissionRequest.Decision.CANCEL, result.get())
        assertFalse(thread.isAlive)
    }

    @Test
    fun `a second concurrent request is denied instead of queued`() {
        val (_, first) = requestAsync()
        val second = PermissionPromptBroker.request(
            PermissionRequest.forApp("com.telegram", "Telegram"),
            timeoutMs = 100,
        )
        assertEquals(PermissionRequest.Decision.CANCEL, second)
        PermissionPromptBroker.answer("Allow once")
        first.join(2_000)
    }

    @Test
    fun `answer for nothing pending is ignored`() {
        assertFalse(PermissionPromptBroker.answer("Allow once"))
    }

    @Test
    fun `blocking on the main thread is refused`() {
        PermissionPromptBroker.isMainThread = { true }
        val decision = PermissionPromptBroker.request(
            PermissionRequest.forApp("com.whatsapp", "WhatsApp"),
            timeoutMs = 100,
        )
        assertEquals(PermissionRequest.Decision.CANCEL, decision)
        assertEquals("the UI must not be opened from the main-thread guard", 0, openedUi.get())
    }

    @Test
    fun `listeners see the request appear and disappear`() {
        val seen = mutableListOf<PermissionRequest?>()
        val latch = CountDownLatch(2)
        val listener: (PermissionRequest?) -> Unit = {
            seen.add(it)
            latch.countDown()
        }
        PermissionPromptBroker.addListener(listener)
        try {
            val (_, thread) = requestAsync()
            PermissionPromptBroker.answer("Allow once") // no-op while request is still being parked
            thread.join(2_000)
            latch.await(2, TimeUnit.SECONDS)
            assertTrue("listener should see the pending request", seen.any { it != null })
            assertTrue("listener should see it resolve", seen.any { it == null })
        } finally {
            PermissionPromptBroker.removeListener(listener)
        }
    }
}
