// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.correction

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import com.returngift.agent.utils.XLog
import java.util.concurrent.Executors

/**
 * SQLite database for self-correction loop.
 *
 * Table: corrections
 * - id: Auto-increment primary key
 * - task_text: Original user task that failed
 * - skill_used: Skill ID that was invoked (or "agent_loop" for full agent)
 * - failure_mode: Enum: wrong_skill, wrong_params, wrong_route, hallucination, timeout, interrupted, user_rejected
 * - correction: User-provided correct action/outcome
 * - expected_outcome: What the user expected
 * - timestamp: Unix epoch ms when correction was recorded
 * - model_version: Version of the model/LoRA at time of failure
 * - route_taken: JSON of the route/steps that were attempted
 * - context_snapshot: JSON of relevant context (screen, entities, etc.)
 * - exported: Whether this correction has been exported to Lightning AI
 * - export_batch_id: Batch ID when exported
 */
class CorrectionDatabase(context: Context) : SQLiteOpenHelper(context, "corrections.db", null, 1) {

    companion object {
        private const val TAG = "CorrectionDatabase"
        const val TABLE = "corrections"
        const val DB_VERSION = 1
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_text TEXT NOT NULL,
                skill_used TEXT NOT NULL,
                failure_mode TEXT NOT NULL,
                correction TEXT NOT NULL,
                expected_outcome TEXT,
                timestamp INTEGER NOT NULL,
                model_version TEXT NOT NULL,
                route_taken TEXT,
                context_snapshot TEXT,
                exported INTEGER DEFAULT 0,
                export_batch_id TEXT
            )
        """)
        db.execSQL("CREATE INDEX idx_corrections_timestamp ON $TABLE(timestamp)")
        db.execSQL("CREATE INDEX idx_corrections_skill ON $TABLE(skill_used)")
        db.execSQL("CREATE INDEX idx_corrections_exported ON $TABLE(exported)")
        db.execSQL("CREATE INDEX idx_corrections_batch ON $TABLE(export_batch_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations
        if (oldVersion < 2) {
            // Example: ALTER TABLE corrections ADD COLUMN new_field TEXT
        }
    }

    /**
     * Insert a new correction record.
     */
    fun insert(correction: Correction): Long {
        val db = writableDatabase
        val cv = correction.toContentValues()
        val id = db.insert(TABLE, null, cv)
        XLog.i(TAG, "Inserted correction id=$id skill=${correction.skillUsed} mode=${correction.failureMode}")
        return id
    }

    /**
     * Get all unexported corrections for batch export.
     */
    fun getUnexported(limit: Int = 100): List<Correction> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE WHERE exported = 0 ORDER BY timestamp ASC LIMIT ?",
            arrayOf(limit.toString())
        )
        val results = mutableListOf<Correction>()
        while (cursor.moveToNext()) {
            results.add(cursorToCorrection(cursor))
        }
        cursor.close()
        return results
    }

    /**
     * Mark corrections as exported with batch ID.
     */
    fun markExported(ids: List<Long>, batchId: String): Int {
        if (ids.isEmpty()) return 0
        val db = writableDatabase
        val placeholders = ids.map { "?" }.joinToString(",")
        val sql = "UPDATE $TABLE SET exported = 1, export_batch_id = ? WHERE id IN ($placeholders)"
        val args = arrayOf(batchId) + ids.map { it.toString() }.toTypedArray()
        db.execSQL(sql, args)
        XLog.i(TAG, "Marked ${ids.size} corrections as exported in batch $batchId")
        return ids.size
    }

    /**
     * Get correction by ID.
     */
    fun getById(id: Long): Correction? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE WHERE id = ?", arrayOf(id.toString()))
        val result = if (cursor.moveToFirst()) cursorToCorrection(cursor) else null
        cursor.close()
        return result
    }

    /**
     * Get corrections for a specific skill (for analysis).
     */
    fun getBySkill(skillId: String, limit: Int = 50): List<Correction> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE WHERE skill_used = ? ORDER BY timestamp DESC LIMIT ?",
            arrayOf(skillId, limit.toString())
        )
        val results = mutableListOf<Correction>()
        while (cursor.moveToNext()) {
            results.add(cursorToCorrection(cursor))
        }
        cursor.close()
        return results
    }

    /**
     * Get correction statistics.
     */
    fun getStats(): CorrectionStats {
        val db = readableDatabase
        var total = 0L
        var exported = 0L
        val bySkill = mutableMapOf<String, Int>()
        val byMode = mutableMapOf<String, Int>()

        val cursor = db.rawQuery("SELECT skill_used, failure_mode, exported FROM $TABLE", null)
        while (cursor.moveToNext()) {
            total++
            val skill = cursor.getString(0)
            val mode = cursor.getString(1)
            val exp = cursor.getInt(2)
            if (exp == 1) exported++
            bySkill[skill] = bySkill.getOrDefault(skill, 0) + 1
            byMode[mode] = byMode.getOrDefault(mode, 0) + 1
        }
        cursor.close()

        return CorrectionStats(
            total = total,
            exported = exported,
            pending = total - exported,
            bySkill = bySkill,
            byFailureMode = byMode
        )
    }

    private fun cursorToCorrection(cursor: Cursor): Correction {
        return Correction(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            taskText = cursor.getString(cursor.getColumnIndexOrThrow("task_text")),
            skillUsed = cursor.getString(cursor.getColumnIndexOrThrow("skill_used")),
            failureMode = FailureMode.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("failure_mode"))),
            correction = cursor.getString(cursor.getColumnIndexOrThrow("correction")),
            expectedOutcome = cursor.getString(cursor.getColumnIndexOrThrow("expected_outcome")),
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
            modelVersion = cursor.getString(cursor.getColumnIndexOrThrow("model_version")),
            routeTaken = cursor.getString(cursor.getColumnIndexOrThrow("route_taken")),
            contextSnapshot = cursor.getString(cursor.getColumnIndexOrThrow("context_snapshot")),
            exported = cursor.getInt(cursor.getColumnIndexOrThrow("exported")) == 1,
            exportBatchId = cursor.getString(cursor.getColumnIndexOrThrow("export_batch_id"))
        )
    }
}

/**
 * Data class for a correction record.
 */
data class Correction(
    val id: Long = 0,
    val taskText: String,
    val skillUsed: String,
    val failureMode: FailureMode,
    val correction: String,
    val expectedOutcome: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val modelVersion: String = "unknown",
    val routeTaken: String? = null,
    val contextSnapshot: String? = null,
    val exported: Boolean = false,
    val exportBatchId: String? = null
) {
    fun toContentValues(): ContentValues {
        return ContentValues().apply {
            put("task_text", taskText)
            put("skill_used", skillUsed)
            put("failure_mode", failureMode.name)
            put("correction", correction)
            put("expected_outcome", expectedOutcome)
            put("timestamp", timestamp)
            put("model_version", modelVersion)
            put("route_taken", routeTaken)
            put("context_snapshot", contextSnapshot)
            put("exported", if (exported) 1 else 0)
            put("export_batch_id", exportBatchId)
        }
    }
}

/**
 * Enum for failure modes.
 */
enum class FailureMode {
    WRONG_SKILL,           // Router picked wrong skill
    WRONG_PARAMS,          // Skill params extracted incorrectly
    WRONG_ROUTE,           // Skill chose wrong execution route
    HALLUCINATION,         // LLM produced wrong action/tool
    TIMEOUT,               // Execution timed out
    INTERRUPTED,           // Interrupted by system dialog/permission
    USER_REJECTED,         // User declined confirmation gate
    SKILL_CRASHED,         // Skill threw exception
    VERIFICATION_FAILED    // Postcondition verification failed
}

data class CorrectionStats(
    val total: Long,
    val exported: Long,
    val pending: Long,
    val bySkill: Map<String, Int>,
    val byFailureMode: Map<String, Int>
)