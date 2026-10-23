// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.prefs

import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog

/**
 * User-facing behaviour switches for the permission + micro-step machinery.
 *
 * Exposed as toggles in Settings → App Permissions so the user can turn the
 * safeguards on/off without a rebuild:
 *
 *  - **Ask before a new app** — when OFF every app is auto-allowed (the legacy
 *    "default ON" behaviour) and no permission card is shown.
 *  - **Remember choices permanently** — ON persists "Always allow";
 *    OFF makes every grant temporary (current task only).
 *  - **Open the permission card in front** — auto-foreground the card.
 *  - **Notify in the background** — heads-up mirror with the same choices.
 *  - **Enforce mid-task micro-steps** — the type→Enter/Send planner and its
 *    premature-finish veto.
 *
 * Storage is injectable so the logic is unit-testable on a plain JVM (MMKV is
 * not initialised there); production reads/writes MMKV through [KVUtils].
 */
object AgentPreferences {

    private const val TAG = "AgentPreferences"

    const val KEY_ASK_BEFORE_NEW_APP = "agent_pref_ask_before_new_app"
    const val KEY_REMEMBER_APP_CHOICES = "agent_pref_remember_app_choices"
    const val KEY_OPEN_PERMISSION_CARD = "agent_pref_open_permission_card"
    const val KEY_NOTIFY_PERMISSION = "agent_pref_notify_permission"
    const val KEY_ENFORCE_MICRO_STEPS = "agent_pref_enforce_micro_steps"

    const val DEFAULT_ASK_BEFORE_NEW_APP = true
    const val DEFAULT_REMEMBER_APP_CHOICES = true
    const val DEFAULT_OPEN_PERMISSION_CARD = true
    const val DEFAULT_NOTIFY_PERMISSION = true
    const val DEFAULT_ENFORCE_MICRO_STEPS = true

    /** Immutable snapshot for the settings UI. */
    data class Snapshot(
        val askBeforeNewApp: Boolean,
        val rememberAppChoices: Boolean,
        val openPermissionCard: Boolean,
        val notifyPermission: Boolean,
        val enforceMicroSteps: Boolean,
    ) {
        /** "Permanent" only when choices are remembered across tasks. */
        val grantLifetimeLabel: String
            get() = if (rememberAppChoices) "Permanent" else "This task only"
    }

    /** Overridable in JVM unit tests. */
    internal var storageGet: (String, Boolean) -> Boolean = { key, default -> safeGet(key, default) }
    internal var storagePut: (String, Boolean) -> Unit = { key, value -> safePut(key, value) }

    var askBeforeNewApp: Boolean
        get() = read(KEY_ASK_BEFORE_NEW_APP, DEFAULT_ASK_BEFORE_NEW_APP)
        set(value) = write(KEY_ASK_BEFORE_NEW_APP, value)

    var rememberAppChoices: Boolean
        get() = read(KEY_REMEMBER_APP_CHOICES, DEFAULT_REMEMBER_APP_CHOICES)
        set(value) = write(KEY_REMEMBER_APP_CHOICES, value)

    var openPermissionCard: Boolean
        get() = read(KEY_OPEN_PERMISSION_CARD, DEFAULT_OPEN_PERMISSION_CARD)
        set(value) = write(KEY_OPEN_PERMISSION_CARD, value)

    var notifyPermission: Boolean
        get() = read(KEY_NOTIFY_PERMISSION, DEFAULT_NOTIFY_PERMISSION)
        set(value) = write(KEY_NOTIFY_PERMISSION, value)

    var enforceMicroSteps: Boolean
        get() = read(KEY_ENFORCE_MICRO_STEPS, DEFAULT_ENFORCE_MICRO_STEPS)
        set(value) = write(KEY_ENFORCE_MICRO_STEPS, value)

    /** A broken store must never break a task: fall back to the default. */
    private fun read(key: String, default: Boolean): Boolean = try {
        storageGet(key, default)
    } catch (e: Throwable) {
        XLog.w(TAG, "preference read failed for $key", e)
        default
    }

    private fun write(key: String, value: Boolean) {
        try {
            storagePut(key, value)
        } catch (e: Throwable) {
            XLog.w(TAG, "preference write failed for $key", e)
        }
    }

    fun snapshot(): Snapshot = Snapshot(
        askBeforeNewApp = askBeforeNewApp,
        rememberAppChoices = rememberAppChoices,
        openPermissionCard = openPermissionCard,
        notifyPermission = notifyPermission,
        enforceMicroSteps = enforceMicroSteps,
    )

    fun resetToDefaults() {
        askBeforeNewApp = DEFAULT_ASK_BEFORE_NEW_APP
        rememberAppChoices = DEFAULT_REMEMBER_APP_CHOICES
        openPermissionCard = DEFAULT_OPEN_PERMISSION_CARD
        notifyPermission = DEFAULT_NOTIFY_PERMISSION
        enforceMicroSteps = DEFAULT_ENFORCE_MICRO_STEPS
        XLog.i(TAG, "permission/micro-step preferences reset to defaults")
    }

    private fun safeGet(key: String, default: Boolean): Boolean = try {
        if (KVUtils.isInitialized) KVUtils.getBoolean(key, default) else default
    } catch (e: Throwable) {
        XLog.w(TAG, "preference read failed for $key", e)
        default
    }

    private fun safePut(key: String, value: Boolean) {
        try {
            if (KVUtils.isInitialized) KVUtils.putBoolean(key, value)
        } catch (e: Throwable) {
            XLog.w(TAG, "preference write failed for $key", e)
        }
    }
}
