// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.plan

/**
 * Deterministic request parsing — pure Kotlin, no LLM, no Android.
 *
 * The parser answers one question: *what kind of thing did the user ask for, and
 * which named things does it involve?* It never guesses an app's package and never
 * hard-codes an app name: names are resolved against [PlanContext], which is built
 * from the device's installed-app census plus the guide registry.
 *
 * Ordered by specificity: an explicit "follow X on Y" outranks a bare "open X".
 * A question with no device verb is not a task at all — it is answered directly.
 */
object IntentParser {

    private val FOLLOW = Regex(
        "^\\s*(?:please\\s+)?(follow|subscribe to|start following)\\s+(.+?)" +
            "(?:\\s+(?:on|in|at|via)\\s+(.+?))?\\s*[.!]*\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val UNFOLLOW = Regex(
        "^\\s*(?:please\\s+)?(unfollow|unsubscribe from|stop following)\\s+(.+?)" +
            "(?:\\s+(?:on|in|at|via)\\s+(.+?))?\\s*[.!]*\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val MESSAGE = Regex(
        "^\\s*(?:please\\s+)?(?:message|text|dm|ping|send (?:a |an )?(?:message|text|dm|note) to)\\s+(.+?)" +
            "(?:\\s+(?:on|in|via)\\s+(.+?))?" +
            "(?:\\s+(?:saying|that says|with(?: the)? text|:)\\s*(.+))?\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val POST = Regex(
        "^\\s*(?:please\\s+)?(?:post|publish|share|tweet)\\s+(.+?)" +
            "(?:\\s+(?:on|to|in)\\s+(.+?))?\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val SEARCH = Regex(
        "^\\s*(?:please\\s+)?(?:search|look up|find|look for)\\s+(?:for\\s+)?" +
            "(.+?)\\s+(?:on|in|inside|within)\\s+(.+?)\\s*[.!]*\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val PLAY = Regex(
        "^\\s*(?:please\\s+)?(?:play|watch|listen to)\\s+(.+?)" +
            "(?:\\s+(?:on|in)\\s+(.+?))?\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val OPEN = Regex(
        "^\\s*(?:please\\s+)?(?:open|launch|start)\\s+(.+?)\\s*[.!]*\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val TOGGLE = Regex(
        "^\\s*(?:please\\s+)?(?:turn\\s+(on|off)|switch\\s+(on|off)|enable|disable|toggle)\\s+" +
            "(?:the\\s+)?(.+?)\\s*[.!]*\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val WEB_HINT = Regex(
        "\\b(latest|today'?s|news|current (price|weather|score|rate)|who won|" +
            "weather (in|at|for)|stock price|exchange rate|how much is .* now)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val QUESTION_WORD = Regex(
        "\\b(what|who|why|how|when|where|which|explain|define|difference between|" +
            "meaning of|capital of|calculate|convert)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Strong clause boundaries: a later clause is a *separate* instruction.
     *
     * Only unambiguous separators are listed. A bare "and" is not one — "message
     * John and say hi" is a single instruction, and splitting it would invent a
     * second task.
     */
    private val CLAUSE_BOUNDARY = Regex(
        "\\s*(?:;|,\\s*then\\b|\\bthen\\b|\\band\\s+then\\b|\\bafter\\s+that\\b|\\bafterwards\\b)\\s*",
        RegexOption.IGNORE_CASE,
    )

    /** A clause that starts by forbidding an action does not ask for that action. */
    private val LEADING_NEGATION = Regex(
        "^\\s*(?:please\\s+)?(?:do\\s+not|don'?t|never|stop|avoid|no\\s+need\\s+to)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** "don't forget to X" is a request *to* do X — the negation is inverted. */
    private val NEGATION_INVERTED = Regex("\\b(?:don'?t|do\\s+not)\\s+forget\\b", RegexOption.IGNORE_CASE)

    /** Leading "don't forget to" is dropped so the real verb can anchor the match. */
    private val FORGET_PREFIX = Regex("^\\s*(?:and\\s+)?(?:don'?t|do\\s+not)\\s+forget\\s+(?:to\\s+)?", RegexOption.IGNORE_CASE)

    /** A quoted span, delimiters included. */
    private val QUOTED_SPAN = Regex("\"[^\"]*\"|\u201c[^\u201d]*\u201d|'[^']*'")

    /** "on/to/in/via <app>" at the end of the sentence, outside quotes. */
    private val PLATFORM_PHRASE = Regex(
        "\\b(?:on|in|via|using|through)\\s+([A-Za-z][A-Za-z0-9 .&'-]{1,30}?)\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /** Phrases that mean "show me the plan and stop" instead of executing. */
    private val PREVIEW_HINT = Regex(
        "\\b(show me the plan|what'?s the plan|what is your plan|just the plan|" +
            "plan only|how will you do it|don'?t (do|execute) (it|anything) yet)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Phrases that mean "stop explaining, just act". */
    private val SILENT_HINT = Regex(
        "\\b(just do it|don'?t ask|no need to ask|skip the (plan|questions)|" +
            "don'?t (explain|confirm)|without asking)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Device settings the agent can flip without opening a third-party app. */
    /** Clauses inspected before giving up on finding an actionable one. */
    private const val MAX_CLAUSES = 4

    private val SETTINGS = listOf(
        "wifi", "wi-fi", "bluetooth", "airplane mode", "mobile data", "flashlight",
        "torch", "do not disturb", "dnd", "auto-rotate", "rotation", "hotspot",
        "location", "gps", "nfc",
    )

    fun wantsPreview(text: String): Boolean = PREVIEW_HINT.containsMatchIn(text)

    fun wantsSilence(text: String): Boolean = SILENT_HINT.containsMatchIn(text)

    /**
     * Strip the control phrases so the plan never echoes "just do it" as a step,
     * and drop the connector the removal leaves behind ("show me the plan **to**
     * follow …").
     */
    fun stripControlPhrases(text: String): String = text
        .replace(PREVIEW_HINT, " ")
        .replace(SILENT_HINT, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimStart(':', ',', ';', '-', '\u2014')
        .trim()
        .removePrefix("to ")
        .removePrefix("that ")
        .removePrefix("then ")
        .trim()

    /**
     * Parse [rawText] into a [TaskIntent].
     *
     * A request may hold several instructions ("open YouTube then search cats").
     * The plan describes the **first actionable clause** — the one the agent will do
     * first — while the whole sentence still travels to the model, so nothing is
     * dropped. Clauses that *forbid* an action are skipped rather than inverted:
     * "don't follow them" must never become "follow them".
     *
     * @param ctx device knowledge; app names are resolved here and nowhere else.
     */
    fun parse(rawText: String, ctx: PlanContext): TaskIntent {
        val text = stripControlPhrases(rawText).trim().ifEmpty { rawText.trim() }
        if (text.isEmpty()) return TaskIntent(PlanCapability.NONE, rawText)
        val parts = clauses(text).take(MAX_CLAUSES)
        for (part in parts) {
            // "don't forget to follow X" is a request to follow, so normalise the
            // inverted negation away before the anchored verb patterns see it.
            val normalized = FORGET_PREFIX.replace(part, "").trim().ifEmpty { part }
            if (isForbidden(normalized)) continue
            val intent = parseClause(normalized, rawText, ctx)
            if (intent.capability != PlanCapability.NONE) return intent
        }
        // Nothing actionable up front: the general agent handles the whole sentence
        // (a direct question keeps its entity for the Q&A path).
        return parseClause(text, rawText, ctx)
    }

    /** Split a request at unambiguous clause boundaries. */
    fun clauses(text: String): List<String> = CLAUSE_BOUNDARY.split(text)
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    /** True when this clause *forbids* its action instead of asking for it. */
    fun isForbidden(clause: String): Boolean =
        LEADING_NEGATION.containsMatchIn(clause.replace(NEGATION_INVERTED, " "))

    /** Content of the first quoted span — the message body when the user quoted it. */
    fun firstQuoted(text: String): String? {
        val range = quotedRanges(text).firstOrNull() ?: return null
        return text.substring(range.first, range.last + 1).trim().ifEmpty { null }
    }

    /**
     * Character ranges of quoted spans (delimiters excluded).
     *
     * An apostrophe inside a word (`don't`) is not a quote; treating it as one would
     * mask the rest of the sentence.
     */
    fun quotedRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var index = 0
        while (index < text.length) {
            val open = text[index]
            val close = when (open) {
                '"' -> '"'
                '\u201c' -> '\u201d'
                '\'' -> '\''
                else -> null
            }
            val insideWord = open == '\'' && index > 0 && text[index - 1].isLetter()
            if (close == null || insideWord) {
                index++
                continue
            }
            val end = text.indexOf(close, index + 1)
            if (end <= index + 1) {
                index++
                continue
            }
            ranges += (index + 1) until end
            index = end + 1
        }
        return ranges
    }

    /** [text] with quoted spans blanked out, so a phrase inside quotes is not a target. */
    fun unquoted(text: String): String {
        val builder = StringBuilder(text)
        quotedRanges(text).forEach { range ->
            for (i in range) if (i in builder.indices) builder[i] = ' '
        }
        return builder.toString()
    }

    /** A platform captured by a regex, unless it was actually inside quotes. */
    private fun platformGroup(group: MatchGroup?, text: String): String? {
        if (group == null) return null
        val value = group.value.trim()
        if (value.isEmpty()) return null
        val range = group.range
        // Overlap (not containment): a capture can start or end on a quote mark.
        val quoted = quotedRanges(text).any { range.first <= it.last && range.last >= it.first }
        return value.takeUnless { quoted }
    }

    /** Remove a trailing "on <app>" from a captured body (the target is not content). */
    private fun stripPlatformPhrase(body: String, platformName: String?): String {
        if (platformName.isNullOrBlank()) return body
        val suffix = Regex(
            "(?:^|\\s+)(?:on|to|in|via)\\s+" + Regex.escape(platformName) + "\\s*$",
            RegexOption.IGNORE_CASE,
        )
        return body.replace(suffix, "").trim()
    }

    /** Resolve "… on <app>" from the parts of [text] that are not quoted. */
    private fun platformFromText(text: String, ctx: PlanContext): Pair<AppRef?, String?>? {
        val masked = unquoted(text)
        val match = PLATFORM_PHRASE.find(masked) ?: return null
        val name = match.groupValues[1].trim()
        if (name.isEmpty()) return null
        return (ctx.resolveApp(name) as? AppMatch.Found)?.app to name
    }

    private fun parseClause(text: String, rawText: String, ctx: PlanContext): TaskIntent {

        UNFOLLOW.find(text)?.let { m ->
            return buildEntityIntent(PlanCapability.UNFOLLOW_ENTITY, rawText, text, m, ctx)
        }
        FOLLOW.find(text)?.let { m ->
            return buildEntityIntent(PlanCapability.FOLLOW_ENTITY, rawText, text, m, ctx)
        }
        MESSAGE.find(text)?.let { m ->
            val quoted = firstQuoted(text)
            // "message John \"running late\"" — the recipient is what precedes the
            // quoted body, not the body itself.
            val recipient = cleanEntity(m.groupValues[1].replace(QUOTED_SPAN, " "))
            val platformText = platformGroup(m.groups[2], text)
            val payload = (quoted ?: m.groupValues[3].ifBlank { null })?.let { cleanPayload(it) }
            if (recipient.isNotEmpty() && (platformText != null || payload != null || recipient.length > 2)) {
                return TaskIntent(
                    capability = PlanCapability.SEND_MESSAGE,
                    rawText = rawText,
                    entity = recipient,
                    payload = payload,
                    platform = resolvePlatform(platformText, ctx),
                    platformName = platformText,
                )
            }
        }
        POST.find(text)?.let { m ->
            // The quoted text is the post body; without quotes the regex capture is.
            val quoted = firstQuoted(text)
            var platformText = platformGroup(m.groups[2], text)
            var platform = resolvePlatform(platformText, ctx)
            if (platform == null) {
                platformFromText(text, ctx)?.let { (app, name) ->
                    platform = app
                    platformText = name
                }
            }
            // "post on LinkedIn" captures "on LinkedIn" as the body; that is the
            // target, not the text, so the user must be asked what to post.
            val body = cleanPayload(
                quoted ?: stripPlatformPhrase(m.groupValues[1], platformText)
            )
            if (body.isNotEmpty() || platformText != null) {
                return TaskIntent(
                    capability = PlanCapability.POST_UPDATE,
                    rawText = rawText,
                    payload = body.takeIf { it.isNotEmpty() },
                    platform = platform,
                    platformName = platformText,
                )
            }
        }
        SEARCH.find(text)?.let { m ->
            val query = cleanEntity(m.groupValues[1])
            val platformText = platformGroup(m.groups[2], text)
            return TaskIntent(
                capability = PlanCapability.SEARCH_AND_OPEN,
                rawText = rawText,
                entity = query,
                payload = query,
                platform = resolvePlatform(platformText, ctx),
                platformName = platformText,
            )
        }
        PLAY.find(text)?.let { m ->
            val media = cleanEntity(m.groupValues[1])
            val platformText = platformGroup(m.groups[2], text)
            if (media.isNotEmpty()) {
                return TaskIntent(
                    capability = PlanCapability.PLAY_MEDIA,
                    rawText = rawText,
                    entity = media,
                    payload = media,
                    platform = resolvePlatform(platformText, ctx),
                    platformName = platformText,
                )
            }
        }
        TOGGLE.find(text)?.let { m ->
            val target = cleanEntity(m.groupValues[3]).lowercase()
            val setting = SETTINGS.firstOrNull { target == it || target.contains(it) }
            if (setting != null) {
                val on = when {
                    m.groupValues[1].equals("on", true) || m.groupValues[2].equals("on", true) -> true
                    m.groupValues[1].equals("off", true) || m.groupValues[2].equals("off", true) -> false
                    text.contains("enable", true) || text.contains("turn on", true) -> true
                    text.contains("disable", true) || text.contains("turn off", true) -> false
                    else -> null
                }
                return TaskIntent(
                    capability = PlanCapability.DEVICE_TOGGLE,
                    rawText = rawText,
                    entity = setting,
                    setting = setting,
                    settingOn = on,
                )
            }
        }
        OPEN.find(text)?.let { m ->
            val target = cleanEntity(m.groupValues[1])
            val match = ctx.resolveApp(target)
            val looksLikeSentence = target.split(' ').size > 4
            if (!looksLikeSentence) {
                return TaskIntent(
                    capability = PlanCapability.OPEN_ONLY,
                    rawText = rawText,
                    entity = target,
                    platform = (match as? AppMatch.Found)?.app,
                    platformName = target,
                )
            }
        }

        // Fresh-information questions get a tiny research plan (search → read → answer).
        if (WEB_HINT.containsMatchIn(text)) {
            return TaskIntent(PlanCapability.WEB_RESEARCH, rawText, entity = text)
        }
        return TaskIntent(PlanCapability.NONE, rawText, entity = text.takeIf { QUESTION_WORD.containsMatchIn(it) })
    }

    private fun buildEntityIntent(
        capability: PlanCapability,
        rawText: String,
        text: String,
        m: MatchResult,
        ctx: PlanContext,
    ): TaskIntent {
        val entity = cleanEntity(m.groupValues[2])
        val platformText = platformGroup(m.groups[3], text)
        return TaskIntent(
            capability = capability,
            rawText = rawText,
            entity = entity.ifEmpty { null },
            entityKind = classifyEntity(entity),
            platform = resolvePlatform(platformText, ctx),
            platformName = platformText,
        )
    }

    private fun resolvePlatform(text: String?, ctx: PlanContext): AppRef? {
        val cleaned = text?.let { cleanEntity(it) } ?: return null
        if (cleaned.isEmpty()) return null
        return (ctx.resolveApp(cleaned) as? AppMatch.Found)?.app
    }

    /** Trim articles, possessives and trailing nouns users add out of habit. */
    fun cleanEntity(raw: String): String = raw
        .trim()
        .trim('"', '\u201c', '\u201d', '\'', '.', ',', '!', '?')
        .removePrefix("the ")
        .removeSuffix(" page")
        .removeSuffix(" account")
        .removeSuffix(" profile")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun cleanPayload(raw: String): String = raw
        .trim()
        .removeSurrounding("\"")
        .removeSurrounding("\u201c", "\u201d")
        .removeSurrounding("'")
        .trim()

    /** Words that mark a brand/organisation rather than a human being. */
    private val ORG_MARKERS = listOf(
        "inc", "ltd", "llc", "corp", "gmbh", "tv", "news", "app", "labs", "studio",
        "studios", "media", "tech", "group", "official", "hq", "ai", "io", "com",
        "foundation", "university", "college", "ministry", "government",
    )

    /**
     * Decide whether the entity is an organisation or a person.
     *
     * This drives one visible plan step ("filter to Companies" vs "filter to
     * People"), so it must be conservative: a single token is an organisation
     * (brands are single words far more often than people are), two or three
     * capitalised words with no organisation marker is a person.
     */
    fun classifyEntity(entity: String?): EntityKind {
        val value = entity?.trim().orEmpty()
        if (value.isEmpty()) return EntityKind.UNKNOWN
        val words = value.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val lower = value.lowercase()
        if (ORG_MARKERS.any { marker -> lower == marker || lower.endsWith(" $marker") }) {
            return EntityKind.ORGANIZATION
        }
        if (words.size == 1) return EntityKind.ORGANIZATION
        val capitalised = words.all { it.first().isUpperCase() }
        return if (capitalised && words.size in 2..3) EntityKind.PERSON else EntityKind.ORGANIZATION
    }
}
