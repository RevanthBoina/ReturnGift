// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.skill

import com.returngift.agent.utils.XLog

/**
 * Executes the recovery: blocks from a YamlSkill when a route fails.
 *
 * Supported recovery actions:
 *  - rescan_after(Nms)          → Thread.sleep + signal caller to retry selector
 *  - scroll_and_retry(N)        → emit scroll steps then retry (returns RETRY)
 *  - next_route                 → signal caller to try next route (returns NEXT_ROUTE)
 *  - abort                      → stop execution (returns ABORT)
 *  - notify_user("msg")         → returns NOTIFY with message
 *  - delegate: skill_id(...)    → returns DELEGATE with target skill id
 *  - escalate: llm_open_loop    → returns ESCALATE
 */
object RecoveryExecutor {

    private const val TAG = "RecoveryExecutor"

    sealed class RecoveryAction {
        object Retry : RecoveryAction()
        object NextRoute : RecoveryAction()
        object Abort : RecoveryAction()
        object Escalate : RecoveryAction()
        data class Notify(val message: String) : RecoveryAction()
        data class Delegate(val skillId: String, val query: String) : RecoveryAction()
    }

    /**
     * Find the matching recovery rule for [errorType] and return the first actionable result.
     * [errorType] examples: "selector_not_found", "auth_wall", "app_not_installed", "all_routes_failed"
     */
    fun handle(rules: List<RecoveryRule>, errorType: String): RecoveryAction {
        val rule = rules.firstOrNull { it.on == errorType || it.on == "all_routes_failed" }
            ?: return RecoveryAction.Escalate

        XLog.i(TAG, "Recovery rule matched: on=${rule.on} actions=${rule.doActions}")

        for (action in rule.doActions) {
            val result = parseAction(action) ?: continue
            return result
        }
        return RecoveryAction.Escalate
    }

    private fun parseAction(action: String): RecoveryAction? {
        val trimmed = action.trim()
        return when {
            trimmed.startsWith("rescan_after(") -> {
                val ms = trimmed.removePrefix("rescan_after(").removeSuffix(")")
                    .replace("ms", "").trim().toLongOrNull() ?: 600L
                Thread.sleep(ms)
                RecoveryAction.Retry
            }
            trimmed.startsWith("scroll_and_retry(") -> RecoveryAction.Retry
            trimmed == "next_route" -> RecoveryAction.NextRoute
            trimmed == "abort" -> RecoveryAction.Abort
            trimmed.startsWith("notify_user(") -> {
                val msg = trimmed.removePrefix("notify_user(").removeSuffix(")")
                    .removeSurrounding("\"")
                RecoveryAction.Notify(msg)
            }
            trimmed.startsWith("delegate:") -> {
                // "delegate: search_install_app(query=\"{app}\")"
                val rest = trimmed.removePrefix("delegate:").trim()
                val skillId = rest.substringBefore("(").trim()
                val query = rest.substringAfter("(").removeSuffix(")").trim()
                RecoveryAction.Delegate(skillId, query)
            }
            trimmed.startsWith("escalate:") -> RecoveryAction.Escalate
            trimmed.startsWith("request_permission(") -> RecoveryAction.Retry
            trimmed == "retry_once" -> RecoveryAction.Retry
            else -> {
                XLog.d(TAG, "Unknown recovery action: $trimmed")
                null
            }
        }
    }
}
