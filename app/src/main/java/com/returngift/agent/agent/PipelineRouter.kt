// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

import android.content.Context
import android.content.Intent
import com.returngift.agent.agent.skill.SkillRegistry
import com.returngift.agent.tool.ToolResult
import com.returngift.agent.tool.ToolRegistry
import com.returngift.agent.utils.XLog
import com.returngift.agent.tool.impl.OpenAppTool

/**
 * 3-Tier Pipeline Router.
 *
 * Tier 1: Deterministic parser (regex) → 0 LLM calls
 * Tier 2: Skill matching with anti-trigger enforcement and entity validation → routes to skill or agent
 * Tier 3: UI agent loop (3-30 calls) → full perception/reasoning/action
 *
 * 3-tier routing: deterministic → skill → agent loop.
 */
// open for test subclassing (TaskOrchestrator tests inject a fake router via
// TaskOrchestrator.routerForTesting to exercise the Tier-1 terminal paths).
open class PipelineRouter(private val context: Context) {

    sealed class Route {
        /** Tier 1: Execute Android intent directly */
        data class DirectIntent(val intent: Intent, val description: String) : Route()

        /** Tier 1: Execute a tool directly (e.g., screenshot, back, home) */
        data class DirectTool(val toolName: String, val params: Map<String, Any>, val description: String) : Route()

        /** Tier 2: Execute a registered skill */
        data class Skill(
            val skillId: String, 
            val params: Map<String, String>, 
            val description: String,
            val confidence: Float = 1.0f
        ) : Route()

        /** Tier 2/3: Run the full agent loop */
        data class AgentLoop(val task: String, val app: String? = null) : Route()

        /** Tier 2: Pure chat response (no phone control) */
        data class Chat(val task: String) : Route()
        
        /** Tier 2: Redirect to a different skill based on anti-trigger */
        data class Redirect(val targetSkillId: String, val reason: String) : Route()
    }

    open fun route(task: String): Route {
        // Compound tasks (containing "and", "then", "after") should go to agent loop,
        // not be partially handled by Tier 1 deterministic matching. Shared guard lives
        // in TaskParser.isCompound so the golden-corpus test and the router can't diverge.
        if (TaskParser.isCompound(task)) {
            Tier1Telemetry.recordFallback()
            XLog.i(TAG, "Compound task detected, skipping Tier 1: $task")
            return Route.AgentLoop(task)
        }

        // Tier 1: Deterministic regex matching
        val parseResult = TaskParser.parse(task)
        if (parseResult != null) {
            Tier1Telemetry.recordHit(parseResult.action)
            XLog.i(TAG, "Tier 1 match: ${parseResult.action} → ${parseResult.description}")

            // Intent-based action (call, alarm, settings, URL)
            if (parseResult.intent != null) {
                return Route.DirectIntent(parseResult.intent, parseResult.description)
            }

            // Tool-based action (screenshot, back, home, open app)
            if (parseResult.toolName != null) {
                return Route.DirectTool(
                    parseResult.toolName,
                    parseResult.toolParams ?: emptyMap(),
                    parseResult.description
                )
            }
        }

        // Tier 1.5: Skill trigger matching with enhanced validation
        val matchResult = SkillRegistry.findByTriggerDetailed(task)
        if (matchResult != null) {
            // Handle anti-trigger redirect
            if (matchResult.redirectTo != null) {
                XLog.i(TAG, "Anti-trigger redirect: ${matchResult.skill.id} → ${matchResult.redirectTo}")
                return Route.Redirect(matchResult.redirectTo, "anti-trigger match")
            }
            
            // Handle low confidence matches - fall through to agent loop
            if (!matchResult.shouldRouteToSkill) {
                val reason = buildString {
                    if (matchResult.confidence < 0.8f) append("low confidence (${matchResult.confidence})")
                    if (matchResult.missingRequiredEntities.isNotEmpty()) {
                        if (isNotEmpty()) append("; ")
                        append("missing entities: ${matchResult.missingRequiredEntities.joinToString()}")
                    }
                }
                XLog.i(TAG, "Skill ${matchResult.skill.id} match below threshold, falling through to agent loop: $reason")
                Tier1Telemetry.recordFallback()
                return Route.AgentLoop(task)
            }
            
            XLog.i(TAG, "Tier 1.5 skill match: ${matchResult.skill.id} confidence=${matchResult.confidence} params=${matchResult.extractedParams}")
            return Route.Skill(
                matchResult.skill.id, 
                matchResult.extractedParams, 
                matchResult.skill.description,
                matchResult.confidence
            )
        }

        // No deterministic match → Tier 3 agent loop
        Tier1Telemetry.recordFallback()
        XLog.i(TAG, "No deterministic match, falling through to agent loop: $task")
        return Route.AgentLoop(task)
    }

