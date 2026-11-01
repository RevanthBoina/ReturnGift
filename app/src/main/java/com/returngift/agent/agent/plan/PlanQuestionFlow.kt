// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.plan

/**
 * The ask → answer → rebuild loop, separated from the Android plumbing that asks.
 *
 * Why this exists as its own object: the previous flow asked only the *first* plan's
 * questions and rebuilt once. Two failures followed from that:
 *
 *  1. a question introduced by the first answer ("where?" → "LinkedIn") was never
 *     asked, so a risky request ended with "I still need one detail" and did nothing;
 *  2. answering the irreversible-action approval rebuilt the identical plan, which
 *     asked the same approval again — forever.
 *
 * The loop here is bounded, remembers which question ids were answered, and treats a
 * granted approval as permission for the rest of the task rather than as text to fold
 * into the sentence. It is pure (the caller supplies [ask]), so the whole thing is
 * unit-tested without a device.
 */
object PlanQuestionFlow {

    /** Ceiling on questions per task. Three is already a lot to ask before acting. */
    const val MAX_QUESTIONS = 3

    sealed class Outcome {
        /** Every blocking question was answered (or none was ever needed). */
        data class Proceed(
            val presentation: PlanFirst.Presentation,
            val answers: Map<String, String>,
        ) : Outcome()

        /** An informational question went unanswered (timeout) or was cancelled. */
        data class Cancelled(val question: String) : Outcome()

        /** The irreversible-action approval was declined, cancelled or timed out. */
        data class ApprovalDeclined(val question: String) : Outcome()

        /** Still blocked after [MAX_QUESTIONS] — never spins, never guesses. */
        data class StillBlocked(val questions: List<String>) : Outcome()
    }

    /**
     * @param initial the presentation built from [rawText] before anything was asked
     * @param ask     parks the caller until an answer arrives; null means timeout/cancel
     */
    fun run(
        rawText: String,
        ctx: PlanContext,
        initial: PlanFirst.Presentation,
        maxQuestions: Int = MAX_QUESTIONS,
        ask: (PlanQuestion) -> String?,
    ): Outcome {
        var presentation = initial
        val answers = mutableMapOf<String, String>()
        var approvalGranted = false
        var clarified = rawText
        var asked = 0

        while (presentation.blueprint.needsClarification && asked < maxQuestions) {
            var askedThisRound = false
            for (question in presentation.blueprint.questions.filter { it.blocking }) {
                if (answers.containsKey(question.id)) continue
                if (asked >= maxQuestions) break

                val answer = ask(question)
                asked++
                if (question.id == APPROVAL_ID) {
                    // The approval is a yes/no gate, not new information: anything that
                    // is not an explicit yes — "Cancel", a timeout, a stray word — means
                    // the action does not run. It is a decision, not a missing detail,
                    // so it is reported as a decline rather than "I need to know …".
                    if (answer == null || !answer.trim().startsWith("Yes", ignoreCase = true)) {
                        return Outcome.ApprovalDeclined(question.question)
                    }
                    answers[question.id] = answer
                    approvalGranted = true
                } else {
                    if (answer.isNullOrBlank() || answer.equals("Cancel", ignoreCase = true)) {
                        return Outcome.Cancelled(question.question)
                    }
                    answers[question.id] = answer
                }
                askedThisRound = true
            }
            if (!askedThisRound) break

            // Fold only the answers that are *information* back into the sentence; the
            // approval is carried as permission, so the rebuilt plan cannot ask it again.
            clarified = PlanClarifier.applyAnswers(clarified, presentation.blueprint, answers - APPROVAL_ID)
            presentation = PlanFirst.plan(
                clarified,
                ctx,
                policy = if (approvalGranted) PlanFirst.Policy(confirmRiskyActions = false) else PlanFirst.Policy(),
            )
        }

        if (presentation.blueprint.needsClarification) {
            return Outcome.StillBlocked(
                presentation.blueprint.questions.filter { it.blocking }.map { it.question },
            )
        }
        return Outcome.Proceed(presentation, answers)
    }

    /** Question id that means "may I do this irreversible thing" rather than "what is it". */
    const val APPROVAL_ID = "approval"
}
