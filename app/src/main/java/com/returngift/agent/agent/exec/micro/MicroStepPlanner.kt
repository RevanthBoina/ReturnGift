// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec.micro

/**
 * Stateless micro-step planner: context in → the next mandatory micro-step(s) out.
 *
 * Deterministic by design. Several rules may match one context (e.g. "typed in a
 * chat" also matches the generic "typed somewhere" fallback); the highest
 * priority wins, and rule order breaks ties so the result is always stable.
 */
object MicroStepPlanner {

    /** The winning plan for [ctx]; an empty plan when nothing is pending. */
    fun plan(ctx: MicroStepContext): MicroPlan {
        val winner = candidates(ctx).firstOrNull()
        if (winner == null) return MicroPlan("none", "no micro-step pending", emptyList(), ctx.stepIndex)
        return MicroPlan(
            ruleId = winner.id,
            summary = winner.summary,
            steps = winner.build(ctx),
            stepIndex = ctx.stepIndex,
        )
    }

    /** Every matching rule, best first. Exposed for diagnostics and tests. */
    fun candidates(ctx: MicroStepContext): List<MicroRule> =
        MicroStepCatalog.rules
            .filter { rule -> safelyMatches(rule, ctx) }
            .sortedWith(compareByDescending<MicroRule> { it.priority }.thenBy { it.id })

    /** True when at least one rule matches [ctx]. */
    fun hasPending(ctx: MicroStepContext): Boolean = plan(ctx).isComplete.not()

    fun rule(id: String): MicroRule? = MicroStepCatalog.rules.firstOrNull { it.id == id }

    /**
     * Human/model-readable enforcement text for injecting into the loop.
     * Deliberately imperative: the model must do exactly this next.
     */
    fun guidance(plan: MicroPlan): String {
        if (plan.isComplete) return ""
        val body = plan.steps.mapIndexed { index, step -> "${index + 1}. ${describe(step)}" }
            .joinToString("\n")
        return "[Micro-step check — rule ${plan.ruleId}]\n" +
            "${plan.summary}\n" +
            "Do this next, before anything else:\n$body"
    }

    /** Short description of one step, used by logs, guidance and tests. */
    fun describe(step: MicroStep): String {
        val target = when (step.kind) {
            MicroStepKind.TAP_LABEL -> if (step.label.isBlank()) "the expected option/button" else "\"${step.label}\""
            MicroStepKind.TAP_TEXT -> if (step.label.isBlank()) "the expected text" else "\"${step.label}\""
            MicroStepKind.VERIFY_TEXT_APPEARS -> "\"${step.expected}\" appears"
            MicroStepKind.VERIFY_TEXT_DISAPPEARS -> "\"${step.expected}\" disappears"
            MicroStepKind.VERIFY_FOREGROUND -> "the foreground app is ${step.expected.ifBlank { "the driven app" }}"
            MicroStepKind.PRESS_ENTER -> "press Enter (system_key enter)"
            MicroStepKind.FOCUS_FIELD -> "focus the text field"
            MicroStepKind.CLEAR_FIELD -> "clear the field"
            MicroStepKind.DISMISS_KEYBOARD -> "dismiss the keyboard"
            MicroStepKind.WAIT_FOR_SETTLE -> "wait for the UI to settle"
            MicroStepKind.WAIT_FOR_RESULTS -> "wait for results to load"
            MicroStepKind.VERIFY_SCREEN_CHANGED -> "verify the screen changed"
            MicroStepKind.OBSERVE_SCREEN -> "read the screen once"
            MicroStepKind.SCROLL -> "scroll to reveal the target"
            MicroStepKind.SELECT_FIRST_RESULT -> "open the first result"
            MicroStepKind.GRANT_PERMISSION -> "grant the permission (${step.label})"
            MicroStepKind.CHOOSE_DIALOG_OPTION -> "choose ${if (step.label.isBlank()) "the safe option" else "\"${step.label}\""}"
            MicroStepKind.TOGGLE_SWITCH -> "toggle the setting on"
            MicroStepKind.OPEN_APP -> "open ${step.expected}"
            MicroStepKind.ASK_USER -> "ask the user: ${step.label}"
            MicroStepKind.STOP_AND_REPORT -> "stop and report: ${step.note}"
        }
        val suffix = when {
            step.mandatory && step.note.isNotEmpty() -> " — ${step.note}"
            step.mandatory -> ""
            else -> if (step.note.isNotEmpty()) " — ${step.note}" else " — optional"
        }
        return "$target$suffix"
    }

    private fun safelyMatches(rule: MicroRule, ctx: MicroStepContext): Boolean =
        try {
            rule.match(ctx)
        } catch (_: Exception) {
            false
        }
}