    /**
     * Execute a Tier 1 direct intent.
     *
     * @return true when the activity launch was accepted; false when it threw (e.g.
     *   ActivityNotFoundException — no handler for the action). The caller must not report
     *   success on a false return (FIX 7): a launch that never happened is a failed task.
     */
    open fun executeIntent(intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            XLog.i(TAG, "Executed intent: ${intent.action}")
            true
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to execute intent: ${intent.action}", e)
            false
        }
    }

    /**
     * Execute a Tier 1 direct tool call.
     *
     * A4/FIX 11: Tier 1 never enters the agent loop, so the per-app allow-list gate
     * (AllowListToolGate / AppAllowListGuard) would otherwise be bypassed. The global
     * blocklist + payment gates are already covered here because ToolRegistry.executeTool()
     * runs SafetyInterceptor.check() unconditionally before every call; what Tier 1 was
     * missing is the per-app allow-list enforcement for tools that act inside a third-party
     * app (send_message's target app). DirectIntent paths target system UIs (dialer, SMS
     * compose, settings, browser) — never a third-party app — so the per-app allow-list does
     * not apply to them; see docs/specs/tier1-intent-matching.md.
     */
    open fun executeTool(toolName: String, params: Map<String, Any>): ToolResult {
        // FIX 11a: global blocklist check runs on EVERY Tier-1 DirectTool before any
        // execution. SafetyInterceptor's skill-YAML blocklist needs an activeSkillId,
        // which Tier 1 never has, so the sensitive-content patterns are enforced here
        // directly (mirrors send_message's blocklist_patterns; see checkGlobalBlocklist).
        val paramText = params.values.joinToString(" ") { it.toString() }
        SafetyInterceptor.checkGlobalBlocklist(paramText)?.let { block ->
            XLog.w(TAG, "Tier-1 tool '$toolName' blocked by safety blocklist: $block")
            return ToolResult.error(block)
        }
        val allowListBlock = allowListBlock(toolName, params)
        if (allowListBlock != null) {
            XLog.w(TAG, "Tier-1 tool '$toolName' blocked by allow-list: $allowListBlock")
            return ToolResult.error(allowListBlock)
        }
        val result = ToolRegistry.getInstance().executeTool(toolName, params)
        XLog.i(TAG, "Executed tool: $toolName → ${if (result.isSuccess) "success" else result.error}")
        return result
    }

    /**
     * Enforce the per-app allow-list for Tier-1 tools that target a specific third-party app.
     * Currently only send_message resolves a target app (its [params] "app" is canonicalized
     * by the parser). A disallowed app → non-null block message; Allowed and FirstTime
     * (default-ON) proceed. pw: matches the semantics of AllowListToolGate.check in the
     * agent loop (FirstTime is recorded and passes by default).
     */
    private fun allowListBlock(toolName: String, params: Map<String, Any>): String? {
        if (toolName != "send_message") return null
        // resolve the target package once; the injected check makes this pure-JVM testable.
        return allowListBlockError(
            app = params["app"]?.toString().orEmpty(),
            ownPackage = context.packageName,
            check = { pkg -> AppAllowListGuard.checkAndRecord(context, pkg) },
        )
    }

    /**
     * Pure decision half of the allow-list gate, separated so it is unit-testable without
     * Android. Resolves [app] to a package (falling back to the raw name when uninstalled),
     * skips the agent's own package, and maps [check]'s results to a block message.
     *
     * @param app        target app name exactly as the parser emitted it (["app"] param)
     * @param ownPackage the agent's own package, exempt from the allow-list
     * @param check      maps a package to its allow-list verdict
     */
    internal fun allowListBlockError(
        app: String,
        ownPackage: String,
        check: (String) -> AppAllowListGuard.CheckResult,
    ): String? {
        val trimmed = app.trim().takeIf { it.isNotBlank() } ?: return null
        val pkg = OpenAppTool.resolveAppNameStatic(trimmed) ?: trimmed
        if (pkg == ownPackage) return null
        return when (val r = check(pkg)) {
            is AppAllowListGuard.CheckResult.Allowed -> null
            is AppAllowListGuard.CheckResult.FirstTime -> null
            is AppAllowListGuard.CheckResult.Blocked -> {
                "Action blocked: the agent is not allowed to act in \"${r.label}\". " +
                    "Enable it in Settings → App Permissions to allow this."
            }
        }
    }

    companion object {
        private const val TAG = "PipelineRouter"
    }
}
