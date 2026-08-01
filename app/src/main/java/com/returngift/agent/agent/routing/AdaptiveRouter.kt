// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.routing

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import com.returngift.agent.agent.TaskParser
import com.returngift.agent.agent.skill.SkillRegistry
import com.returngift.agent.utils.XLog

/**
 * Adaptive routing engine for task complexity classification.
 * 
 * Classifies requests based on:
 * - Task complexity (deterministic, simple, standard, complex)
 * - Available skills
 * - Historical success rate
 * - System state (battery, thermal, RAM, latency targets)
 */
class AdaptiveRouter(private val context: Context) {

    enum class RequestComplexity {
        DETERMINISTIC, SIMPLE, STANDARD, COMPLEX
    }
    
    enum class ExecutionTier {
        DIRECT_SKILL, SMALL_MODEL, LARGE_MODEL, CLOUD_MODEL
    }
    
    data class RoutingDecision(
        val complexity: RequestComplexity,
        val tier: ExecutionTier,
        val skillId: String? = null,
        val params: Map<String, String> = emptyMap(),
        val model: String? = null,
        val reason: String,
        val confidence: Float = 0f
    )
    
    data class SystemState(
        val batteryLevel: Int,
        val isCharging: Boolean,
        val thermalStatus: ThermalStatus,
        val availableRamMb: Long,
        val latencyTargetMs: Long
    )
    
    enum class ThermalStatus { NOMINAL, CAUTION, SERIOUS, CRITICAL }
    
    data class RouterStats(
        val skillSuccessRates: Map<String, Float>,
        val cloudEnabled: Boolean,
        val latencyTargetMs: Long,
        val batteryThreshold: Int
    )
    
    private var cloudEnabled = false
    private var latencyTargetMs = 5000L
    private var batteryThreshold = 20
    private val skillSuccessRates = mutableMapOf<String, Float>()
    
    fun routeAdaptive(task: String): RoutingDecision {
        val systemState = getSystemState()
        val lower = task.lowercase()
        
        if (lower.contains(" and ") || lower.contains(" then ") || lower.contains(" after ")) {
            return RoutingDecision(
                complexity = RequestComplexity.COMPLEX,
                tier = determineTier(RequestComplexity.COMPLEX, systemState),
                reason = "Compound command requires multi-step planning",
                confidence = 1.0f
            )
        }
        
        val parseResult = TaskParser.parse(task)
        if (parseResult != null && (parseResult.intent != null || parseResult.toolName != null)) {
            val successRate = getSkillSuccessRate("deterministic")
            if (successRate > 0.9f) {
                return RoutingDecision(
                    complexity = RequestComplexity.DETERMINISTIC,
                    tier = ExecutionTier.DIRECT_SKILL,
                    skillId = parseResult.toolName,
                    params = parseResult.toolParams?.mapValues { it.value.toString() } ?: emptyMap(),
                    reason = "Deterministic action",
                    confidence = successRate
                )
            }
        }
        
        val semanticMatch = SkillRegistry.findBySemanticRetrieval(task)
        val keywordMatch = SkillRegistry.findByTriggerDetailed(task)
        val match = semanticMatch ?: keywordMatch
        
        if (match != null) {
            val skillId = match.skill.id
            val successRate = getSkillSuccessRate(skillId)
            
            if (match.isHighConfidence && successRate > 0.85f) {
                if (systemState.thermalStatus == ThermalStatus.NOMINAL || 
                    systemState.thermalStatus == ThermalStatus.CAUTION) {
                    return RoutingDecision(
                        complexity = RequestComplexity.SIMPLE,
                        tier = ExecutionTier.DIRECT_SKILL,
                        skillId = skillId,
                        params = match.extractedParams,
                        reason = "High confidence skill match",
                        confidence = match.confidence * successRate
                    )
                }
            }
            
            if (match.confidence >= 0.5f) {
                return RoutingDecision(
                    complexity = RequestComplexity.STANDARD,
                    tier = ExecutionTier.SMALL_MODEL,
                    skillId = skillId,
                    params = match.extractedParams,
                    model = "gemma-3b",
                    reason = "Medium confidence, small model",
                    confidence = match.confidence
                )
            }
        }
        
        val complexity = assessComplexity(task, match)
        val tier = determineTier(complexity, systemState)
        
        return RoutingDecision(
            complexity = complexity,
            tier = tier,
            reason = when (tier) {
                ExecutionTier.DIRECT_SKILL -> "Deterministic with skill"
                ExecutionTier.SMALL_MODEL -> "Simple task"
                ExecutionTier.LARGE_MODEL -> "Complex reasoning"
                ExecutionTier.CLOUD_MODEL -> "Cloud inference enabled"
            },
            confidence = match?.confidence ?: 0.3f
        )
    }
    
