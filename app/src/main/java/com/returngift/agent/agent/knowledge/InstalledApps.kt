// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.knowledge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.returngift.agent.utils.XLog

/**
 * Launchable apps actually installed on this device.
 *
 * Used by Settings → App Permissions (so guided apps like Chrome / LinkedIn /
 * YouTube can be toggled even before the agent has touched them) and by the
 * system prompt (so the model gets the usage guide only for apps that exist).
 *
 * The list is cached briefly: the permission screen and every task start both
 * ask for it, and a PackageManager query is not free on low-end devices.
 */
object InstalledApps {

    private const val TAG = "InstalledApps"
    private const val CACHE_TTL_MS = 60_000L

    data class AppInfo(
        val packageName: String,
        val label: String,
    )

    @Volatile private var cache: List<AppInfo> = emptyList()
    @Volatile private var cachedAtMs: Long = 0L

    /** Launchable apps (one entry per package), sorted by label. */
    fun launchable(context: Context, forceRefresh: Boolean = false): List<AppInfo> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cache.isNotEmpty() && now - cachedAtMs < CACHE_TTL_MS) return cache

        val result = mutableListOf<AppInfo>()
        try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val activities = pm.queryIntentActivities(
                intent,
                PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS,
            )
            val seen = mutableSetOf<String>()
            for (info in activities) {
                val pkg = info.activityInfo.packageName
                if (!seen.add(pkg)) continue
                val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
                result.add(AppInfo(pkg, label))
            }
            result.sortBy { it.label.lowercase() }
        } catch (e: Throwable) {
            XLog.w(TAG, "could not query launchable apps", e)
        }
        cache = result
        cachedAtMs = now
        XLog.d(TAG, "launchable apps: ${result.size}")
        return result
    }

    fun packageNames(context: Context): List<String> = launchable(context).map { it.packageName }

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun labelOf(context: Context, packageName: String): String? =
        launchable(context).firstOrNull { it.packageName == packageName }?.label

    fun invalidate() {
        cache = emptyList()
        cachedAtMs = 0L
    }
}
