// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.plan

/**
 * Turns a [TaskIntent] into a readable, executable [TaskBlueprint].
 *
 * Every plan shape below is expressed in *capabilities* (open an app, search,
 * filter, open a result, act, verify) — never in app names. The app label and the
 * kind of filter come from [PlanContext] and from the intent's entity, so
 * "follow … on …" produces the same six steps for any platform, and a platform the
 * user has not installed produces a question instead of a broken plan.
 *
 * Deterministic on purpose: the plan is available before any model call, works on
 * the local model path, costs no tokens, and can be unit-tested.
 */
object PlanComposer {

    /** Steps longer than this are a sign the parser swallowed too much text. */
    private const val MAX_STEPS = 8

    fun compose(intent: TaskIntent, ctx: PlanContext): TaskBlueprint = when (intent.capability) {
        PlanCapability.FOLLOW_ENTITY -> follow(intent, ctx, following = true)
        PlanCapability.UNFOLLOW_ENTITY -> follow(intent, ctx, following = false)
        PlanCapability.SEND_MESSAGE -> message(intent, ctx)
        PlanCapability.POST_UPDATE -> post(intent, ctx)
        PlanCapability.SEARCH_AND_OPEN -> search(intent, ctx)
        PlanCapability.PLAY_MEDIA -> play(intent, ctx)
        PlanCapability.OPEN_ONLY -> openOnly(intent, ctx)
        PlanCapability.DEVICE_TOGGLE -> toggle(intent)
        PlanCapability.WEB_RESEARCH -> webResearch(intent)
        PlanCapability.NONE -> TaskBlueprint.direct(intent.rawText)
    }.let { blueprint ->
        if (blueprint.steps.size > MAX_STEPS) {
            blueprint.copy(steps = blueprint.steps.take(MAX_STEPS))
        } else {
            blueprint
        }
    }

    // ---------------------------------------------------------------- follow

    private fun follow(intent: TaskIntent, ctx: PlanContext, following: Boolean): TaskBlueprint {
        val verb = if (following) "Follow" else "Unfollow"
        val verbLower = if (following) "follow" else "unfollow"
        val questions = mutableListOf<PlanQuestion>()

        if (intent.entity.isNullOrBlank()) {
            questions += PlanQuestion(
                id = "entity",
                question = "Who or what should I $verbLower?",
                reason = "no target named",
            )
            return TaskBlueprint(intent, questions = questions)
        }

        val entity = entityLabel(intent.entity, ctx)
        val platform = intent.platform
        if (platform == null) {
            val choices = followers(ctx).map { it.label }.distinct().take(4).toMutableList()
            ctx.browserLabel?.takeIf { choices.isNotEmpty() }?.let { if (it !in choices) choices += it }
            questions += PlanQuestion(
                id = "platform",
                question = "Where should I $verbLower $entity?",
                choices = choices,
                allowFreeText = choices.isEmpty(),
                reason = "the request does not name an app I can drive",
            )
            return TaskBlueprint(intent, questions = questions)
        }

        val kindLabel = when (intent.entityKind) {
            EntityKind.PERSON -> "People"
            EntityKind.ORGANIZATION -> "Companies"
            EntityKind.UNKNOWN -> null
        }

        // Asking is better than silently browsing a missing app on the user's behalf.
        if (!platform.installed) {
            val browser = ctx.browserLabel
            val fallbackSteps = if (browser != null) {
                listOf(
                    step(1, PlanAction.OPEN_APP, "Open $browser"),
                    step(2, PlanAction.SEARCH, "$entity on ${platform.label}"),
                    step(3, PlanAction.OPEN_RESULT, "the official $entity page"),
                    step(4, PlanAction.TAP, verb),
                    step(5, PlanAction.VERIFY, "the button shows ${if (following) "Following" else "Follow"}"),
                )
            } else {
                emptyList()
            }
            questions += PlanQuestion(
                id = "install",
                question = "${platform.label} is not installed on this device. " +
                    (browser?.let { "Use $it instead?" } ?: "Install it first?"),
                choices = if (browser != null) listOf("Use $browser", "Cancel") else listOf("Install", "Cancel"),
                allowFreeText = false,
                reason = "target app missing",
            )
            return TaskBlueprint(
                intent = intent,
                steps = fallbackSteps,
                questions = questions,
                assumptions = if (browser != null) {
                    listOf("The plan above searches $browser for the $platform.label page")
                } else {
                    listOf("Nothing will run until $platform.label is installed")
                },
            )
        }

        val steps = buildList {
            add(step(1, PlanAction.OPEN_APP, "Open ${platform.label}"))
            add(step(2, PlanAction.SEARCH, entity))
            if (kindLabel != null) add(step(3, PlanAction.FILTER, kindLabel))
            add(
                step(
                    if (kindLabel != null) 4 else 3,
                    PlanAction.OPEN_RESULT,
                    "the official $entity page",
                )
            )
            add(step(if (kindLabel != null) 5 else 4, PlanAction.TAP, verb))
            add(
                step(
                    if (kindLabel != null) 6 else 5,
                    PlanAction.VERIFY,
                    "the button shows ${if (following) "Following" else "Follow"}",
                )
            )
        }

        val assumptions = buildList {
            if (kindLabel != null) add("results can be filtered to $kindLabel")
            add("the first matching result is ${entity}'s own page, not a fan page")
        }

        return TaskBlueprint(
            intent = intent,
            steps = steps,
            questions = questions,
            assumptions = assumptions,
        )
    }

