// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.plan

import android.content.Context
import com.returngift.agent.agent.knowledge.AppUsageGuide
import com.returngift.agent.agent.knowledge.InstalledApps
import com.returngift.agent.utils.XLog

/**
 * Builds the planner's view of the device.
 *
 * Two inputs only: the launchable-app census (what can actually be driven) and the
 * app-guide registry (what kind of surface each app exposes). A row whose app is not
 * installed is kept — the planner needs it to answer "…is not installed, use the
 * browser?" instead of silently ignoring the name.
 *
 * Cached briefly: the census is a PackageManager scan, and the plan is computed on
 * the worker thread right before a task starts.
 */
object PlanContextFactory {

    private const val TAG = "PlanContextFactory"
    private const val CACHE_TTL_MS = 30_000L

    @Volatile private var cached: PlanContext? = null
    @Volatile private var cachedAtMs: Long = 0L

    /**
     * [build] with the application context when the process has one, and an empty
     * context otherwise (JVM unit tests, or a task that starts before `onCreate`
     * finishes). Planning degrades to "no known apps"; it never aborts the task.
     */
    fun buildOrEmpty(): PlanContext {
        val app = runCatching { com.returngift.agent.ReturnGiftApplication.instance }.getOrNull()
            ?: return PlanContext(apps = emptyList())
        return runCatching { build(app) }.getOrElse { e ->
            XLog.w(TAG, "plan context unavailable; planning without the app census", e)
            PlanContext(apps = emptyList())
        }
    }

    fun build(context: Context, forceRefresh: Boolean = false): PlanContext {
        val now = System.currentTimeMillis()
        cached?.let { if (!forceRefresh && now - cachedAtMs < CACHE_TTL_MS) return it }

        val installed = try {
            InstalledApps.launchable(context, forceRefresh)
        } catch (e: Exception) {
            XLog.w(TAG, "installed-app census failed; planning without it", e)
            emptyList()
        }
        val installedByPackage = installed.associateBy { it.packageName }

        val guides = AppUsageGuide.all()
        val apps = guides.map { guide ->
            val installedLabel = installedByPackage[guide.packageName]?.label
            AppRef(
                label = installedLabel ?: guide.label,
                packageName = guide.packageName,
                surface = guide.surface,
                installed = installedLabel != null,
                aliases = guide.aliases + listOf(guide.label),
                capabilities = setOfNotNull(guide.surface?.capability()),
            )
        } + installed
            .filter { info -> guides.none { it.packageName == info.packageName } }
            .map { info ->
                AppRef(
                    label = info.label,
                    packageName = info.packageName,
                    surface = null,
                    installed = true,
                    capabilities = emptySet(),
                )
            }

        val planContext = PlanContext(
            apps = apps.distinctBy { it.label.lowercase() + "|" + it.packageName },
            browserLabel = apps.firstOrNull { it.surface?.capability() == SurfaceCapability.BROWSE }?.label
                ?: "Browser",
        )
        cached = planContext
        cachedAtMs = now
        return planContext
    }

    fun invalidate() {
        cached = null
        cachedAtMs = 0L
    }
}
