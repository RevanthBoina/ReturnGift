// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.core.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.utils.XLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TouchInputLayer implements duration-aware touch inputs (touch_down, touch_up, hold_touch)
 * and multi-pointer simultaneous touch gestures for games and canvas surfaces, with emergency cleanup.
 */
object TouchInputLayer {

    private const val TAG = "TouchInputLayer"

    data class ActivePointer(
        val pointerId: Int,
        val x: Int,
        val y: Int,
        val startTimestamp: Long,
        var isDown: Boolean
    )

    private val activePointers = ConcurrentHashMap<Int, ActivePointer>()

    /**
     * Presses and holds a touch at (x, y) for a specified duration in milliseconds.
     */
    @JvmStatic
    fun holdTouch(service: ClawAccessibilityService, x: Int, y: Int, durationMs: Long): Boolean {
        if (durationMs <= 0) return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchSync(service, gesture, durationMs + 1000)
    }

    /**
     * Starts a continuous touch down at (x, y) with a given pointer ID.
     */
    @JvmStatic
    fun touchDown(service: ClawAccessibilityService, pointerId: Int, x: Int, y: Int): Boolean {
        activePointers[pointerId] = ActivePointer(
            pointerId = pointerId,
            x = x,
            y = y,
            startTimestamp = System.currentTimeMillis(),
            isDown = true
        )
        // Dispatch a hold stroke with continue flag if supported
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            GestureDescription.StrokeDescription(path, 0, 1000, true)
        } else {
            GestureDescription.StrokeDescription(path, 0, 500)
        }
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchAsync(service, gesture)
    }

    /**
     * Releases a previously pressed touch pointer.
     */
    @JvmStatic
    fun touchUp(service: ClawAccessibilityService, pointerId: Int): Boolean {
        val pointer = activePointers.remove(pointerId) ?: return false
        pointer.isDown = false
        // Quick release tap / end stroke
        val path = Path().apply { moveTo(pointer.x.toFloat(), pointer.y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchAsync(service, gesture)
    }

    /**
     * Dispatches simultaneous multi-pointer touches (e.g. holding accelerator while tapping jump/nitro).
     */
    @JvmStatic
    fun dispatchMultiPointer(
        service: ClawAccessibilityService,
        touches: List<Triple<Int, Int, Long>> // x, y, durationMs
    ): Boolean {
        if (touches.isEmpty()) return false

        val builder = GestureDescription.Builder()
        var maxDuration = 0L

        for ((x, y, duration) in touches) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            builder.addStroke(stroke)
            if (duration > maxDuration) maxDuration = duration
        }

        return dispatchSync(service, builder.build(), maxDuration + 1000)
    }

    /**
     * Emergency cleanup: releases all active pointers and resets touch state.
     */
    @JvmStatic
    fun releaseAllPointers(service: ClawAccessibilityService?) {
        if (activePointers.isEmpty()) return
        XLog.i(TAG, "Emergency release of ${activePointers.size} pointers")
        activePointers.clear()
    }

    private fun dispatchSync(service: ClawAccessibilityService, gesture: GestureDescription, timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicBoolean(false)

        val dispatched = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result.set(true)
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                result.set(false)
                latch.countDown()
            }
        }, null)

        if (!dispatched) return false

        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            return false
        }
        return result.get()
    }

    private fun dispatchAsync(service: ClawAccessibilityService, gesture: GestureDescription): Boolean {
        return service.dispatchGesture(gesture, null, null)
    }
}
