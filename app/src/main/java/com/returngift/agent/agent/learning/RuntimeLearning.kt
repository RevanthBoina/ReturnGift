// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.learning

import com.returngift.agent.agent.skill.SkillRegistry
import com.returngift.agent.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Native runtime learning system that records execution traces and
 * generates learned playbook overlays.
 * 
 * Based on principles from OpenHands runtime learning:
 * - Record execution traces with screen states, tool calls, parameters
 * - Track retries, failures, corrections, execution times
 * - Cluster successful traces to generate playbook improvements
 * - Update confidence scores after repeated successful executions
 * - Support rollback and version history
 */
object RuntimeLearning {

    private const val TAG = "RuntimeLearning"
    private const val TRACE_DIR = "execution_traces"
    private const val PLAYBOOK_DIR = "learned_playbooks"
    private const val MIN_SUCCESS_COUNT = 3
    private const val CONFIDENCE_INCREMENT = 0.1f
    private const val MAX_TRACES_PER_SKILL = 100
    
    private val traces = ConcurrentHashMap<String, MutableList<ExecutionTrace>>()
    private val playbookOverlays = ConcurrentHashMap<String, PlaybookOverlay>()
    private val versionHistory = ConcurrentHashMap<String, MutableList<PlaybookVersion>>()
    private var activeTrace: ActiveTraceRecorder? = null
    
    data class ExecutionTrace(
        val skillId: String,
        val taskDescription: String,
        val startTime: Long,
        val endTime: Long,
        val outcome: TraceOutcome,
        val steps: List<TraceStep>,
        val screenStates: List<String>,
        val errorMessage: String? = null
    )
    
    data class TraceStep(
        val stepIndex: Int,
        val toolName: String,
        val parameters: Map<String, Any>,
        val result: StepResult,
        val durationMs: Long,
        val retries: Int = 0
    )
    
    enum class StepResult { SUCCESS, FAILURE, RETRY_SUCCESS, SKIPPED }
    
    enum class TraceOutcome { SUCCESS, PARTIAL_SUCCESS, FAILURE, TIMEOUT, CANCELLED }
    
    data class PlaybookOverlay(
        val skillId: String,
        val improvements: List<LearnedImprovement>,
        val confidenceScore: Float,
        val successCount: Int,
        val lastUpdated: Long
    )
    
    data class LearnedImprovement(
        val type: ImprovementType,
        val originalStep: Int?,
        val newToolName: String?,
        val newParameters: Map<String, Any>?,
        val confidence: Float,
        val reason: String
    )
    
    enum class ImprovementType { PARAMETER_TUNING, TOOL_REPLACEMENT, STEP_REORDERING, NEW_ROUTE, GUARD_CONDITION }
    
    data class PlaybookVersion(
        val version: Int,
        val playbook: PlaybookOverlay,
        val timestamp: Long,
        val reason: String
    )
    
    data class ActiveTraceRecorder(
        val skillId: String,
        val taskDescription: String,
        val startTime: Long = System.currentTimeMillis(),
        val steps: MutableList<TraceStep> = mutableListOf(),
        val screenStates: MutableList<String> = mutableListOf()
    )
    
    fun startTrace(skillId: String, taskDescription: String) {
        activeTrace = ActiveTraceRecorder(skillId, taskDescription)
        XLog.d(TAG, "Started trace recording for skill: $skillId")
    }
    
    fun recordStep(
        stepIndex: Int,
        toolName: String,
        parameters: Map<String, Any>,
        result: StepResult,
        durationMs: Long,
        retries: Int = 0,
        screenHash: String? = null
    ) {
        activeTrace?.let { trace ->
            trace.steps.add(TraceStep(stepIndex, toolName, parameters, result, durationMs, retries))
            screenHash?.let { trace.screenStates.add(it) }
        }
    }
    
    fun recordScreenState(screenHash: String) {
        activeTrace?.screenStates?.add(screenHash)
    }
    
    fun endTrace(outcome: TraceOutcome, errorMessage: String? = null): ExecutionTrace? {
        val recorder = activeTrace ?: return null
        
        val trace = ExecutionTrace(
            skillId = recorder.skillId,
            taskDescription = recorder.taskDescription,
            startTime = recorder.startTime,
            endTime = System.currentTimeMillis(),
            outcome = outcome,
            steps = recorder.steps.toList(),
            screenStates = recorder.screenStates.toList(),
            errorMessage = errorMessage
        )
        
        val skillTraces = traces.getOrPut(recorder.skillId) { mutableListOf() }
        skillTraces.add(trace)
        
        while (skillTraces.size > MAX_TRACES_PER_SKILL) {
            skillTraces.removeAt(0)
        }
        
        if (outcome == TraceOutcome.SUCCESS || outcome == TraceOutcome.PARTIAL_SUCCESS) {
            analyzeAndImprove(trace)
        }
        
        activeTrace = null
        return trace
    }
    
