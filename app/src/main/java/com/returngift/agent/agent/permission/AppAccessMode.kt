// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.permission

/**
 * Per-app access policy (pure Kotlin — no Android dependency).
 *
 *  - [ALWAYS]  permanent grant: the agent never asks again for this app
 *  - [ASK]     temporary: the permission card is shown once per task
 *  - [BLOCKED] the agent never acts in this app (no prompt, hard block)
 *
 * Stored by `AppAllowListStore` and honoured by [AppAccessGate] /
 * [AppAccessPolicy]; users set it per app in Settings → App permissions.
 */
enum class AppAccessMode(val storageValue: String, val uiLabel: String) {
    ALWAYS("always", "Always allow"),
    ASK("ask", "Ask"),
    BLOCKED("blocked", "Blocked");

    companion object {
        /** Parse a stored value; unknown values fall back to the safe [ASK]. */
        fun fromStorage(value: String?): AppAccessMode =
            entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) } ?: ASK
    }
}
