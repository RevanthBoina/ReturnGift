// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock

/**
 * Tier 1: Deterministic task parser.
 * Matches user input against regex patterns to resolve tasks that can be
 * handled with a direct Android intent — zero LLM calls.
 *
 * English-only intent vocabulary (decision D2). Design contract lives in
 * docs/specs/tier1-intent-matching.md — see it before changing anything here.
 */
object TaskParser {

    // ── Compiled regex objects (hoisted from per-call creates — TASK 3.1) ─────────
    private val WS_Collapse = Regex("\\s+")
    private val PHONE_TOKEN = Regex("[0-9][0-9\\s\\-+().]*")
    private val EXT_PATTERN = Regex("x\\d+|ext\\.?\\s*\\d*")

    data class ParseResult(
        val action: String,
        val intent: Intent?,
        val toolName: String? = null,
        val toolParams: Map<String, Any>? = null,
        val description: String = ""
    )

    // ── Normalization (single step, applied to every matcher) ──────────────

    private val TRAILING_POLITENESS = listOf(
        " please", " pls", " thank you", " thanks", " thx", " ok", " okay"
    )

    /**
     * Normalize once, before every matcher (fixes A9).
     * trim + lowercase + collapse whitespace + strip trailing politeness + terminal punct.
     */
    internal fun normalize(raw: String): String {
        var s = raw.trim().lowercase()
        s = WS_Collapse.replace(s, " ")
        var changed = true
        while (changed) {
            changed = false
            for (p in TRAILING_POLITENESS) {
                if (s == p) {
                    s = ""
                    changed = true
                } else if (s.endsWith(p)) {
                    s = s.dropLast(p.length).trim()
                    changed = true
                }
            }
        }
        s = s.trimEnd('.', '?', '!')
        return s.trim()
    }

    /**
     * Like [normalize] but preserves the ORIGINAL case of the remaining text, so
     * identity-bearing captures ("Mom", "Girlfriend") keep their capitalization.
     */
    private fun stripPoliteness(raw: String): String {
        var s = raw.trim()
        s = WS_Collapse.replace(s, " ")
        var changed = true
        while (changed) {
            changed = false
            for (p in TRAILING_POLITENESS) {
                if (s == p) {
                    s = ""
                    changed = true
                } else if (s.endsWith(p)) {
                    s = s.dropLast(p.length).trim()
                    changed = true
                }
            }
        }
        return s.trimEnd('.', '?', '!').trim()
    }

    /**
     * Compound guard (A10, D1). Crude substring test on normalized text:
     * ` and ` / ` then ` / ` after ` → true (task must go to the agent loop).
     * Kept deliberately simple and over-broad; corpus pins the wide cases.
     */
    fun isCompound(raw: String): Boolean {
        val n = normalize(raw)
        return n.contains(" and ") || n.contains(" then ") || n.contains(" after ")
    }

    /**
     * Resolve the Tier-1 semantic intent token for [task], or null when the task
     * should fall through to Tier 2/3. This is the single oracle that the golden
     * corpus test asserts against, so it defines the contract.
     */
    fun tier1Intent(raw: String): String? = parse(raw)?.let { semanticIntent(it.action) }

    /**
     * Map a [ParseResult].action to the stable Tier-1 semantic intent vocabulary
     * (call | send_message | sms | alarm | timer | screenshot | back | home |
     * open_url | open_settings | open_app | camera | flashlight). Flashlight sub-actions
     * collapse to a single token; camera stays "camera".
     */
    private fun semanticIntent(action: String): String = when {
        action.startsWith("flashlight") -> "flashlight"
        else -> action
    }

    /**
     * Try to parse a task into a direct action.
     * Returns null if no pattern matches (falls through to Tier 2 / agent loop).
     *
     * The compound guard is order-0 here too: a compound task must never produce a
     * partial Tier-1 intent even if a caller forgets to check isCompound first.
     */
    fun parse(task: String, installedPackages: List<String> = emptyList()): ParseResult? {
        if (isCompound(task)) return null
        val normalized = normalize(task)
        val original = stripPoliteness(task)

        return matchCall(normalized)
            ?: matchSendMessage(normalized, original)
            ?: matchSms(normalized)
            ?: matchAlarm(normalized)
            ?: matchTimer(normalized)
            ?: matchScreenshot(normalized)
            ?: matchFlashlight(normalized)
            ?: matchCamera(normalized)
            ?: matchBackHome(normalized)
            ?: matchOpenUrl(normalized)
            ?: matchOpenSettings(normalized)
            ?: matchOpenApp(normalized)
    }

