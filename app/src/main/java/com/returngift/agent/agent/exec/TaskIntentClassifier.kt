// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec

/**
 * Deterministic task-intent gate (pure Kotlin, JVM-unit-testable).
 *
 * The incident this fixes: a general cloud Q&A ("what is X?") entered the
 * device-automation loop, burned observes on ReturnGift's own chat UI, and died
 * at the token ceiling / OmniRoute timeout. The gate runs BEFORE the loop starts:
 * knowledge / vault / research questions never receive observation tooling, so
 * the model cannot UI-scrape its own chat window instead of answering.
 *
 * Precedence: device automation > external AI > vault > web research > knowledge.
 * Device intent requires an explicit device action — "open", "tap", "send … on
 * WhatsApp" — never inferred from a bare question mark.
 */
object TaskIntentClassifier {

    enum class Intent {
        /** Pure Q&A — answer from knowledge; no screen, no device tools. */
        KNOWLEDGE_QA,
        /** Read the user's own vault (kb_read/kb_search). */
        VAULT_QUERY,
        /** Needs fresh public information (web_search/web_fetch). */
        WEB_RESEARCH,
        /** Drive an external AI app/service (ChatGPT, Gemini, …) with a query. */
        EXTERNAL_AI_QUERY,
        /** Operate apps/UI on the device. */
        DEVICE_AUTOMATION,
    }

    data class Result(
        val intent: Intent,
        val reason: String,
    )

    // Verbs that require touching the device UI or device state.
    private val DEVICE_VERBS = Regex(
        "\\b(open|launch|tap|click|swipe|scroll|type into|fill in|install|uninstall|" +
            "enable|disable|turn on|turn off|toggle|set alarm|set a reminder|call|dial|" +
            "text|send (a )?(message|sms|whatsapp|dm)|post|upload|download|screenshot|" +
            "screen ?shot|record|mute|unmute|volume|brightness|wifi|bluetooth|airplane|" +
            "navigate to|go to|compose|reply to|forward|delete (that|this|the) (photo|file|message)|" +
            "like|comment on|share (this|that|it) (on|to|with))\\b"
    )

    // Apps/services that imply UI automation when paired with an action.
    private val EXTERNAL_AI_APPS = Regex(
        "\\b(chatgpt|gpt|gemini|bard|claude|copilot|perplexity|grok|poe|deepseek)\\b"
    )

    private val VAULT_HINTS = Regex(
        "\\b(my (notes?|vault|files?|saved)|vault|knowledge base|kb_|saved note|" +
            "what did i (save|note|write))\\b"
    )

    private val WEB_RESEARCH_HINTS = Regex(
        "\\b(search the web|google|look up|latest|today'?s|news|current (price|weather|score)|" +
            "who won|weather (in|for|today)|stock price)\\b"
    )

    private val KNOWLEDGE_HINTS = Regex(
        "\\b(what is|what'?s|what are|who is|who was|why (is|are|does|do|did)|" +
            "how (does|do|did|much|many|to)\\b|explain|define|difference between|" +
            "meaning of|translate .{0,40} (to|into) (english|hindi|spanish|french|german)|" +
            "summari[sz]e|capital of|calculate|convert \\d)\\b"
    )

    private val QUESTION_MARK = Regex("\\?\\s*$")

    /**
     * Classify [task]. The result is advisory for prompt selection and
     * authoritative for tool gating — KNOWLEDGE_QA/VAULT_QUERY/WEB_RESEARCH tasks
     * get NO observation/device tools.
     */
    fun classify(task: String): Result {
        val t = task.trim()
        val lower = t.lowercase()

        // Device intent requires the sentence to COMMAND a device action — a
        // device verb inside a question ("what does this button do when I tap
        // it?", "what is open source?") is not a request to act.
        val hasDeviceVerb = DEVICE_VERBS.containsMatchIn(lower)
        val startsImperative = Regex(
            "^(please )?(open|launch|tap|click|swipe|scroll|type|fill|install|uninstall|" +
                "enable|disable|turn|toggle|set|call|dial|text|send|post|upload|download|" +
                "screenshot|record|mute|unmute|navigate|go|compose|reply|forward|delete|" +
                "like|comment|share|check)\\b"
        ).containsMatchIn(lower)
        val startsInterrogative = Regex(
            "^(what|what's|who|whom|whose|why|how|when|where|which|is|are|was|were|do|does|did|can|could|would|should)\\b"
        ).containsMatchIn(lower)
        val deviceIntent = startsImperative || (hasDeviceVerb && !startsInterrogative)
        val mentionsExternalAi = EXTERNAL_AI_APPS.containsMatchIn(lower)

        // "ask ChatGPT …", "generate an image with Gemini" → external AI drive.
        if (mentionsExternalAi &&
            (deviceIntent || lower.contains("ask ") || lower.contains("generate") ||
                lower.contains("create") || lower.contains("image") || lower.contains("using"))
        ) {
            return Result(Intent.EXTERNAL_AI_QUERY, "external AI app mention + action")
        }

        // Explicit device action wins over everything else.
        if (deviceIntent) {
            return Result(Intent.DEVICE_AUTOMATION, "device action verb present")
        }

        if (VAULT_HINTS.containsMatchIn(lower)) {
            return Result(Intent.VAULT_QUERY, "vault/notes reference")
        }

        if (WEB_RESEARCH_HINTS.containsMatchIn(lower)) {
            return Result(Intent.WEB_RESEARCH, "fresh/public-information cue")
        }

        if (KNOWLEDGE_HINTS.containsMatchIn(lower) || QUESTION_MARK.containsMatchIn(t)) {
            return Result(Intent.KNOWLEDGE_QA, "question pattern, no device action")
        }

        // Unknown: stay safe — an ambiguous prompt keeps full tools only if it is
        // imperative (command shape); declarative sentences default to knowledge.
        val imperativeShape = Regex("^(please )?(open|send|show|find|get|set|make|create|write|check)\\b")
            .containsMatchIn(lower)
        return if (imperativeShape) {
            Result(Intent.DEVICE_AUTOMATION, "imperative command shape (fallback)")
        } else {
            Result(Intent.KNOWLEDGE_QA, "declarative/question shape (fallback)")
        }
    }
}
