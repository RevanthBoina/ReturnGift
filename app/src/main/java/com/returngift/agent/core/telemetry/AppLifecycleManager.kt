// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.core.telemetry

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.utils.XLog

/**
 * AppLifecycleManager manages deterministic application launching, task-stack state verification,
 * and foreground transition telemetry.
 */
object AppLifecycleManager {

    private const val TAG = "AppLifecycleManager"
    private const val LAUNCH_TIMEOUT_MS = 5000L
    private const val POLL_INTERVAL_MS = 250L

    data class LaunchResult(
        val success: Boolean,
        val packageName: String,
        val timeToForegroundMs: Long,
        val error: String? = null
    )

    /**
     * Deterministically launches an app by package name, handling stale stacks,
     * verifying that it actually transitions to the foreground.
     */
    fun launchAndVerify(context: Context, packageName: String): LaunchResult {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
            ?: return LaunchResult(
                success = false,
                packageName = packageName,
                timeToForegroundMs = 0,
                error = "Package '$packageName' not found or has no launchable launcher activity"
            )

        // Clear stale task stacks and bring cleanly to front
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
        )

        val startTime = SystemClock.elapsedRealtime()
        try {
            context.startActivity(launchIntent)
            XLog.i(TAG, "Dispatched launch intent for $packageName")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to startActivity for $packageName", e)
            return LaunchResult(
                success = false,
                packageName = packageName,
                timeToForegroundMs = 0,
                error = "Failed to launch activity: ${e.message}"
            )
        }

        // Verify that the requested package achieves foreground state
        val deadline = startTime + LAUNCH_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val currentPkg = getActiveForegroundPackage(context)
            if (currentPkg != null && (currentPkg.equals(packageName, ignoreCase = true) || currentPkg.startsWith(packageName))) {
                val elapsed = SystemClock.elapsedRealtime() - startTime
                XLog.i(TAG, "App '$packageName' confirmed in foreground in ${elapsed}ms")
                return LaunchResult(
                    success = true,
                    packageName = packageName,
                    timeToForegroundMs = elapsed
                )
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        // Final check via Accessibility service root node
        val service = ClawAccessibilityService.getInstance()
        if (service != null) {
            val root = service.rootInActiveWindow
            if (root != null) {
                val rootPkg = root.packageName?.toString()
                if (rootPkg != null && rootPkg.startsWith(packageName)) {
                    val elapsed = SystemClock.elapsedRealtime() - startTime
                    return LaunchResult(
                        success = true,
                        packageName = packageName,
                        timeToForegroundMs = elapsed
                    )
                }
            }
        }

        return LaunchResult(
            success = false,
            packageName = packageName,
            timeToForegroundMs = SystemClock.elapsedRealtime() - startTime,
            error = "App '$packageName' launched but did not achieve foreground within ${LAUNCH_TIMEOUT_MS}ms"
        )
    }

    /**
     * Inspects active foreground package using AccessibilityService or ActivityManager.
     */
    fun getActiveForegroundPackage(context: Context): String? {
        val service = ClawAccessibilityService.getInstance()
        if (service != null) {
            val root = service.rootInActiveWindow
            if (root != null) {
                val pkg = root.packageName?.toString()
                if (!pkg.isNullOrBlank()) {
                    return pkg
                }
            }
        }

        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val runningTasks = am.getRunningTasks(1)
                if (!runningTasks.isNullOrEmpty()) {
                    val topActivity = runningTasks[0].topActivity
                    if (topActivity != null) {
                        return topActivity.packageName
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }
}