    // ==================== Pattern Matchers ====================

    /**
     * Numeric eligibility for call/sms (A6): a phone number must contain 3..15
     * digits after stripping punctuation. 1-2 digits (not dialable) and >15 digits
     * (not a real number) fall through. Extensions are dropped (documented
     * limitation, A7): the candidate stops at the first non-numeric separator.
     */
    private fun extractPhoneNumber(text: String): String? {
        val m = PHONE_TOKEN.find(text) ?: return null
        val candidate = m.value
        val digits = candidate.filter(Char::isDigit)
        if (digits.length < 3 || digits.length > 15) return null
        return digits
    }

    // ——— call ———
    private val CALL_VERB = Regex("^(call|phone|ring|dial)(?:\\s+(?:to|the number|the phone))?\\s*(.+)$")

    private fun matchCall(normalized: String): ParseResult? {
        // Start-anchored verb (FIX 2): a non-start verb intentionally falls through.
        val m = CALL_VERB.find(normalized) ?: return null
        val target = m.groupValues[2].trim()
        val number = extractPhoneNumber(target) ?: return null
        return ParseResult(
            action = "call",
            intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")),
            description = "Dialing $number"
        )
    }

    // ——— send_message ———
    private val SEND_MESSAGE_PATTERN = Regex(
        """(?:send|message|text)\s+(.+?)\s+to\s+(.+?)(?:\s+on\s+([\p{L}\p{N} ._-]+))?$""",
        RegexOption.IGNORE_CASE
    )

    /** Bare content phrases that do not carry a real message — must not fake a send. */
    private val VECTOR_CONTENT = setOf(
        "a message", "message", "the message", "text", "a text",
        "sms", "an sms", "the sms", "a note"
    )

    /**
     * Case-preserving send_message matcher. The regex runs against the politeness-stripped
     * ORIGINAL-case string (IGNORE_CASE) so "Mom"/"Girlfriend" keep their capitalization in
     * the tool params; validation (email / vector / contextual / numeric) runs on lowercased
     * groups. This fixes the A9 normalization inconsistency without losing identity casing.
     */
    private fun matchSendMessage(normalized: String, original: String): ParseResult? {
        if (normalized.contains("email")) return null
        val match = SEND_MESSAGE_PATTERN.find(original) ?: return null
        val message = match.groupValues[1].trim().trim('"', '\'')
        val contact = match.groupValues[2].trim().trim('"', '\'')
        val app = canonicalMessagingApp(match.groupValues.getOrNull(3))
        val messageLowerCase = message.lowercase()
        val contactLowerCase = contact.lowercase()

        if (message.isBlank() || contact.isBlank()) return null
        if (contactLowerCase.contains("@")) return null

        // A message body that is only a vector phrase, or has no letters at all
        // (e.g. a bare number), is not something we can confidently send.
        if (messageLowerCase in VECTOR_CONTENT) return null
        if (!message.any { it.isLetter() }) return null

        val contextual = setOf("that", "this", "it", "them", "above", "summary", "token")
        val messageTokens = messageLowerCase.split(WS_Collapse).filter { it.isNotBlank() }
        if (messageTokens.any { it in contextual }) return null

        return ParseResult(
            action = "send_message",
            intent = null,
            toolName = "send_message",
            toolParams = mapOf(
                "contact" to contact,
                "message" to message,
                "app" to app,
            ),
            description = "Sending '$message' to $contact via $app"
        )
    }

    private fun canonicalMessagingApp(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return "WhatsApp"
        return when (value.lowercase()) {
            "wa", "whatsapp", "whats app" -> "WhatsApp"
            "telegram", "tg" -> "Telegram"
            "sms", "message", "messages", "android messages", "google messages" -> "Messages"
            else -> value
        }
    }

