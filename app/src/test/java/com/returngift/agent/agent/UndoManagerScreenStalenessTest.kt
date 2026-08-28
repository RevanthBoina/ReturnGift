// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

import com.returngift.agent.tool.ToolResult
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C4: an Undo registration captures the active screen's hierarchy hash; if the screen has
 * VISIBLY changed by the time the user taps Undo, the inverse action must NOT execute —
 * a wrong Back/input-clear on a mutated screen is worse than no undo at all. Null hashes
 * on either side skip the check (service unavailable).
 *
 * All seams are literal lambdas (same pattern as TaskOrchestrator.sendMessageConfirm) —
 * no mocks.
 */
class UndoManagerScreenStalenessTest {

    private val executedInverse = CopyOnWriteArrayList<Pair<String, Map<String, Any>>>()
    private val failures = CopyOnWriteArrayList<String>()

    private fun installSeams() {
        UndoManager.toolExecutor = { name, params ->
            executedInverse.add(name to params)
            ToolResult.success("ok")
        }
        UndoManager.listener = object : UndoManager.UndoListener {
            override fun onUndoAvailable(undo: UndoManager.PendingUndo) {}
            override fun onUndoExpired() {}
            override fun onUndoFailed(reason: String) {
                failures.add(reason)
            }
        }
    }

    @After
    fun teardown() {
        UndoManager.clear()
        UndoManager.screenHashProvider = null
        UndoManager.toolExecutor = { name, params ->
            com.returngift.agent.tool.ToolRegistry.getInstance().executeTool(name, params)
        }
        UndoManager.listener = null
        executedInverse.clear()
        failures.clear()
    }

    @Test
    fun `stale screen blocks the inverse action and reports honest failure`() {
        installSeams()
        var hash = 111L
        UndoManager.screenHashProvider = { hash }

        UndoManager.record("tap", mapOf("x" to 10, "y" to 20), "Tap")
        assertTrue("undo should be pending", UndoManager.hasPending())

        // Simulate a visible screen mutation between register and execute.
        hash = 222L

        val executed = UndoManager.executeUndo()
        assertFalse("stale undo must not execute", executed)
        assertTrue("inverse action executed anyway", executedInverse.isEmpty())
        assertEquals(
            listOf("Can't undo — screen has changed"),
            failures.toList(),
        )
    }

    @Test
    fun `unchanged screen executes the inverse action`() {
        installSeams()
        UndoManager.screenHashProvider = { 111L }

        UndoManager.record("tap", mapOf("x" to 10, "y" to 20), "Tap")
        assertTrue("unchanged undo must execute", UndoManager.executeUndo())
        assertEquals("system_key" to mapOf("key" to "back"), executedInverse.firstOrNull())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `null hash on either side skips the check and executes`() {
        installSeams()
        var hash: Long? = null
        UndoManager.screenHashProvider = { hash }

        UndoManager.record("input_text", mapOf("text" to "hello", "node_id" to "n4"), "Type")
        hash = 999L // recompute now succeeds — but registration was null, so no comparison
        assertTrue(UndoManager.executeUndo())
        assertEquals(1, executedInverse.size)
        assertTrue(failures.isEmpty())
    }
}