    // --------------------------------------------------------------- message

    private fun message(intent: TaskIntent, ctx: PlanContext): TaskBlueprint {
        val questions = mutableListOf<PlanQuestion>()
        val recipient = intent.entity
        val platform = intent.platform

        if (platform == null) {
            val choices = ctx.appsWith(SurfaceCapability.MESSAGE).map { it.label }.distinct().take(4)
            questions += PlanQuestion(
                id = "platform",
                question = "Which app should I message $recipient from?",
                choices = choices,
                allowFreeText = choices.isEmpty(),
                reason = "no messaging app named",
            )
        }
        if (recipient.isNullOrBlank()) {
            questions += PlanQuestion(
                id = "recipient",
                question = "Who should I message?",
                reason = "no recipient named",
            )
        }
        if (intent.payload.isNullOrBlank()) {
            questions += PlanQuestion(
                id = "payload",
                question = "What should the message say?",
                reason = "no message text given",
            )
        }
        if (questions.isNotEmpty()) return TaskBlueprint(intent, questions = questions)

        val steps = buildList {
            add(step(1, PlanAction.OPEN_APP, "Open ${platform!!.label}"))
            add(step(2, PlanAction.SEARCH, recipient!!))
            add(step(3, PlanAction.OPEN_RESULT, "the chat with $recipient"))
            add(step(4, PlanAction.INPUT_TEXT, intent.payload!!))
            add(step(5, PlanAction.SEND, "Send"))
            add(step(6, PlanAction.VERIFY, "the message appears in the thread"))
        }
        return TaskBlueprint(intent, steps, assumptions = listOf("the recipient is an existing contact"))
    }

    // ------------------------------------------------------------------ post

    private fun post(intent: TaskIntent, ctx: PlanContext): TaskBlueprint {
        val questions = mutableListOf<PlanQuestion>()
        val platform = intent.platform
        if (platform == null) {
            val choices = ctx.appsWith(SurfaceCapability.PUBLISH).map { it.label }.distinct().take(4)
            questions += PlanQuestion(
                id = "platform",
                question = "Where should I post this?",
                choices = choices,
                allowFreeText = choices.isEmpty(),
                reason = "no publishing app named",
            )
        }
        if (intent.payload.isNullOrBlank()) {
            questions += PlanQuestion(id = "payload", question = "What should the post say?")
        }
        if (questions.isNotEmpty()) return TaskBlueprint(intent, questions = questions)

        val steps = buildList {
            add(step(1, PlanAction.OPEN_APP, "Open ${platform!!.label}"))
            add(step(2, PlanAction.TAP, "the compose button"))
            add(step(3, PlanAction.INPUT_TEXT, intent.payload!!))
            add(step(4, PlanAction.TAP, "Post"))
            add(step(5, PlanAction.VERIFY, "the post appears at the top of the feed"))
        }
        return TaskBlueprint(intent, steps)
    }

    // ---------------------------------------------------------------- search

    private fun search(intent: TaskIntent, ctx: PlanContext): TaskBlueprint {
        val questions = mutableListOf<PlanQuestion>()
        val platform = intent.platform
        val query = intent.payload ?: intent.entity
        if (platform == null) {
            val choices = ctx.appsWith(SurfaceCapability.SEARCH).map { it.label }.distinct().take(4)
            questions += PlanQuestion(
                id = "platform",
                question = "Which app should I search in?",
                choices = choices,
                allowFreeText = choices.isEmpty(),
            )
        }
        if (query.isNullOrBlank()) {
            questions += PlanQuestion(id = "query", question = "What should I search for?")
        }
        if (questions.isNotEmpty()) return TaskBlueprint(intent, questions = questions)

        val steps = listOf(
            step(1, PlanAction.OPEN_APP, "Open ${platform!!.label}"),
            step(2, PlanAction.SEARCH, query!!),
            step(3, PlanAction.OPEN_RESULT, "the best match"),
            step(4, PlanAction.VERIFY, "the result opened"),
        )
        return TaskBlueprint(intent, steps)
    }

    // ------------------------------------------------------------------ play

