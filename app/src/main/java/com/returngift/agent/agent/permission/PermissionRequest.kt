// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.permission

/**
 * Pure model for every permission request ReturnGift raises.
 *
 * Two kinds:
 *  - [Kind.APP_ACCESS] — the agent wants to act inside a third-party app
 *    (per-app allow-list, first encounter or a previously disabled app).
 *  - [Kind.SYSTEM_CAPABILITY] — ReturnGift itself needs an Android capability
 *    (accessibility, overlay, notification access, storage, battery…).
 *
 * The UI that shows one of these is [com.returngift.agent.ui.permission.PermissionPromptActivity];
 * the blocking bridge is [PermissionPromptBroker]. Both are driven by this model,
 * which has no Android dependencies so the decision logic is unit-testable.
 */
data class PermissionRequest(
    val id: String,
    val kind: Kind,
    /** Target app package (APP_ACCESS only). */
    val packageName: String = "",
    /** Human-readable app label or capability name. */
    val label: String = "",
    /** Why the agent needs it — shown verbatim on the permission card. */
    val reason: String = "",
    /** [com.returngift.agent.AppRequirement] name for SYSTEM_CAPABILITY requests. */
    val capabilityId: String = "",
) {
    enum class Kind { APP_ACCESS, SYSTEM_CAPABILITY }

    /** True when the request is about acting inside another installed app. */
    val isAppAccess: Boolean get() = kind == Kind.APP_ACCESS

    /** Card title, e.g. "Allow ReturnGift to act in WhatsApp?" */
    fun title(): String = when (kind) {
        Kind.APP_ACCESS ->
            "Allow ReturnGift to act in ${label.ifBlank { packageName }}?"
        Kind.SYSTEM_CAPABILITY ->
            "ReturnGift needs ${label.ifBlank { capabilityId }} permission"
    }

    /** Card body: what will happen and what the choices mean. */
    fun body(): String = buildString {
        if (reason.isNotBlank()) append(reason).append("\n\n")
        when (kind) {
            Kind.APP_ACCESS -> append(
                "\"Allow once\" covers this task only. \"Always allow\" remembers it " +
                    "until you turn it off in Settings → App Permissions."
            )
            Kind.SYSTEM_CAPABILITY -> append(
                "Tap \"Open settings\" to grant it. Android shows the switch on the " +
                    "system page — ReturnGift cannot toggle it for you."
            )
        }
    }

    /** Ordered choice labels exactly as the UI + notifications render them. */
    fun choices(): List<String> = when (kind) {
        Kind.APP_ACCESS -> APP_CHOICES
        Kind.SYSTEM_CAPABILITY -> CAPABILITY_CHOICES
    }

    /** Maps a tapped choice (or typed answer) to a [Decision]; null = unknown. */
    fun decisionFor(choice: String?): Decision? = when (choice?.trim()?.lowercase()) {
        null -> null
        CHOICE_ALLOW_ONCE.lowercase(), "allow", "allow once" -> Decision.ALLOW_ONCE
        CHOICE_ALWAYS_ALLOW.lowercase(), "always allow", "add to allow-list", "remember" ->
            Decision.ALWAYS_ALLOW
        CHOICE_CANCEL.lowercase(), "cancel", "deny", "no", "not now" -> Decision.CANCEL
        CHOICE_OPEN_SETTINGS.lowercase(), "open settings", "grant", "settings" ->
            Decision.OPEN_SETTINGS
        else -> null
    }

    /** Timeout / unknown / dismissed all resolve to the safe answer: don't act. */
    fun safeDefault(): Decision = Decision.CANCEL

    companion object {
        const val CHOICE_ALLOW_ONCE = "Allow once"
        const val CHOICE_ALWAYS_ALLOW = "Always allow"
        const val CHOICE_CANCEL = "Cancel"
        const val CHOICE_OPEN_SETTINGS = "Open settings"

        val APP_CHOICES: List<String> = listOf(CHOICE_ALLOW_ONCE, CHOICE_ALWAYS_ALLOW, CHOICE_CANCEL)
        val CAPABILITY_CHOICES: List<String> = listOf(CHOICE_OPEN_SETTINGS, CHOICE_CANCEL)

        /** Build an app-access request (per-app allow-list). */
        fun forApp(
            packageName: String,
            label: String,
            reason: String = "ReturnGift is about to act inside $label for your task.",
            id: String = java.util.UUID.randomUUID().toString(),
        ): PermissionRequest = PermissionRequest(
            id = id,
            kind = Kind.APP_ACCESS,
            packageName = packageName,
            label = label.ifBlank { packageName },
            reason = reason,
        )

        /** Build a system-capability request. */
        fun forCapability(
            capabilityId: String,
            label: String,
            reason: String = "This permission is required before ReturnGift can continue.",
            id: String = java.util.UUID.randomUUID().toString(),
        ): PermissionRequest = PermissionRequest(
            id = id,
            kind = Kind.SYSTEM_CAPABILITY,
            label = label,
            reason = reason,
            capabilityId = capabilityId,
        )
    }

    enum class Decision {
        /** Proceed for this task run only (never persisted). */
        ALLOW_ONCE,

        /** Proceed and persist the grant (allow-list row / remembered) */
        ALWAYS_ALLOW,

        /** Open the relevant system settings page, then re-check. */
        OPEN_SETTINGS,

        /** Do not act; the tool call is blocked with an honest message. */
        CANCEL,
    }
}
