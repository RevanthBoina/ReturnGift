// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.clarify

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
import java.util.concurrent.atomic.AtomicReference

/**
 * JVM unit tests for [ClarificationManager]. Exercises the real latch/timeout
 * machinery on background threads — no mocks. android.os.Looper/Handler are
 * stubbed by unitTests.isReturnDefaultValues; [ClarificationManager.isMainThread]
 * is overridden to bypass the (stubbed) Looper comparison.
 */
class ClarificationManagerTest {

    @Before
    fun setUp() {
        ClarificationManager.isMainThread = { false }
        ClarificationManager.cancelPending()
    }

    @After
    fun tearDown() {
        ClarificationManager.cancelPending()
    }

    private fun requestAsync(
        question: String = "Which app?",
        choices: List<String> = listOf("ChatGPT", "Claude"),
        allowFreeText: Boolean = true,
        timeoutMs: Long = 5000,
    ): Pair<AtomicReference<String?>, Thread> {
        val result = AtomicReference<String?>("<<unset>>")
        val t = Thread {
            result.set(ClarificationManager.request(question, choices, allowFreeText, timeoutMs))
        }
        t.start()
        // Give the request thread time to park before the test acts.
        Thread.sleep(100)
        return result to t
    }

    @Test
    fun `request parks until answered and returns the answer`() {
        val (result, thread) = requestAsync()
        assertNotNull(ClarificationManager.snapshot())
        assertTrue(ClarificationManager.answer("Claude"))
        thread.join(3000)
        assertEquals("Claude", result.get())
        assertNull(ClarificationManager.snapshot())
    }

    @Test
    fun `typed free text answer is accepted`() {
        val (result, thread) = requestAsync(choices = emptyList())
        assertTrue(ClarificationManager.answer("use my work account please"))
        thread.join(3000)
        assertEquals("use my work account please", result.get())
    }

    @Test
    fun `free text rejected for choice-only questions`() {
        val (result, thread) = requestAsync(allowFreeText = false)
        assertTrue(ClarificationManager.answer("something not in choices")) // consumed but ignored
        assertNotNull(ClarificationManager.snapshot()) // still parked
        assertTrue(ClarificationManager.answer("Claude"))
        thread.join(3000)
        assertEquals("Claude", result.get())
    }

    @Test
    fun `timeout returns null and clears pending state`() {
        val (result, thread) = requestAsync(timeoutMs = 600)
        thread.join(3000)
        assertNull(result.get())
        assertNull(ClarificationManager.snapshot())
    }

    @Test
    fun `cancelPending unblocks a parked request with null`() {
        val (result, thread) = requestAsync()
        ClarificationManager.cancelPending()
        thread.join(3000)
        assertNull(result.get())
        assertNull(ClarificationManager.snapshot())
    }

    @Test
    fun `cancelPending is a no-op when nothing is pending`() {
        ClarificationManager.cancelPending()
        assertNull(ClarificationManager.snapshot())
    }

    @Test
    fun `answer returns false when nothing is pending`() {
        assertFalse(ClarificationManager.answer("hello"))
    }

    @Test
    fun `second request while one is pending returns null immediately`() {
        val (firstResult, firstThread) = requestAsync(timeoutMs = 5000)
        val second = ClarificationManager.request("Second?", emptyList(), true, 5000)
        assertNull(second)
        ClarificationManager.answer("ChatGPT")
        firstThread.join(3000)
        assertEquals("ChatGPT", firstResult.get())
    }

    @Test
    fun `request refused on the main thread`() {
        ClarificationManager.isMainThread = { true }
        assertNull(ClarificationManager.request("Q?", emptyList(), true, 1000))
        assertNull(ClarificationManager.snapshot())
    }

    @Test
    fun `listeners are notified on pending and on resolve`() {
        val seen = mutableListOf<String?>()
        val latch = CountDownLatch(2)
        val listener: (ClarificationManager.PendingQuestion?) -> Unit = { q ->
            seen.add(q?.question)
            latch.countDown()
        }
        ClarificationManager.addListener(listener)
        try {
            val (_, thread) = requestAsync()
            ClarificationManager.answer("Claude")
            thread.join(3000)
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("Which app?", null), seen)
        } finally {
            ClarificationManager.removeListener(listener)
        }
    }

    @Test
    fun `pending question carries question and choices`() {
        val (_, thread) = requestAsync(question = "Pick one", choices = listOf("A", "B"), allowFreeText = false)
        val q = ClarificationManager.snapshot()
        assertNotNull(q)
        assertEquals("Pick one", q!!.question)
        assertEquals(listOf("A", "B"), q.choices)
        assertFalse(q.allowFreeText)
        assertTrue(q.id.isNotBlank())
        ClarificationManager.answer("A")
        thread.join(3000)
    }
}
