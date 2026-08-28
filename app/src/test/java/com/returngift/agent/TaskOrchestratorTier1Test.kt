// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent

import android.content.Context
import android.content.Intent
import com.returngift.agent.agent.AgentConfig
import com.returngift.agent.agent.PipelineRouter
import com.returngift.agent.channel.Channel
import com.returngift.agent.tool.ToolResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FIX 7 + FIX 8: Tier-1 DirectIntent / DirectTool terminal paths must use the typed
 * [-TaskEvent]- protocol — a launch failure or a tool error is a `TaskEvent.Failed`,
 * never a `Completed` carrying failure text (which the UI pattern-matches as success).
 *
 * The orchestrator's heavy Android singletons (ChannelManager, FloatingCircleManager,
 * ClawApplication) are circumvented by injecting a fake [PipelineRouter] plus the
 * observable [TaskOrchestrator.channelMessageSink] / [floatingStateSink] seams.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TaskOrchestratorTier1Test {

    private class FakeRouter(
        private val launchResult: () -> Boolean = { true },
        private val toolResult: () -> ToolResult = { ToolResult.success("") },
        private val toolHook: ((String, Map<String, Any>) -> ToolResult)? = null,
    ) : PipelineRouter(org.mockito.Mockito.mock(Context::class.java)) {

        val launched = CopyOnWriteArrayList<Intent>()
        val executedTool = CopyOnWriteArrayList<String>()

        override fun route(task: String): Route {
            return when (task) {
                "stub" -> Route.DirectIntent(Intent(Intent.ACTION_VIEW), "stub intent")
                "skill" -> Route.Skill("cancellable_skill", mapOf(), "run a skill")
                "sendmsg" -> Route.DirectTool(
                    "send_message", mapOf("app" to "WhatsApp", "contact" to "Mom", "message" to "hi"), "send hi"
                )
                else -> Route.DirectTool("stub_tool", mapOf(), "stub tool")
            }
        }

        override fun executeIntent(intent: Intent): Boolean {
            launched.add(intent)
            return launchResult()
        }

        override fun executeTool(toolName: String, params: Map<String, Any>): ToolResult {
            executedTool.add(toolName)
            val hook = toolHook
            if (hook != null) return hook(toolName, params)
            return toolResult()
        }
    }

    private val terminalEvents = CopyOnWriteArrayList<TaskEvent>()
    private val channelMessages = CopyOnWriteArrayList<String>()
    private val floatingStates = CopyOnWriteArrayList<Boolean>()
    private var finishedCount = 0
    @Volatile private var finishedFlag = false

    private fun awaitFinished(timeoutSeconds: Long = 5): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (!finishedFlag && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        return finishedFlag
    }

    private lateinit var orchestrator: TaskOrchestrator
    private lateinit var router: FakeRouter

    @Before
    fun setup() {
        router = FakeRouter()
        orchestrator = TaskOrchestrator(
            agentConfigProvider = { AgentConfig(apiKey = "test", baseUrl = "https://example.com") },
            onTaskFinished = { finishedCount++; finishedFlag = true },
        )
        orchestrator.routerForTesting = router
        orchestrator.appContext = org.robolectric.RuntimeEnvironment.getApplication()
        orchestrator.channelMessageSink = { _, content, _ -> channelMessages.add(content) }
        orchestrator.floatingStateSink = { success -> floatingStates.add(success) }
    }

    @After
    fun teardown() {
        awaitFinished(2)
        orchestrator.routerForTesting = null
        orchestrator.channelMessageSink = null
        orchestrator.floatingStateSink = null
    }

    @Test
    fun `intent launch failure emits Failed not Completed and releases the lock`() {
        router = FakeRouter(launchResult = { false })
        orchestrator.routerForTesting = router
        val latch = CountDownLatch(1)
        orchestrator.taskEventCallback = { event -> terminalEvents.add(event); if (event is TaskEvent.Failed || event is TaskEvent.Completed) latch.countDown() }

        orchestrator.startNewTask(Channel.LOCAL, "stub", "m1")
        assertTrue("terminal event not delivered", latch.await(5, TimeUnit.SECONDS))
        assertTrue("task not finished", awaitFinished())

        val failed = terminalEvents.filterIsInstance<TaskEvent.Failed>()
        assertEquals("events=$terminalEvents", 1, failed.size)
        assertEquals("events=$terminalEvents", 0, terminalEvents.filterIsInstance<TaskEvent.Completed>().size)
        val message = channelMessages.firstOrNull { it.startsWith("✗") }
        assertTrue("expected ✗ channel message, got $channelMessages", message != null)
        assertFalse("expected error floating state", floatingStates.last())
        // lock released: a new task can start
        assertFalse(orchestrator.isTaskRunning())
        assertEquals(1, finishedCount)
    }

    @Test
    fun `intent launch success emits Completed`() {
        val latch = CountDownLatch(1)
        orchestrator.taskEventCallback = { event -> terminalEvents.add(event); if (event is TaskEvent.Completed) latch.countDown() }
        orchestrator.startNewTask(Channel.LOCAL, "stub", "m1")
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue("task not finished", awaitFinished())
        assertEquals(1, terminalEvents.filterIsInstance<TaskEvent.Completed>().size)
        assertTrue("expected ✓ message", channelMessages.any { it.startsWith("✓") })
        assertFalse(orchestrator.isTaskRunning())
    }

    @Test
    fun `failed tool returns a typed Failed event not Completed`() {
        router = FakeRouter(toolResult = { ToolResult.error("boom") })
        orchestrator.routerForTesting = router
        val latch = CountDownLatch(1)
        orchestrator.taskEventCallback = { event -> terminalEvents.add(event); if (event is TaskEvent.Failed || event is TaskEvent.Completed) latch.countDown() }

        orchestrator.startNewTask(Channel.LOCAL, "stub_tool", "m1")
        val delivered = latch.await(5, TimeUnit.SECONDS)
        assertTrue("no terminal event; events=$terminalEvents msgs=$channelMessages", delivered)
        assertTrue("task not finished; floating=$floatingStates", awaitFinished())

        assertEquals("events=$terminalEvents", 1, terminalEvents.filterIsInstance<TaskEvent.Failed>().size)
        assertEquals("events=$terminalEvents", 0, terminalEvents.filterIsInstance<TaskEvent.Completed>().size)
        assertTrue("expected ✗ message; msgs=$channelMessages", channelMessages.any { it.startsWith("✗") })
        assertFalse(orchestrator.isTaskRunning())
        assertEquals(1, finishedCount)
    }

    @Test
    fun `successful tool returns Completed`() {
        val latch = CountDownLatch(1)
        orchestrator.taskEventCallback = { event -> terminalEvents.add(event); if (event is TaskEvent.Completed) latch.countDown() }
        orchestrator.startNewTask(Channel.LOCAL, "stub_x", "m1") // DirectTool branch
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue("task not finished", awaitFinished())
        assertEquals(0, terminalEvents.filterIsInstance<TaskEvent.Failed>().size)
        assertTrue(channelMessages.any { it.startsWith("✓") })
        assertFalse(orchestrator.isTaskRunning())
    }

    // ── FIX 10: a hung Tier-1 tool cannot lock the app ───────────────────────────
    @Test
    fun `hung tier-1 tool times out, emits Failed, and releases the lock for the next task`() {
        orchestrator.directToolTimeoutMs = 300
        val hungLatch = CountDownLatch(1)
        router = FakeRouter(
            toolHook = { _, _ ->
                hungLatch.countDown() // signal the tool "started" (blocking)
                Thread.sleep(30_000)  // never returns
                ToolResult.success("never")
            }
        )
        orchestrator.routerForTesting = router
        val latch = CountDownLatch(1)
        orchestrator.taskEventCallback = { event ->
            terminalEvents.add(event)
            if (event is TaskEvent.Failed || event is TaskEvent.Completed) latch.countDown()
        }

        assertEquals(0, router.executedTool.size)
        orchestrator.startNewTask(Channel.LOCAL, "hang", "m1")
        assertTrue(hungLatch.await(3, TimeUnit.SECONDS))
        // the tool actually started executing (blocked)
        assertEquals(1, router.executedTool.size)
        val delivered = latch.await(5, TimeUnit.SECONDS)
        assertTrue("no terminal event; events=$terminalEvents msgs=$channelMessages", delivered)
        assertTrue("task not finished; floating=$floatingStates", awaitFinished())

        assertTrue("expected Failed(timeout), got $terminalEvents",
            terminalEvents.filterIsInstance<TaskEvent.Failed>().any { it.error.contains("timed out") })
        assertEquals(0, terminalEvents.filterIsInstance<TaskEvent.Completed>().size)
        assertTrue("expected ✗ message; msgs=$channelMessages",
            channelMessages.any { it.startsWith("✗") && it.contains("timed out") })
        // lock released even though the underlying tool thread is still stuck
        assertFalse(orchestrator.isTaskRunning())

        // a second task starts immediately afterwards (not rejected by the hang): its
        // tool call reaches a fresh router instead of a "Another task is still running" error.
        val fresh = FakeRouter()
        orchestrator.routerForTesting = fresh
        terminalEvents.clear()
        val secondLatch = CountDownLatch(1)
        orchestrator.taskEventCallback = { event ->
            terminalEvents.add(event)
            if (event is TaskEvent.Failed || event is TaskEvent.Completed) secondLatch.countDown()
        }
        orchestrator.startNewTask(Channel.LOCAL, "stub_x", "m2")
        assertTrue("second task not started; events=$terminalEvents", secondLatch.await(5, TimeUnit.SECONDS))
        assertTrue("second task not finished", awaitFinished())
        assertEquals("expected exactly one tool execution on the fresh router",
            1, fresh.executedTool.size)
        assertTrue("second task should not be a 'Another task is still running' rejection: $terminalEvents",
            terminalEvents.none { it is TaskEvent.Failed && it.error.contains("Another task") })
    }

    // ── FIX 9: a cancelled skill must not respawn the agent loop ────────────────
    @Test
    fun `cancelled skill emits Cancelled and does not fall back to the agent loop`() {
        // Inject a SkillExecutor whose execute() returns a cancellation-shaped result —
        // the orchestrator must emit TaskEvent.Cancelled, never fall back to the agent
        // loop (which would re-route through startNewTask on an already-released session).
        val fakeSkillExecutor = object : com.returngift.agent.agent.skill.SkillExecutor() {
            override fun execute(
                skill: com.returngift.agent.agent.skill.Skill,
                params: Map<String, String>,
                taskText: String,
                routeUsed: String,
                onProgress: ((Int, Int, String) -> Unit)?,
                stopRequested: (() -> Boolean)?,
                wallClockMs: Long
            ): com.returngift.agent.agent.skill.SkillResult {
                return com.returngift.agent.agent.skill.SkillResult(
                    success = false,
                    stepsUsed = 1,
                    message = "cancelled",
                    cancelled = true
                )
            }
        }
        orchestrator.skillExecutorForTesting = fakeSkillExecutor

        val latch = CountDownLatch(1)
        orchestrator.taskEventCallback = { event ->
            terminalEvents.add(event)
            if (event is TaskEvent.Cancelled || event is TaskEvent.Completed || event is TaskEvent.Failed) latch.countDown()
        }

        // A real SkillRegistry lookup is needed for route "skill"; SkillRegistry may not
        // contain "cancellable_skill", so the branch would log "not found, falling through".
        // To reach the executor branch the skill must exist: register it directly.
        val registrySkill = com.returngift.agent.agent.skill.Skill(
            id = "cancellable_skill",
            name = "cancellable skill",
            description = "test",
            category = com.returngift.agent.agent.skill.SkillCategory.GENERAL,
            steps = emptyList(),
            fallbackGoal = "agent loop goal"
        )
        com.returngift.agent.agent.skill.SkillRegistry.register(registrySkill)

        orchestrator.startNewTask(Channel.LOCAL, "skill", "m1")
        assertTrue("no terminal event; events=$terminalEvents", latch.await(5, TimeUnit.SECONDS))
        assertTrue("task not finished", awaitFinished())

        assertTrue(
            "expected a Cancelled event, got $terminalEvents",
            terminalEvents.any { it is TaskEvent.Cancelled }
        )
        assertEquals(0, terminalEvents.filterIsInstance<TaskEvent.Failed>().size)
        // no agent-loop fallback started: the lock was released exactly once and no
        // channel message announces an AI retry.
        assertFalse(orchestrator.isTaskRunning())
        assertFalse(channelMessages.any { it.contains("Retrying with AI agent") })
        assertEquals(1, finishedCount)
    }

    // ── C3 invariant: tryAcquire is exclusive while a session is running ─────────
    @Test
    fun `tryAcquire returns false while a session is running`() {
        assertTrue(orchestrator.taskSessionStore.tryAcquire("m1", Channel.LOCAL))
        assertTrue(orchestrator.taskSessionStore.isTaskRunning())
        assertFalse(orchestrator.taskSessionStore.tryAcquire("m2", Channel.LOCAL))
        orchestrator.taskSessionStore.release()
        assertTrue(orchestrator.taskSessionStore.tryAcquire("m3", Channel.LOCAL))
    }

    // ── FIX 12: terminal cleanup CAS is idempotent ───────────────────────────────
    @Test
    fun `releaseIfMatches releases only the current owner and no-ops afterwards`() {
        val store = orchestrator.taskSessionStore
        assertTrue(store.tryAcquire("msg-a", Channel.LOCAL))
        // wrong owner id → no release, session still running
        assertNull(store.releaseIfMatches("msg-other"))
        assertTrue(store.isTaskRunning())
        // correct owner id → released, session now free
        val released = store.releaseIfMatches("msg-a")
        assertNotNull(released)
        assertEquals("msg-a", released!!.messageId)
        assertFalse(store.isTaskRunning())
        // second cleanup for the same owner is a no-op (racing skill→fallback cancel)
        assertNull(store.releaseIfMatches("msg-a"))
        assertFalse(store.isTaskRunning())
    }

    @Test
    fun `racing cancel that already released the session suppresses the tool terminal double-report`() {
        // Simulate a racing cancel that released the session BEFORE this tool's terminal
        // path runs: the DirectTool thread must then report nothing (no Completed/Failed,
        // no channel message, no onTaskFinished, no floating state) — the cancel handler
        // owns those. Conversely the pre-canceled path's normal tool might still run and
        // mutate; but the terminal accounting must stay exactly silent here.
        val toolRan = CountDownLatch(1)
        router = FakeRouter(
            toolHook = { _, _ ->
                // simulate cancelCurrentTask's terminal release racing this thread
                orchestrator.taskSessionStore.release()
                toolRan.countDown()
                ToolResult.success("done")
            }
        )
        orchestrator.routerForTesting = router

        orchestrator.taskEventCallback = { event -> terminalEvents.add(event) }
        // start the DirectTool task, then wait until the tool body has run and released
        // the session, then assert the thread's terminal block stayed silent.
        orchestrator.startNewTask(Channel.LOCAL, "stub_x", "m1")
        assertTrue(toolRan.await(5, TimeUnit.SECONDS))
        // give the DirectTool thread time to reach its (suppressed) terminal block
        Thread.sleep(300)
        // no terminal event, no message, no floating-state, no onTaskFinished from this path
        assertEquals(0, terminalEvents.size)
        assertEquals(0, channelMessages.size)
        assertEquals(0, floatingStates.size)
        assertEquals(0, finishedCount)
        assertFalse(orchestrator.isTaskRunning())
    }

    // ── D3: Tier-1 send_message pre-send confirmation ────────────────────────────
    @Test
    fun `send_message declined confirm does not execute and emits Failed`() {
        router = FakeRouter()
        orchestrator.routerForTesting = router
        orchestrator.sendMessageConfirm = { false } // user cancelled / 5s auto-cancel

        val latch = CountDownLatch(1)
        orchestrator.taskEventCallback = { event ->
            terminalEvents.add(event)
            if (event is TaskEvent.Failed || event is TaskEvent.Completed) latch.countDown()
        }
        orchestrator.startNewTask(Channel.LOCAL, "sendmsg", "m1")
        assertTrue("terminal event not delivered", latch.await(5, TimeUnit.SECONDS))
        assertTrue("task not finished", awaitFinished())

        assertEquals(0, router.executedTool.size)
        assertEquals(1, terminalEvents.filterIsInstance<TaskEvent.Failed>().size)
        assertEquals("events=$terminalEvents", 0, terminalEvents.filterIsInstance<TaskEvent.Completed>().size)
        assertTrue("expected ✗ message, got $channelMessages", channelMessages.any { it.startsWith("✗") })
        assertFalse(orchestrator.isTaskRunning())
    }

    @Test
    fun `send_message confirmed executes and emits Completed`() {
        router = FakeRouter(toolResult = { ToolResult.success("Written: ok") })
        orchestrator.routerForTesting = router
        orchestrator.sendMessageConfirm = { true } // user tapped Send

        val latch = CountDownLatch(1)
        orchestrator.taskEventCallback = { event ->
            terminalEvents.add(event)
            if (event is TaskEvent.Failed || event is TaskEvent.Completed) latch.countDown()
        }
        orchestrator.startNewTask(Channel.LOCAL, "sendmsg", "m1")
        assertTrue("terminal event not delivered", latch.await(5, TimeUnit.SECONDS))
        assertTrue("task not finished", awaitFinished())

        assertEquals(listOf("send_message"), router.executedTool)
        assertEquals(1, terminalEvents.filterIsInstance<TaskEvent.Completed>().size)
        assertTrue("expected ✓ message, got $channelMessages", channelMessages.any { it.startsWith("✓") })
        assertFalse(orchestrator.isTaskRunning())
    }
}