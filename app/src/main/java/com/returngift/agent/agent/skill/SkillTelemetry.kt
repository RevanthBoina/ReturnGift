// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.skill

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.returngift.agent.utils.XLog

/**
 * Skill Telemetry - structured event emission for skill execution.
 * 
 * Emits: route_used, step_latency, selector_hit_rank, retries, outcome, confirmation
 * 
 * Extends the existing XLog/AppLogStore infrastructure rather than standing up
 * a second logging system.
 */
object SkillTelemetry {

    private const val TAG = "SkillTelemetry"
    private var database: TelemetryDatabase? = null

    /**
     * Initialize telemetry with context.
     */
    fun init(context: Context) {
        if (database == null) {
            database = TelemetryDatabase(context.applicationContext)
            XLog.i(TAG, "SkillTelemetry initialized")
        }
    }

    /**
     * Emit a skill execution event.
     */
    fun emit(event: TelemetryEvent) {
        try {
            database?.insert(event)
            XLog.d(TAG, "Telemetry event: ${event.eventType} skill=${event.skillId} outcome=${event.outcome}")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to emit telemetry event", e)
        }
    }

    /**
     * Emit a skill execution start event.
     */
    fun emitSkillStart(skillId: String, taskText: String, routeUsed: String) {
        emit(TelemetryEvent(
            eventType = EventType.SKILL_START,
            skillId = skillId,
            taskText = taskText,
            routeUsed = routeUsed,
            timestamp = System.currentTimeMillis()
        ))
    }

    /**
     * Emit a skill step event.
     */
    fun emitStep(
        skillId: String,
        stepIndex: Int,
        stepName: String,
        latencyMs: Long,
        selectorHitRank: Int = 0,
        retries: Int = 0
    ) {
        emit(TelemetryEvent(
            eventType = EventType.STEP,
            skillId = skillId,
            stepIndex = stepIndex,
            stepName = stepName,
            stepLatencyMs = latencyMs,
            selectorHitRank = selectorHitRank,
            retries = retries,
            timestamp = System.currentTimeMillis()
        ))
    }

    /**
     * Emit a skill completion event.
     */
    fun emitSkillComplete(
        skillId: String,
        outcome: Outcome,
        stepsUsed: Int,
        confirmationGiven: Boolean = false,
        errorMessage: String? = null
    ) {
        emit(TelemetryEvent(
            eventType = EventType.SKILL_COMPLETE,
            skillId = skillId,
            outcome = outcome,
            stepsUsed = stepsUsed,
            confirmationGiven = confirmationGiven,
            errorMessage = errorMessage,
            timestamp = System.currentTimeMillis()
        ))
    }

    /**
     * Get recent telemetry events for a skill.
     */
    fun getRecentEvents(skillId: String, limit: Int = 100): List<TelemetryEvent> {
        return database?.getEventsForSkill(skillId, limit) ?: emptyList()
    }

    /**
     * Get telemetry summary for all skills.
     */
    fun getSummary(): TelemetrySummary {
        return database?.getSummary() ?: TelemetrySummary(0, 0, emptyMap(), emptyMap())
    }

    /**
     * Telemetry event types.
     */
    enum class EventType {
        SKILL_START,
        STEP,
        SKILL_COMPLETE
    }

    /**
     * Skill execution outcomes.
     */
    enum class Outcome {
        SUCCESS,
        FAILURE,
        PARTIAL,
        TIMEOUT,
        INTERRUPTED,
        USER_REJECTED
    }

