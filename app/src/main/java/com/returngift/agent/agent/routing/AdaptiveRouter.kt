// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.routing

import android.content.Context
import com.returngift.agent.agent.skill.SkillRegistry

/**
 * Adaptive execution tier selection router.
 */
class AdaptiveRouter(private val context: Context) {

    enum class ExecutionTier {
        DIRECT_SKILL,
        SMALL_MODEL,
        LARGE_MODEL,
        CLOUD_MODEL
    }

    data class RoutingDecision(
        val tier: ExecutionTier,
        val skillId: String? = null,
        val params: Map<String, String> = emptyMap(),
        val confidence: Float = 1.0f
    )

    data class RouterStats(
        val directSkillExecutions: Int = 0,
        val modelExecutions: Int = 0,
        val totalDecisions: Int = 0
    )

    private var directSkillCount = 0
    private var modelCount = 0
    private var totalCount = 0

    fun routeAdaptive(task: String): RoutingDecision {
        totalCount++
        val matchingSkill = SkillRegistry.getAll().firstOrNull { skill ->
            skill.matches(task)
        }

        return if (matchingSkill != null) {
            directSkillCount++
            RoutingDecision(
                tier = ExecutionTier.DIRECT_SKILL,
                skillId = matchingSkill.id,
                confidence = 0.95f
            )
        } else {
            modelCount++
            RoutingDecision(
                tier = ExecutionTier.SMALL_MODEL,
                confidence = 0.8f
            )
        }
    }

    fun recordResult(skillId: String, success: Boolean) {
        // Track stats for adaptive feedback
    }

    fun getStats(): RouterStats {
        return RouterStats(
            directSkillExecutions = directSkillCount,
            modelExecutions = modelCount,
            totalDecisions = totalCount
        )
    }
}
