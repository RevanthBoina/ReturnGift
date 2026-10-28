// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.returngift.agent.service.ReturnGiftAccessibilityService
import com.returngift.agent.service.ReturnGiftNotificationListener
import com.returngift.agent.service.ForegroundService
import com.returngift.agent.utils.KVUtils

enum class ServiceBindingState {
    DISABLED,
    CONNECTING,
    READY,
    DEGRADED,
}

enum class AppRequirement(val label: String) {
    ACCESSIBILITY("Accessibility"),
    NOTIFICATION_PERMISSION("Notifications"),
    NOTIFICATION_ACCESS("Notification Access"),
    OVERLAY("Overlay"),
    BATTERY_OPTIMIZATION("Background Run"),
    STORAGE("Storage"),
    RESTRICTED_SETTINGS("Restricted Settings"),
}

data class AppCapabilitySnapshot(
    val accessibilityState: ServiceBindingState,
    val notificationAccessState: ServiceBindingState,
    val notificationPermissionGranted: Boolean,
    val foregroundServiceRunning: Boolean,
    val overlayGranted: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val storageAccessGranted: Boolean,
) {
    val canRunInteractiveTask: Boolean
        get() = accessibilityState == ServiceBindingState.READY

    val canRunMonitor: Boolean
        get() = accessibilityState == ServiceBindingState.READY &&
            notificationAccessState == ServiceBindingState.READY

    val accessibilityStatusLabel: String
        get() = when (accessibilityState) {
            ServiceBindingState.READY -> "Enabled"
            ServiceBindingState.CONNECTING -> "Connecting"
            ServiceBindingState.DEGRADED -> "Disconnected"
            ServiceBindingState.DISABLED -> "Disabled"
        }

    val notificationAccessStatusLabel: String
        get() = when (notificationAccessState) {
            ServiceBindingState.READY -> "Connected"
            ServiceBindingState.CONNECTING -> "Connecting"
            ServiceBindingState.DEGRADED -> "Disconnected"
            ServiceBindingState.DISABLED -> "Disabled"
        }

    val notificationPermissionStatusLabel: String
        get() = if (notificationPermissionGranted) "Enabled" else "Disabled"
}

object AppCapabilityCoordinator {
    private const val SERVICE_REBIND_GRACE_MS = 15_000L
    private const val PROCESS_START_REBIND_GRACE_MS = 30_000L
    private const val ACCESSIBILITY_INTERRUPT_GRACE_MS = 4_000L

    @Volatile
    private var processStartTimestamp: Long = System.currentTimeMillis()

    fun markProcessStart() {
        processStartTimestamp = System.currentTimeMillis()
    }

    fun snapshot(context: Context): AppCapabilitySnapshot {
        return AppCapabilitySnapshot(
            accessibilityState = accessibilityState(context),
            notificationAccessState = notificationAccessState(context),
            notificationPermissionGranted = isNotificationPermissionGranted(context),
            foregroundServiceRunning = ForegroundService.isRunning(),
            overlayGranted = Settings.canDrawOverlays(context),
            batteryOptimizationIgnored = isIgnoringBatteryOptimizations(context),
            storageAccessGranted = hasStorageAccess(context),
        )
    }

    fun accessibilityState(context: Context): ServiceBindingState {
        return bindingState(
            enabled = ReturnGiftAccessibilityService.isEnabledInSettings(context),
            running = ReturnGiftAccessibilityService.isRunning(),
            pendingRepair = KVUtils.hasPendingAccessibilityReturn(),
            lastConnectedAt = KVUtils.getAccessibilityLastConnectedAt(),
            lastHeartbeatAt = KVUtils.getAccessibilityLastHeartbeatAt(),
            lastInterruptedAt = KVUtils.getAccessibilityLastInterruptedAt(),
            lastDisconnectedAt = KVUtils.getAccessibilityLastDisconnectedAt(),
        )
    }

    fun notificationAccessState(context: Context): ServiceBindingState {
        return bindingState(
            enabled = ReturnGiftNotificationListener.isEnabledInSettings(context),
            running = ReturnGiftNotificationListener.isConnected(),
            pendingRepair = KVUtils.hasPendingNotificationAccessReturn(),
            lastConnectedAt = KVUtils.getNotificationListenerLastConnectedAt(),
            lastDisconnectedAt = KVUtils.getNotificationListenerLastDisconnectedAt(),
        )
    }

    private fun bindingState(
        enabled: Boolean,
        running: Boolean,
        pendingRepair: Boolean,
        lastConnectedAt: Long,
        lastHeartbeatAt: Long = 0L,
        lastInterruptedAt: Long = 0L,
        lastDisconnectedAt: Long,
    ): ServiceBindingState {
        if (!enabled) return ServiceBindingState.DISABLED
        if (pendingRepair) return ServiceBindingState.CONNECTING

        val now = System.currentTimeMillis()
        val lastHealthyAt = maxOf(lastConnectedAt, lastHeartbeatAt)

        if (running) {
            if (lastInterruptedAt > lastHealthyAt) {
                return if (now - lastInterruptedAt <= ACCESSIBILITY_INTERRUPT_GRACE_MS) {
                    ServiceBindingState.CONNECTING
                } else {
                    ServiceBindingState.DEGRADED
                }
            }
            return ServiceBindingState.READY
        }

        // Process-young grace: after any process restart (manual force-stop, OS task-kill on
        // aggressive OEMs like Xiaomi/Samsung), the AccessibilityService singleton is null and
        // onServiceConnected hasn't fired yet for the new process. The OS rebinds within seconds.
        // Anchor the grace to PROCESS start time so a fresh process always gets a fair window
        // regardless of how stale lastHealthyAt is in MMKV.
        if (now - processStartTimestamp <= PROCESS_START_REBIND_GRACE_MS) {
            return ServiceBindingState.CONNECTING
        }

        if (lastHealthyAt <= 0L) return ServiceBindingState.CONNECTING

        if (now - lastHealthyAt <= SERVICE_REBIND_GRACE_MS) {
            return ServiceBindingState.CONNECTING
        }
        if (lastDisconnectedAt > 0L && lastDisconnectedAt >= lastHealthyAt) {
            return ServiceBindingState.DEGRADED
        }
        if (lastInterruptedAt > 0L && lastInterruptedAt >= lastHealthyAt) {
            return ServiceBindingState.DEGRADED
        }
        return ServiceBindingState.DEGRADED
    }