    /**
     * Telemetry event data.
     */
    data class TelemetryEvent(
        val eventType: EventType,
        val skillId: String,
        val taskText: String = "",
        val routeUsed: String = "",
        val stepIndex: Int = 0,
        val stepName: String = "",
        val stepLatencyMs: Long = 0,
        val selectorHitRank: Int = 0,
        val retries: Int = 0,
        val outcome: Outcome? = null,
        val stepsUsed: Int = 0,
        val confirmationGiven: Boolean = false,
        val errorMessage: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Telemetry summary statistics.
     */
    data class TelemetrySummary(
        val totalExecutions: Int,
        val successfulExecutions: Int,
        val bySkill: Map<String, Int>,
        val byOutcome: Map<String, Int>
    )

    /**
     * SQLite database for telemetry events.
     */
    class TelemetryDatabase(context: Context) : SQLiteOpenHelper(context, "skill_telemetry.db", null, 1) {

        companion object {
            const val TABLE = "telemetry_events"
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE $TABLE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_type TEXT NOT NULL,
                    skill_id TEXT NOT NULL,
                    task_text TEXT,
                    route_used TEXT,
                    step_index INTEGER DEFAULT 0,
                    step_name TEXT,
                    step_latency_ms INTEGER DEFAULT 0,
                    selector_hit_rank INTEGER DEFAULT 0,
                    retries INTEGER DEFAULT 0,
                    outcome TEXT,
                    steps_used INTEGER DEFAULT 0,
                    confirmation_given INTEGER DEFAULT 0,
                    error_message TEXT,
                    timestamp INTEGER NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX idx_telemetry_skill ON $TABLE(skill_id)")
            db.execSQL("CREATE INDEX idx_telemetry_timestamp ON $TABLE(timestamp)")
            db.execSQL("CREATE INDEX idx_telemetry_outcome ON $TABLE(outcome)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Future migrations
        }

        fun insert(event: TelemetryEvent) {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("event_type", event.eventType.name)
                put("skill_id", event.skillId)
                put("task_text", event.taskText)
                put("route_used", event.routeUsed)
                put("step_index", event.stepIndex)
                put("step_name", event.stepName)
                put("step_latency_ms", event.stepLatencyMs)
                put("selector_hit_rank", event.selectorHitRank)
                put("retries", event.retries)
                put("outcome", event.outcome?.name)
                put("steps_used", event.stepsUsed)
                put("confirmation_given", if (event.confirmationGiven) 1 else 0)
                put("error_message", event.errorMessage)
                put("timestamp", event.timestamp)
            }
            db.insert(TABLE, null, cv)
        }

        fun getEventsForSkill(skillId: String, limit: Int): List<TelemetryEvent> {
            val results = mutableListOf<TelemetryEvent>()
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT * FROM $TABLE WHERE skill_id = ? ORDER BY timestamp DESC LIMIT ?",
                arrayOf(skillId, limit.toString())
            )
            while (cursor.moveToNext()) {
                results.add(cursorToEvent(cursor))
            }
            cursor.close()
            return results
        }

        fun getSummary(): TelemetrySummary {
            val db = readableDatabase
            var total = 0
            var successful = 0
            val bySkill = mutableMapOf<String, Int>()
            val byOutcome = mutableMapOf<String, Int>()

            val cursor = db.rawQuery(
                "SELECT skill_id, outcome, COUNT(*) FROM $TABLE WHERE event_type = 'SKILL_COMPLETE' GROUP BY skill_id, outcome",
                null
            )
            while (cursor.moveToNext()) {
                val skill = cursor.getString(0)
                val outcome = cursor.getString(1)
                val count = cursor.getInt(2)
                
                total += count
                if (outcome == Outcome.SUCCESS.name) successful += count
                
                bySkill[skill] = bySkill.getOrDefault(skill, 0) + count
                byOutcome[outcome] = byOutcome.getOrDefault(outcome, 0) + count
            }
            cursor.close()

            return TelemetrySummary(total, successful, bySkill, byOutcome)
        }

        private fun cursorToEvent(cursor: Cursor): TelemetryEvent {
            return TelemetryEvent(
                eventType = EventType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("event_type"))),
                skillId = cursor.getString(cursor.getColumnIndexOrThrow("skill_id")),
                taskText = cursor.getString(cursor.getColumnIndexOrThrow("task_text")) ?: "",
                routeUsed = cursor.getString(cursor.getColumnIndexOrThrow("route_used")) ?: "",
                stepIndex = cursor.getInt(cursor.getColumnIndexOrThrow("step_index")),
                stepName = cursor.getString(cursor.getColumnIndexOrThrow("step_name")) ?: "",
                stepLatencyMs = cursor.getLong(cursor.getColumnIndexOrThrow("step_latency_ms")),
                selectorHitRank = cursor.getInt(cursor.getColumnIndexOrThrow("selector_hit_rank")),
                retries = cursor.getInt(cursor.getColumnIndexOrThrow("retries")),
                outcome = cursor.getString(cursor.getColumnIndexOrThrow("outcome"))?.let { Outcome.valueOf(it) },
                stepsUsed = cursor.getInt(cursor.getColumnIndexOrThrow("steps_used")),
                confirmationGiven = cursor.getInt(cursor.getColumnIndexOrThrow("confirmation_given")) == 1,
                errorMessage = cursor.getString(cursor.getColumnIndexOrThrow("error_message")),
                timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"))
            )
        }
    }
}
