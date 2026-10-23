// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.permission

/**
 * Pure decision rules for per-app access (no Android, no I/O).
 *
 * Kept separate from [AppAccessGate] so every branch — including the user's
 * toggles — is unit-tested on a plain JVM.
 */
object AppAccessPolicy {

    /** What should happen before the tool runs. */
    enum class Step { ALLOW, BLOCK, ASK }

    data class PreCheck(val step: Step, val reason: String)

    /** What to persist (and whether to grant for this task only) after an answer. */
    data class Outcome(
        val allow: Boolean,
        /** Mode to write for the app, or null when nothing should be written. */
        val persistMode: AppAccessMode?,
        /** True when the grant must not outlive the current task. */
        val taskOnly: Boolean,
        val reason: String,
    )

    /**
     * Decide whether the user has to be asked.
     *
     * @param mode            stored policy, null = never seen before
     * @param grantedOnce     package already granted for this task run
     * @param askBeforeNewApp the "Ask before a new app" toggle
     */
    fun preCheck(
        mode: AppAccessMode?,
        grantedOnce: Boolean,
        askBeforeNewApp: Boolean,
    ): PreCheck = when {
        mode == AppAccessMode.ALWAYS ->
            PreCheck(Step.ALLOW, "always-allowed app")
        mode == AppAccessMode.BLOCKED ->
            PreCheck(Step.BLOCK, "blocked by the user")
        grantedOnce ->
            PreCheck(Step.ALLOW, "already granted for this task")
        !askBeforeNewApp ->
            PreCheck(Step.ALLOW, "ask-before-new-app is off")
        else ->
            PreCheck(Step.ASK, if (mode == null) "first encounter" else "set to ask every task")
    }

    /** Map the card answer to the action + what to persist. */
    fun applyDecision(
        choice: PermissionRequest.Decision,
        rememberChoices: Boolean,
    ): Outcome = when (choice) {
        PermissionRequest.Decision.ALLOW_ONCE ->
            Outcome(
                allow = true,
                persistMode = AppAccessMode.ASK,
                taskOnly = true,
                reason = "allow once",
            )
        PermissionRequest.Decision.ALWAYS_ALLOW ->
            if (rememberChoices) {
                Outcome(
                    allow = true,
                    persistMode = AppAccessMode.ALWAYS,
                    taskOnly = false,
                    reason = "always allow (remembered)",
                )
            } else {
                Outcome(
                    allow = true,
                    persistMode = AppAccessMode.ASK,
                    taskOnly = true,
                    reason = "remembering is off — grant limited to this task",
                )
            }
        PermissionRequest.Decision.OPEN_SETTINGS ->
            Outcome(false, AppAccessMode.ASK, true, "settings opened, no grant")
        PermissionRequest.Decision.CANCEL ->
            Outcome(false, AppAccessMode.ASK, true, "declined / timed out")
    }
}
