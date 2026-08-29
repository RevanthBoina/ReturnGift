// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.provenance

import com.returngift.agent.agent.knowledge.KBManager
import com.returngift.agent.agent.grounding.SelectorCache
import com.returngift.agent.agent.session.AppSessionManager
import com.returngift.agent.agent.tracker.ExecutionTracker
import com.returngift.agent.utils.XLog

/**
 * Privacy management utilities for P3.3 provenance + forget-app workflow.
 *
 * Provides a single entry point for "forget this app" operations that:
 * 1. Deletes ExecutionTracker observation rows tagged with `screen:<package>`
 * 2. Deletes vault files whose frontmatter provenance matches `screen:<package>`
 * 3. Invalidates SelectorCache entries for the package
 * 4. Clears AppSessionManager state for the package
 */
object PrivacyManager {

    private const val TAG = "PrivacyManager"

    /**
     * Forget all data associated with a package name.
     *
     * @param packageName The Android package name to forget (e.g. "com.whatsapp")
     * @return A summary of what was deleted, for user feedback
     */
    fun forgetApp(packageName: String): ForgetResult {
        val safePackage = packageName.trim().lowercase()
        if (safePackage.isEmpty()) {
            return ForgetResult(0, 0, false, false, "Invalid package name")
        }

        var totalDeleted = 0
        var vaultDeleted = 0
        var cacheCleared = false
        var sessionCleared = false

        // (a) Delete ExecutionTracker observation rows tagged with screen:<package>
        val trackerDeleted = ExecutionTracker.forgetApp(safePackage)
        totalDeleted += trackerDeleted

        // (b) Delete vault files whose frontmatter provenance matches screen:<package>
        val origin = "screen:$safePackage"
        val vaultFiles = KBManager.vaultFilesForProvenance(origin)
        for (file in vaultFiles) {
            KBManager.delete(file.path).getOrElse { XLog.w(TAG, "Failed to delete vault file: ${file.path}") }
            vaultDeleted++
        }
        totalDeleted += vaultDeleted

        // (c) Invalidate SelectorCache entries for the package
        SelectorCache.invalidatePackage(safePackage)
        cacheCleared = true

        // (d) Clear AppSessionManager state for the package
        val session = AppSessionManager.getActiveSession(safePackage)
        if (session != null) {
            // AppSessionManager doesn't have a per-package clear, so clear all and re-add others
            // For simplicity, we clear the specific package's entry by recreating the map
            // This is a bit heavy-handed but matches the "forget" semantics
            clearAppSession(safePackage)
        }
        sessionCleared = true

        XLog.i(TAG, "forgetApp($safePackage): tracker=$trackerDeleted vault=$vaultDeleted cacheCleared=$cacheCleared sessionCleared=$sessionCleared")
        return ForgetResult(
            trackerDeleted = trackerDeleted,
            vaultDeleted = vaultDeleted,
            cacheCleared = cacheCleared,
            sessionCleared = sessionCleared,
            message = "Forgot $safePackage (tracker: $trackerDeleted, vault: $vaultDeleted)"
        )
    }

    /**
     * Clear a single app's session from AppSessionManager.
     */
    private fun clearAppSession(packageName: String) {
        AppSessionManager.forgetApp(packageName)
        XLog.d(TAG, "Removed $packageName from AppSessionManager")
    }

    /**
     * Result of a forgetApp operation.
     */
    data class ForgetResult(
        val trackerDeleted: Int,
        val vaultDeleted: Int,
        val cacheCleared: Boolean,
        val sessionCleared: Boolean,
        val message: String
    )
}