    private fun analyzeAndImprove(trace: ExecutionTrace) {
        val skillId = trace.skillId
        val skillTraces = traces[skillId] ?: return
        
        val successCount = skillTraces.count { 
            it.outcome == TraceOutcome.SUCCESS || it.outcome == TraceOutcome.PARTIAL_SUCCESS 
        }
        
        if (successCount < MIN_SUCCESS_COUNT) return
        
        val improvements = mutableListOf<LearnedImprovement>()
        
        for (successTrace in skillTraces.filter { it.outcome == TraceOutcome.SUCCESS }) {
            for (step in successTrace.steps) {
                if (step.result == StepResult.SUCCESS) {
                    val existingOverlay = playbookOverlays[skillId]
                    val currentConfidence = existingOverlay?.confidenceScore ?: 0.5f
                    val newConfidence = (currentConfidence + CONFIDENCE_INCREMENT).coerceAtMost(1.0f)
                    
                    improvements.add(LearnedImprovement(
                        type = ImprovementType.PARAMETER_TUNING,
                        originalStep = step.stepIndex,
                        newToolName = step.toolName,
                        newParameters = step.parameters.mapValues { it.value.toString() },
                        confidence = newConfidence,
                        reason = "Verified by $successCount successful executions"
                    ))
                }
            }
        }
        
        val overlay = PlaybookOverlay(
            skillId = skillId,
            improvements = improvements.distinctBy { "${it.type}_${it.originalStep}_${it.newToolName}" },
            confidenceScore = improvements.maxOfOrNull { it.confidence } ?: 0.5f,
            successCount = successCount,
            lastUpdated = System.currentTimeMillis()
        )
        
        saveVersion(skillId, overlay, "Learned from $successCount successful traces")
        playbookOverlays[skillId] = overlay
        XLog.i(TAG, "Updated playbook overlay for $skillId: ${improvements.size} improvements")
    }
    
    fun getPlaybookOverlay(skillId: String): PlaybookOverlay? = playbookOverlays[skillId]
    fun getTraces(skillId: String): List<ExecutionTrace> = traces[skillId]?.toList() ?: emptyList()
    
    fun getStats(skillId: String): SkillStats {
        val skillTraces = traces[skillId] ?: return SkillStats(0, 0, 0, 0f, 0)
        val total = skillTraces.size
        val successes = skillTraces.count { it.outcome == TraceOutcome.SUCCESS }
        val failures = skillTraces.count { it.outcome == TraceOutcome.FAILURE }
        val overlay = playbookOverlays[skillId]
        return SkillStats(
            totalExecutions = total,
            successes = successes,
            failures = failures,
            currentConfidence = overlay?.confidenceScore ?: 0f,
            improvementCount = overlay?.improvements?.size ?: 0
        )
    }
    
    fun rollback(skillId: String, toVersion: Int): Boolean {
        val history = versionHistory[skillId] ?: return false
        val targetVersion = history.find { it.version == toVersion } ?: return false
        playbookOverlays[skillId] = targetVersion.playbook
        return true
    }
    
    fun getVersionHistory(skillId: String): List<PlaybookVersion> = versionHistory[skillId]?.toList() ?: emptyList()
    
    private fun saveVersion(skillId: String, playbook: PlaybookOverlay, reason: String) {
        val history = versionHistory.getOrPut(skillId) { mutableListOf() }
        val version = history.size + 1
        history.add(PlaybookVersion(version, playbook, System.currentTimeMillis(), reason))
        while (history.size > 10) history.removeAt(0)
    }
    
