// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.knowledge

import com.returngift.agent.agent.exec.micro.AppSurface

/**
 * Turns completed task steps into app knowledge.
 *
 * The agent already knows what it did and whether it worked. Instead of shipping a
 * hand-written file per app, each *successful* action on an app that has no guide
 * (or no search entry) contributes evidence, and enough evidence becomes a learned
 * [AppGuide] row in [AppGuideStore]:
 *
 *   typed + submitted in <app> 3×  →  "search/chat box: type → Enter" row
 *   a guide already exists         →  we only add aliases / search labels
 *
 * Rules that keep it safe:
 *  - **success only** — a failed tap teaches nothing;
 *  - **evidence threshold** — a single lucky run never rewrites knowledge;
 *  - **no invented selector chains** — the learner records surfaces and labels it
 *    actually observed, never resource-ids or coordinates;
 *  - **bounded** — see [AppGuideStore.MAX_ROWS].
 *
 * Pure Kotlin apart from the store seam, so the whole policy is unit-testable.
 */
object AppGuideLearner {

    private const val TAG = "AppGuideLearner"

    /** Successful observations needed before a learned row is written. */
    const val MIN_EVIDENCE = 3

    /** Confidence ladder for learned rows: never above a curated row. */
    const val LEARNED_CONFIDENCE = 0.6

    data class Observation(
        val packageName: String,
        val toolName: String,
        val surface: AppSurface,
        val success: Boolean,
        val label: String = "",
        val typedText: String = "",
        val screenChanged: Boolean = true,
        val now: Long = System.currentTimeMillis(),
    )

    /** Evidence counters per package, kept in memory for the process lifetime. */
    private val evidence = mutableMapOf<String, MutableMap<String, Int>>()

    /**
     * Optional label lookup (the app sets this to InstalledApps). Kept as a seam so
     * the learner stays free of Android imports and unit-testable.
     */
    internal var labelResolver: (String) -> String? = { null }

    /** Clears the evidence counters (tests / "forget app" in Settings). */
    @Synchronized
    fun reset() {
        evidence.clear()
    }

    @Synchronized
    fun evidenceCount(packageName: String, surface: AppSurface): Int =
        evidence[packageName.trim().lowercase()]?.get(surface.name) ?: 0

    /**
     * Record one observation.
     *
     * @return the row written to the store, or null when nothing was learned yet
     */
    fun observe(obs: Observation): AppGuide? {
        if (!obs.success || !obs.screenChanged) return null
        if (obs.packageName.isBlank()) return null
        // Only typing/submitting teaches a surface; taps are too ambiguous.
        val learnedSurface = when (obs.surface) {
            AppSurface.SEARCH_FIELD, AppSurface.CHAT_COMPOSER,
            AppSurface.BROWSER_ADDRESS, AppSurface.EMAIL_COMPOSE,
            -> obs.surface
            else -> return null
        }
        if (obs.typedText.isBlank()) return null

        val pkg = obs.packageName.trim().lowercase()
        val count = synchronized(this) {
            val perSurface = evidence.getOrPut(pkg) { mutableMapOf() }
            val next = (perSurface[learnedSurface.name] ?: 0) + 1
            perSurface[learnedSurface.name] = next
            next
        }

        // Evidence threshold first: a single lucky run never rewrites knowledge.
        if (count < MIN_EVIDENCE) return null

        // A curated row is authoritative for *how* the app works; learning may only
        // add observed evidence (labels/aliases), never replace authored steps.
        val curated = AppUsageGuide.builtinGuides().firstOrNull {
            it.packageName.equals(pkg, ignoreCase = true)
        }
        if (curated != null) return enrich(curated, obs)

        val existingLearned = AppGuideStore.get(pkg)?.takeIf { it.source == GuideSource.LEARNED }
        if (existingLearned != null) return enrich(existingLearned, obs)

        val row = build(pkg, learnedSurface, obs, count)
        AppGuideStore.upsert(row)
        return row
    }

    /** Convenience wrapper used by the agent loop. */
    fun observeStep(
        toolName: String,
        packageName: String?,
        params: Map<String, Any>,
        success: Boolean,
        surface: AppSurface,
        screenChanged: Boolean = true,
    ): AppGuide? {
        if (packageName.isNullOrBlank()) return null
        val text = params["text"]?.toString().orEmpty()
        val label = params["label"]?.toString()
            ?: params["content_desc"]?.toString()
            ?: params["text"]?.toString()
            ?: ""
        return observe(
            Observation(
                packageName = packageName,
                toolName = toolName,
                surface = surface,
                success = success,
                label = label,
                typedText = text,
                screenChanged = screenChanged,
            )
        )
    }

    private fun build(
        pkg: String,
        surface: AppSurface,
        obs: Observation,
        count: Int,
    ): AppGuide {
        val label = AppUsageGuide.forPackage(pkg)?.label
            ?: labelResolver(pkg)
            ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        val tip = when (surface) {
            AppSurface.SEARCH_FIELD ->
                "Search: tap the search control" + labelSuffix(obs.label) +
                    " → type the query → press Enter → open the first result."
            AppSurface.CHAT_COMPOSER ->
                "Message: open the chat" + labelSuffix(obs.label) +
                    " → type → send (Enter or the Send button) → verify the message appears."
            AppSurface.BROWSER_ADDRESS ->
                "Address bar" + labelSuffix(obs.label) + ": type the site/query → press Enter → wait for the page."
            AppSurface.EMAIL_COMPOSE ->
                "Compose" + labelSuffix(obs.label) + ": fill the field → advance → send → verify."
            else -> "Type into the field" + labelSuffix(obs.label) + " → submit → verify the screen changed."
        }
        return AppGuide(
            packageName = pkg,
            label = label,
            surface = surface,
            aliases = listOfNotNull(label.lowercase().takeIf { it.isNotBlank() }),
            tips = listOf(tip),
            source = GuideSource.LEARNED,
            updatedAt = obs.now,
            confidence = LEARNED_CONFIDENCE,
            searchLabels = listOfNotNull(obs.label.trim().takeIf { it.isNotBlank() }),
        )
    }

    /** Existing curated row: add the observed label as evidence, never rewrite tips. */
    private fun enrich(existing: AppGuide, obs: Observation): AppGuide? {
        val label = obs.label.trim()
        if (label.isEmpty()) return null
        if (existing.searchLabels.any { it.equals(label, ignoreCase = true) }) return null
        if (existing.surface != obs.surface && existing.surface != AppSurface.UNKNOWN) return null
        val updated = existing.copy(
            searchLabels = (existing.searchLabels + label).distinct(),
            updatedAt = obs.now,
        )
        // Persist as LEARNED (so it outranks a later connector row) but keep the
        // curated tips: only the evidence we actually observed is added.
        val stored = updated.copy(source = GuideSource.LEARNED, confidence = LEARNED_CONFIDENCE)
        AppGuideStore.upsert(stored)
        return stored
    }

    private fun labelSuffix(label: String): String =
        if (label.isBlank()) "" else " (\"${label.trim().take(40)}\")"
}
