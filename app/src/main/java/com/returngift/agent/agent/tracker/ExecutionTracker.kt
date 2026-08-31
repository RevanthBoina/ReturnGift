// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.tracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.returngift.agent.ClawApplication
import com.returngift.agent.agent.provenance.ProvenanceTag
import com.returngift.agent.utils.XLog
import org.json.JSONObject

/**
 * Agent Execution Tracker & Trajectory Store.
 *
 * Inspired by Browser Use's AgentHistory/AgentHistoryList and OpenHands' EventStream:
 * - Append-only structured execution trajectory logger.
 * - Records every step: screen state, action chosen, execution result, latency, and outcome.
 * - Stored trajectories enable learning reusable procedures and diagnosing failure loops.
 */
object ExecutionTracker {

    private const val TAG = "ExecutionTracker"
    private const val DB_NAME = "execution_tracker.db"
    private const val DB_VERSION = 3
    private const val TABLE_EVENTS = "execution_events"
    private const val TABLE_TASKS = "task_trajectories"

    enum class EventType {
        TASK_START,
        TIER,
        OBSERVE,
        THINK,
        ACT,
        TOOL_RESULT,
        ERROR,
        RECOVER,
        TASK_COMPLETE,
        LEAVE  // P3.4: data egress event (cloud LLM response)
    }

    data class ExecutionEvent(
        val taskId: String,
        val stepIndex: Int,
        val eventType: EventType,
        val timestamp: Long = System.currentTimeMillis(),
        val screenHash: String? = null,
        val screenSummary: String? = null,
        val actionTool: String? = null,
        val actionParams: String? = null,
        val resultSuccess: Boolean = true,
        val resultSummary: String? = null,
        val latencyMs: Long = 0L,
        val appPackage: String? = null,
        val metadataJson: String? = null,
        val targetResolution: String? = null,
        val verificationResult: String? = null,
        val recoveryAction: String? = null,
        /** P3.3: provenance source tag — e.g. "screen:com.whatsapp" for screen observations. */
        val source: String? = null
    )

    data class Trajectory(
        val taskId: String,
        val taskText: String,
        val channel: String,
        val startedAt: Long,
        val completedAt: Long = 0L,
        val outcome: String = "RUNNING",
        val totalSteps: Int = 0,
        val totalTokens: Int = 0,
        val events: List<ExecutionEvent> = emptyList()
    )

