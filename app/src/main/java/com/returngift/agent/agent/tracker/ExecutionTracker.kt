// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.tracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.returngift.agent.ClawApplication
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
    private const val DB_VERSION = 1
    private const val TABLE_EVENTS = "execution_events"
    private const val TABLE_TASKS = "task_trajectories"

    enum class EventType {
        TASK_START,
        OBSERVE,
        THINK,
        ACT,
        TOOL_RESULT,
        ERROR,
        RECOVER,
        TASK_COMPLETE
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
        val metadataJson: String? = null
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
                    metadata_json TEXT
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_task ON $TABLE_EVENTS(task_id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private var dbHelper: DbHelper? = null

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

    fun recordObservation(
        taskId: String,
        stepIndex: Int,
        screenHash: String,
        screenSummary: String,
        appPackage: String? = null
    ) {
        recordEvent(
            ExecutionEvent(
                taskId = taskId,
                stepIndex = stepIndex,
                eventType = EventType.OBSERVE,
                screenHash = screenHash,
                screenSummary = screenSummary.take(300),
                appPackage = appPackage
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
        try {
            val db = getDb()
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
                        metadataJson = eventsCursor.getString(eventsCursor.getColumnIndexOrThrow("metadata_json"))
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
}
