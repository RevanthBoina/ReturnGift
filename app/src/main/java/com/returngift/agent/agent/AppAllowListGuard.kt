// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

import android.content.Context
import android.content.pm.PackageManager
import com.returngift.agent.utils.XLog

/**
 * Part B — Per-app allow-list gate.
 *
 * Call checkAndRecord() BEFORE any tool call that targets a specific app package.
 * The result tells the caller whether to proceed, block, or show the first-time
 * permission prompt with "Allow once" and "Add to allow-list" buttons.
 *
 * Rules:
 *  - Default for any newly-encountered app: ALLOWED.
 *  - First encounter → return FIRST_TIME so the UI can show the prompt.
 *  - Subsequent encounters where allowed=true → ALLOWED.
 *  - Subsequent encounters where allowed=false → BLOCKED (show prompt again).
 */
object AppAllowListGuard {

    private const val TAG = "AppAllowListGuard"

    sealed class CheckResult {
        /** App is in allow-list and enabled. Proceed with the tool call. */
        object Allowed : CheckResult()

        /**
         * First time this package is seen. Caller should surface a two-button prompt
         * ("Allow once" / "Add to allow-list") before executing the tool.
         */
        data class FirstTime(val packageName: String, val label: String) : CheckResult()

        /**
         * App is in allow-list but disabled (user turned it OFF).
         * Block the tool call and surface the permission prompt.
         */
        data class Blocked(val packageName: String, val label: String) : CheckResult()
    }

    /**
     * Check the allow-list for [packageName] and record first encounter if needed.
     *
     * @param context     app context for PackageManager label lookup and DB access
     * @param packageName target app package (e.g. "com.whatsapp")
     * @return            CheckResult — caller decides whether to proceed or prompt
     */
    fun checkAndRecord(context: Context, packageName: String): CheckResult {
        if (packageName.isBlank()) return CheckResult.Allowed

        val store = AppAllowListStore.getInstance(context)
        val label = resolveLabel(context, packageName)

        return if (store.isFirstEncounter(packageName)) {
            // Insert with default ON so subsequent calls see it as known
            store.touchApp(packageName, label, allowed = true)
            XLog.i(TAG, "First encounter: $packageName ($label)")
            CheckResult.FirstTime(packageName, label)
        } else if (store.isAllowed(packageName)) {
            CheckResult.Allowed
        } else {
            XLog.i(TAG, "Blocked by allow-list: $packageName")
            CheckResult.Blocked(packageName, label)
        }
    }

    /** Resolve a human-readable label for a package, falling back to the package name. */
    fun resolveLabel(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
