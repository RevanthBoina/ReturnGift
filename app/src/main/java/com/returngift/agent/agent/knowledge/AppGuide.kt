// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.knowledge

import com.returngift.agent.agent.exec.micro.AppSurface

/**
 * One app's usage guide — a *data* record, never a per-app class or file.
 *
 * Guiding principle for this repo: **no code file is dedicated to a single app.**
 * App knowledge lives here as rows and arrives from three interchangeable sources,
 * highest precedence last:
 *
 *  1. [GuideSource.BUILTIN]   — compiled-in rows maintained with the repo
 *  2. [GuideSource.CONNECTOR] — pushed by an MCP connector / knowledge service
 *  3. [GuideSource.LEARNED]   — distilled from tasks the agent actually completed
 *
 * Adding an app never means adding a file: it means adding a row (built-in), or
 * letting a task/connector produce one at runtime.
 *
 * @param packageName      exact Android package (case-insensitive compare)
 * @param label            user-facing app name
 * @param surface          semantic surface that drives the micro-step planner
 * @param aliases          lower-case tokens a user may type instead of the label
 * @param tips             ordered, concrete steps the model should follow
 * @param source           where this row came from (see above)
 * @param updatedAt        epoch ms; the newest row wins a conflict
 * @param confidence       0..1; learned rows start below built-in ones
 * @param searchLabels     UI labels that open this app's search box (may be empty)
 */
data class AppGuide(
    val packageName: String,
    val label: String,
    val surface: AppSurface,
    val aliases: List<String> = emptyList(),
    val tips: List<String> = emptyList(),
    val source: GuideSource = GuideSource.BUILTIN,
    val updatedAt: Long = 0L,
    val confidence: Double = 1.0,
    val searchLabels: List<String> = emptyList(),
) {
    /** One-line hint for the settings row / permission card. */
    val shortHint: String get() = tips.firstOrNull().orEmpty()

    /** True when this row may override [other] in a merge. */
    fun beats(other: AppGuide): Boolean {
        if (source != other.source) {
            // LEARNED > CONNECTOR > BUILTIN: the closer to the device, the more current.
            return source.rank > other.source.rank
        }
        if (updatedAt != other.updatedAt) return updatedAt > other.updatedAt
        return confidence > other.confidence
    }

    /** Same app, different source — keep the winning payload but merge aliases. */
    fun mergedWith(other: AppGuide): AppGuide {
        val winner = if (beats(other)) this else other
        val loser = if (winner === this) other else this
        val mergedAliases = (winner.aliases + loser.aliases)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
        val mergedLabels = (winner.searchLabels + loser.searchLabels)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return winner.copy(aliases = mergedAliases, searchLabels = mergedLabels)
    }

    /** Compact, prompt-safe identity of the row. */
    fun identity(): String = packageName.trim().lowercase()
}

/** Provenance of an [AppGuide] row; [rank] decides merge precedence. */
enum class GuideSource(val rank: Int) {
    /** Shipped with the repo. */
    BUILTIN(0),

    /** Delivered by an MCP connector / remote knowledge service. */
    CONNECTOR(1),

    /** Distilled on-device from completed tasks. */
    LEARNED(2),
    ;

    companion object {
        fun fromStorage(value: String?): GuideSource =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: BUILTIN
    }
}
