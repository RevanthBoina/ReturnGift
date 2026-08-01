// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.planner

import com.returngift.agent.agent.memory.ContextualMemory
import com.returngift.agent.agent.skill.SkillRegistry
import com.returngift.agent.agent.skill.SkillExecutor
import com.returngift.agent.agent.learning.RuntimeLearning
import com.returngift.agent.utils.XLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Compound command planner that parses conjunctions and conditional statements
 * into a dependency graph of subtasks.
 * 
 * Based on LangGraph's task decomposition:
 * - Parse conjunctions: "and", "then", "after", "before", conditional statements
 * - Build dependency graph of subtasks
 * - Execute each node independently through semantic skill retrieval
 * - Verify completion after every step
 * - Replan only the failed branch instead of restarting entire workflow
 */
object TaskPlanner {

    private const val TAG = "TaskPlanner"
    
    private val CONJUNCTION_PATTERNS = listOf(
        PatternInfo("then", DependencyType.SEQUENTIAL, 1),
        PatternInfo("after that", DependencyType.SEQUENTIAL, 1),
        PatternInfo("next", DependencyType.SEQUENTIAL, 1),
        PatternInfo("after i", DependencyType.SEQUENTIAL, 2),
        PatternInfo("before i", DependencyType.BEFORE, 2),
        PatternInfo(" and ", DependencyType.PARALLEL, 0),
        PatternInfo(" if ", DependencyType.CONDITIONAL, 0),
        PatternInfo(" when ", DependencyType.CONDITIONAL, 0),
        PatternInfo(" once ", DependencyType.CONDITIONAL, 0),
        PatternInfo(" after completing ", DependencyType.SEQUENTIAL, 1),
        PatternInfo(" with ", DependencyType.WITH, 0),
        PatternInfo(" using ", DependencyType.WITH, 0),
    )
    
    enum class DependencyType { SEQUENTIAL, PARALLEL, CONDITIONAL, BEFORE, WITH }
    
    data class PatternInfo(val pattern: String, val type: DependencyType, val priority: Int)
    
    data class SubTask(
        val id: String,
        val description: String,
        val dependencies: List<String> = emptyList(),
        val status: TaskStatus = TaskStatus.PENDING,
        val skillId: String? = null,
        val params: Map<String, String> = emptyMap(),
        val result: TaskResult? = null,
        val retryCount: Int = 0,
        val maxRetries: Int = 2
    )
    
