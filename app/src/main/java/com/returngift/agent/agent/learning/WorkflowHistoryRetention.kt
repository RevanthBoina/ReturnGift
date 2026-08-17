// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.learning

import android.content.Context
import com.returngift.agent.ui.chat.ChatDatabase
import com.returngift.agent.ui.chat.ChatHistoryManager
import com.returngift.agent.utils.XLog
import java.io.File

/**
 * Retention policy for on-device workflow history.
 *
 * In ReturnGift a "workflow" is one complete user task, which maps to a chat
 * conversation: a markdown file in `chats/` (source of truth) plus its indexed
 * rows in the `returngift.db` `conversations`/`messages` tables. The step-level
 * artifacts produced while a workflow runs (failure captures, screenshots, app
 * logs, http logs, debug reports, screen fixtures) are not tagged with a
 * workflow id, so they are pruned by a timestamp cutoff derived from the oldest
 * retained workflow — anything older than the retention window is by definition
 * orphaned (its workflow has already been removed) and is deleted to prevent
 * orphaned filesystem/DB entries and unbounded storage growth.
 *
 * Adapted from trajectory-storage patterns in Browser Use `AgentHistory`
 * (one record per agent run, with steps/screenshots nested under it) and
 * OpenHands execution history (retain the newest N events, drop the rest).
 * Because ReturnGift does not thread a workflow id through every artifact, the
 * cascade is conversation-rooted for the data that is keyed by conversation id
 * and timestamp-rooted for the per-step caches, which achieves the same
 * "no orphans, bounded history" guarantee within the existing architecture.
 *
 * Per-skill learned aggregates (`execution_traces/<skillId>_traces.json`,
 * `learned_playbooks/<skillId>_overlay.json`, `skill_telemetry.db`) are
 * cumulative across many workflows and are NOT deleted here — they are not
 * owned by a single workflow.
 */
object WorkflowHistoryRetention {

    private const val TAG = "WorkflowRetention"
    const val DEFAULT_KEEP_COUNT = 5

    /**
     * Pluggable view over the conversation store so the retention algorithm is
     * unit-testable without Android filesystem/SQLite.
     */
    interface ConversationStoreView {
        /** Conversations, newest-first by created timestamp. */
        fun listNewestFirst(): List<ConversationRef>

        /** Delete a conversation's markdown file. */
        fun deleteConversationFile(ref: ConversationRef)

        /** Delete a conversation's DB rows (conversations + messages). */
        fun deleteConversationIndex(id: String)
    }

    /**
     * A workflow reference: stable id, created timestamp, and the markdown file.
     */
    data class ConversationRef(val id: String, val created: Long, val file: File)

    /**
     * Pluggable view over the per-step artifact caches that are timestamp-keyed.
     */
    interface ArtifactPruner {
        /** Delete every artifact whose timestamp is older than [cutoffMillis]. */
        fun pruneOlderThan(cutoffMillis: Long)
    }

    data class RetentionResult(
        val totalWorkflows: Int,
        val kept: Int,
        val deletedConversations: Int,
        val prunedArtifacts: Int,
        val skippedRunningWorkflowId: String?
    )

    /**
     * Retain the newest [keepCount] workflows and permanently remove older
     * workflow data plus orphaned step-level artifacts.
     *
     * Safeguards:
     * - The currently running workflow ([runningWorkflowId]) is NEVER deleted,
     *   even if it falls outside the keep window.
     * - If fewer than [keepCount] workflows exist, all are retained.
     * - Conversations with id == [runningWorkflowId] are always in the keep set.
     * - DB rows and files are deleted together; partial failures are logged but
     *   do not abort the rest of the cleanup (best-effort, no orphans left).
     */
    @Synchronized
    fun retainNewest(
        store: ConversationStoreView,
        artifactPruner: ArtifactPruner,
        keepCount: Int = DEFAULT_KEEP_COUNT,
        runningWorkflowId: String? = null
    ): RetentionResult {
        val all = store.listNewestFirst()
        if (all.isEmpty()) {
            XLog.i(TAG, "No workflows to retain; nothing to do")
            return RetentionResult(0, 0, 0, 0, null)
        }

        val effectiveKeep = keepCount.coerceAtLeast(0)
        // Always preserve the running workflow regardless of the keep window.
        val keepIds = LinkedHashSet<String>()
        all.take(effectiveKeep).forEach { keepIds.add(it.id) }
        if (!runningWorkflowId.isNullOrEmpty()) keepIds.add(runningWorkflowId)

        val toDelete = all.filter { it.id !in keepIds }
        val keptCount = all.size - toDelete.size

        XLog.i(
            TAG,
            "retainNewest: total=${all.size}, keep=${effectiveKeep}, running=$runningWorkflowId, " +
                "kept=$keptCount, deleting=${toDelete.size}"
        )

        var deletedConversations = 0
        for (ref in toDelete) {
            try {
                store.deleteConversationFile(ref)
            } catch (e: Exception) {
                XLog.w(TAG, "Failed to delete workflow file for ${ref.id}", e)
            }
            try {
                store.deleteConversationIndex(ref.id)
                deletedConversations++
            } catch (e: Exception) {
                XLog.w(TAG, "Failed to delete workflow DB rows for ${ref.id}", e)
            }
        }

        // Cutoff = oldest retained workflow's created timestamp. Any step-level
        // artifact older than this is orphaned (its workflow is gone).
        val cutoff = if (keepIds.isEmpty() || keptCount == 0) {
            // Nothing retained (e.g. keepCount=0) — prune everything older than now.
            System.currentTimeMillis()
        } else {
            all.filter { it.id in keepIds }.minOf { it.created }
        }

        var prunedArtifacts = 0
        try {
            artifactPruner.pruneOlderThan(cutoff)
            prunedArtifacts = (artifactPruner as? CountingCapablePruner)?.prunedCount() ?: 0
        } catch (e: Exception) {
            XLog.w(TAG, "Artifact pruning failed (best-effort)", e)
        }

        XLog.i(
            TAG,
            "retainNewest done: deletedConversations=$deletedConversations, prunedArtifacts=$prunedArtifacts"
        )
        return RetentionResult(
            totalWorkflows = all.size,
            kept = keptCount,
            deletedConversations = deletedConversations,
            prunedArtifacts = prunedArtifacts,
            skippedRunningWorkflowId = runningWorkflowId?.takeIf { it.isNotEmpty() }
        )
    }

