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
    private const val KEY_TS_SUFFIX = "_ts"

    /** "Allow & remember" grants expire after this; re-prompt afterwards. */
    const val REMEMBER_TTL_MS: Long = 60L * 24 * 60 * 60 * 1000  // 60 days

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

    /** Grant timestamps (epoch ms) for TTL expiry; injectable for tests. */
    internal var persistenceGetLong: (String) -> Long = { key ->
        try {
            KVUtils.getLong(key, 0L)
        } catch (e: Exception) {
            XLog.w(TAG, "consent ts read failed for $key", e)
            0L
        }
    }

    internal var persistencePutLong: (String, Long) -> Unit = { key, value ->
        try {
            KVUtils.putLong(key, value)
        } catch (e: Exception) {
            XLog.w(TAG, "consent ts write failed for $key", e)
        }
    }

    /** Wall clock — injectable so TTL tests don't sleep. */
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

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

    /**
     * True only when a remembered grant exists AND is within its TTL. Expired
     * grants are dropped on read so the next task re-prompts.
     */
    fun isRemembered(appLabel: String): Boolean {
        val key = KEY_PREFIX + appLabel.lowercase()
        if (!persistenceGet(key)) return false
        val grantedAt = persistenceGetLong(key + KEY_TS_SUFFIX)
        if (grantedAt <= 0L) return true  // legacy grant without timestamp — keep honoring
        if (nowMs() - grantedAt > REMEMBER_TTL_MS) {
            forget(appLabel)
            XLog.i(TAG, "remembered consent for $appLabel expired (TTL ${REMEMBER_TTL_MS}ms)")
            return false
        }
        return true
    }

    /** Persisted "Allow & remember" decisions — one key per app label, with timestamp. */
    fun remember(appLabel: String) {
        val key = KEY_PREFIX + appLabel.lowercase()
        persistencePut(key, true)
        persistencePutLong(key + KEY_TS_SUFFIX, nowMs())
        XLog.i(TAG, "remembered consent for $appLabel")
    }

    /** Revoke a remembered consent (Settings reset / privacy UI). */
    fun forget(appLabel: String) {
        val key = KEY_PREFIX + appLabel.lowercase()
        persistencePut(key, false)
        persistencePutLong(key + KEY_TS_SUFFIX, 0L)
        XLog.i(TAG, "forgot consent for $appLabel")
    }

    /** All known app labels (for the Settings revocation list). */
    fun knownAppLabels(): List<String> = APP_LABELS.map { it.second }.distinct()

    /** The labels with a currently-active remembered grant (for Settings UI). */
    fun rememberedApps(): List<String> = knownAppLabels().filter { isRemembered(it) }

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

    // ── Dispatch-site check (additive to the pre-loop text gate) ─────────────
    // The pre-loop gate reads the TASK TEXT; the dispatch-site gate reads the
    // actual TOOL CALL, so a personal surface the model reaches mid-task (e.g.
    // "also check my WhatsApp" after a Gmail summary) is gated too.

    /** Tools that open a specific app — package name is in the args. */
    private val APP_TARGETING_TOOLS = setOf("open_app", "switch_app")

    /** Tools that read whatever is on screen — gated on the tracked target app. */
    private val CONTENT_READING_TOOLS = setOf(
        "get_screen_info", "take_screenshot", "find_and_tap", "tap_node",
        "input_text", "long_press",
    )

    /** Android package name → personal-content label (null = not personal). */
    private val PACKAGE_TO_LABEL: Map<String, String> = mapOf(
        "com.google.android.gm" to "Gmail",
        "com.microsoft.office.outlook" to "Outlook",
        "com.whatsapp" to "WhatsApp",
        "org.telegram.messenger" to "Telegram",
        "com.google.android.apps.messaging" to "Messages",
        "com.samsung.android.messaging" to "Messages",
        "com.android.contacts" to "Contacts",
        "com.google.android.contacts" to "Contacts",
        "com.google.android.apps.photos" to "Photos",
        "com.sec.android.gallery3d" to "Photos",
        "com.google.android.calendar" to "Calendar",
        "com.google.android.documentsui" to "Files",
    )

    /** The personal label for a package name, or null. */
    fun labelForPackage(pkg: String?): String? = pkg?.let { PACKAGE_TO_LABEL[it] }

    /**
     * Which personal surface (if any) a dispatched tool call would touch.
     * [currentTargetPackage] is the app the task is currently driving (for
     * content-reading tools that don't name an app in their args).
     */
    fun checkToolTarget(
        toolName: String,
        params: Map<String, Any?>,
        currentTargetPackage: String?,
    ): String? = when {
        toolName in APP_TARGETING_TOOLS ->
            labelForPackage(params["package_name"]?.toString())
        toolName in CONTENT_READING_TOOLS ->
            labelForPackage(currentTargetPackage)
        else -> null
    }
}