    // ——— sms ———
    private val SMS_VERB = Regex(
        "^(?:sms|text|message|send\\s+an?\\s+sms|send\\s+an?\\s+text)\\s+(?:to\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )

    private fun matchSms(normalized: String): ParseResult? {
        val m = SMS_VERB.find(normalized) ?: return null
        val target = m.groupValues[1].trim()
        val numMatch = PHONE_TOKEN.find(target) ?: return null
        val digits = numMatch.value.filter(Char::isDigit)
        if (digits.length < 3 || digits.length > 15) return null
        // Only a pure number opens the compose UI. Text beyond the number token
        // (e.g. "555-0100 to mom") makes the recipient ambiguous → fall through.
        val remainder = target.substring(numMatch.range.last + 1).trim()
        if (remainder.isNotEmpty()) {
            val extOnly = remainder.lowercase().matches(EXT_PATTERN)
            if (!extOnly) return null
        }
        return ParseResult(
            action = "sms",
            intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$digits")),
            description = "Opening SMS to $digits"
        )
    }

    // ——— alarm ———
    private val ALARM_PATTERN = Regex(
        """(?:set|create)?\s*(?:a\s+)?(?:alarm|wake\s*(?:me\s*)?(?:up)?)\s*(?:at|for)?\s*(\d{1,2})\s*(?::\s*)?(\d{2})?\s*(am|pm)?""",
        RegexOption.IGNORE_CASE
    )

    private fun matchAlarm(normalized: String): ParseResult? {
        val match = ALARM_PATTERN.find(normalized) ?: return null
        val hourRaw = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val ampm = match.groupValues[3].lowercase()
        if (hourRaw > 23 || minute > 59) return null
        var hour = hourRaw
        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0

        return ParseResult(
            action = "alarm",
            intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            },
            description = "Setting alarm for ${String.format("%02d:%02d", hour, minute)}"
        )
    }

    // ——— timer ———
    private val TIMER_WORD = Regex("timer|countdown", RegexOption.IGNORE_CASE)
    private val TIMER_DURATION = Regex(
        """(\d+)\s*(second|seconds|sec|minute|minutes|min|hour|hours|hr|hrs|s|m|h)\b""",
        RegexOption.IGNORE_CASE
    )

