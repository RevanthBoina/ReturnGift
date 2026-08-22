// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec

/**
 * Terminal outcomes for the bounded executor (pure Kotlin).
 *
 * Every task MUST terminate as exactly one of these — there is no open-ended
 * "still thinking" end state. The report carries a concise reason plus the
 * execution state (reads/actions/retries/elapsed) so failures are diagnosable
 * from logs alone.
 */
enum class ExecOutcome {
    SUCCESS,
    FAILED_TARGET_NOT_FOUND,
    FAILED_ACTION,
    FAILED_VERIFICATION,
    TIMEOUT,
    BUDGET_EXCEEDED,
}

/** Immutable end-of-run snapshot. */
data class ExecReport(
    val outcome: ExecOutcome,
    val reason: String,
    val screenReads: Int,
    val actions: Int,
    val escalations: Int,
    val elapsedMs: Long,
    /** Ordered "STATE@elapsedMs" trace for post-mortem. */
    val stateTrace: List<String>,
) {
    fun toSummary(): String = buildString {
        append(outcome.name)
        append(": ")
        append(reason)
        append(" (reads=").append(screenReads)
        append(", actions=").append(actions)
        append(", escalations=").append(escalations)
        append(", elapsed=").append(elapsedMs).append("ms)")
    }

    /** ExecutionTracker endTask status string. */
    fun trackerStatus(): String = when (outcome) {
        ExecOutcome.SUCCESS -> "SUCCESS"
        ExecOutcome.FAILED_TARGET_NOT_FOUND -> "FAILED_TARGET_NOT_FOUND"
        ExecOutcome.FAILED_ACTION -> "FAILED_ACTION"
        ExecOutcome.FAILED_VERIFICATION -> "FAILED_VERIFICATION"
        ExecOutcome.TIMEOUT -> "TIMEOUT"
        ExecOutcome.BUDGET_EXCEEDED -> "BUDGET_EXCEEDED"
    }
}
