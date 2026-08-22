// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec

import com.returngift.agent.agent.exec.routines.LinkedInPostRoutine

/**
 * Registry of structured routines that run on the deterministic executor
 * instead of the open LLM loop. A routine match means the whole task is
 * known-up-front: the controller executes it end-to-end and the AI is only
 * consulted (via the escalation seam) when a selector misses.
 */
object StructuredRoutineRegistry {

    data class Match(
        val routineId: String,
        val spec: DeterministicUiExecutor.Spec,
    )

    /** @return the routine to run for [task], or null → normal agent loop. */
    fun match(task: String): Match? {
        if (LinkedInPostRoutine.matches(task)) {
            val text = LinkedInPostRoutine.extractPostText(task) ?: return null
            return Match("linkedin_post", LinkedInPostRoutine.buildSpec(text))
        }
        return null
    }
}
