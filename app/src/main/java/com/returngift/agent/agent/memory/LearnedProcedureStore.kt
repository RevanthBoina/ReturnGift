// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.returngift.agent.ClawApplication
import com.returngift.agent.agent.tracker.ExecutionTracker
import com.returngift.agent.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Learned Procedure Store for distilled successful action sequences.
 *
 * Inspired by Stagehand's action caching and Browser Use's trajectory distillation:
 * - Automatically learns optimal multi-step procedures from successful task trajectories.
 * - Extracts reusable action sequences and normalizes parameters (e.g. contact names, queries).
 * - Dynamically injects proven procedures into agent reasoning so subsequent runs bypass exploratory trial-and-error.
 */
object LearnedProcedureStore {

    private const val TAG = "LearnedProcedureStore"
    private const val DB_NAME = "learned_procedures.db"
    private const val DB_VERSION = 1
    private const val TABLE_PROCEDURES = "learned_procedures"

    data class LearnedStep(
        val toolName: String,
        val params: String,
        val summary: String
    )

    data class LearnedProcedure(
        val id: String = UUID.randomUUID().toString(),
        val taskPattern: String,
        val appSequence: List<String>,
        val steps: List<LearnedStep>,
        val successCount: Int = 1,
        val failureCount: Int = 0,
        val avgDurationMs: Long = 0L,
        val lastUsedAt: Long = System.currentTimeMillis(),
        val createdAt: Long = System.currentTimeMillis()
    ) {
        val successRate: Float
            get() {
                val total = successCount + failureCount
                return if (total > 0) successCount.toFloat() / total else 0f
            }
    }