    private fun play(intent: TaskIntent, ctx: PlanContext): TaskBlueprint {
        val platform = intent.platform
        val media = intent.payload ?: intent.entity
        val questions = mutableListOf<PlanQuestion>()
        if (platform == null) {
            val choices = ctx.appsWith(SurfaceCapability.MEDIA).map { it.label }.distinct().take(4)
            questions += PlanQuestion(
                id = "platform",
                question = "Where should I play $media?",
                choices = choices,
                allowFreeText = choices.isEmpty(),
            )
        }
        if (media.isNullOrBlank()) questions += PlanQuestion(id = "entity", question = "What should I play?")
        if (questions.isNotEmpty()) return TaskBlueprint(intent, questions = questions)

        val steps = listOf(
            step(1, PlanAction.OPEN_APP, "Open ${platform!!.label}"),
            step(2, PlanAction.SEARCH, media!!),
            step(3, PlanAction.TAP, "the first result"),
            step(4, PlanAction.VERIFY, "playback started"),
        )
        return TaskBlueprint(intent, steps)
    }

    // -------------------------------------------------------------- open only

    private fun openOnly(intent: TaskIntent, ctx: PlanContext): TaskBlueprint {
        val platform = intent.platform
        if (platform == null) {
            val choices = ctx.installed.map { it.label }.distinct().take(6)
            return TaskBlueprint(
                intent = intent,
                questions = listOf(
                    PlanQuestion(
                        id = "platform",
                        question = "Which app should I open?",
                        choices = choices,
                        allowFreeText = choices.isEmpty(),
                        reason = "\"${intent.platformName ?: intent.entity}\" is not an app on this device",
                    )
                ),
            )
        }
        if (!platform.installed) {
            return TaskBlueprint(
                intent = intent,
                questions = listOf(
                    PlanQuestion(
                        id = "install",
                        question = "${platform.label} is not installed on this device.",
                        choices = listOf("Cancel"),
                        allowFreeText = false,
                    )
                ),
            )
        }
        // One step: launching an app is not a plan, and a "plan" for it is noise.
        return TaskBlueprint(
            intent = intent,
            steps = listOf(step(1, PlanAction.OPEN_APP, "Open ${platform.label}")),
        )
    }

    // ---------------------------------------------------------------- toggle

    private fun toggle(intent: TaskIntent): TaskBlueprint {
        val setting = intent.setting ?: intent.entity ?: return TaskBlueprint(
            intent,
            questions = listOf(PlanQuestion(id = "setting", question = "Which setting should I change?")),
        )
        val state = when (intent.settingOn) {
            true -> "on"
            false -> "off"
            null -> "the opposite of its current state"
        }
        return TaskBlueprint(
            intent = intent,
            steps = listOf(
                step(1, PlanAction.TAP, "the Quick Settings panel"),
                step(2, PlanAction.TAP, "$setting ${if (state == "on" || state == "off") "to turn it $state" else "to flip it"}"),
                step(3, PlanAction.VERIFY, "$setting is $state"),
            ),
        )
    }

    // --------------------------------------------------------- web research

    private fun webResearch(intent: TaskIntent): TaskBlueprint {
        val query = intent.entity?.takeIf { it.isNotBlank() } ?: intent.rawText
        return TaskBlueprint(
            intent = intent,
            steps = listOf(
                step(1, PlanAction.SEARCH, "the web for $query"),
                step(2, PlanAction.OPEN_RESULT, "the most authoritative result"),
                step(3, PlanAction.VERIFY, "the answer comes from the fetched page"),
            ),
            assumptions = listOf("I'll answer from the page I actually read and name the source"),
        )
    }

    /**
     * Prefer the app registry's canonical spelling when the entity is itself an app
     * ("youtube" → "YouTube"), so the plan matches what the user sees on screen.
     * Unknown entities keep the user's own spelling — the planner invents nothing.
     */
    fun entityLabel(entity: String?, ctx: PlanContext): String {
        val raw = entity?.trim().orEmpty()
        if (raw.isEmpty()) return raw
        return (ctx.resolveApp(raw) as? AppMatch.Found)?.app?.label ?: raw
    }

    /**
     * Apps where "follow" is a real operation. Ordered by how likely each surface
     * is to host an entity page: publishing apps first, then searchable apps, then
     * the browser. Never a hard-coded app list.
     */
    fun followers(ctx: PlanContext): List<AppRef> {
        val order = listOf(SurfaceCapability.PUBLISH, SurfaceCapability.SEARCH, SurfaceCapability.BROWSE)
        return order.flatMap { capability -> ctx.appsWith(capability) }.distinctBy { it.packageName }
    }

    private fun step(number: Int, action: PlanAction, detail: String, conditional: Boolean = false) =
        PlanStep(number, action, detail, conditional)
}