    fun save() {
        try {
            val traceDir = File(android.os.Environment.getExternalStorageDirectory(), TRACE_DIR)
            val playbookDir = File(android.os.Environment.getExternalStorageDirectory(), PLAYBOOK_DIR)
            traceDir.mkdirs()
            playbookDir.mkdirs()
            
            for ((skillId, skillTraces) in traces) {
                val traceFile = File(traceDir, "${skillId}_traces.json")
                val json = JSONArray()
                for (trace in skillTraces) json.put(traceToJson(trace))
                traceFile.writeText(json.toString())
            }
            
            for ((skillId, overlay) in playbookOverlays) {
                val overlayFile = File(playbookDir, "${skillId}_overlay.json")
                overlayFile.writeText(playbookToJson(overlay).toString(2))
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to save learning data", e)
        }
    }
    
    fun load() {
        try {
            val traceDir = File(android.os.Environment.getExternalStorageDirectory(), TRACE_DIR)
            val playbookDir = File(android.os.Environment.getExternalStorageDirectory(), PLAYBOOK_DIR)
            if (!traceDir.exists() || !playbookDir.exists()) return
            
            traceDir.listFiles()?.filter { it.name.endsWith("_traces.json") }?.forEach { file ->
                val skillId = file.name.removeSuffix("_traces.json")
                val json = JSONArray(file.readText())
                val loadedTraces = mutableListOf<ExecutionTrace>()
                for (i in 0 until json.length()) {
                    loadedTraces.add(jsonToTrace(json.getJSONObject(i)))
                }
                traces[skillId] = loadedTraces
            }
            
            playbookDir.listFiles()?.filter { it.name.endsWith("_overlay.json") }?.forEach { file ->
                val skillId = file.name.removeSuffix("_overlay.json")
                playbookOverlays[skillId] = jsonToPlaybook(JSONObject(file.readText()))
            }
            
            XLog.i(TAG, "Loaded ${traces.size} skill traces and ${playbookOverlays.size} overlays")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to load learning data", e)
        }
    }
    
    private fun traceToJson(trace: ExecutionTrace): JSONObject {
        val json = JSONObject()
        json.put("skillId", trace.skillId)
        json.put("taskDescription", trace.taskDescription)
        json.put("startTime", trace.startTime)
        json.put("endTime", trace.endTime)
        json.put("outcome", trace.outcome.name)
        json.put("errorMessage", trace.errorMessage)
        
        val stepsArray = JSONArray()
        for (step in trace.steps) {
            val stepJson = JSONObject()
            stepJson.put("stepIndex", step.stepIndex)
            stepJson.put("toolName", step.toolName)
            stepJson.put("parameters", JSONObject(step.parameters.mapValues { it.value.toString() }))
            stepJson.put("result", step.result.name)
            stepJson.put("durationMs", step.durationMs)
            stepJson.put("retries", step.retries)
            stepsArray.put(stepJson)
        }
        json.put("steps", stepsArray)
        json.put("screenStates", JSONArray(trace.screenStates))
        return json
    }
    
    private fun jsonToTrace(json: JSONObject): ExecutionTrace {
        val stepsArray = json.getJSONArray("steps")
        val steps = mutableListOf<TraceStep>()
        
        for (i in 0 until stepsArray.length()) {
            val stepJson = stepsArray.getJSONObject(i)
            val paramsObj = stepJson.optJSONObject("parameters")
            val params = paramsObj?.let { obj ->
                obj.keys().asSequence().associateWith { key -> obj.get(key).toString() }
            } ?: emptyMap()
            
            steps.add(TraceStep(
                stepIndex = stepJson.getInt("stepIndex"),
                toolName = stepJson.getString("toolName"),
                parameters = params,
                result = StepResult.valueOf(stepJson.getString("result")),
                durationMs = stepJson.getLong("durationMs"),
                retries = stepJson.optInt("retries", 0)
            ))
        }
        
        val screenArray = json.getJSONArray("screenStates")
        val screens = (0 until screenArray.length()).map { screenArray.getString(it) }
        
        return ExecutionTrace(
            skillId = json.getString("skillId"),
            taskDescription = json.getString("taskDescription"),
            startTime = json.getLong("startTime"),
            endTime = json.getLong("endTime"),
            outcome = TraceOutcome.valueOf(json.getString("outcome")),
            steps = steps,
            screenStates = screens,
            errorMessage = json.optString("errorMessage", null)
        )
    }
    
    private fun playbookToJson(overlay: PlaybookOverlay): JSONObject {
        val json = JSONObject()
        json.put("skillId", overlay.skillId)
        json.put("confidenceScore", overlay.confidenceScore.toDouble())
        json.put("successCount", overlay.successCount)
        json.put("lastUpdated", overlay.lastUpdated)
        
        val improvementsArray = JSONArray()
        for (imp in overlay.improvements) {
            val impJson = JSONObject()
            impJson.put("type", imp.type.name)
            impJson.put("originalStep", imp.originalStep)
            impJson.put("newToolName", imp.newToolName)
            impJson.put("newParameters", JSONObject(imp.newParameters ?: emptyMap<String, Any>()))
            impJson.put("confidence", imp.confidence.toDouble())
            impJson.put("reason", imp.reason)
            improvementsArray.put(impJson)
        }
        json.put("improvements", improvementsArray)
        return json
    }
    
    private fun jsonToPlaybook(json: JSONObject): PlaybookOverlay {
        val improvementsArray = json.getJSONArray("improvements")
        val improvements = mutableListOf<LearnedImprovement>()
        
        for (i in 0 until improvementsArray.length()) {
            val impJson = improvementsArray.getJSONObject(i)
            val paramsObj = impJson.optJSONObject("newParameters")
            val params = paramsObj?.let { obj ->
                obj.keys().asSequence().associateWith { key -> obj.get(key).toString() }
            }
            
            improvements.add(LearnedImprovement(
                type = ImprovementType.valueOf(impJson.getString("type")),
                originalStep = if (impJson.has("originalStep") && !impJson.isNull("originalStep")) impJson.getInt("originalStep") else null,
                newToolName = impJson.optString("newToolName", null),
                newParameters = params,
                confidence = impJson.getDouble("confidence").toFloat(),
                reason = impJson.getString("reason")
            ))
        }
        
        return PlaybookOverlay(
            skillId = json.getString("skillId"),
            improvements = improvements,
            confidenceScore = json.getDouble("confidenceScore").toFloat(),
            successCount = json.getInt("successCount"),
            lastUpdated = json.getLong("lastUpdated")
        )
    }
    
    data class SkillStats(
        val totalExecutions: Int,
        val successes: Int,
        val failures: Int,
        val currentConfidence: Float,
        val improvementCount: Int = 0
    )
}
