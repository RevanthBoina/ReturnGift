// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.learning

import com.returngift.agent.agent.learning.WorkflowHistoryRetention.ConversationRef
import com.returngift.agent.agent.learning.WorkflowHistoryRetention.ConversationStoreView
import com.returngift.agent.agent.learning.WorkflowHistoryRetention.CountingCapablePruner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure JVM unit tests for [WorkflowHistoryRetention] using in-memory fakes — no
 * Android filesystem or SQLite required. Covers the 0, 1–4, 5, and 6+ workflow
 * cases, the running-workflow safety guard, chronological ordering, and the
 * orphaned-artifact cutoff.
 */
class WorkflowHistoryRetentionTest {

    private fun ref(id: String, created: Long) = ConversationRef(id, created, File("/tmp/$id.md"))

    /** In-memory store that records all delete operations. */
    private class FakeStore(workflows: List<ConversationRef>) : ConversationStoreView {
        private val live = workflows.toMutableList()
        val deletedFiles = mutableListOf<String>()
        val deletedIndex = mutableListOf<String>()

        override fun listNewestFirst(): List<ConversationRef> =
            live.sortedByDescending { it.created }

        override fun deleteConversationFile(ref: ConversationRef) {
            live.removeAll { it.id == ref.id }
            deletedFiles.add(ref.id)
        }

        override fun deleteConversationIndex(id: String) {
            deletedIndex.add(id)
        }

        fun remaining(): List<String> =
            live.sortedByDescending { it.created }.map { it.id }
    }

    /** In-memory pruner that records the cutoffs it was asked to prune with. */
    private class FakePruner : CountingCapablePruner {
        val cutoffs = mutableListOf<Long>()
        private var count = 0
        fun feed(count: Int) { this.count = count }
        override fun pruneOlderThan(cutoffMillis: Long) { cutoffs.add(cutoffMillis) }
        override fun prunedCount(): Int = count
    }

    @Test
    fun `0 workflows - nothing to do`() {
        val store = FakeStore(emptyList())
        val pruner = FakePruner()
        val result = WorkflowHistoryRetention.retainNewest(store, pruner, keepCount = 5)
        assertEquals(0, result.totalWorkflows)
        assertEquals(0, result.kept)
        assertEquals(0, result.deletedConversations)
        assertTrue(store.deletedFiles.isEmpty())
        assertTrue(pruner.cutoffs.isEmpty())
    }

    @Test
    fun `1-4 workflows - retain all`() {
        val workflows = listOf(
            ref("w1", 1000L), ref("w2", 2000L), ref("w3", 3000L)
        )
        val store = FakeStore(workflows)
        val pruner = FakePruner()
        val result = WorkflowHistoryRetention.retainNewest(store, pruner, keepCount = 5)
        assertEquals(3, result.totalWorkflows)
        assertEquals(3, result.kept)
        assertEquals(0, result.deletedConversations)
        assertTrue(store.deletedFiles.isEmpty())
        // Artifacts older than the oldest retained (1000) are pruned.
        assertEquals(1, pruner.cutoffs.size)
        assertEquals(1000L, pruner.cutoffs.first())
    }

    @Test
    fun `exactly 5 workflows - retain all, none deleted`() {
        val workflows = (1..5).map { ref("w$it", it.toLong() * 1000) }
        val store = FakeStore(workflows)
        val pruner = FakePruner()
        val result = WorkflowHistoryRetention.retainNewest(store, pruner, keepCount = 5)
        assertEquals(5, result.totalWorkflows)
        assertEquals(5, result.kept)
        assertEquals(0, result.deletedConversations)
        assertTrue(store.deletedFiles.isEmpty())
        assertEquals(1000L, pruner.cutoffs.first())
    }