    private class DbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_PROCEDURES (
                    id TEXT PRIMARY KEY,
                    task_pattern TEXT NOT NULL,
                    app_sequence TEXT NOT NULL,
                    steps_json TEXT NOT NULL,
                    success_count INTEGER NOT NULL,
                    failure_count INTEGER NOT NULL,
                    avg_duration_ms INTEGER NOT NULL,
                    last_used_at INTEGER NOT NULL,
                    created_at INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_proc_pattern ON $TABLE_PROCEDURES(task_pattern)")
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

    /**
     * Extract a reusable procedure from a successfully completed execution trajectory.
     */
    fun extractAndStore(trajectory: ExecutionTracker.Trajectory) {
        if (!trajectory.outcome.equals("SUCCESS", ignoreCase = true) || trajectory.events.isEmpty()) {
            return
        }

        try {
            val actEvents = trajectory.events.filter { it.eventType == ExecutionTracker.EventType.ACT && it.resultSuccess }
            if (actEvents.size < 2) return // Don't learn single-step trivial actions

            val steps = actEvents.map { event ->
                LearnedStep(
                    toolName = event.actionTool ?: "",
                    params = event.actionParams ?: "{}",
                    summary = event.resultSummary ?: ""
                )
            }.filter { it.toolName.isNotEmpty() && it.toolName != "finish" }

            if (steps.isEmpty()) return

            val apps = actEvents.mapNotNull { it.appPackage }.distinct()
            val normalizedPattern = normalizeTaskPattern(trajectory.taskText)
            val duration = if (trajectory.completedAt > trajectory.startedAt) trajectory.completedAt - trajectory.startedAt else 0L

            val db = getDb()
            // Check if existing procedure for this task pattern
            val cursor = db.query(
                TABLE_PROCEDURES,
                arrayOf("id", "success_count", "avg_duration_ms"),
                "task_pattern = ?",
                arrayOf(normalizedPattern),
                null, null, null
            )

            val cv = ContentValues().apply {
                put("task_pattern", normalizedPattern)
                put("app_sequence", JSONArray(apps).toString())
                put("steps_json", JSONArray(steps.map { mapOf("tool" to it.toolName, "params" to it.params, "summary" to it.summary) }).toString())
                put("last_used_at", System.currentTimeMillis())
            }

            if (cursor.moveToFirst()) {
                val id = cursor.getString(0)
                val currentSuccess = cursor.getInt(1)
                val currentAvg = cursor.getLong(2)
                val newAvg = if (currentAvg > 0) (currentAvg + duration) / 2 else duration

                cv.put("success_count", currentSuccess + 1)
                cv.put("avg_duration_ms", newAvg)
                db.update(TABLE_PROCEDURES, cv, "id = ?", arrayOf(id))
                XLog.i(TAG, "Updated learned procedure for '$normalizedPattern' (successCount=${currentSuccess + 1})")
            } else {
                val newId = UUID.randomUUID().toString()
                cv.put("id", newId)
                cv.put("success_count", 1)
                cv.put("failure_count", 0)
                cv.put("avg_duration_ms", duration)
                cv.put("created_at", System.currentTimeMillis())
                db.insert(TABLE_PROCEDURES, null, cv)
                XLog.i(TAG, "Learned new procedure for '$normalizedPattern' with ${steps.size} steps")
            }
            cursor.close()
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to extract/store procedure: ${e.message}", e)
        }
    }

    /**
     * Find a proven procedure for a given task query.
     */
    fun findProcedure(taskText: String, minSuccessRate: Float = 0.6f): LearnedProcedure? {
        try {
            val normalized = normalizeTaskPattern(taskText)
            val queryTokens = normalized.split("\\s+".toRegex()).filter { it.length > 2 }
            if (queryTokens.isEmpty()) return null

            val db = getDb()
            val cursor = db.query(
                TABLE_PROCEDURES,
                null,
                null, null, null, null,
                "success_count DESC, last_used_at DESC",
                "20"
            )

            var bestMatch: LearnedProcedure? = null
            var bestScore = 0

            while (cursor.moveToNext()) {
                val id = cursor.getString(cursor.getColumnIndexOrThrow("id"))
                val pattern = cursor.getString(cursor.getColumnIndexOrThrow("task_pattern"))
                val appSeqJson = cursor.getString(cursor.getColumnIndexOrThrow("app_sequence"))
                val stepsJson = cursor.getString(cursor.getColumnIndexOrThrow("steps_json"))
                val successCount = cursor.getInt(cursor.getColumnIndexOrThrow("success_count"))
                val failureCount = cursor.getInt(cursor.getColumnIndexOrThrow("failure_count"))
                val avgDuration = cursor.getLong(cursor.getColumnIndexOrThrow("avg_duration_ms"))
                val lastUsedAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_used_at"))
                val createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))

                val patternTokens = pattern.split("\\s+".toRegex()).filter { it.length > 2 }
                val overlap = queryTokens.count { q -> patternTokens.any { p -> p.contains(q) || q.contains(p) } }

                if (overlap > bestScore && overlap >= 2) {
                    val apps = try {
                        val arr = JSONArray(appSeqJson)
                        (0 until arr.length()).map { arr.getString(it) }
                    } catch (_: Exception) { emptyList() }

                    val steps = try {
                        val arr = JSONArray(stepsJson)
                        (0 until arr.length()).map { idx ->
                            val obj = arr.getJSONObject(idx)
                            LearnedStep(
                                toolName = obj.optString("tool", ""),
                                params = obj.optString("params", "{}"),
                                summary = obj.optString("summary", "")
                            )
                        }
                    } catch (_: Exception) { emptyList() }

                    val proc = LearnedProcedure(
                        id = id,
                        taskPattern = pattern,
                        appSequence = apps,
                        steps = steps,
                        successCount = successCount,
                        failureCount = failureCount,
                        avgDurationMs = avgDuration,
                        lastUsedAt = lastUsedAt,
                        createdAt = createdAt
                    )

                    if (proc.successRate >= minSuccessRate) {
                        bestMatch = proc
                        bestScore = overlap
                    }
                }
            }
            cursor.close()
            return bestMatch
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to find procedure: ${e.message}", e)
            return null
        }
    }

    /**
     * Convert learned procedure into an in-context guidance prompt.
     */
    fun toProcedurePrompt(procedure: LearnedProcedure): String {
        return buildString {
            append("## Proven Procedure (Learned from previous successful executions):\n")
            append("Pattern: ${procedure.taskPattern} (Success rate: ${(procedure.successRate * 100).toInt()}%)\n")
            append("Recommended action sequence:\n")
            procedure.steps.forEachIndexed { index, step ->
                append("${index + 1}. Call `${step.toolName}`\n")
            }
            append("Follow this sequence if applicable, but verify screen transitions.\n\n")
        }
    }

    /**
     * Record outcome of following a learned procedure.
     */
    fun recordOutcome(procedureId: String, success: Boolean) {
        try {
            val db = getDb()
            val field = if (success) "success_count" else "failure_count"
            db.execSQL(
                "UPDATE $TABLE_PROCEDURES SET $field = $field + 1, last_used_at = ? WHERE id = ?",
                arrayOf(System.currentTimeMillis(), procedureId)
            )
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to record procedure outcome: ${e.message}")
        }
    }

    private fun normalizeTaskPattern(task: String): String {
        return task.lowercase()
            .replace("[^a-z0-9\\s]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }
}
