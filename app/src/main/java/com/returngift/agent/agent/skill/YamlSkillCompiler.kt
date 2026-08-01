// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.skill

import com.returngift.agent.utils.XLog

/**
 * Compiles a YamlSkill into a Skill + SkillStep list that SkillExecutor can run.
 *
 * Strategy: pick the highest-reliability route, convert its steps/then/intent
 * into SkillStep objects using the existing tool names already in ToolRegistry.
 */
object YamlSkillCompiler {

    private const val TAG = "YamlSkillCompiler"

    fun compile(yaml: YamlSkill): Skill? {
        val routes = yaml.execution.routes
            .sortedByDescending { it.reliability }
            .ifEmpty {
                XLog.w(TAG, "No routes in ${yaml.skillId}, skipping")
                return null
            }

        val steps = mutableListOf<SkillStep>()

        for (route in routes) {
            when (route.kind) {
                "intent" -> {
                    val spec = route.intent ?: continue
                    // Emit a launch_intent step using the open_app tool as the closest match,
                    // then run any "then" steps (confirm_with_user, tap).
                    steps += SkillStep(
                        toolName = "open_app",
                        params = buildMap {
                            if (spec.pkg.isNotEmpty()) put("package_name", spec.pkg)
                            if (spec.data.isNotEmpty()) put("uri", spec.data)
                            put("intent_action", spec.action)
                        },
                        description = "Launch via intent: ${spec.action}",
                    )
                    steps += compileThenSteps(route.then, yaml.skillId)
                }
                "ui_automation" -> {
                    steps += compileUiSteps(route.steps, yaml.skillId)
                }
                else -> {
                    XLog.w(TAG, "Unknown route kind '${route.kind}' in ${yaml.skillId}")
                }
            }
            // first_success: stop after first route's steps are added
            if (yaml.execution.strategy == "first_success") break
        }

        if (steps.isEmpty()) {
            XLog.w(TAG, "Compiled 0 steps for ${yaml.skillId}, skipping")
            return null
        }

        val category = domainToCategory(yaml.taxonomy.domain)
        val successTemplate = yaml.output.successTemplate
        val failedTemplate = yaml.output.failedTemplate

        return Skill(
            id = yaml.skillId,
            name = yaml.skillId.replace('_', ' ').replaceFirstChar { it.uppercase() },
            description = successTemplate,
            category = category,
            estimatedStepsSaved = yaml.taxonomy.estSteps,
            steps = steps,
            parameters = yaml.slots.map { (name, slot) ->
                SkillParameter(
                    name = name,
                    type = slot.type,
                    required = slot.required,
                    description = slot.promptIfMissing,
                    defaultValue = slot.defaultFrom,
                )
            },
            triggerPatterns = yaml.routing.triggers,
            fallbackGoal = failedTemplate,
            userFacing = yaml.taxonomy.riskTier <= 2,
        )
    }

    private fun compileThenSteps(thenSteps: List<StepSpec>, skillId: String): List<SkillStep> =
        thenSteps.mapNotNull { s -> opToSkillStep(s, skillId) }

    private fun compileUiSteps(steps: List<StepSpec>, skillId: String): List<SkillStep> =
        steps.mapNotNull { s -> opToSkillStep(s, skillId) }

    private fun opToSkillStep(s: StepSpec, skillId: String): SkillStep? = when (s.op) {
        "tap" -> SkillStep(
            toolName = "find_and_tap",
            params = mapOf("text" to resolveTarget(s.target)),
            description = s.id.ifEmpty { "tap ${s.target}" },
        )
        "type" -> SkillStep(
            toolName = "input_text",
            params = mapOf("text" to s.text),
            description = "type: ${s.text.take(30)}",
        )
        "launch_app" -> SkillStep(
            toolName = "open_app",
            params = mapOf("package_name" to s.text),
            description = "launch ${s.text}",
        )
        "wait" -> SkillStep(
            toolName = "wait",
            params = mapOf("duration_ms" to s.timeoutMs.toString()),
            description = "wait ${s.timeoutMs}ms",
        )
        "confirm_with_user" -> SkillStep(
            toolName = "confirm_with_user",
            params = mapOf("message" to s.render),
            description = "confirm: ${s.render.take(40)}",
        )
        "wait_for_screen", "wait" -> SkillStep(
            toolName = "wait",
            params = mapOf("duration_ms" to s.timeoutMs.toString()),
            description = "wait for screen",
        )
        else -> {
            XLog.d(TAG, "[$skillId] Unmapped op '${s.op}', skipping step")
            null
        }
    }

    private fun resolveTarget(target: Any?): String = when (target) {
        is String -> target
        is Map<*, *> -> target["value"]?.toString() ?: target.values.firstOrNull()?.toString() ?: ""
        else -> ""
    }

    private fun domainToCategory(domain: String): SkillCategory = when (domain) {
        "communication" -> SkillCategory.MESSAGING
        "navigation", "default_app" -> SkillCategory.NAVIGATION
        "entertainment", "content_creation" -> SkillCategory.MEDIA
        "utility", "developer_tools", "app_management", "info_academic", "social", "health_lifestyle" -> SkillCategory.GENERAL
        else -> SkillCategory.GENERAL
    }
}