    fun missingMonitorRequirements(context: Context): List<AppRequirement> {
        val capabilities = snapshot(context)
        val missing = mutableListOf<AppRequirement>()
        if (capabilities.accessibilityState != ServiceBindingState.READY) {
            missing.add(AppRequirement.ACCESSIBILITY)
        }
        if (capabilities.notificationAccessState != ServiceBindingState.READY) {
            missing.add(AppRequirement.NOTIFICATION_ACCESS)
        }
        return missing
    }

    fun isNotificationPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Ask for a system capability by opening ReturnGift's own permission card in
     * the foreground first (the APK interface), with an "Open settings" button
     * that lands on the exact system page from [openSystemSettings].
     *
     * Use this instead of jumping straight into system settings when the request
     * is raised from the background (agent loop, monitor setup, notifications):
     * the user always sees WHO is asking and WHY before leaving the app.
     */
    fun requestCapability(context: Context, requirement: AppRequirement) {
        // Android grants these itself, so no notification button can answer them: the
        // notification is a door to the right settings page, shown once per window.
        val prompter = com.returngift.agent.service.NotificationPrompter
        if (prompter.notificationsAvailable(context)) {
            prompter.capabilityNeeded(context, requirement, capabilityReason(requirement))
            return
        }
        val request = com.returngift.agent.agent.permission.PermissionRequest.forCapability(
            capabilityId = requirement.name,
            label = capabilityLabel(requirement),
            reason = capabilityReason(requirement),
        )
        com.returngift.agent.agent.permission.PermissionPromptLauncher.open(context, request)
    }

    /** User-facing name of a capability (short, used in the permission card title). */
    fun capabilityLabel(requirement: AppRequirement): String = when (requirement) {
        AppRequirement.ACCESSIBILITY -> "Accessibility"
        AppRequirement.NOTIFICATION_PERMISSION -> "Notifications"
        AppRequirement.NOTIFICATION_ACCESS -> "Notification access"
        AppRequirement.OVERLAY -> "Display over other apps"
        AppRequirement.BATTERY_OPTIMIZATION -> "Background running"
        AppRequirement.STORAGE -> "All files access"
        AppRequirement.RESTRICTED_SETTINGS -> "Install from this source"
    }

    /** One-sentence explanation shown on the permission card. */
    fun capabilityReason(requirement: AppRequirement): String = when (requirement) {
        AppRequirement.ACCESSIBILITY ->
            "ReturnGift needs the accessibility service to see and tap the screen for your tasks."
        AppRequirement.NOTIFICATION_PERMISSION ->
            "Notifications let ReturnGift tell you when a task needs you while it runs in the background."
        AppRequirement.NOTIFICATION_ACCESS ->
            "Notification access is required to read and reply to messages for monitored chats."
        AppRequirement.OVERLAY ->
            "Display-over-other-apps lets the running-task pill and confirmations appear above other apps."
        AppRequirement.BATTERY_OPTIMIZATION ->
            "Exempting ReturnGift from battery optimisation keeps long tasks from being killed."
        AppRequirement.STORAGE ->
            "All-files access is needed to read and save files on your device for your tasks."
        AppRequirement.RESTRICTED_SETTINGS ->
            "Android restricts installs from this source; allow it in app info to update ReturnGift."
    }

    fun openSystemSettings(context: Context, requirement: AppRequirement) {
        when (requirement) {
            AppRequirement.ACCESSIBILITY -> {
                if (accessibilityState(context) == ServiceBindingState.READY) {
                    KVUtils.clearPendingAccessibilityReturn()
                } else {
                    KVUtils.markPendingAccessibilityReturn()
                }
                launch(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            AppRequirement.NOTIFICATION_ACCESS -> {
                if (notificationAccessState(context) == ServiceBindingState.READY) {
                    KVUtils.clearPendingNotificationAccessReturn()
                } else {
                    KVUtils.markPendingNotificationAccessReturn()
                }
                launch(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            AppRequirement.OVERLAY -> {
                launch(
                    context,
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
                )
            }
            AppRequirement.BATTERY_OPTIMIZATION -> {
                launch(
                    context,
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                )
            }
            AppRequirement.STORAGE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    launch(
                        context,
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = "package:${context.packageName}".toUri()
                        }
                    )
                }
            }
            AppRequirement.RESTRICTED_SETTINGS -> {
                launch(
                    context,
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                )
            }
            AppRequirement.NOTIFICATION_PERMISSION -> Unit
        }
    }

    private fun hasStorageAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun launch(context: Context, intent: Intent) {
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
