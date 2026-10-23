// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec.micro

/**
 * Cross-round micro-step state machine.
 *
 * One instance lives for the duration of a task run. The agent loop feeds it a
 * [MicroStepContext] after **every** tool execution:
 *
 *   typed text          → observe() → outstanding "press Enter / tap Send"
 *   system_key enter    → observe() → outstanding satisfied → cleared
 *   unrelated tap       → observe() → outstanding is KEPT (the message is still unsent)
 *   finish (too early)  → blockFinishReason() ≠ null → the loop rejects finish
 *
 * Pure Kotlin, no Android dependencies: fully JVM-unit-testable.
 */
class MicroStepTracker(
    private val planner: (MicroStepContext) -> MicroPlan = MicroStepPlanner::plan,
) {
    private var current: MicroPlan? = null
    private var outstanding: MicroPlan? = null
    private val injectedRules = mutableSetOf<String>()

    /** Most recent plan (empty when nothing is pending). */
    fun pending(): MicroPlan? = outstanding?.takeIf { it.steps.isNotEmpty() }

    /** Rule id of the last computed plan, empty plan included. */
    fun lastRuleId(): String = current?.ruleId ?: "none"

    /** Rule id currently outstanding (what the agent still owes). */
    fun outstandingRuleId(): String = pending()?.ruleId ?: "none"

    /**
     * Record the outcome of a tool call and recompute the plan.
     * @return the newly computed plan (possibly empty = this action owed nothing).
     */
    fun observe(ctx: MicroStepContext): MicroPlan {
        val next = planner(ctx)
        current = next
        when {
            // A new situation with a real action supersedes the previous requirement.
            next.hasBlockingAction -> outstanding = next
            satisfiesOutstanding(ctx) -> {
                outstanding = null
                injectedRules.clear()
            }
            // Verify-only plans and unrelated actions must NOT erase an outstanding
            // requirement: typing a message then tapping elsewhere still blocks finish.
            else -> Unit
        }
        return next
    }

    /**
     * Guidance to inject into the model's message history — returned at most
     * once per outstanding rule so a stalled turn does not spam the same
     * reminder every round.
     */
    fun guidanceOnce(): String? {
        val plan = pending() ?: return null
        if (!injectedRules.add(plan.ruleId)) return null
        return MicroStepPlanner.guidance(plan)
    }

    /**
     * Non-null when a `finish` call must be rejected: a mandatory micro-step is
     * still outstanding (e.g. text was typed but never sent).
     */
    fun blockFinishReason(): String? {
        val plan = pending() ?: return null
        val step = plan.firstMandatory ?: return null
        return "Not finished yet: ${MicroStepPlanner.describe(step)}. " +
            "(${plan.summary}) Do that step first, then call finish."
    }

    /** True when the model may legitimately mark the task done. */
    fun canFinish(): Boolean = blockFinishReason() == null

    fun reset() {
        current = null
        outstanding = null
        injectedRules.clear()
    }

    /** Did [ctx] perform the outstanding first step? */
    private fun satisfiesOutstanding(ctx: MicroStepContext): Boolean {
        val plan = outstanding ?: return true
        val step = plan.steps.firstOrNull() ?: return true
        // Tapping the exact labelled target always counts, whatever the step kind.
        if (ctx.lastAction == LastAction.TAPPED && step.label.isNotBlank() &&
            ctx.label.contains(step.label, ignoreCase = true)
        ) {
            return true
        }
        return when (step.kind) {
            MicroStepKind.PRESS_ENTER -> ctx.lastAction == LastAction.PRESSED_ENTER
            MicroStepKind.TAP_LABEL, MicroStepKind.TAP_TEXT, MicroStepKind.TOGGLE_SWITCH,
            MicroStepKind.CHOOSE_DIALOG_OPTION, MicroStepKind.SELECT_FIRST_RESULT,
            MicroStepKind.SCROLL ->
                ctx.lastAction == LastAction.TAPPED ||
                    ctx.lastAction == LastAction.SWIPED ||
                    ctx.lastAction == LastAction.LONG_PRESSED
            MicroStepKind.GRANT_PERMISSION -> ctx.lastAction == LastAction.GRANTED_PERMISSION ||
                ctx.lastAction == LastAction.TAPPED
            MicroStepKind.FOCUS_FIELD, MicroStepKind.CLEAR_FIELD, MicroStepKind.DISMISS_KEYBOARD ->
                ctx.lastAction == LastAction.TAPPED || ctx.lastAction == LastAction.TYPED_TEXT
            MicroStepKind.OPEN_APP -> ctx.lastAction == LastAction.OPENED_APP
            MicroStepKind.ASK_USER -> ctx.lastAction == LastAction.NO_ACTION
            MicroStepKind.WAIT_FOR_SETTLE, MicroStepKind.WAIT_FOR_RESULTS,
            MicroStepKind.VERIFY_SCREEN_CHANGED, MicroStepKind.VERIFY_TEXT_APPEARS,
            MicroStepKind.VERIFY_TEXT_DISAPPEARS, MicroStepKind.VERIFY_FOREGROUND,
            MicroStepKind.OBSERVE_SCREEN, MicroStepKind.STOP_AND_REPORT ->
                ctx.lastAction != LastAction.NO_ACTION
        }
    }
}
