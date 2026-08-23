// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec

import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog

/**
 * Personal-content consent gate (pure decision logic + persisted memory).
 *
 * Reading the user's own emails/messages/photos is a legitimate device task
 * (the classifier routes it to DEVICE_AUTOMATION), but the agent must ask
 * once before the first content read. The user picks:
 *   Allow once  — this task only
 *   Allow & remember — persisted per app label (e.g. "gmail"), never asked again
 *   Cancel — task stops with an honest explanation
 *
 * The persistence and questioner are injectable so the decision logic stays
 * JVM-unit-testable (KVUtils/MMKV is uninitialized under plain JUnit).
 */
object PersonalContentConsentGuard {

    private const val TAG = "PersonalContentConsent"
    private const val KEY_PREFIX = "personal_consent_"

    enum class Decision { ALLOW_ONCE, ALLOW_REMEMBER, CANCEL }

    enum class Status { ALLOWED_ONCE, ALLOWED_REMEMBERED, NO_PERSONAL_CONTENT, DENIED }

    data class Outcome(
        val status: Status,
        val apps: List<String>,
        val question: String? = null,
        val answer: String? = null,
    )

    // UI labels for the three choices — kept in one place so the loop and tests
    // agree on the exact strings ask_user will surface.
    const val CHOICE_ALLOW_ONCE = "Allow once"
    const val CHOICE_ALLOW_REMEMBER = "Allow & remember"
    const val CHOICE_CANCEL = "Cancel"

    val CHOICES: List<String> = listOf(CHOICE_ALLOW_ONCE, CHOICE_ALLOW_REMEMBER, CHOICE_CANCEL)

    /**
     * Content-app labels we can detect from free text, with a friendly display
     * name used in the consent question. Keys are lowercase match tokens.
     */
    private val APP_LABELS: List<Pair<Regex, String>> = listOf(
        Regex("\\b(gmail|google mail)\\b") to "Gmail",
        Regex("\\b(outlook|hotmail)\\b") to "Outlook",
        Regex("\\b(whatsapp)\\b") to "WhatsApp",
        Regex("\\b(telegram)\\b") to "Telegram",
        Regex("\\b(sms|text messages?|messaging app)\\b") to "Messages",
        Regex("\\b(contacts?|address book)\\b") to "Contacts",
        Regex("\\b(photos?|gallery|pictures?)\\b") to "Photos",
        Regex("\\b(calendar|events?)\\b") to "Calendar",
        Regex("\\b(files?|downloads?|documents?)\\b") to "Files",
        // Generic fallbacks — checked after named apps so "Gmail emails"
        // reports Gmail rather than the generic "email" label.
        Regex("\\b(e-?mails?|inbox|mailbox)\\b") to "Email",
        Regex("\\b(messages?|chats?|conversations?|dm)\\b") to "Messages",
    )

    /** Overridable in JVM unit tests (KVUtils needs an initialized MMKV). */
    internal var persistenceGet: (String) -> Boolean = { key ->
        try {
            KVUtils.getBoolean(key, false)
        } catch (e: Exception) {
            XLog.w(TAG, "consent read failed for $key", e)
            false
        }
    }

    internal var persistencePut: (String, Boolean) -> Unit = { key, value ->
        try {
            KVUtils.putBoolean(key, value)
        } catch (e: Exception) {
            XLog.w(TAG, "consent write failed for $key", e)
        }
    }

    /**
     * The distinct content-app labels mentioned in [task], in detection order.
     * Empty when the task does not touch personal content.
     */
    fun detectApps(task: String): List<String> {
        val lower = task.lowercase()
        return APP_LABELS.mapNotNull { (pattern, label) ->
            if (pattern.containsMatchIn(lower)) label else null
        }.distinct()
    }

    fun isRemembered(appLabel: String): Boolean =
        persistenceGet(KEY_PREFIX + appLabel.lowercase())

    /** Persisted "Allow & remember" decisions — one key per app label. */
    fun remember(appLabel: String) {
        persistencePut(KEY_PREFIX + appLabel.lowercase(), true)
        XLog.i(TAG, "remembered consent for $appLabel")
    }

    /** Revoke a remembered consent (Settings reset / future privacy UI). */
    fun forget(appLabel: String) {
        persistencePut(KEY_PREFIX + appLabel.lowercase(), false)
        XLog.i(TAG, "forgot consent for $appLabel")
    }

    /** The question text surfaced to the user for the detected apps. */
    fun buildQuestion(apps: List<String>): String {
        val target = when (apps.size) {
            1 -> apps[0]
            2 -> "${apps[0]} and ${apps[1]}"
            else -> apps.dropLast(1).joinToString(", ") + ", and " + apps.last()
        }
        return "This task reads personal content from $target. May I open it and read it?"
    }

    /** Map an ask_user answer back to a Decision; null = unrecognized answer. */
    fun decisionFor(answer: String): Decision? = when (answer.trim().lowercase()) {
        CHOICE_ALLOW_ONCE.lowercase(), "allow", "yes", "ok" -> Decision.ALLOW_ONCE
        CHOICE_ALLOW_REMEMBER.lowercase(), "always allow", "remember" -> Decision.ALLOW_REMEMBER
        CHOICE_CANCEL.lowercase(), "no", "deny", "stop" -> Decision.CANCEL
        else -> null
    }
}