    @Test
    fun `6+ workflows - delete oldest, keep newest 5 in chronological order`() {
        val workflows = (1..7).map { ref("w$it", it.toLong() * 1000) }
        val store = FakeStore(workflows)
        val pruner = FakePruner()
        val result = WorkflowHistoryRetention.retainNewest(store, pruner, keepCount = 5)
        assertEquals(7, result.totalWorkflows)
        assertEquals(5, result.kept)
        assertEquals(2, result.deletedConversations)
        // Newest 5 = w7..w3 (created 7000..3000); oldest 2 = w1,w2 deleted.
        assertEquals(listOf("w1", "w2"), store.deletedFiles)
        assertEquals(listOf("w1", "w2"), store.deletedIndex)
        // Cutoff = oldest retained (w3, created 3000).
        assertEquals(3000L, pruner.cutoffs.first())
        // Remaining must be the newest 5, in descending created order.
        assertEquals(listOf("w7", "w6", "w5", "w4", "w3"), store.remaining())
    }

    @Test
    fun `running workflow is never deleted even if outside keep window`() {
        // 6 workflows; running one is the OLDEST (would normally be deleted).
        val workflows = (1..6).map { ref("w$it", it.toLong() * 1000) }
        val store = FakeStore(workflows)
        val pruner = FakePruner()
        val result = WorkflowHistoryRetention.retainNewest(
            store, pruner, keepCount = 5, runningWorkflowId = "w1"
        )
        // Kept = newest 5 (w6..w2) PLUS the running w1 = 6 kept, only w0... none outside.
        // Outside keep window would be w1, but it's running -> protected. So 0 deleted.
        assertEquals(6, result.kept)
        assertEquals(0, result.deletedConversations)
        assertFalse(store.deletedFiles.contains("w1"))
        assertEquals("w1", result.skippedRunningWorkflowId)
    }

    @Test
    fun `running workflow inside keep window is also preserved`() {
        val workflows = (1..7).map { ref("w$it", it.toLong() * 1000) }
        val store = FakeStore(workflows)
        val pruner = FakePruner()
        val result = WorkflowHistoryRetention.retainNewest(
            store, pruner, keepCount = 5, runningWorkflowId = "w5"
        )
        // Newest 5 = w7..w3 (includes w5). Deleted = w1, w2. w5 protected either way.
        assertEquals(listOf("w1", "w2"), store.deletedFiles)
        assertEquals(5, result.kept)
    }

    @Test
    fun `keepCount 0 with no running workflow deletes all conversations`() {
        val workflows = listOf(ref("w1", 1000L), ref("w2", 2000L))
        val store = FakeStore(workflows)
        val pruner = FakePruner()
        val result = WorkflowHistoryRetention.retainNewest(store, pruner, keepCount = 0)
        assertEquals(2, result.deletedConversations)
        assertTrue(store.remaining().isEmpty())
    }

    @Test
    fun `newest 5 always preserved in correct chronological order after many workflows`() {
        // 12 workflows, keep 5 — newest 5 must survive, oldest 7 deleted.
        val bigStore = FakeStore((1..12).map { ref("w$it", it.toLong() * 1000) })
        val pruner = FakePruner()
        val result = WorkflowHistoryRetention.retainNewest(bigStore, pruner, keepCount = 5)
        assertEquals(5, result.kept)
        assertEquals(7, result.deletedConversations)
        assertEquals(listOf("w12", "w11", "w10", "w9", "w8"), bigStore.remaining())
    }

    @Test
    fun `partial failure does not abort cleanup`() {
        val workflows = (1..7).map { ref("w$it", it.toLong() * 1000) }
        val failingStore = object : ConversationStoreView {
            val live = workflows.toMutableList()
            val deletedIndex = mutableListOf<String>()
            override fun listNewestFirst() = live.sortedByDescending { it.created }
            override fun deleteConversationFile(ref: ConversationRef) {
                throw RuntimeException("disk error")
            }
            override fun deleteConversationIndex(id: String) { deletedIndex.add(id) }
        }
        val pruner = FakePruner()
        val result = WorkflowHistoryRetention.retainNewest(failingStore, pruner, keepCount = 5)
        // File deletes failed, but DB deletes proceeded.
        assertEquals(2, result.deletedConversations)
        assertEquals(2, failingStore.deletedIndex.size)
    }
}
