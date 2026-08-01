// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.pipeline

import android.content.Context
import com.returngift.agent.agent.AgentCallback
import com.returngift.agent.agent.AgentService
import com.returngift.agent.agent.anomaly.BehavioralAnomalyDetector
import com.returngift.agent.agent.grounding.VisualUIGrounding
import com.returngift.agent.agent.memory.ContextualMemory
import com.returngift.agent.agent.planner.GraphState
import com.returngift.agent.agent.planner.HierarchicalPlanner
import com.returngift.agent.agent.rag.PersonalRAG
import com.returngift.agent.agent.skill.SkillExecutor
import com.returngift.agent.agent.skill.SkillRegistry
import com.returngift.agent.utils.XLog

/**
 * Phase 2 Integrated Pipeline combining all four systems:
 * 
 * 1. Hierarchical Planner (LangGraph-style execution graph)
 * 2. Visual UI Grounding (OpenHands-style verification)
 * 3. Personal RAG (hybrid retrieval from knowledge)
 * 4. Behavioral Anomaly Detection (context-aware safety)
 * 
 * Execution lifecycle follows OpenHands' observe → reason → act → verify pattern.
 */
class Phase2Pipeline(private val context: Context) {

    private val TAG = "Phase2Pipeline"
    
    // Component instances
    private val planner = HierarchicalPlanner()
    private val visualGrounding: VisualUIGrounding by lazy { VisualUIGrounding(context) }
    private val anomalyDetector: BehavioralAnomalyDetector by lazy { BehavioralAnomalyDetector.getInstance(context) }
    private val personalRAG: PersonalRAG by lazy { PersonalRAG.getInstance(context) }
    private val memory = ContextualMemory
    private val skillExecutor = SkillExecutor()
    
    // Telemetry
    private var totalExecutions = 0
    private var successfulExecutions = 0
    private var anomalyDetections = 0
    private var visualVerifications = 0
    
    /**
     * Initialize the pipeline.
     */
    fun initialize() {
        XLog.i(TAG, "Initializing Phase 2 pipeline...")
        
        // Load persistent data
        memory.load()
        anomalyDetector.load()
        personalRAG.load()
        
        // Index existing knowledge
        personalRAG.indexKBContent()
        
        XLog.i(TAG, "Phase 2 pipeline initialized")
    }
    
    /**
     * Execute task through integrated pipeline.
     */
    fun execute(
        task: String,
        agentService: AgentService?,
        callback: AgentCallback
    ): PipelineExecution {
        val executionId = "exec_${System.currentTimeMillis()}"
        val startTime = System.currentTimeMillis()
        
        totalExecutions++
        
        XLog.i(TAG, "Executing: $task")
        
        // Step 1: PERSONAL RAG - Retrieve relevant knowledge before planning
        val retrievedContext = retrieveContext(task)
        
        // Step 2: Add to short-term memory
        memory.addTurn("user", task)
        
        // Step 3: Check for high-risk actions using ANOMALY DETECTION
        val riskLevel = assessRiskLevel(task)
        if (anomalyDetector.requiresConfirmation(task, riskLevel)) {
            val anomalyScore = anomalyDetector.calculateAnomalyScore(task, riskLevel)
            val confirmationMsg = anomalyDetector.getConfirmationMessage(task, anomalyScore)
            
            XLog.w(TAG, "Anomaly detected: score=$anomalyScore, requiring confirmation")
            anomalyDetections++
            
            // Report as needing confirmation
            callback.onError(0, SecurityException(confirmationMsg), 0)
            
            return PipelineExecution(
                executionId = executionId,
                success = false,
                message = confirmationMsg,
                requiresConfirmation = true,
                anomalyScore = anomalyScore,
                totalTimeMs = System.currentTimeMillis() - startTime
            )
        }
        
        // Step 4: VISUAL UI GROUNDING - Pre-execution observation
        val preObservation = visualGrounding.observe()
        
        // Step 5: HIERARCHICAL PLANNER - Parse and execute graph
        val graph = planner.parseTask(task)
        
        // Execute graph with all integrations
        val plannerResult = planner.execute(
            graph = graph,
            visualGrounding = visualGrounding,
            anomalyDetector = anomalyDetector,
            onProgress = { nodeId, status, message ->
                XLog.d(TAG, "Node $nodeId: $status - $message")
                
                // Record to memory
                if (status == GraphState.NodeStatus.COMPLETED) {
                    memory.extractAndStoreFacts(
                        taskDescription = task,
                        skillId = null,
                        outcome = ContextualMemory.Outcome.SUCCESS,
                        entities = emptyMap(),
                        confidence = 0.9f
                    )
                }
                
                // Record to anomaly detector
                if (status == GraphState.NodeStatus.COMPLETED || status == GraphState.NodeStatus.FAILED) {
                    anomalyDetector.recordExecution(
                        actionType = nodeId,
                        success = status == GraphState.NodeStatus.COMPLETED,
                        durationMs = 0
                    )
                }
            }
        )
        
        // Step 6: VISUAL UI GROUNDING - Post-execution verification
        val postObservation = visualGrounding.observe()
        val screenDiff = visualGrounding.compareScreens(preObservation, postObservation)
        
        if (screenDiff.significantChange) {
            visualVerifications++
            XLog.d(TAG, "Visual verification: screen changed (${screenDiff.addedCount} added, ${screenDiff.removedCount} removed)")
        }
        
        // Step 7: Record to Personal RAG
        if (plannerResult.success) {
            personalRAG.addDocument(PersonalRAG.Document(
                id = "task_$executionId",
                content = task,
                metadata = PersonalRAG.DocumentMetadata(
                    source = PersonalRAG.SourceType.TASK_RESULT,
                    entities = extractEntities(task)
                )
            ))
            personalRAG.save()
            
            successfulExecutions++
        }
        
        // Step 8: Save persistent state
        memory.save()
        anomalyDetector.save()
        
        val totalTime = System.currentTimeMillis() - startTime
        
        XLog.i(TAG, "Execution completed: success=${plannerResult.success}, time=${totalTime}ms")
        
        // Report completion
        callback.onComplete(
            round = plannerResult.completedNodes.size,
            finalAnswer = if (plannerResult.success) "Task completed successfully" else "Task failed",
            totalTokens = 0,
            modelName = "integrated"
        )
        
        return PipelineExecution(
            executionId = executionId,
            success = plannerResult.success,
            completedNodes = plannerResult.completedNodes,
            failedNodes = plannerResult.failedNodes,
            message = if (plannerResult.success) "Task completed successfully" else "Task failed",
            requiresConfirmation = false,
            anomalyScore = 0f,
            screenChanged = screenDiff.significantChange,
            retrievedContext = retrievedContext,
            totalTimeMs = totalTime
        )
    }
    
