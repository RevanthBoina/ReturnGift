// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.core.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import com.returngift.agent.service.ClawAccessibilityService

/**
 * DirectTelemetryTools provides atomic, structured snapshots of device and foreground app state.
 */
object DirectTelemetryTools {

    data class DeviceStateSnapshot(
        val batteryPercentage: Int,
        val isCharging: Boolean,
        val foregroundPackage: String,
        val isScreenInteractive: Boolean,
        val activeWindowLayerCount: Int
    )

    fun getSnapshot(context: Context): DeviceStateSnapshot {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct: Int = if (level >= 0 && scale > 0) ((level * 100) / scale) else -1
        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isInteractive = powerManager?.isInteractive ?: true

        val fgPkg = AppLifecycleManager.getActiveForegroundPackage(context) ?: "unknown"

        val service = ClawAccessibilityService.getInstance()
        val windows = service?.windows
        val windowCount = windows?.size ?: 1

        return DeviceStateSnapshot(
            batteryPercentage = batteryPct,
            isCharging = isCharging,
            foregroundPackage = fgPkg,
            isScreenInteractive = isInteractive,
            activeWindowLayerCount = windowCount
        )
    }
}
