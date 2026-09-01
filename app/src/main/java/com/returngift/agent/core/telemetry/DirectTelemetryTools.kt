// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.core.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.view.accessibility.AccessibilityWindowInfo
import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.utils.XLog
import java.util.concurrent.atomic.AtomicInteger

/**
 * DirectTelemetryTools provides atomic, zero-overhead telemetry snapshots of device state,
 * active windows, battery, and foreground interactivity.
 */
object DirectTelemetryTools {

    private const val TAG = "DirectTelemetryTools"
    private val activeWindowCount = AtomicInteger(0)

    data class DeviceTelemetrySnapshot(
        val batteryPercentage: Int,
        val isCharging: Boolean,
        val isScreenInteractive: Boolean,
        val foregroundPackage: String,
        val activeWindowCount: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    @JvmStatic
    fun updateWindowTelemetry(windows: List<AccessibilityWindowInfo>?) {
        val count = windows?.size ?: 0
        activeWindowCount.set(count)
    }

    @JvmStatic
    fun getSnapshot(service: ClawAccessibilityService): DeviceTelemetrySnapshot {
        var batteryLevel = -1
        var isCharging = false
        var isInteractive = true

        try {
            val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryIntent = service.registerReceiver(null, batteryFilter)
            if (batteryIntent != null) {
                val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    batteryLevel = (level * 100) / scale
                }
                val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
            }

            val powerManager = service.getSystemService(Context.POWER_SERVICE) as? PowerManager
            isInteractive = powerManager?.isInteractive ?: true
        } catch (e: Exception) {
            XLog.e(TAG, "Error querying system telemetry", e)
        }

        return DeviceTelemetrySnapshot(
            batteryPercentage = batteryLevel,
            isCharging = isCharging,
            isScreenInteractive = isInteractive,
            foregroundPackage = service.foregroundPackage ?: "",
            activeWindowCount = activeWindowCount.get()
        )
    }
}
