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
    ) : PipelineRouter(org.mockito.Mockito.mock(Context::class.java)) {

        val launched = CopyOnWriteArrayList<Intent>()
        val executedTool = CopyOnWriteArrayList<String>()

        override fun route(task: String): Route {
            return when (task) {
                "stub" -> Route.DirectIntent(Intent(Intent.ACTION_VIEW), "stub intent")
                else -> Route.DirectTool("stub_tool", mapOf(), "stub tool")
            }
        }

        override fun executeIntent(intent: Intent): Boolean {
            launched.add(intent)
            return launchResult()
        }

        override fun executeTool(toolName: String, params: Map<String, Any>): ToolResult {
            executedTool.add(toolName)
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
}