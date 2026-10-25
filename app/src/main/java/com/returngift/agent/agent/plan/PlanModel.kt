// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.plan

import com.returngift.agent.agent.exec.micro.AppSurface

/**
 * Plan-first model — pure Kotlin data, no Android imports.
 *
 * The agent MUST be able to say what it is about to do before it does it, in the
 * user's own terms:
 *
 * ```
 * 1. Open <app>
 * 2. Search "<entity>"
 * 3. Filter results to Companies
 * 4. Open the official <entity> page
 * 5. Tap Follow
 * 6. Verify the button shows Following
 * ```
 *
 * Nothing here knows any app by name. App labels, packages and surfaces arrive in
 * [PlanContext] from the installed-app census and the app-guide registry, so a new
 * app is a row, never a file (AGENTS.md hard rule #1).
 */

/** One app the device can actually drive, as seen by the planner. */
data class AppRef(
    val label: String,
    val packageName: String,
    val surface: AppSurface? = null,
    val installed: Boolean = true,
    val aliases: List<String> = emptyList(),
    /** What the app can do, derived from its guide row (empty = unknown). */
    val capabilities: Set<SurfaceCapability> = emptySet(),
) {
    /** Every spelling a user might type for this app. */
    val tokens: List<String>
        get() = (listOf(label) + aliases)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
}

/** Coarse capability of an app, derived from its [AppSurface] — never from its name. */
enum class SurfaceCapability {
    /** Has a search box: can find an entity inside the app. */
    SEARCH,

    /** Can publish content (post / tweet / update). */
    PUBLISH,

    /** Can message a person or group. */
    MESSAGE,

    /** Can consume media. */
    MEDIA,

    /** Can browse arbitrary pages (browser). */
    BROWSE,
}

/** Everything the planner is allowed to know about this device. */
data class PlanContext(
    val apps: List<AppRef> = emptyList(),
    /** A browser is available to fall back on when a target app is missing. */
    val browserLabel: String? = "Browser",
) {
    val installed: List<AppRef> get() = apps.filter { it.installed }

    /** Resolve a user-typed app name against labels, aliases and packages. */
    fun resolveApp(text: String?): AppMatch {
        val needle = text?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return AppMatch.None
        val exact = apps.filter { ref ->
            ref.tokens.any { it == needle } || ref.packageName.lowercase() == needle
        }
        if (exact.size == 1) return AppMatch.Found(exact.first())
        if (exact.size > 1) return AppMatch.Ambiguous(exact)

        val loose = apps.filter { ref ->
            ref.tokens.any { it.length >= 3 && (needle.contains(it) || it.contains(needle)) } ||
                (ref.packageName.length >= 3 && needle.contains(ref.packageName.lowercase()))
        }.distinctBy { it.packageName }
        return when (loose.size) {
            0 -> AppMatch.None
            1 -> AppMatch.Found(loose.first())
            else -> AppMatch.Ambiguous(loose)
        }
    }

    /** Installed apps that can do [capability], most capable first. */
    fun appsWith(capability: SurfaceCapability): List<AppRef> =
        apps.filter { capability in it.capabilities || capability == it.surface?.capability() }
}

/** Result of resolving an app name — ambiguity is a first-class outcome. */
sealed class AppMatch {
    data object None : AppMatch()
    data class Found(val app: AppRef) : AppMatch()
    data class Ambiguous(val candidates: List<AppRef>) : AppMatch()
}

/** The capability an [AppSurface] implies; the only bridge from UI shape to plan. */
fun AppSurface.capability(): SurfaceCapability? = when (this) {
    AppSurface.SEARCH_FIELD, AppSurface.PLAY_STORE, AppSurface.MAPS -> SurfaceCapability.SEARCH
    AppSurface.SOCIAL_POST -> SurfaceCapability.PUBLISH
    AppSurface.CHAT_COMPOSER, AppSurface.EMAIL_COMPOSE -> SurfaceCapability.MESSAGE
    AppSurface.BROWSER_ADDRESS -> SurfaceCapability.BROWSE
    AppSurface.VIDEO, AppSurface.MEDIA -> SurfaceCapability.MEDIA
    else -> null
}

/** The verb class of a request. Plan shape is chosen from this, never from an app name. */
enum class PlanCapability {
    /** follow / subscribe to an entity on a platform. */
    FOLLOW_ENTITY,

    /** unfollow / unsubscribe. */
    UNFOLLOW_ENTITY,

    /** find something inside an app and open a result. */
    SEARCH_AND_OPEN,

    /** message a person/group. */
    SEND_MESSAGE,

    /** publish a post/update. */
    POST_UPDATE,

    /** play / watch media. */
    PLAY_MEDIA,

    /** just launch an app. */
    OPEN_ONLY,

    /** flip a device setting (wifi, bluetooth, brightness, …). */
    DEVICE_TOGGLE,

    /** needs fresh public information (web search → read → answer). */
    WEB_RESEARCH,

