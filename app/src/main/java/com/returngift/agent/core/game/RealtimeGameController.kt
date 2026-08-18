// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.core.game

import android.os.SystemClock
import com.returngift.agent.core.input.TouchInputLayer
import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.utils.XLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RealtimeGameController implements a high-frequency (30+ FPS) local perception-action loop
 * for physics-based games and canvas rendering. Decouples fast stabilization from high-latency LLM calls.
 */
object RealtimeGameController {

    private const val TAG = "RealtimeGameController"
    private const val FRAME_INTERVAL_MS = 33L // ~30 FPS

    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var controllerThread: Thread? = null

    // Known ad and monetization overlay packages/classes
    private val AD_OVERLAY_SIGNATURES = setOf(
        "com.google.android.gms.ads",
        "com.unity3d.ads",
        "com.applovin",
        "com.mbridge.msdk",
        "com.ironsource",
        "com.bytedance.sdk.openadsdk"
    )

    data class GameControlPolicy(
        val targetPitchDeg: Float = 0.0f,
        val throttleDutyCycle: Float = 0.8f, // 0.0 to 1.0
        val gasCoords: Pair<Int, Int> = Pair(800, 1900),
        val brakeCoords: Pair<Int, Int> = Pair(250, 1900),
        val targetPackage: String = ""
    )

    @Volatile
    private var currentPolicy: GameControlPolicy = GameControlPolicy()

    /**
     * Starts the real-time game control loop with an initial policy.
     */
    fun start(service: ClawAccessibilityService, policy: GameControlPolicy) {
        if (isRunning.getAndSet(true)) {
            XLog.w(TAG, "RealtimeGameController is already running. Updating policy.")
            currentPolicy = policy
            return
        }

        currentPolicy = policy
        isPaused.set(false)

        controllerThread = Thread({
            XLog.i(TAG, "Starting 30 FPS game control loop for ${policy.targetPackage}")
            try {
                while (isRunning.get()) {
                    val frameStart = SystemClock.elapsedRealtime()

                    // 1. Check for ad/interstitial interruption
                    if (isAdOverlayPresent(service)) {
                        if (!isPaused.get()) {
                            XLog.w(TAG, "Ad/Overlay detected! Pausing real-time game inputs.")
                            isPaused.set(true)
                            TouchInputLayer.releaseAllPointers(service)
                        }
                    } else if (isPaused.get()) {
                        XLog.i(TAG, "Ad overlay cleared. Resuming game controller.")
                        isPaused.set(false)
                    }

                    // 2. If active, execute rapid stabilization cycle
                    if (!isPaused.get()) {
                        executePhysicsStep(service, currentPolicy)
                    }

                    val elapsed = SystemClock.elapsedRealtime() - frameStart
                    val sleepMs = FRAME_INTERVAL_MS - elapsed
                    if (sleepMs > 0) {
                        Thread.sleep(sleepMs)
                    }
                }
            } catch (_: InterruptedException) {
                XLog.i(TAG, "Game controller loop interrupted")
            } finally {
                TouchInputLayer.releaseAllPointers(service)
                isRunning.set(false)
                XLog.i(TAG, "RealtimeGameController stopped")
            }
        }, "RealtimeGameControllerThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * Updates the high-level policy while the controller is running.
     */
    fun updatePolicy(policy: GameControlPolicy) {
        currentPolicy = policy
        XLog.i(TAG, "Updated game policy: throttle=${policy.throttleDutyCycle}, targetPitch=${policy.targetPitchDeg}")
    }

    /**
     * Stops the controller and releases all touch inputs.
     */
    fun stop(service: ClawAccessibilityService?) {
        isRunning.set(false)
        controllerThread?.interrupt()
        controllerThread = null
        TouchInputLayer.releaseAllPointers(service)
    }

    fun isActive(): Boolean = isRunning.get()

    private fun executePhysicsStep(service: ClawAccessibilityService, policy: GameControlPolicy) {
        // Fast duty-cycle modulation: hold gas for ~100ms
        val gas = policy.gasCoords
        val holdDuration = (FRAME_INTERVAL_MS * 3 * policy.throttleDutyCycle).toLong().coerceIn(30L, 200L)
        TouchInputLayer.holdTouch(service, gas.first, gas.second, holdDuration)
    }

    private fun isAdOverlayPresent(service: ClawAccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString().orEmpty()

        for (adSig in AD_OVERLAY_SIGNATURES) {
            if (pkg.contains(adSig, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