    private class DbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_TASKS (
                    task_id TEXT PRIMARY KEY,
                    task_text TEXT NOT NULL,
                    channel TEXT NOT NULL,
                    started_at INTEGER NOT NULL,
                    completed_at INTEGER,
                    outcome TEXT NOT NULL,
                    total_steps INTEGER NOT NULL,
                    total_tokens INTEGER NOT NULL
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_EVENTS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_id TEXT NOT NULL,
                    step_index INTEGER NOT NULL,
                    event_type TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    screen_hash TEXT,
                    screen_summary TEXT,
                    action_tool TEXT,
                    action_params TEXT,
                    result_success INTEGER NOT NULL,
                    result_summary TEXT,
                    latency_ms INTEGER NOT NULL,
                    app_package TEXT,
                    metadata_json TEXT,
                    target_resolution TEXT,
                    verification_result TEXT,
                    recovery_action TEXT,
                    source TEXT
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_task ON $TABLE_EVENTS(task_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_source ON $TABLE_EVENTS(source)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v2: add target-resolution / verification / recovery columns to support the
            // unified observe→resolve→act→verify→recover control loop. Existing data is
            // preserved; new columns default to NULL.
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE $TABLE_EVENTS ADD COLUMN target_resolution TEXT")
                db.execSQL("ALTER TABLE $TABLE_EVENTS ADD COLUMN verification_result TEXT")
                db.execSQL("ALTER TABLE $TABLE_EVENTS ADD COLUMN recovery_action TEXT")
            }
            // v3: P3.3 provenance — add source column for observation origin tracking.
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE $TABLE_EVENTS ADD COLUMN source TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_source ON $TABLE_EVENTS(source)")
            }
        }
    }

    private var dbHelper: DbHelper? = null

    // P2.6: Round-level batching — accumulate events in a pending list within a
    // transaction and flush once per round, instead of one DB write per event.
    private var batchActive = false
    private val pendingEvents = mutableListOf<ExecutionEvent>()

    private fun getDb(): SQLiteDatabase {
        if (dbHelper == null) {
            synchronized(this) {
                if (dbHelper == null) {
                    dbHelper = DbHelper(ClawApplication.instance)
                }
            }
        }
        return dbHelper!!.writableDatabase
    }

    /**
     * P2.6: Begin a new round batch. Opens a transaction and clears any stale
     * pending events from a previous cancelled round.
     */
    fun beginRound() {
        pendingEvents.clear()
        batchActive = true
        try {
            getDb().beginTransactionNonExclusive()
        } catch (e: Exception) {
            batchActive = false
            XLog.w(TAG, "beginRound: failed to open transaction: ${e.message}")
        }
    }

    /**
     * P2.6: End the current round batch.
     * @param commit true = flush pending events to DB and commit the transaction.
     *              false = discard pending events and rollback the transaction.
     * Called at the natural end of each loop iteration (commit) and at every
     * early-exit `return` site (rollback).
     */
    fun endRound(commit: Boolean) {
        if (!batchActive) return
        batchActive = false
        try {
            val db = getDb()
            if (commit) {
                // Flush all pending events in one batch
                for (event in pendingEvents) {
                    flushEventToDb(db, event)
                }
                pendingEvents.clear()
                db.setTransactionSuccessful()
                XLog.d(TAG, "endRound: committed ${pendingEvents.size} events")
            } else {
                pendingEvents.clear()
                XLog.d(TAG, "endRound: rolled back")
            }
        } catch (e: Exception) {
            XLog.w(TAG, "endRound: failed: ${e.message}")
        } finally {
            try { getDb().endTransaction() } catch (_: Exception) {}
        }
    }

    fun beginTask(taskId: String, taskText: String, channel: String) {
        try {
            val db = getDb()
            val cv = ContentValues().apply {
                put("task_id", taskId)
                put("task_text", taskText)
                put("channel", channel)
                put("started_at", System.currentTimeMillis())
                put("outcome", "RUNNING")
                put("total_steps", 0)
                put("total_tokens", 0)
            }
            db.insertWithOnConflict(TABLE_TASKS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            
            recordEvent(
                ExecutionEvent(
                    taskId = taskId,
                    stepIndex = 0,
                    eventType = EventType.TASK_START,
                    resultSummary = taskText
                )
            )
            XLog.i(TAG, "Execution trajectory started: $taskId ($taskText)")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to begin task trajectory: ${e.message}", e)
        }
    }

    /**
     * P1.1c: Record which tier a task was routed to at its start.
     * tier is one of: "tier1", "tier2", "tier3"
     * route is the action/tool name (tier1), skill id (tier2), or "agent-loop" (tier3)
     */
    fun recordTier(taskId: String, tier: String, route: String) {
        // Closed-vocab guard: reject unexpected tier values
        if (tier !in listOf("tier1", "tier2", "tier3")) {
            XLog.w(TAG, "recordTier: rejected invalid tier value '$tier'")
            return
        }
        try {
            val metadata = JSONObject().apply {
                put("tier", tier)
                put("route", route)
            }
            recordEvent(
                ExecutionEvent(
                    taskId = taskId,
                    stepIndex = 0,
                    eventType = EventType.TIER,
                    resultSummary = "$tier:$route",
                    metadataJson = metadata.toString()
                )
            )
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to record tier event: ${e.message}", e)
        }
    }

    fun recordObservation(
        taskId: String,
        stepIndex: Int,
        screenHash: String,
        screenSummary: String,
        appPackage: String? = null,
        provenance: ProvenanceTag? = null
    ) {
        val source = provenance?.toStorageString() ?: appPackage?.let { "screen:$it" }
        recordEvent(
            ExecutionEvent(
                taskId = taskId,
                stepIndex = stepIndex,
                eventType = EventType.OBSERVE,
                screenHash = screenHash,
                screenSummary = screenSummary.take(300),
                appPackage = appPackage,
                source = source
            )
        )
    }

    fun recordThinking(taskId: String, stepIndex: Int, reasoning: String) {
        recordEvent(
            ExecutionEvent(
                taskId = taskId,
                stepIndex = stepIndex,
                eventType = EventType.THINK,
                resultSummary = reasoning.take(300)
            )
        )
    }

    fun recordAction(
        taskId: String,
        stepIndex: Int,
        toolName: String,
        params: String,
        resultSuccess: Boolean,
        resultSummary: String,
        latencyMs: Long,
        appPackage: String? = null
    ) {
        recordEvent(
            ExecutionEvent(
                taskId = taskId,
                stepIndex = stepIndex,
                eventType = EventType.ACT,
                actionTool = toolName,
                actionParams = params.take(500),
                resultSuccess = resultSuccess,
                resultSummary = resultSummary.take(300),
                latencyMs = latencyMs,
                appPackage = appPackage
            )
        )
    }

    fun recordError(taskId: String, stepIndex: Int, errorMessage: String, recoveryHint: String? = null) {
        recordEvent(
            ExecutionEvent(
                taskId = taskId,
                stepIndex = stepIndex,
                eventType = EventType.ERROR,
                resultSuccess = false,
                resultSummary = errorMessage.take(300),
                metadataJson = recoveryHint?.let { JSONObject(mapOf("recovery" to it)).toString() }
            )
        )
    }

    fun endTask(taskId: String, outcome: String, totalSteps: Int, totalTokens: Int) {
        try {
            val db = getDb()
            val cv = ContentValues().apply {
                put("completed_at", System.currentTimeMillis())
                put("outcome", outcome)
                put("total_steps", totalSteps)
                put("total_tokens", totalTokens)
            }
            db.update(TABLE_TASKS, cv, "task_id = ?", arrayOf(taskId))

            recordEvent(
                ExecutionEvent(
                    taskId = taskId,
                    stepIndex = totalSteps,
                    eventType = EventType.TASK_COMPLETE,
                    resultSuccess = outcome.equals("SUCCESS", ignoreCase = true),
                    resultSummary = outcome
                )
            )
            XLog.i(TAG, "Execution trajectory ended: $taskId -> $outcome (steps=$totalSteps, tokens=$totalTokens)")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to end task trajectory: ${e.message}", e)
        }
    }

    private fun recordEvent(event: ExecutionEvent) {
        if (!batchActive) {
            // No batch active — write immediately (fallback for beginTask/endTask
            // which are called outside the round loop)
            flushEventToDb(getDb(), event)
        } else {
            // Batch active — accumulate and flush at round end
            pendingEvents.add(event)
        }
    }

    /** Flush a single event to the database (used by recordEvent). */
    private fun flushEventToDb(db: SQLiteDatabase, event: ExecutionEvent) {
        try {
            val cv = ContentValues().apply {
                put("task_id", event.taskId)
                put("step_index", event.stepIndex)
                put("event_type", event.eventType.name)
                put("timestamp", event.timestamp)
                put("screen_hash", event.screenHash)
                put("screen_summary", event.screenSummary)
                put("action_tool", event.actionTool)
                put("action_params", event.actionParams)
                put("result_success", if (event.resultSuccess) 1 else 0)
                put("result_summary", event.resultSummary)
                put("latency_ms", event.latencyMs)
                put("app_package", event.appPackage)
                put("metadata_json", event.metadataJson)
                put("target_resolution", event.targetResolution)
                put("verification_result", event.verificationResult)
                put("recovery_action", event.recoveryAction)
                put("source", event.source)
            }
            db.insert(TABLE_EVENTS, null, cv)
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to record execution event: ${e.message}")
        }
    }

    fun getTrajectory(taskId: String): Trajectory? {
        try {
            val db = getDb()
            val taskCursor = db.query(
                TABLE_TASKS,
                null,
                "task_id = ?",
                arrayOf(taskId),
                null, null, null
            )
            if (!taskCursor.moveToFirst()) {
                taskCursor.close()
                return null
            }
            val taskText = taskCursor.getString(taskCursor.getColumnIndexOrThrow("task_text"))
            val channel = taskCursor.getString(taskCursor.getColumnIndexOrThrow("channel"))
            val startedAt = taskCursor.getLong(taskCursor.getColumnIndexOrThrow("started_at"))
            val completedAt = taskCursor.getLong(taskCursor.getColumnIndexOrThrow("completed_at"))
            val outcome = taskCursor.getString(taskCursor.getColumnIndexOrThrow("outcome"))
            val totalSteps = taskCursor.getInt(taskCursor.getColumnIndexOrThrow("total_steps"))
            val totalTokens = taskCursor.getInt(taskCursor.getColumnIndexOrThrow("total_tokens"))
            taskCursor.close()

            val events = mutableListOf<ExecutionEvent>()
            val eventsCursor = db.query(
                TABLE_EVENTS,
                null,
                "task_id = ?",
                arrayOf(taskId),
                null, null,
                "step_index ASC, id ASC"
            )
            while (eventsCursor.moveToNext()) {
                    events.add(
                        ExecutionEvent(
                            taskId = taskId,
                            stepIndex = eventsCursor.getInt(eventsCursor.getColumnIndexOrThrow("step_index")),
                            eventType = try { EventType.valueOf(eventsCursor.getString(eventsCursor.getColumnIndexOrThrow("event_type"))) } catch (_: Exception) { EventType.ACT },
                            timestamp = eventsCursor.getLong(eventsCursor.getColumnIndexOrThrow("timestamp")),
                            screenHash = eventsCursor.getString(eventsCursor.getColumnIndexOrThrow("screen_hash")),
                            screenSummary = eventsCursor.getString(eventsCursor.getColumnIndexOrThrow("screen_summary")),
                            actionTool = eventsCursor.getString(eventsCursor.getColumnIndexOrThrow("action_tool")),
                            actionParams = eventsCursor.getString(eventsCursor.getColumnIndexOrThrow("action_params")),
                            resultSuccess = eventsCursor.getInt(eventsCursor.getColumnIndexOrThrow("result_success")) == 1,
                            resultSummary = eventsCursor.getString(eventsCursor.getColumnIndexOrThrow("result_summary")),
                            latencyMs = eventsCursor.getLong(eventsCursor.getColumnIndexOrThrow("latency_ms")),
                            appPackage = eventsCursor.getString(eventsCursor.getColumnIndexOrThrow("app_package")),
                            metadataJson = eventsCursor.getString(eventsCursor.getColumnIndexOrThrow("metadata_json")),
                            targetResolution = eventsCursor.optNullableString("target_resolution"),
                            verificationResult = eventsCursor.optNullableString("verification_result"),
                            recoveryAction = eventsCursor.optNullableString("recovery_action"),
                            source = eventsCursor.optNullableString("source")
                        )
                    )
            }
            eventsCursor.close()

            return Trajectory(
                taskId = taskId,
                taskText = taskText,
                channel = channel,
                startedAt = startedAt,
                completedAt = completedAt,
                outcome = outcome,
                totalSteps = totalSteps,
                totalTokens = totalTokens,
                events = events
            )
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to get trajectory: ${e.message}", e)
            return null
        }
    }

    /**
     * Retrieve a list of recent task trajectories for diagnostics.
     */
    fun getRecentTrajectories(limit: Int = 20): List<Trajectory> {
        val trajectories = mutableListOf<Trajectory>()
        try {
            val db = getDb()
            val cursor = db.query(
                TABLE_TASKS,
                arrayOf("task_id"),
                null, null, null, null,
                "started_at DESC",
                limit.toString()
            )
            val taskIds = mutableListOf<String>()
            while (cursor.moveToNext()) {
                taskIds.add(cursor.getString(0))
            }
            cursor.close()

            taskIds.forEach { id ->
                getTrajectory(id)?.let { trajectories.add(it) }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to get recent trajectories: ${e.message}", e)
        }
        return trajectories
    }

    /**
     * Export trajectory to standardized JSON string format.
     */
    fun exportTrajectoryToJson(taskId: String): String? {
        val trajectory = getTrajectory(taskId) ?: return null
        return try {
            val json = JSONObject().apply {
                put("taskId", trajectory.taskId)
                put("taskText", trajectory.taskText)
                put("channel", trajectory.channel)
                put("startedAt", trajectory.startedAt)
                put("completedAt", trajectory.completedAt)
                put("outcome", trajectory.outcome)
                put("totalSteps", trajectory.totalSteps)
                put("totalTokens", trajectory.totalTokens)
                
                val eventsArray = org.json.JSONArray()
                trajectory.events.forEach { event ->
                    val ev = JSONObject().apply {
                        put("stepIndex", event.stepIndex)
                        put("eventType", event.eventType.name)
                        put("timestamp", event.timestamp)
                        event.screenHash?.let { put("screenHash", it) }
                        event.screenSummary?.let { put("screenSummary", it) }
                        event.actionTool?.let { put("actionTool", it) }
                        event.actionParams?.let { put("actionParams", it) }
                        put("resultSuccess", event.resultSuccess)
                        event.resultSummary?.let { put("resultSummary", it) }
                        put("latencyMs", event.latencyMs)
                        event.appPackage?.let { put("appPackage", it) }
                    }
                    eventsArray.put(ev)
                }
                put("events", eventsArray)
            }
            json.toString(2)
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to export trajectory to JSON: ${e.message}", e)
            null
        }
    }

    /**
     * Prune old trajectories to bound storage usage on mobile device.
     */
    fun pruneOldTrajectories(keepCount: Int = 100) {
        try {
            val db = getDb()
            val cursor = db.query(
                TABLE_TASKS,
                arrayOf("task_id"),
                null, null, null, null,
                "started_at DESC",
                null
            )
            val total = cursor.count
            if (total > keepCount) {
                cursor.moveToPosition(keepCount - 1)
                val taskIdsToDelete = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    taskIdsToDelete.add(cursor.getString(0))
                }
                cursor.close()

                taskIdsToDelete.forEach { id ->
                    db.delete(TABLE_TASKS, "task_id = ?", arrayOf(id))
                    db.delete(TABLE_EVENTS, "task_id = ?", arrayOf(id))
                }
                XLog.i(TAG, "Pruned ${taskIdsToDelete.size} old task trajectories (retained $keepCount)")
            } else {
                cursor.close()
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to prune old trajectories: ${e.message}", e)
        }
    }

    /**
     * Record a verified action event carrying the target-resolution method, verification
     * result, and recovery action — the structured execution trace for the unified
     * observe→resolve→act→verify→recover control loop.
     */
    fun recordVerifiedAction(
        taskId: String,
        stepIndex: Int,
        toolName: String,
        params: String,
        resultSuccess: Boolean,
        resultSummary: String,
        latencyMs: Long,
        appPackage: String? = null,
        targetResolution: String? = null,
        verificationResult: String? = null,
        recoveryAction: String? = null
    ) {
        recordEvent(
            ExecutionEvent(
                taskId = taskId,
                stepIndex = stepIndex,
                eventType = EventType.ACT,
                actionTool = toolName,
                actionParams = params.take(500),
                resultSuccess = resultSuccess,
                resultSummary = resultSummary.take(300),
                latencyMs = latencyMs,
                appPackage = appPackage,
                targetResolution = targetResolution,
                verificationResult = verificationResult?.take(200),
                recoveryAction = recoveryAction?.take(200)
            )
        )
    }

    /**
     * P3.4: Count observations per package (for P3.4 privacy dashboard).
     * Returns a map of package name → observation count, sorted by count desc.
     */
    fun observationCountsByPackage(limit: Int = 10): List<Pair<String, Int>> {
        return try {
            val db = getDb()
            val cursor = db.query(
                TABLE_EVENTS,
                arrayOf("app_package", "COUNT(*) as cnt"),
                "event_type = ? AND app_package IS NOT NULL",
                arrayOf(EventType.OBSERVE.name),
                "app_package",
                null,
                "cnt DESC",
                limit.toString()
            )
            val result = mutableListOf<Pair<String, Int>>()
            while (cursor.moveToNext()) {
                val pkg = cursor.getString(0) ?: continue
                val count = cursor.getInt(1)
                result.add(pkg to count)
            }
            cursor.close()
            result
        } catch (e: Exception) {
            XLog.e(TAG, "observationCountsByPackage failed: ${e.message}", e)
            emptyList()
        }
    }

    // ======================== P3.4: LEAVE event stats ========================

    data class LeaveStats(
        val count: Int,
        val totalOutputTokens: Int,
        val firstEventTimestampMs: Long,
        val lastEventTimestampMs: Long
    )

    /**
     * P3.4: Aggregate LEAVE events by (provider, model) for the privacy dashboard.
     * Returns provider/model → stats so users can see how much data has been sent
     * to each cloud LLM provider.
     */
    fun leaveEventStats(): Map<Pair<String, String>, LeaveStats> {
        return try {
            val db = getDb()
            val cursor = db.query(
                TABLE_EVENTS,
                arrayOf(
                    "metadata_json",
                    "COUNT(*) as cnt",
                    "MIN(timestamp) as first_ts",
                    "MAX(timestamp) as last_ts"
                ),
                "event_type = ?",
                arrayOf(EventType.LEAVE.name),
                "metadata_json",
                null,
                null,
                null
            )

            val result = mutableMapOf<Pair<String, String>, LeaveStats>()
            while (cursor.moveToNext()) {
                val metadata = cursor.optNullableString("metadata_json") ?: continue
                val count = cursor.getInt(1)
                val firstTs = cursor.getLong(2)
                val lastTs = cursor.getLong(3)

                val json = JSONObject(metadata)
                val provider = json.optString("provider", "unknown")
                val model = json.optString("model", "unknown")
                val totalTokens = json.optInt("total_output_tokens", 0)

                val key = provider to model
                val existing = result[key]
                if (existing != null) {
                    result[key] = existing.copy(
                        count = existing.count + count,
                        totalOutputTokens = existing.totalOutputTokens + totalTokens,
                        firstEventTimestampMs = minOf(existing.firstEventTimestampMs, firstTs),
                        lastEventTimestampMs = maxOf(existing.lastEventTimestampMs, lastTs)
                    )
                } else {
                    result[key] = LeaveStats(
                        count = count,
                        totalOutputTokens = totalTokens,
                        firstEventTimestampMs = firstTs,
                        lastEventTimestampMs = lastTs
                    )
                }
            }
            cursor.close()
            result
        } catch (e: Exception) {
            XLog.e(TAG, "leaveEventStats failed: ${e.message}", e)
            emptyMap()
        }
    }

    /**
     * P3.4: Record a LEAVE event (data egress to a cloud LLM provider).
     *
     * @param provider The provider name (openai, anthropic, omniroute)
     * @param model The model name (e.g. "gpt-4o-mini", "claude-3-5-sonnet")
     * @param inputTokens Approximate input tokens (optional, for future use)
     * @param outputTokens Approximate output tokens from the response
     * @param taskId Optional task ID to associate with the event
     */
    fun recordLeave(
        provider: String,
        model: String,
        inputTokens: Int = 0,
        outputTokens: Int,
        taskId: String = "standalone"
    ) {
        // Do NOT record for LOCAL provider (zero-row invariant)
        if (provider == "local") return

        getDb().insert(TABLE_EVENTS, null, android.content.ContentValues().apply {
            put("task_id", taskId)
            put("step_index", 0)
            put("event_type", EventType.LEAVE.name)
            put("timestamp", System.currentTimeMillis())
            put("metadata_json", JSONObject().apply {
                put("provider", provider)
                put("model", model)
                put("input_tokens", inputTokens)
                put("output_tokens", outputTokens)
                put("total_output_tokens", outputTokens)
            }.toString())
        })
        XLog.d(TAG, "LEAVE event recorded: provider=$provider model=$model outputTokens=$outputTokens")
    }
}

/** Returns the String value of a column that may not exist on a v1 schema mid-upgrade. */
private fun android.database.Cursor.optNullableString(column: String): String? {
    val idx = getColumnIndex(column)
    if (idx < 0 || isNull(idx)) return null
    return getString(idx)
}
