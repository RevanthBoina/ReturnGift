// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.pipeline

import android.content.Context
import com.returngift.agent.agent.AgentCallback
import com.returngift.agent.agent.AgentService
import com.returngift.agent.agent.embedding.SkillEmbeddingIndex
import com.returngift.agent.agent.learning.RuntimeLearning
import com.returngift.agent.agent.memory.ContextualMemory
import com.returngift.agent.agent.planner.TaskPlanner
import com.returngift.agent.agent.routing.AdaptiveRouter
import com.returngift.agent.agent.skill.SkillExecutor
import com.returngift.agent.agent.skill.SkillRegistry
import com.returngift.agent.utils.XLog

/**
 * Integrated agent pipeline that wires together all five features:
 * 
 * 1. Semantic Retrieval - Uses embeddings for skill matching
 * 2. Memory - Provides contextual facts and historical context
 * 3. Learning - Records traces and improves playbooks
 * 4. Planning - Handles compound commands with dependency graphs
 * 5. Routing - Adaptive execution tier selection
 */
class IntegratedAgentPipeline(private val context: Context) {

    private val adaptiveRouter = AdaptiveRouter(context)
    private val embeddingIndex = SkillEmbeddingIndex.getInstance()
    private val memory = ContextualMemory
    private val learning = RuntimeLearning
    private val planner = TaskPlanner
    
    private val skillExecutor = SkillExecutor()
    
    fun initialize() {
        XLog.i(TAG, "Initializing integrated agent pipeline...")
        memory.load()
        learning.load()
        embeddingIndex.rebuild()
        XLog.i(TAG, "Pipeline initialized")
    }
    
    fun executeTask(
        task: String,
        agentService: AgentService?,
        callback: AgentCallback
    ): PipelineResult {
        val startTime = System.currentTimeMillis()
        
        memory.addTurn("user", task)
        val contextPrompt = memory.buildContextPrompt(task)
        val routingDecision = adaptiveRouter.routeAdaptive(task)
        XLog.d(TAG, "Routing: ${routingDecision.tier}")
        
        return when (routingDecision.tier) {
            AdaptiveRouter.ExecutionTier.DIRECT_SKILL -> {
                executeDirectSkill(routingDecision, task, callback)
            }
            AdaptiveRouter.ExecutionTier.SMALL_MODEL,
            AdaptiveRouter.ExecutionTier.LARGE_MODEL -> {
                executeWithAgent(routingDecision, task, contextPrompt, agentService, callback)
            }
            AdaptiveRouter.ExecutionTier.CLOUD_MODEL -> {
                PipelineResult(false, "Cloud not available", totalTimeMs = 0)
            }
        }.also { result ->
            routingDecision.skillId?.let { adaptiveRouter.recordResult(it, result.success) }
            
            if (result.success) {
                memory.extractAndStoreFacts(
                    taskDescription = task,
                    skillId = routingDecision.skillId,
                    outcome = ContextualMemory.Outcome.SUCCESS,
                    entities = routingDecision.params,
                    confidence = routingDecision.confidence
                )
            }
            
            memory.save()
            learning.save()
        }
    }
    
    fun executeCompoundCommand(task: String, agentService: AgentService?, callback: AgentCallback): PipelineResult {
        val startTime = System.currentTimeMillis()
        
        val plan = planner.parseCompoundCommand(task) ?: return PipelineResult(
            success = false,
            message = "Could not parse compound command",
            totalTimeMs = System.currentTimeMillis() - startTime
        )
        
        val planResult = planner.executePlan(plan)
        
        return PipelineResult(
            success = planResult.success,
            completedTasks = planResult.completedTasks,
            failedTasks = planResult.failedTasks,
            message = planResult.message,
            totalTimeMs = planResult.totalTimeMs
        )
    }
    
    private fun executeDirectSkill(
        decision: AdaptiveRouter.RoutingDecision,
        task: String,
        callback: AgentCallback
    ): PipelineResult {
        val startTime = System.currentTimeMillis()
        
        val skillId = decision.skillId ?: return PipelineResult(
            success = false,
            message = "No skill ID",
            totalTimeMs = System.currentTimeMillis() - startTime
        )
        
        val skill = SkillRegistry.findById(skillId) ?: return PipelineResult(
            success = false,
            message = "Skill not found",
            totalTimeMs = System.currentTimeMillis() - startTime
        )
        
        learning.startTrace(skillId, task)
        val result = skillExecutor.execute(skill, decision.params, task)
        
        learning.endTrace(
            if (result.success) RuntimeLearning.TraceOutcome.SUCCESS
            else RuntimeLearning.TraceOutcome.FAILURE,
            result.errorMessage
        )
        
        callback.onComplete(0, result.message, 0, "direct")
        
        return PipelineResult(
            success = result.success,
            message = result.message,
            skillUsed = true,
            skillId = skillId,
            totalTimeMs = System.currentTimeMillis() - startTime
        )
    }
    
    private fun executeWithAgent(
        decision: AdaptiveRouter.RoutingDecision,
        task: String,
        contextPrompt: String,
        agentService: AgentService?,
        callback: AgentCallback
    ): PipelineResult {
        val startTime = System.currentTimeMillis()
        
        if (agentService == null) {
            return PipelineResult(false, "Agent service not available", totalTimeMs = 0)
        }
        
        val enhancedTask = if (contextPrompt.isNotEmpty()) "$task\n\nContext:\n$contextPrompt" else task
        agentService.executeTask(enhancedTask, callback)
        
        return PipelineResult(
            success = true,
            message = "Via agent model",
            totalTimeMs = System.currentTimeMillis() - startTime
        )
    }
    
    fun getStats(): PipelineStats {
        return PipelineStats(
            memoryStats = memory.getStats(),
            embeddingStats = embeddingIndex.getStats(),
            routerStats = adaptiveRouter.getStats()
        )
    }
    
    fun clearShortTermMemory() { memory.clearShortTerm() }
    fun clearAllData() { memory.clearAll() }
    
    data class PipelineResult(
        val success: Boolean,
        val message: String = "",
        val completedTasks: List<String> = emptyList(),
        val failedTasks: List<String> = emptyList(),
        val skillUsed: Boolean = false,
        val skillId: String? = null,
        val totalTimeMs: Long = 0
    )
    
    data class PipelineStats(
        val memoryStats: ContextualMemory.MemoryStats,
        val embeddingStats: SkillEmbeddingIndex.IndexStats,
        val routerStats: AdaptiveRouter.RouterStats
    )
    
    companion object {
        private const val TAG = "IntegratedPipeline"
        
        @Volatile
        private var instance: IntegratedAgentPipeline? = null
        
        fun getInstance(context: Context): IntegratedAgentPipeline {
            return instance ?: synchronized(this) {
                instance ?: IntegratedAgentPipeline(context).also { instance = it }
            }
        }
    }
}