    private fun assessComplexity(task: String, match: SkillRegistry.MatchResult?): RequestComplexity {
        val lower = task.lowercase()
        
        if (lower.startsWith("open ") || lower.startsWith("go to ") ||
            lower.startsWith("take screenshot") || lower == "back" || lower == "home") {
            return RequestComplexity.DETERMINISTIC
        }
        
        val multiStepIndicators = listOf("search for", "find and", "open then", 
            "send and", "open and", "go to and", "check and")
        if (multiStepIndicators.any { lower.contains(it) }) {
            return RequestComplexity.COMPLEX
        }
        
        if (task.length > 100) return RequestComplexity.COMPLEX
        
        if (match != null && match.extractedParams.isNotEmpty()) {
            return RequestComplexity.SIMPLE
        }
        
        return RequestComplexity.STANDARD
    }
    
    private fun determineTier(complexity: RequestComplexity, state: SystemState): ExecutionTier {
        if (state.batteryLevel < batteryThreshold && !state.isCharging) {
            return when (complexity) {
                RequestComplexity.DETERMINISTIC -> ExecutionTier.DIRECT_SKILL
                RequestComplexity.SIMPLE -> ExecutionTier.DIRECT_SKILL
                RequestComplexity.STANDARD -> ExecutionTier.SMALL_MODEL
                RequestComplexity.COMPLEX -> ExecutionTier.SMALL_MODEL
            }
        }
        
        when (state.thermalStatus) {
            ThermalStatus.CRITICAL -> return ExecutionTier.DIRECT_SKILL
            ThermalStatus.SERIOUS -> return ExecutionTier.SMALL_MODEL
            ThermalStatus.CAUTION -> {}
            ThermalStatus.NOMINAL -> {}
        }
        
        if (state.latencyTargetMs < 2000 && complexity == RequestComplexity.COMPLEX) {
            return ExecutionTier.SMALL_MODEL
        }
        
        if (state.availableRamMb < 1024) {
            return when (complexity) {
                RequestComplexity.DETERMINISTIC -> ExecutionTier.DIRECT_SKILL
                else -> ExecutionTier.SMALL_MODEL
            }
        }
        
        return when (complexity) {
            RequestComplexity.DETERMINISTIC -> ExecutionTier.DIRECT_SKILL
            RequestComplexity.SIMPLE -> ExecutionTier.SMALL_MODEL
            RequestComplexity.STANDARD -> ExecutionTier.LARGE_MODEL
            RequestComplexity.COMPLEX -> {
                if (cloudEnabled) ExecutionTier.CLOUD_MODEL
                else ExecutionTier.LARGE_MODEL
            }
        }
    }
    
    private fun getSystemState(): SystemState {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = batteryManager.isCharging
        
        val thermalStatus = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                when (pm.currentThermalStatus) {
                    android.os.PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NOMINAL
                    android.os.PowerManager.THERMAL_STATUS_LIGHT,
                    android.os.PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.CAUTION
                    android.os.PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SERIOUS
                    else -> ThermalStatus.CRITICAL
                }
            } else ThermalStatus.NOMINAL
        } catch (e: Exception) { ThermalStatus.NOMINAL }
        
        val runtime = Runtime.getRuntime()
        val availableRamMb = runtime.freeMemory() / (1024 * 1024)
        
        return SystemState(batteryLevel, isCharging, thermalStatus, availableRamMb, latencyTargetMs)
    }
    
    fun recordResult(skillId: String, success: Boolean) {
        val current = skillSuccessRates[skillId] ?: 0.5f
        val newRate = if (success) current * 0.9f + 0.1f else current * 0.9f - 0.1f
        skillSuccessRates[skillId] = newRate.coerceIn(0f, 1f)
    }
    
    private fun getSkillSuccessRate(skillId: String): Float {
        return skillSuccessRates[skillId] ?: 0.7f
    }
    
    fun setCloudEnabled(enabled: Boolean) { cloudEnabled = enabled }
    fun setLatencyTarget(targetMs: Long) { latencyTargetMs = targetMs }
    
    fun getStats(): RouterStats {
        return RouterStats(
            skillSuccessRates = skillSuccessRates.toMap(),
            cloudEnabled = cloudEnabled,
            latencyTargetMs = latencyTargetMs,
            batteryThreshold = batteryThreshold
        )
    }
}
