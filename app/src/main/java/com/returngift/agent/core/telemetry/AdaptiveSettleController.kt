// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.core.telemetry

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicLong

/**
 * AdaptiveSettleController provides event-driven screen settlement detection.
 * Instead of waiting for static Thread.sleep delays (300-500ms), it monitors
 * UI layout/scroll/window events and resumes immediately when the UI becomes quiet (50-80ms).
 */
object AdaptiveSettleController {

    private val lastEventTimestamp = AtomicLong(SystemClock.elapsedRealtime())
    private const val DEFAULT_QUIET_WINDOW_MS = 60L
    private const val MAX_SETTLE_TIMEOUT_MS = 500L

    /**
     * Records an incoming accessibility event timestamp.
     */
    @JvmStatic
    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        // Only track layout/scroll/window changing events
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            lastEventTimestamp.set(SystemClock.elapsedRealtime())
        }
    }

    /**
     * Waits until the UI becomes quiet (no layout/scroll events for [quietWindowMs]),
     * or until [maxTimeoutMs] elapses.
     */
    @JvmStatic
    @JvmOverloads
    fun waitForSettle(
        quietWindowMs: Long = DEFAULT_QUIET_WINDOW_MS,
        maxTimeoutMs: Long = MAX_SETTLE_TIMEOUT_MS
    ): Long {
        val start = SystemClock.elapsedRealtime()
        val deadline = start + maxTimeoutMs

        // Give a minimum 20ms for initial event dispatch
        try {
            Thread.sleep(20)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return SystemClock.elapsedRealtime() - start
        }

        while (SystemClock.elapsedRealtime() < deadline) {
            val quietDuration = SystemClock.elapsedRealtime() - lastEventTimestamp.get()
            if (quietDuration >= quietWindowMs) {
                // UI has stabilized
                return SystemClock.elapsedRealtime() - start
            }
            try {
                Thread.sleep(15)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        return SystemClock.elapsedRealtime() - start
    }
}
