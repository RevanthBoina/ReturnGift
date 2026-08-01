// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.skill

import com.returngift.agent.agent.SafetyInterceptor
import com.returngift.agent.tool.ToolRegistry
import com.returngift.agent.utils.XLog

/**
 * Executes skill steps deterministically using the ToolRegistry.
 *
 * Flow:
 * 1. Execute each step's tool with resolved parameters
 * 2. If a step fails and is not optional, retry up to step.retries times
 * 3. If retries exhausted, return failure with fallbackGoal for LLM agent loop
 *
 * Emits telemetry events for each execution (route_used, step_latency, retries, outcome, confirmation).
 *
 * Architecture reference:
 * - Server-side deterministic execution pattern
 * - Hardcoded step sequence pattern
 */
class SkillExecutor {

    /**
     * Execute a skill with resolved parameters.
     *
     * @param skill the skill definition
     * @param params user-provided parameter values (e.g., {"query": "cat videos"})
     * @param taskText the original task text for telemetry
     * @param routeUsed the route ID that was used for this skill
     * @param onProgress callback for each step progress
     * @return SkillResult indicating success or failure
     */
    fun execute(
        skill: Skill,
        params: Map<String, String>,
        taskText: String = "",
        routeUsed: String = "default",
        onProgress: ((step: Int, total: Int, description: String) -> Unit)? = null
    ): SkillResult {
        val startTime = System.currentTimeMillis()
        
        XLog.i(TAG, "Executing skill: ${skill.id} with params: $params")
        SkillTelemetry.emitSkillStart(skill.id, taskText, routeUsed)

        // C3 fix: SafetyInterceptor.check() reads activeSkillId to look up the skill's
        // YAML safety block (blocklist_patterns, risk_tier, confirmation_mode,
        // never_retry_after). Without setting this, the interceptor is a permanent no-op.
        SafetyInterceptor.activeSkillId = skill.id
        try {
        val totalSteps = skill.steps.size
        var stepsUsed = 0
        var confirmationGiven = false
        var lastError: String? = null

        for ((index, step) in skill.steps.withIndex()) {
            stepsUsed = index + 1
            val stepNum = index + 1
            onProgress?.invoke(stepNum, totalSteps, step.description)
            
            val stepStartTime = System.currentTimeMillis()
            XLog.d(TAG, "Step $stepNum/$totalSteps: ${step.toolName} — ${step.description}")

            // Resolve parameter placeholders in tool params
            val resolvedParams = resolveParams(step.params, params)

            // Execute with retries
            var succeeded = false
            var totalRetries = 0
            var selectorHitRank = 0
            
            for (attempt in 1..step.retries) {
                totalRetries++
                try {
                    val toolParams = resolvedParams.mapValues { (_, v) -> v as Any }
                    val result = ToolRegistry.getInstance().executeTool(step.toolName, toolParams)

                    if (result.isSuccess) {
                        val stepLatency = System.currentTimeMillis() - stepStartTime
                        XLog.d(TAG, "Step $stepNum OK: ${result.data?.take(100)}")
                        
                        // Track if confirmation was given
                        if (step.toolName == "confirm_with_user") {
                            confirmationGiven = true
                        }
                        
                        // Emit step telemetry
                        SkillTelemetry.emitStep(
                            skillId = skill.id,
                            stepIndex = stepNum,
                            stepName = step.toolName,
                            latencyMs = stepLatency,
                            selectorHitRank = selectorHitRank,
                            retries = totalRetries - 1  // retries used, not attempts
                        )
                        
                        succeeded = true
                        break
                    } else {
                        XLog.w(TAG, "Step $stepNum failed (attempt $attempt/${step.retries}): ${result.error}")
                        lastError = result.error
                        if (attempt < step.retries) {
                            Thread.sleep(500) // brief pause before retry
                        }
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Step $stepNum exception (attempt $attempt/${step.retries})", e)
                    lastError = e.message
                    if (attempt < step.retries) {
                        Thread.sleep(500)
                    }
                }
            }

            if (!succeeded) {
                if (step.optional) {
                    XLog.i(TAG, "Step $stepNum failed but optional, continuing")
                    continue
                }
                // Non-optional step failed after retries
                XLog.w(TAG, "Skill ${skill.id} failed at step $stepNum: ${step.description}")
                
                // Emit failure telemetry with capture_on_failure
                val totalLatency = System.currentTimeMillis() - startTime
                SkillTelemetry.emitSkillComplete(
                    skillId = skill.id,
                    outcome = SkillTelemetry.Outcome.FAILURE,
                    stepsUsed = stepsUsed,
                    confirmationGiven = confirmationGiven,
                    errorMessage = lastError
                )
                
                // Trigger capture_on_failure if enabled
                CaptureOnFailure.capture(skill.id, stepNum, lastError)
                
                return SkillResult(
                    success = false,
                    stepsUsed = stepsUsed,
                    fallbackUsed = false,
                    message = "Failed at step $stepNum: ${step.description}. " +
                              "Fallback goal: ${resolveTemplate(skill.fallbackGoal, params)}",
                    errorMessage = lastError,
                    telemetryData = mapOf(
                        "total_latency_ms" to totalLatency,
                        "route_used" to routeUsed,
                        "retries" to totalRetries - 1
                    )
                )
            }
        }

        val totalLatency = System.currentTimeMillis() - startTime
        XLog.i(TAG, "Skill ${skill.id} completed in $stepsUsed steps")
        
        // Emit success telemetry
        SkillTelemetry.emitSkillComplete(
            skillId = skill.id,
            outcome = SkillTelemetry.Outcome.SUCCESS,
            stepsUsed = stepsUsed,
            confirmationGiven = confirmationGiven
        )
        
        return SkillResult(
            success = true,
            stepsUsed = stepsUsed,
            message = "Skill '${skill.name}' completed successfully in $stepsUsed steps.",
            telemetryData = mapOf(
                "total_latency_ms" to totalLatency,
                "route_used" to routeUsed
            )
        )
        } finally {
            SafetyInterceptor.resetSession()
        }
    }

    /**
     * Replace {param_name} placeholders in step params with actual values.
     */
    private fun resolveParams(
        stepParams: Map<String, String>,
        userParams: Map<String, String>
    ): Map<String, String> {
        return stepParams.mapValues { (_, template) ->
            resolveTemplate(template, userParams)
        }
    }

    private fun resolveTemplate(template: String, params: Map<String, String>): String {
        var result = template
        for ((key, value) in params) {
            result = result.replace("{$key}", value)
        }
        return result
    }

    companion object {
        private const val TAG = "SkillExecutor"
    }
}
