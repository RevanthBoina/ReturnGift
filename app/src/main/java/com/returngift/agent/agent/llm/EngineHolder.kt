// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.llm

import com.returngift.agent.utils.XLog
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import android.content.ComponentCallbacks2
import android.os.Build

/**
 * Process-wide singleton that keeps a single LiteRT-LM Engine alive across
 * the chat UI and the task agent.
 *
 * Why: Engine initialisation on CPU backend takes 2-3 s. Without this,
 * ComposeChatActivity closes its engine before a task, TaskOrchestrator opens a
 * new one, then after the task chat reloads again — 4-6 s wasted per round trip.
 *
 * Thread safety: all mutations are @Synchronized so chat executor and task
 * executor threads can both call getOrCreate() safely.
 */
object EngineHolder {

    private const val TAG = "EngineHolder"

    private var engine: Engine? = null
    private var currentModelPath: String? = null
    private var currentBackendLabel: String? = null

    // Fast model engine slot — loaded lazily only for tasks with learned procedure
    // matching. Release automatically at task end. Never preloaded app-wide.
    private var fastEngine: Engine? = null
    private var fastModelPath: String? = null
    private var fastBackendLabel: String? = null

    private fun backendLabel(backend: Backend): String =
        if (backend is Backend.CPU) "CPU" else if (backend is Backend.GPU) "GPU" else backend.javaClass.simpleName

    /**
     * Return the existing Engine if the model path matches, otherwise close the
     * old one and create a fresh Engine for the new model.
     *
     * @param modelPath  absolute path to the .task model file
     * @param cacheDir   app's cacheDir.path — passed in so this object stays
     *                   context-free and easier to unit-test
     */
    @Synchronized
    @JvmOverloads
    fun getOrCreate(modelPath: String, cacheDir: String, backend: Backend = Backend.CPU()): Engine {
        val existing = engine
        val requestedBackendLabel = backendLabel(backend)
        if (existing != null && currentModelPath == modelPath && currentBackendLabel == requestedBackendLabel) {
            XLog.d(TAG, "getOrCreate: reusing engine for $modelPath (${currentBackendLabel ?: "unknown"})")
            return existing
        }

        // Different model or first call — close old engine first
        if (existing != null) {
            XLog.i(
                TAG,
                "getOrCreate: runtime changed (model=$currentModelPath/${currentBackendLabel ?: "?"} -> $modelPath/$requestedBackendLabel), closing old engine"
            )
            try {
                existing.close()
            } catch (e: Exception) {
                XLog.w(TAG, "getOrCreate: error closing old engine", e)
            }
            engine = null
            currentModelPath = null
        }

        XLog.i(TAG, "getOrCreate: creating new engine for $modelPath with $requestedBackendLabel")
        return try {
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                maxNumTokens = 8192,
                cacheDir = cacheDir
            )
            if (backend is Backend.GPU) {
                LocalBackendHealth.markGpuInitStarted(modelPath)
            }
            val newEngine = Engine(engineConfig).also { it.initialize() }
            if (backend is Backend.GPU) {
                LocalBackendHealth.markGpuInitFinished()
                LocalBackendHealth.noteGpuInitSuccess(modelPath)
            }
            engine = newEngine
            currentModelPath = modelPath
            currentBackendLabel = requestedBackendLabel
            XLog.i(TAG, "getOrCreate: engine ready for $modelPath (${currentBackendLabel})")
            newEngine
        } catch (e: Exception) {
            if (backend is Backend.GPU) {
                LocalBackendHealth.noteRecoverableGpuFailure(modelPath, e)
            } else {
                LocalBackendHealth.markGpuInitFinished()
            }
            XLog.e(TAG, "getOrCreate: failed to create engine for $modelPath", e)
            throw e
        }
    }

    /**
     * Explicitly close and release the engine. Call only when the model is being
     * unloaded entirely (e.g. user deletes the model file). Normal chat/task
     * transitions should NOT call this — they just close their Conversation objects.
     */
    @Synchronized
    fun close() {
        XLog.i(TAG, "close: releasing engine for $currentModelPath")
        try {
            engine?.close()
        } catch (e: Exception) {
            XLog.w(TAG, "close: error closing engine", e)
        }
        engine = null
        currentModelPath = null
        currentBackendLabel = null
        XLog.i(TAG, "close: done")
    }

    /** Returns true if an engine is live for the given model path. */
    @Synchronized
    fun isReady(modelPath: String): Boolean = engine != null && currentModelPath == modelPath

    /** Returns the actual backend label of the current shared engine, if any. */
    @Synchronized
    fun getBackendLabel(modelPath: String? = null): String? {
        return if (modelPath == null || currentModelPath == modelPath) currentBackendLabel else null
    }

    /** Acquire the fast (mechanical-step) engine for the given model path.
     *  Checks the memory gate before loading — returns null if insufficient resources.
     *  Caller must release with releaseFast().
     *
     * @param modelPath absolute path to the .task model file
     * @return LocalEngineLease or null if cannot acquire
     */
    @Synchronized
    fun acquireFast(modelPath: String): LocalEngineLease? {
        if (!LocalBackendHealth.canRunSecondEngine()) {
            XLog.w(TAG, "acquireFast: memory gate blocks fast engine load")
            return null
        }

        if (fastEngine != null && fastModelPath == modelPath) {
            XLog.d(TAG, "acquireFast: reusing fast engine for $modelPath")
            return LocalEngineLease(engine = fastEngine!!, backendLabel = fastBackendLabel!!)
        }

        if (fastEngine != null) {
            XLog.i(TAG, "acquireFast: replacing previous fast engine")
            try { fastEngine!!.close() } catch (e: Exception) { XLog.w(TAG, "acquireFast: close error", e) }
            fastEngine = null
            fastModelPath = null
            fastBackendLabel = null
        }

        if (engine != null && currentModelPath != modelPath) {
            XLog.i(TAG, "acquireFast: closing main engine to load fast")
            try { engine!!.close() } catch (e: Exception) { XLog.w(TAG, "acquireFast: main close error", e) }
            engine = null
            currentModelPath = null
            currentBackendLabel = null
        }

        XLog.i(TAG, "acquireFast: creating new fast engine for $modelPath")
        return try {
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                maxNumTokens = 8192,
            )
            val newEngine = Engine(engineConfig).also { it.initialize() }
            fastEngine = newEngine
            fastModelPath = modelPath
            fastBackendLabel = "CPU"
            XLog.i(TAG, "acquireFast: fast engine ready for $modelPath")
            LocalEngineLease(engine = newEngine, backendLabel = fastBackendLabel)
        } catch (e: Exception) {
            fastEngine = null
            fastModelPath = null
            fastBackendLabel = null
            XLog.e(TAG, "acquireFast: failed to create fast engine for $modelPath", e)
            null
        }
    }

    /** Release the fast engine. Safe to call even if none is loaded (no-op). */
    @Synchronized
    fun releaseFast() {
        if (fastEngine == null) return
        XLog.i(TAG, "releaseFast: releasing fast engine for ${fastModelPath ?: "unknown"}")
        try { fastEngine!!.close() } catch (e: Exception) { XLog.w(TAG, "releaseFast: close error", e) }
        fastEngine = null
        fastModelPath = null
        fastBackendLabel = null
    }

    @Synchronized
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            XLog.w(TAG, "onTrimMemory level $level: releasing engine to free memory")
            close()
        }
    }
}
