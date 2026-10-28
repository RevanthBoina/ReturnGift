// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.plan

/**
 * Entry point for plan-first behaviour.
 *
 * Contract (what the user asked for, in their words):
 *  1. understand the request and *show* the steps before acting;
 *  2. ask a question when the request cannot be executed as written;
 *  3. do all of that only when it helps — a one-tap launch or a plain question
 *     must not be buried under ceremony.
 *
 * The decision is a pure function of the text and [PlanContext], so it is
 * reproducible, free of token cost, and identical on the local-model path.
 */
object PlanFirst {

    /** How much of the plan the user sees before the agent starts. */
    enum class Mode {
        /** Show the plan (when it has ≥2 steps) and continue. */
        AUTO,

        /** Show the plan and stop; the user must send a follow-up to execute. */
        PREVIEW,

        /** Do not show the plan; required questions are still asked. */
        SILENT,
    }

    data class Policy(
        /** Ask before irreversible actions even when the plan is clear. */
        val confirmRiskyActions: Boolean = true,
        /** Show plans for single-step requests (off by default: it is noise). */
        val showSingleStepPlans: Boolean = false,
    )

    data class Presentation(
        val mode: Mode,
        val blueprint: TaskBlueprint,
        val markdown: String,
        val showPlan: Boolean,
        val executeAfter: Boolean,
    )

    private val RISKY = Regex(
        "\\b(delete|remove|erase|wipe|pay|payment|transfer|checkout|buy|purchase|" +
            "cancel (my )?(order|booking|subscription)|unsubscribe|block|report)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun mode(text: String): Mode = when {
        IntentParser.wantsPreview(text) -> Mode.PREVIEW
        IntentParser.wantsSilence(text) -> Mode.SILENT
        else -> Mode.AUTO
    }

    /**
     * Build the blueprint and decide how to present it.
     *
     * @param rawText the user's message
     * @param ctx     device knowledge (installed apps + guide-derived capabilities)
     * @param policy  user/UX preferences
     */
    fun plan(
        rawText: String,
        ctx: PlanContext,
        policy: Policy = Policy(),
    ): Presentation {
        val mode = mode(rawText)
        val intent = IntentParser.parse(rawText, ctx)
        var blueprint = PlanComposer.compose(intent, ctx)

        if (policy.confirmRiskyActions &&
            RISKY.containsMatchIn(rawText) &&
            blueprint.questions.isEmpty() &&
            blueprint.steps.isNotEmpty()
        ) {
            blueprint = blueprint.copy(
                questions = blueprint.questions + PlanQuestion(
                    id = "approval",
                    question = "This looks irreversible. Should I go ahead?",
                    choices = listOf("Yes, do it", "Cancel"),
                    allowFreeText = false,
                    reason = "risky verb",
                )
            )
        }

        val showPlan = when (mode) {
            Mode.SILENT -> false
            Mode.PREVIEW -> !blueprint.isEmpty || blueprint.questions.isNotEmpty()
            Mode.AUTO -> blueprint.steps.size > 1 ||
                policy.showSingleStepPlans ||
                blueprint.questions.isNotEmpty()
        }

        if (showPlan) com.returngift.agent.agent.tracker.UsageCounters.bump("engine", "plan")
        return Presentation(
            mode = mode,
            blueprint = blueprint,
            markdown = if (showPlan) blueprint.toMarkdown() else "",
            showPlan = showPlan,
            executeAfter = mode != Mode.PREVIEW && !blueprint.needsClarification,
        )
    }

    /** Capability the execution loop should be allowed to use for this intent. */
    fun requiresDevice(intent: TaskIntent): Boolean = intent.needsDevice

    /**
     * The plan as a compact line for the system prompt. Keeps the model aligned
     * with what the user was promised, without spending a full markdown block.
     */
    fun promptLine(blueprint: TaskBlueprint): String {
        if (blueprint.steps.size < 2) return ""
        return "Agreed plan: ${blueprint.chain()}. Follow it; deviate only if the UI differs and say why."
    }
}