    /** Optional capability implemented by an [ArtifactPruner] to report counts. */
    interface CountingCapablePruner : ArtifactPruner {
        fun prunedCount(): Int
    }

    // ------------------------------------------------------------------
    // Android-backed real implementation
    // ------------------------------------------------------------------

    /**
     * Run retention against the real on-device stores. Safe to call from a
     * background thread (it does file + SQLite I/O).
     *
     * @param runningWorkflowId the conversation id of the currently running
     *   workflow, if any, so it is never deleted.
     */
    fun retainNewestOnDevice(
        context: Context,
        keepCount: Int = DEFAULT_KEEP_COUNT,
        runningWorkflowId: String? = null
    ): RetentionResult {
        val store = AndroidConversationStore(context)
        val pruner = AndroidArtifactPruner(context)
        return retainNewest(store, pruner, keepCount, runningWorkflowId)
    }

    /** Android-backed [ConversationStoreView] over ChatHistoryManager + ChatDatabase. */
    private class AndroidConversationStore(private val context: Context) : ConversationStoreView {
        override fun listNewestFirst(): List<ConversationRef> {
            return ChatHistoryManager.listConversations(context).map {
                ConversationRef(id = it.id, created = it.created, file = it.file)
            }
        }

        override fun deleteConversationFile(ref: ConversationRef) {
            ChatHistoryManager.delete(ref.file)
        }

        override fun deleteConversationIndex(id: String) {
            ChatDatabase(context).deleteConversation(id)
        }
    }

    /**
     * Android-backed [ArtifactPruner]. Prunes the per-step cache directories
     * (failure_captures, screenshots, app_logs, http_logs, debug_reports) and
     * the screen_fixtures cache (files + fixtures.db rows) older than the
     * cutoff. These are cacheDir-backed and timestamp-keyed.
     */
    private class AndroidArtifactPruner(private val context: Context) :
        CountingCapablePruner {

        private var pruned = 0

        override fun pruneOlderThan(cutoffMillis: Long) {
            pruned = 0
            val cacheDir = context.cacheDir
            val artifactDirs = listOf(
                "failure_captures",
                "screenshots",
                "app_logs",
                "http_logs",
                "debug_reports",
                "screen_fixtures"
            )
            for (name in artifactDirs) {
                pruned += pruneDir(File(cacheDir, name), cutoffMillis)
            }
            // Prune stale fixture DB rows older than the cutoff.
            pruned += pruneFixtureDb(cutoffMillis)
        }

        override fun prunedCount(): Int = pruned

        private fun pruneDir(dir: File, cutoffMillis: Long): Int {
            if (!dir.exists()) return 0
            var removed = 0
            val files = dir.listFiles() ?: return 0
            for (f in files) {
                try {
                    if (f.isFile && f.lastModified() < cutoffMillis) {
                        if (f.delete()) removed++
                    }
                } catch (e: Exception) {
                    XLog.w(TAG, "Failed to prune artifact ${f.name}", e)
                }
            }
            return removed
        }

        private fun pruneFixtureDb(cutoffMillis: Long): Int {
            return try {
                com.returngift.agent.service.ScreenCaptureManager
                    .pruneFixturesOlderThan(context, cutoffMillis)
            } catch (e: Exception) {
                XLog.w(TAG, "Failed to prune fixtures DB", e)
                0
            }
        }
    }
}
