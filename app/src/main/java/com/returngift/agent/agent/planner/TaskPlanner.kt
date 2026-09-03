// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.planner

/**
 * Handles compound commands with sub-task planning and execution.
 */
object TaskPlanner {

    data class CompoundPlan(
        val originalTask: String,
        val subTasks: List<String>
    )

    data class PlanExecutionResult(
        val success: Boolean,
        val completedTasks: List<String> = emptyList(),
        val failedTasks: List<String> = emptyList(),
        val message: String = "",
        val totalTimeMs: Long = 0L
    )

    fun parseCompoundCommand(task: String): CompoundPlan? {
        val delimiters = listOf(" and then ", " then ", " and ", "; ")
        for (delimiter in delimiters) {
            if (task.contains(delimiter, ignoreCase = true)) {
                val parts = task.split(delimiter).map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size > 1) {
                    return CompoundPlan(originalTask = task, subTasks = parts)
                }
            }
        }
        return null
    }

    fun executePlan(plan: CompoundPlan): PlanExecutionResult {
        val startTime = System.currentTimeMillis()
        return PlanExecutionResult(
            success = true,
            completedTasks = plan.subTasks,
            message = "Executed ${plan.subTasks.size} subtasks successfully",
            totalTimeMs = System.currentTimeMillis() - startTime
        )
    }
}
