// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.permission

import android.content.Context
import android.content.pm.PackageManager
import com.returngift.agent.agent.AppAllowListStore
import com.returngift.agent.agent.knowledge.AppUsageGuide
import com.returngift.agent.agent.prefs.AgentPreferences
import com.returngift.agent.utils.XLog
import java.util.Collections

/**
 * Single decision point for "may the agent act inside this app?".
 *
 * Both agent paths go through here so the answer can never diverge:
 *  - the LLM loop (`AllowListToolGate.check`)
 *  - Tier-1 direct tools (`AppAllowListGuard.checkAndRecord`)
 *
 * Resolution order (first match wins), driven by the user's toggles in
 * Settings → App Permissions ([AgentPreferences]):
 *
 *  1. per-app mode ALWAYS  → allowed, no prompt
 *  2. per-app mode BLOCKED → blocked, no prompt (the user said never)
 *  3. "Allow once" grant for this task → allowed
 *  4. ask-before-new-app toggle OFF → recorded and allowed (legacy behaviour)
 *  5. otherwise → open the permission card and wait for the answer
 *     (remember-choices OFF downgrades "Always allow" to this task only)
 */
object AppAccessGate {

    private const val TAG = "AppAccessGate"

    /** "Allow once" grants for the current task run (process memory only). */
    private val onceGrants: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())

    enum class Decision { ALLOWED, ALLOWED_ONCE, BLOCKED, BLOCKED_BY_SETTING }

    data class Result(
        val decision: Decision,
        val packageName: String,
        val label: String,
        val firstEncounter: Boolean = false,
        /** User-facing explanation when the call must be blocked. */
        val blockMessage: String? = null,
        /** True when the decision was reached without asking the user. */
        val automatic: Boolean = false,
    ) {
        val allowed: Boolean
            get() = decision == Decision.ALLOWED || decision == Decision.ALLOWED_ONCE
    }

    fun resetTaskGrants() {
        onceGrants.clear()
    }

    /** Packages currently granted for this task by "Allow once". */
    fun taskGrants(): Set<String> = onceGrants.toSet()

    /**
     * Evaluate [packageName]. May park the calling thread on the permission card —
     * never call this on the main thread.
     */
    fun evaluate(context: Context, packageName: String, reason: String? = null): Result {
        if (packageName.isBlank()) return Result(Decision.ALLOWED, packageName, packageName, automatic = true)

        val store = AppAllowListStore.getInstance(context)
        val label = resolveLabel(context, packageName)
        val mode = store.modeFor(packageName)
        val firstEncounter = mode == null
        val pre = AppAccessPolicy.preCheck(
            mode = mode,
            grantedOnce = packageName in onceGrants,
            askBeforeNewApp = AgentPreferences.askBeforeNewApp,
        )

        when (pre.step) {
            AppAccessPolicy.Step.ALLOW -> {
                if (firstEncounter) {
                    // Record the encounter so the app shows up in Settings, but keep
                    // the ASK policy so switching the toggle back on asks next time.
                    store.touchApp(packageName, label, AppAccessMode.ASK)
                }
                XLog.i(TAG, "allowed $packageName (${pre.reason})")
                return Result(
                    decision = if (pre.reason.contains("this task")) Decision.ALLOWED_ONCE else Decision.ALLOWED,
                    packageName = packageName,
                    label = label,
                    firstEncounter = firstEncounter,
                    automatic = true,
                )
            }
            AppAccessPolicy.Step.BLOCK -> {
                XLog.i(TAG, "$packageName blocked (${pre.reason}) — no prompt")
                return Result(
                    decision = Decision.BLOCKED_BY_SETTING,
                    packageName = packageName,
                    label = label,
                    blockMessage = blockedMessage(label),
                    automatic = true,
                )
            }
            AppAccessPolicy.Step.ASK -> Unit // fall through to the card
        }

        val request = PermissionRequest.forApp(
            packageName = packageName,
            label = label,
            reason = reason ?: defaultReason(packageName, label, firstEncounter),
        )
        XLog.i(TAG, "asking the user for access to $packageName (${pre.reason})")
        val choice = PermissionPromptBroker.request(request)
        val outcome = AppAccessPolicy.applyDecision(choice, AgentPreferences.rememberAppChoices)

        outcome.persistMode?.let { persist ->
            if (choice == PermissionRequest.Decision.ALWAYS_ALLOW && outcome.taskOnly) {
                store.touchApp(packageName, label, persist)
            } else {
                store.setMode(packageName, persist, label)
            }
        }
        if (outcome.taskOnly && outcome.allow) onceGrants.add(packageName)

        XLog.i(TAG, "access to $packageName: ${outcome.reason} (allow=${outcome.allow})")
        return if (outcome.allow) {
            Result(
                decision = if (outcome.taskOnly) Decision.ALLOWED_ONCE else Decision.ALLOWED,
                packageName = packageName,
                label = label,
                firstEncounter = firstEncounter,
            )
        } else {
            Result(
                decision = Decision.BLOCKED,
                packageName = packageName,
                label = label,
                firstEncounter = firstEncounter,
                blockMessage = blockedMessage(label),
            )
        }
    }

    /** Human-readable label for a package (falls back to the package name). */
    fun resolveLabel(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            AppUsageGuide.forPackage(packageName)?.label ?: packageName
        }
    }

    private fun defaultReason(packageName: String, label: String, firstEncounter: Boolean): String {
        val how = AppUsageGuide.forPackage(packageName)?.shortHint
        return buildString {
            if (firstEncounter) {
                append("ReturnGift is about to act inside \"$label\" for the first time.")
            } else {
                append("\"$label\" is set to ask before every task.")
            }
            if (!how.isNullOrBlank()) append(" It will: ").append(how)
        }
    }

    private fun blockedMessage(label: String): String =
        "Action blocked: permission to act in \"$label\" was not granted. " +
            "Tap Allow once or Always allow on the ReturnGift permission card, " +
            "or set the app to Always allow in Settings → App Permissions, then try again."
}