    /**
     * Retrieve context from Personal RAG.
     */
    private fun retrieveContext(task: String): String {
        // First try Personal RAG
        val ragResults = personalRAG.retrieve(task, topK = 3)
        if (ragResults.isNotEmpty()) {
            return personalRAG.retrieveForContext(task)
        }
        
        // Fall back to Contextual Memory
        return memory.buildContextPrompt(task)
    }
    
    /**
     * Assess risk level for anomaly detection.
     */
    private fun assessRiskLevel(task: String): GraphState.RiskLevel {
        val lower = task.lowercase()
        
        return when {
            lower.contains("delete") || lower.contains("bank") || lower.contains("pay") -> GraphState.RiskLevel.HIGH
            lower.contains("send") || lower.contains("share") || lower.contains("call") -> GraphState.RiskLevel.MEDIUM
            else -> GraphState.RiskLevel.LOW
        }
    }
    
    /**
     * Extract entities from task for RAG indexing.
     */
    private fun extractEntities(task: String): List<String> {
        val entities = mutableListOf<String>()
        
        // Simple entity extraction - look for quoted strings and capitalized words
        val quotedPattern = Regex("\"([^\"]+)\"")
        quotedPattern.findAll(task).forEach { entities.add(it.groupValues[1]) }
        
        return entities
    }
    
    /**
     * Resume failed execution from checkpoint.
     */
    fun resume(executionId: String, callback: AgentCallback): PipelineExecution? {
        val checkpoint = planner.resume(
            taskId = executionId,
            visualGrounding = visualGrounding,
            anomalyDetector = anomalyDetector
        ) ?: return null
        
        XLog.i(TAG, "Resumed execution: $executionId")
        
        return PipelineExecution(
            executionId = executionId,
            success = checkpoint.success,
            completedNodes = checkpoint.completedNodes,
            failedNodes = checkpoint.failedNodes,
            message = "Resumed from checkpoint",
            totalTimeMs = checkpoint.totalTimeMs
        )
    }
    
    /**
     * Get pipeline telemetry.
     */
    fun getTelemetry(): PipelineTelemetry {
        return PipelineTelemetry(
            totalExecutions = totalExecutions,
            successfulExecutions = successfulExecutions,
            anomalyDetections = anomalyDetections,
            visualVerifications = visualVerifications,
            anomalyStats = anomalyDetector.getActionStats(),
            ragStats = personalRAG.getStats(),
            memoryStats = memory.getStats()
        )
    }
    
    /**
     * Clear pipeline state.
     */
    fun clearState() {
        memory.clearShortTerm()
        anomalyDetector.clearProfile()
        XLog.d(TAG, "Cleared pipeline state")
    }
    
    data class PipelineExecution(
        val executionId: String,
        val success: Boolean,
        val completedNodes: List<String> = emptyList(),
        val failedNodes: List<String> = emptyList(),
        val message: String = "",
        val requiresConfirmation: Boolean = false,
        val anomalyScore: Float = 0f,
        val screenChanged: Boolean = false,
        val retrievedContext: String = "",
        val totalTimeMs: Long = 0
    )
    
    data class PipelineTelemetry(
        val totalExecutions: Int,
        val successfulExecutions: Int,
        val anomalyDetections: Int,
        val visualVerifications: Int,
        val anomalyStats: Map<String, BehavioralAnomalyDetector.ActionStats>,
        val ragStats: PersonalRAG.IndexStats,
        val memoryStats: ContextualMemory.MemoryStats
    )
    
    companion object {
        @Volatile
        private var instance: Phase2Pipeline? = null
        
        fun getInstance(context: Context): Phase2Pipeline {
            return instance ?: synchronized(this) {
                instance ?: Phase2Pipeline(context).also { instance = it }
            }
        }
    }
}