    enum class TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED }
    
    data class TaskResult(
        val success: Boolean,
        val message: String,
        val skillUsed: Boolean,
        val executionTimeMs: Long = 0
    )
    
    data class ExecutionPlan(
        val tasks: MutableMap<String, SubTask>,
        val rootTasks: List<String>
    )
    
    data class PlanResult(
        val success: Boolean,
        val completedTasks: List<String>,
        val failedTasks: List<String>,
        val skippedTasks: List<String>,
        val totalTimeMs: Long,
        val message: String
    )
    
    fun parseCompoundCommand(command: String): ExecutionPlan? {
        val lower = command.lowercase()
        
        val hasConjunction = CONJUNCTION_PATTERNS.any { lower.contains(it.pattern) }
        if (!hasConjunction) return null
        
        val segments = splitByConjunctions(command)
        if (segments.size < 2) return null
        
        val tasks = ConcurrentHashMap<String, SubTask>()
        val processedDescriptions = mutableSetOf<String>()
        
        for ((index, segment) in segments.withIndex()) {
            val trimmed = segment.trim()
            if (trimmed.isEmpty()) continue
            
            val taskId = "task_$index"
            val cleanDescription = cleanTaskDescription(trimmed)
            
            if (cleanDescription in processedDescriptions) continue
            processedDescriptions.add(cleanDescription)
            
            val skillMatch = SkillRegistry.findByTriggerDetailed(cleanDescription)
            
            tasks[taskId] = SubTask(
                id = taskId,
                description = cleanDescription,
                skillId = skillMatch?.skill?.id,
                params = skillMatch?.extractedParams ?: emptyMap()
            )
        }
        
        val taskList = tasks.values.toList()
        for (i in 1 until taskList.size) {
            val prevTask = taskList[i - 1]
            val currTask = taskList[i]
            
            tasks[currTask.id] = currTask.copy(
                dependencies = currTask.dependencies + prevTask.id
            )
        }
        
        val rootTasks = taskList.filter { it.dependencies.isEmpty() }.map { it.id }
        
        XLog.i(TAG, "Parsed compound command into ${tasks.size} tasks")
        
        return ExecutionPlan(tasks = tasks, rootTasks = rootTasks)
    }
    
    fun executePlan(plan: ExecutionPlan): PlanResult {
        val startTime = System.currentTimeMillis()
        val completedTasks = mutableListOf<String>()
        val failedTasks = mutableListOf<String>()
        val skippedTasks = mutableListOf<String>()
        
        val contextPrompt = ContextualMemory.buildContextPrompt(
            plan.tasks.values.joinToString(" ") { it.description },
            maxTokens = 300
        )
        
        val pendingTasks = plan.tasks.values.toMutableList()
        val completedSet = mutableSetOf<String>()
        
        while (pendingTasks.isNotEmpty()) {
            val readyTasks = pendingTasks.filter { task ->
                task.dependencies.all { completedSet.contains(it) }
            }
            
            if (readyTasks.isEmpty()) break
            
            for (task in readyTasks) {
                pendingTasks.remove(task)
                plan.tasks[task.id] = task.copy(status = TaskStatus.RUNNING)
                
                val result = executeSubTask(task, contextPrompt)
                plan.tasks[task.id] = task.copy(
                    status = if (result.success) TaskStatus.COMPLETED else TaskStatus.FAILED,
                    result = result
                )
                
                if (result.success) {
                    completedTasks.add(task.id)
                    completedSet.add(task.id)
                    
                    ContextualMemory.extractAndStoreFacts(
                        taskDescription = task.description,
                        skillId = task.skillId,
                        outcome = ContextualMemory.Outcome.SUCCESS,
                        entities = task.params,
                        confidence = 0.8f
                    )
                } else {
                    failedTasks.add(task.id)
                    completedSet.add(task.id)
                    
                    if (task.retryCount < task.maxRetries) {
                        val replanned = replanTask(task, plan, contextPrompt)
                        if (replanned != null) {
                            plan.tasks[replanned.id] = replanned
                            pendingTasks.add(replanned)
                        }
                    }
                }
            }
        }
        
        val totalTime = System.currentTimeMillis() - startTime
        val success = failedTasks.isEmpty() && completedTasks.isNotEmpty()
        
        return PlanResult(
            success = success,
            completedTasks = completedTasks,
            failedTasks = failedTasks,
            skippedTasks = skippedTasks,
            totalTimeMs = totalTime,
            message = buildResultMessage(completedTasks, failedTasks, skippedTasks, totalTime)
        )
    }
    
    private fun replanTask(
        failedTask: SubTask,
        plan: ExecutionPlan,
        contextPrompt: String
    ): SubTask? {
        val retryCount = failedTask.retryCount + 1
        val semanticMatch = SkillRegistry.findBySemanticRetrieval(failedTask.description)
        
        return failedTask.copy(
            retryCount = retryCount,
            skillId = semanticMatch?.skill?.id ?: failedTask.skillId,
            params = semanticMatch?.extractedParams ?: failedTask.params
        )
    }
    
    private fun executeSubTask(task: SubTask, contextPrompt: String): TaskResult {
        val startTime = System.currentTimeMillis()
        RuntimeLearning.startTrace(task.skillId ?: "agent", task.description)
        
        return if (task.skillId != null) {
            val skill = SkillRegistry.findById(task.skillId)
            if (skill == null) {
                val result = TaskResult(false, "Skill not found", false)
                RuntimeLearning.endTrace(RuntimeLearning.TraceOutcome.FAILURE, result.message)
                return result
            }
            
            val executor = SkillExecutor()
            val skillResult = executor.execute(skill, task.params, task.description)
            
            RuntimeLearning.endTrace(
                if (skillResult.success) RuntimeLearning.TraceOutcome.SUCCESS 
                else RuntimeLearning.TraceOutcome.FAILURE,
                skillResult.errorMessage
            )
            
            TaskResult(
                success = skillResult.success,
                message = skillResult.message,
                skillUsed = true,
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        } else {
            TaskResult(
                success = false,
                message = "No skill matched",
                skillUsed = false,
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
    
    private fun splitByConjunctions(command: String): List<String> {
        val parts = mutableListOf<String>()
        var remaining = command
        
        while (remaining.isNotEmpty()) {
            var earliestIndex = remaining.length
            var earliestPattern = ""
            
            for (info in CONJUNCTION_PATTERNS.sortedByDescending { it.priority }) {
                val idx = remaining.lowercase().indexOf(info.pattern)
                if (idx >= 0 && idx < earliestIndex) {
                    earliestIndex = idx
                    earliestPattern = info.pattern
                }
            }
            
            if (earliestPattern.isEmpty()) {
                parts.add(remaining)
                break
            }
            
            val before = remaining.substring(0, earliestIndex).trim()
            if (before.isNotEmpty()) parts.add(before)
            
            remaining = remaining.substring(earliestIndex + earliestPattern.length).trim()
        }
        
        return parts
    }
    
    private fun cleanTaskDescription(description: String): String {
        return description
            .replace(Regex("^(first|then|after|next|finally),?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex(",?\\s*(and|then|after|before|with|using)\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
    }
    
    private fun buildResultMessage(
        completed: List<String>,
        failed: List<String>,
        skipped: List<String>,
        timeMs: Long
    ): String {
        return buildString {
            if (completed.isNotEmpty()) append("Completed ${completed.size} tasks")
            if (failed.isNotEmpty()) { if (isNotEmpty()) append(", "); append("failed ${failed.size}") }
            if (skipped.isNotEmpty()) { if (isNotEmpty()) append(", "); append("skipped ${skipped.size}") }
            if (isNotEmpty()) append(". ")
            append("Total time: ${timeMs}ms")
        }
    }
}
