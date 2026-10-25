// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.plan

/**
 * Folds the user's answers back into the request so the plan can be rebuilt.
 *
 * The alternative — mutating a half-built plan with answers — produced exactly the
 * bug this class avoids: a plan that quietly keeps a stale assumption (asking
 * "where?" and then still opening the old app). Re-parsing a *clarified sentence*
 * means the second plan is derived the same way as the first, and every capability
 * benefits from the answer without per-question special cases.
 *
 * Pure Kotlin, so the rewriting rules are unit-tested.
 */
object PlanClarifier {

    /** Answers keyed by [PlanQuestion.id]. */
    fun applyAnswers(
        rawText: String,
        blueprint: TaskBlueprint,
        answers: Map<String, String>,
    ): String {
        if (answers.isEmpty()) return rawText
        var text = rawText.trim()

        // Platform / app: the answer is the app the action happens in.
        answers["platform"]?.takeIf { it.isNotBlank() }?.let { app ->
            text = if (hasTrailingApp(text)) replaceTrailingApp(text, app) else "$text on $app"
        }
        answers["install"]?.takeIf { it.isNotBlank() }?.let { choice ->
            if (choice.startsWith("Use ", ignoreCase = true)) {
                text = replaceTrailingApp(text, choice.removePrefix("Use ").trim())
            }
        }
        answers["payload"]?.takeIf { it.isNotBlank() }?.let { body ->
            text = "$text saying $body"
        }
        answers["recipient"]?.takeIf { it.isNotBlank() }?.let { who ->
            text = text.replaceFirst(Regex("(?i)\\b(message|text|dm|ping)\\b"), "$1 $who")
        }
        answers["entity"]?.takeIf { it.isNotBlank() }?.let { what ->
            text = "$text $what"
        }
        answers["query"]?.takeIf { it.isNotBlank() }?.let { query ->
            text = "$text $query"
        }
        answers["setting"]?.takeIf { it.isNotBlank() }?.let { setting ->
            text = "$text $setting"
        }
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private val TRAILING_APP = Regex("(?i)\\s+(?:on|in|at|via)\\s+[\\w .&'-]+$")

    private fun hasTrailingApp(text: String): Boolean = TRAILING_APP.containsMatchIn(text)

    /**
     * Replace the app named at the end of the sentence ("follow x on oldsapp").
     * Only the trailing clause is touched, so entity names survive.
     */
    private fun replaceTrailingApp(text: String, app: String): String {
        val match = TRAILING_APP.find(text) ?: return "$text on $app"
        return text.substring(0, match.range.first) + " on " + app
    }

    /** True when every blocking question has an answer. */
    fun isAnswered(blueprint: TaskBlueprint, answers: Map<String, String>): Boolean =
        blueprint.questions.filter { it.blocking }.all { answers[it.id]?.isNotBlank() == true }
}
