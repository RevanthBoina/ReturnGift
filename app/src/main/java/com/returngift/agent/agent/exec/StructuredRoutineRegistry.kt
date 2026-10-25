// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec

import com.returngift.agent.agent.exec.routines.SocialPostRoutine

/**
 * Registry of structured routines that run on the deterministic executor
 * instead of the open LLM loop. A routine match means the whole task is
 * known-up-front: the controller executes it end-to-end and the AI is only
 * consulted (via the escalation seam) when a selector misses.
 *
 * Routines are **data-driven**: there is no per-app class. A family of tasks
 * ("post <text> on <social app>") is one routine with a profile table, so adding
 * an app is a row in `SocialPostRoutine.profiles`, not a new file.
 */
object StructuredRoutineRegistry {

    data class Match(
        val routineId: String,
        val spec: DeterministicUiExecutor.Spec,
        /** Human label for the clarification prompt ("What should the LinkedIn post say?"). */
        val askPrompt: String = "What should the post say?",
    )

    /** @return the routine to run for [task], or null → normal agent loop. */
    fun match(task: String): Match? {
        val profile = SocialPostRoutine.matchProfile(task) ?: return null
        val routineId = profile.routineId
        val ask = "What should the ${profile.label} post say?"

        val text = SocialPostRoutine.extractPostText(task, profile)
            ?: return Match(
                routineId = routineId,
                // Empty spec = clarification needed (caller asks via ClarificationManager).
                spec = DeterministicUiExecutor.Spec(
                    taskLabel = "${profile.label} post (needs text)",
                    targetPackage = profile.packageName,
                    steps = emptyList(),
                ),
                askPrompt = ask,
            )
        return Match(
            routineId = routineId,
            spec = SocialPostRoutine.buildSpec(text, profile),
            askPrompt = ask,
        )
    }
}
