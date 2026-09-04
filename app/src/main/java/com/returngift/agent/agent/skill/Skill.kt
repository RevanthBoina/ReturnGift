// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.skill

/**
 * ReturnGift Skill definition.
 *
 * A skill is a pre-defined multi-step action sequence that saves 3-10+
 * LLM rounds. Skills execute deterministically first, falling back to
 * LLM agent loop only when deterministic steps fail.
 *
 * Format: structured skill definition with steps, params, and triggers.
 */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val category: SkillCategory,
    val estimatedStepsSaved: Int = 5,
    val steps: List<SkillStep>,
    val parameters: List<SkillParameter> = emptyList(),
    val triggerPatterns: List<String> = emptyList(),
    val fallbackGoal: String = "",
    /** If true, this skill appears in the Task UI for users to initiate.
     *  If false, it's only used internally by the LLM agent (e.g. dismiss_popup, go_back). */
    val userFacing: Boolean = false
) {
    fun matches(task: String): Boolean {
        val lower = task.lowercase()
        return triggerPatterns.any { pattern -> lower.contains(pattern.lowercase()) }
    }
}

enum class SkillCategory(val label: String, val icon: String) {
    INPUT("Input", "\uD83D\uDD0D"),        // 🔍
    NAVIGATION("Navigation", "\uD83D\uDCF1"), // 📱
    MESSAGING("Messaging", "\uD83D\uDCAC"),   // 💬
    DISMISS("Dismiss", "❌"),
    MEDIA("Media", "\uD83D\uDCF7"),           // 📷
    GENERAL("General", "\uD83D\uDCCB")        // 📋
}

data class SkillStep(
    val toolName: String,
    val params: Map<String, String> = emptyMap(),
    val description: String = "",
    val optional: Boolean = false,
    val retries: Int = 1
)

data class SkillParameter(
    val name: String,
    val type: String = "string",
    val required: Boolean = true,
    val description: String = "",
    val defaultValue: String = ""
)

/**
 * Result of a skill execution.
 *
 * @property success        true when every non-optional step succeeded
 * @property cancelled      true when execution was stopped by the caller (stop predicate,
 *                          FIX 9) — distinct from failure so the orchestrator never falls
 *                          back to the agent loop for a cancelled skill
 * @property timedOut       true when the wall-clock bound was exceeded (C5/FIX 5) — again
 *                          distinct from failure
 */
data class SkillResult(
    val success: Boolean,
    val stepsUsed: Int,
    val tokensUsed: Int = 0,
    val fallbackUsed: Boolean = false,
    val message: String = "",
    val errorMessage: String? = null,
    val telemetryData: Map<String, Any> = emptyMap(),
    val cancelled: Boolean = false,
    val timedOut: Boolean = false
)