    private fun matchTimer(normalized: String): ParseResult? {
        if (!TIMER_WORD.containsMatchIn(normalized)) return null
        val m = TIMER_DURATION.find(normalized) ?: return null
        val amount = m.groupValues[1].toIntOrNull() ?: return null
        val unit = m.groupValues[2].lowercase()
        val seconds = when {
            unit.startsWith("h") -> amount * 3600
            unit.startsWith("m") -> amount * 60
            else -> amount
        }

        return ParseResult(
            action = "timer",
            intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            },
            description = "Setting timer for $amount ${m.groupValues[2]}"
        )
    }

    // ——— screenshot ———
    private fun matchScreenshot(normalized: String): ParseResult? {
        if (!normalized.contains("screenshot") && !normalized.contains("screencap")) return null
        return ParseResult(
            action = "screenshot",
            intent = null,
            toolName = "take_screenshot",
            toolParams = emptyMap(),
            description = "Taking a screenshot"
        )
    }

    // ——— flashlight / torch ———
    // A1: adjacency-anchored on/off only. The previous bare `\bon\b` alternative turned the
    // torch ON for ANY sentence containing flashlight/torch plus the word "on"
    // ("find the flashlight on the desk"). The "is" form is descriptive, not a command —
    // "flashlight is off" never routes.
    private val FLASHLIGHT_SUFFIX = Regex("(?:flashlight|torch)\\s+(on|off)\\s*$", RegexOption.IGNORE_CASE)
    private val FLASHLIGHT_VERB = Regex("(?:turn|switch)\\s+(on|off)\\s+(?:the\\s+)?(?:flashlight|torch)\\b", RegexOption.IGNORE_CASE)
    private val FLASHLIGHT_PREFIX = Regex("(on|off)\\s+(?:the\\s+)?(?:flashlight|torch)\\b", RegexOption.IGNORE_CASE)

    private fun matchFlashlight(normalized: String): ParseResult? {
        val hasFlash = normalized.contains("flashlight") || normalized.contains("torch")
        if (!hasFlash) return null
        val bare = normalized == "flashlight" || normalized == "torch"
        val state = FLASHLIGHT_SUFFIX.find(normalized)?.groupValues?.get(1)
            ?: FLASHLIGHT_VERB.find(normalized)?.groupValues?.get(1)
            ?: FLASHLIGHT_PREFIX.find(normalized)?.groupValues?.get(1)
        return when {
            state != null -> flashlightResult(on = state.equals("on", ignoreCase = true))
            bare -> flashlightResult(on = null) // toggle with remembered state
            else -> null // e.g. "dim the flashlight" / "flashlight is off" — not a toggle
        }
    }

    private fun flashlightResult(on: Boolean?): ParseResult = ParseResult(
        action = if (on == true) "flashlight_on" else if (on == false) "flashlight_off" else "flashlight_toggle",
        intent = null,
        toolName = "flashlight",
        toolParams = if (on == null) emptyMap() else mapOf("on" to on),
        description = if (on == null) "Toggling flashlight" else "Turning flashlight ${if (on) "on" else "off"}"
    )

    // ——— camera ———
    private fun matchCamera(normalized: String): ParseResult? {
        if (normalized == "camera") {
            return cameraAction()
        }
        val m = Regex("^(?:open|launch|start)\\s+(?:the\\s+|my\\s+)?camera(?:\\s+app)?$").find(normalized)
            ?: return null
        return cameraAction()
    }

    private fun cameraAction(): ParseResult {
        return ParseResult(
            action = "camera",
            intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            description = "Opening camera"
        )
    }

    // ——— back / home ———
    private val BACK_EXACT = setOf(
        "back", "go back", "press back", "press the back button", "press the back key"
    )
    private val HOME_EXACT = setOf(
        "home", "go home", "press home", "press the home button", "press the home key",
        "go to the home screen", "go to home screen"
    )

    private fun matchBackHome(normalized: String): ParseResult? {
        return when {
            normalized in BACK_EXACT -> ParseResult("back", null, "system_key", mapOf("key" to "back"), "Going back")
            normalized in HOME_EXACT -> ParseResult("home", null, "system_key", mapOf("key" to "home"), "Going home")
            else -> null
        }
    }

    // ——— open_url ———
    private val URL_PATTERN = Regex(
        """(?:open|go\s*to|visit|navigate\s*to)\s+(https?://\S+)""", RegexOption.IGNORE_CASE
    )

    private fun matchOpenUrl(normalized: String): ParseResult? {
        val match = URL_PATTERN.find(normalized) ?: return null
        val url = match.groupValues[1].trim()
        return ParseResult(
            action = "open_url",
            intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            description = "Opening $url"
        )
    }

    // ——— open_settings ———
    private val SETTINGS_KEYWORDS = mapOf(
        "wifi" to "android.settings.WIFI_SETTINGS",
        "bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
        "display" to "android.settings.DISPLAY_SETTINGS",
        "brightness" to "android.settings.DISPLAY_SETTINGS",
        "sound" to "android.settings.SOUND_SETTINGS",
        "volume" to "android.settings.SOUND_SETTINGS",
        "battery" to "android.intent.action.POWER_USAGE_SUMMARY",
        "storage" to "android.settings.INTERNAL_STORAGE_SETTINGS",
        "location" to "android.settings.LOCATION_SOURCE_SETTINGS",
        "airplane" to "android.settings.AIRPLANE_MODE_SETTINGS",
        "notification" to "android.settings.APP_NOTIFICATION_SETTINGS",
        "accessibility" to "android.settings.ACCESSIBILITY_SETTINGS",
    )
    private val OPEN_SETTINGS_VERB = Regex("(?:open|go\\s*to)\\s*(?:the\\s+)?settings")

    private fun matchOpenSettings(normalized: String): ParseResult? {
        // Keyword matches only fire in an explicit settings context — otherwise
        // "change the wifi password" would spuriously open wifi settings.
        if (!normalized.contains("settings")) return null
        for ((keyword, action) in SETTINGS_KEYWORDS) {
            if (normalized.contains(keyword)) {
                return ParseResult(
                    action = "open_settings",
                    intent = Intent(action),
                    description = "Opening $keyword settings"
                )
            }
        }
        if (OPEN_SETTINGS_VERB.containsMatchIn(normalized)) {
            return ParseResult(
                action = "open_settings",
                intent = Intent(android.provider.Settings.ACTION_SETTINGS),
                description = "Opening Settings"
            )
        }
        return null
    }

    // ——— open_app ———
    private val OPEN_APP_PATTERN = Regex("^(open|launch|start)\\s+(?:the\\s+)?(.+)")

    /** Reject bare-domain / URL-like app targets ("open example.com", "open the browser to example.com"). */
    private val BARE_DOMAIN = Regex("[a-z0-9-]+\\.[a-z]{2,}")

    /** Generic action words that are not real app names ("start a countdown"). */
    private val NOT_APP_NAME = setOf(
        "countdown", "timer", "alarm", "stopwatch", "search", "query", "music player"
    )

    private fun matchOpenApp(normalized: String): ParseResult? {
        val match = OPEN_APP_PATTERN.find(normalized) ?: return null
        var appName = match.groupValues[2].trim()
        if (BARE_DOMAIN.containsMatchIn(appName)) return null
        // "open book settings" is not an app launch — settings-like targets fall through.
        if (appName.contains("settings")) return null
        // Strip a leading article to check generic action words.
        val stripped = appName.removePrefix("a ").removePrefix("an ").removePrefix("the ")
        if (stripped in NOT_APP_NAME) return null
        return ParseResult(
            action = "open_app",
            intent = null,
            toolName = "open_app",
            toolParams = mapOf("app_name" to appName),
            description = "Opening $appName"
        )
    }
}