    /** nothing the phone must do — answer from knowledge. */
    NONE,
}

/** Whether the entity being acted on looks like an organisation or a person. */
enum class EntityKind {
    ORGANIZATION,
    PERSON,
    UNKNOWN,
}

/** Parsed, device-independent understanding of the request. */
data class TaskIntent(
    val capability: PlanCapability,
    val rawText: String,
    /** The thing acted upon ("YouTube"), when the request names one. */
    val entity: String? = null,
    val entityKind: EntityKind = EntityKind.UNKNOWN,
    /** The app the action happens in, when the request names one and it exists. */
    val platform: AppRef? = null,
    /** The app name exactly as the user typed it (kept even when unresolved). */
    val platformName: String? = null,
    /** Free text payload for SEND_MESSAGE / POST_UPDATE / SEARCH_AND_OPEN. */
    val payload: String? = null,
    /** Device setting key for DEVICE_TOGGLE. */
    val setting: String? = null,
    val settingOn: Boolean? = null,
) {
    val needsDevice: Boolean
        get() = capability != PlanCapability.NONE && capability != PlanCapability.WEB_RESEARCH
}

/** One step the user can read and the model can follow. */
data class PlanStep(
    val number: Int,
    val action: PlanAction,
    val detail: String,
    /** True when the app may not show this control; the executor must adapt. */
    val conditional: Boolean = false,
)

enum class PlanAction {
    OPEN_APP,
    SEARCH,
    FILTER,
    OPEN_RESULT,
    INPUT_TEXT,
    TAP,
    SEND,
    VERIFY,
    ASK_USER,
}

/** A question the agent must ask before acting. */
data class PlanQuestion(
    val id: String,
    val question: String,
    val choices: List<String> = emptyList(),
    val allowFreeText: Boolean = true,
    /** True when the plan cannot be executed until answered. */
    val blocking: Boolean = true,
    val reason: String = "",
)

/** Full pre-flight understanding: what to do, what to ask, what could go wrong. */
data class TaskBlueprint(
    val intent: TaskIntent,
    val steps: List<PlanStep> = emptyList(),
    val questions: List<PlanQuestion> = emptyList(),
    /** Assumptions the plan makes (shown to the user, never hidden). */
    val assumptions: List<String> = emptyList(),
    /** True when the plan is only a preview and execution must not start. */
    val previewOnly: Boolean = false,
) {
    val needsClarification: Boolean get() = questions.any { it.blocking }
    val isEmpty: Boolean get() = steps.isEmpty()

    /** One-line chain in the form users ask for: "Open X → Search Y → …". */
    fun chain(): String = steps.joinToString(" \u2192 ") { it.shortLabel() }

    /**
     * The plan as the user reads it in chat. Steps are numbered, questions are
     * listed separately so a blocking question is never buried in prose.
     */
    fun toMarkdown(heading: String = "Plan"): String = buildString {
        if (intent.capability == PlanCapability.NONE) return ""
        append(heading).append(" \u2014 ").append(intent.rawText.trim()).append('\n')
        steps.forEach { step ->
            append(step.number).append(". ").append(step.detail)
            if (step.conditional) append("  _(if shown)_")
            append('\n')
        }
        if (assumptions.isNotEmpty()) {
            append("\nAssumptions: ").append(assumptions.joinToString("; ")).append('\n')
        }
        if (questions.isNotEmpty()) {
            append('\n')
            questions.forEach { q ->
                append("**").append(q.question).append("**")
                if (q.choices.isNotEmpty()) {
                    append(" \u2014 ").append(q.choices.joinToString(" / "))
                }
                append('\n')
            }
        }
    }.trimEnd()

    companion object {
        /** An "answer directly" blueprint: no device work, nothing to confirm. */
        fun direct(text: String): TaskBlueprint =
            TaskBlueprint(intent = TaskIntent(PlanCapability.NONE, text))
    }
}

/** Compact label used in the one-line chain ("Open X → Search “Y” → Tap Follow"). */
fun PlanStep.shortLabel(): String = when (action) {
    PlanAction.OPEN_APP -> detail.trim()
    PlanAction.SEARCH -> "Search ${quoted(detail)}"
    PlanAction.FILTER -> "Filter by ${detail.trim()}"
    PlanAction.OPEN_RESULT -> "Open ${detail.trim()}"
    PlanAction.INPUT_TEXT -> "Type ${quoted(detail)}"
    PlanAction.TAP -> "Tap ${detail.trim()}"
    PlanAction.SEND -> "Send"
    PlanAction.VERIFY -> "Verify ${detail.trim()}"
    PlanAction.ASK_USER -> "Ask you"
}

/** Quote a phrase the way the chat UI shows it, without double-quoting. */
private fun quoted(detail: String): String {
    val trimmed = detail.trim()
    return if (trimmed.startsWith('"') || trimmed.startsWith('\u201c')) trimmed else "\u201c$trimmed\u201d"
}
