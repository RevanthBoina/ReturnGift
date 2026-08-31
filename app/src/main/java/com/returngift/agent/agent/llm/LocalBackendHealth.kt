// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.llm

import android.content.Context
import android.os.Build
import android.os.Process
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog

object LocalBackendHealth {

    private const val TAG = "LocalBackendHealth"
    private const val CRASH_MARKER_MAX_AGE_MS = 1000L * 60L * 60L * 24L * 30L
    private const val VERIFIED_GPU_CPU_SAFE_RETRY_COOLDOWN_MS = 1000L * 60L * 60L * 24L
private const val ASSET_NAME = "local_backend_health.json"

// Hardcoded fallback values (first-boot safety net until JSON loads)
private val HARDCODED_CONSERVATIVE_CPU_MANUFACTURERS = setOf(
    "xiaomi",
    "redmi",
    "poco",
)

private val HARDCODED_CONSERVATIVE_CPU_MODELS = listOf(
    "xiaomi 15",
    "mi 15",
    "galaxy z fold4",
    "sm-f936",
    "z flip7",
    "sm-f766",
)

private val HARDCODED_CONSERVATIVE_CPU_HARDWARE_HINTS = listOf(
    "mt",
    "mediatek",
    "dimensity",
)

// M5 fix: make conservative CPU lists externalizable via assets/local_backend_health.json.
// Falls back to hardcoded defaults if the asset is absent or malformed.
private val CONSERVATIVE_CPU_MANUFACTURERS: Set<String> by lazy {
    loadConservativeSet(
        "conservative_cpu_manufacturers",
        HARDCODED_CONSERVATIVE_CPU_MANUFACTURERS,
    )
}

private val CONSERVATIVE_CPU_MODELS: List<String> by lazy {
    loadConservativeList(
        "conservative_cpu_models",
        HARDCODED_CONSERVATIVE_CPU_MODELS,
    )
}

private val CONSERVATIVE_CPU_HARDWARE_HINTS: List<String> by lazy {
    loadConservativeList(
        "conservative_cpu_hardware_hints",
        HARDCODED_CONSERVATIVE_CPU_HARDWARE_HINTS,
    )
}

private fun loadConservativeSet(key: String, fallback: Set<String>): Set<String> {
    return try {
        val json = com.blankj.utilcode.util.Utils.getApp()
            .assets
            .open(ASSET_NAME)
            .bufferedReader()
            .use { it.readText() }

        val node = com.google.gson.JsonParser.parseString(json).asJsonObject
        node.getAsJsonArray(key)?.map { it.asString }?.toSet() ?: fallback
    } catch (e: Exception) {
        fallback
    }
}

private fun loadConservativeList(key: String, fallback: List<String>): List<String> {
    return try {
        val json = com.blankj.utilcode.util.Utils.getApp()
            .assets
            .open(ASSET_NAME)
            .bufferedReader()
            .use { it.readText() }

        val node = com.google.gson.JsonParser.parseString(json).asJsonObject
        node.getAsJsonArray(key)?.map { it.asString } ?: fallback
    } catch (e: Exception) {
        fallback
    }
}
    // Runtime-loaded values (from JSON asset, cached in memory)
    @Volatile
    private var conservativeCpuManufacturers: Set<String>? = null
    @Volatile
    private var conservativeCpuModels: List<String>? = null
    @Volatile
    private var conservativeCpuHardwareHints: List<String>? = null
    @Volatile
    private var jsonLoaded = false

    // Lazy initialization flag (set once JSON loads)
    private val JSON_LOADED_KEY = "local_backend_health_loaded"
    private val JSON_MANUFACTURERS_KEY = "conservative_cpu_manufacturers"
    private val JSON_MODELS_KEY = "conservative_cpu_models"
    private val JSON_HARDWARE_HINTS_KEY = "conservative_cpu_hardware_hints"

    /**
     * Load conservative CPU lists from JSON asset.
     * Falls back to hardcoded values if JSON is missing or malformed.
     * Called lazily on first access; results cached in memory.
     */
    @Synchronized
    fun loadConservativeCpuLists(context: Context) {
        if (jsonLoaded && conservativeCpuManufacturers != null) return

        // Try to load from cached KV values first
        val cachedManufacturers = KVUtils.getString(JSON_MANUFACTURERS_KEY, "")
        val cachedModels = KVUtils.getString(JSON_MODELS_KEY, "")
        val cachedHints = KVUtils.getString(JSON_HARDWARE_HINTS_KEY, "")

        if (cachedManufacturers.isNotEmpty() && cachedModels.isNotEmpty() && cachedHints.isNotEmpty()) {
            conservativeCpuManufacturers = cachedManufacturers.split(",").toSet()
            conservativeCpuModels = cachedModels.split(",")
            conservativeCpuHardwareHints = cachedHints.split(",")
            jsonLoaded = true
            XLog.i(TAG, "Loaded conservative CPU lists from KV cache")
            return
        }

        // Load from asset
        try {
            val inputStream = context.assets.open(ASSET_NAME)
            val json = inputStream.bufferedReader().use { it.readText() }
            inputStream.close()

            val gson = Gson()
            val root = gson.fromJson(json, JsonObject::class.java)

            val conservative = root.getAsJsonObject("conservative_cpu")
            if (conservative != null) {
                conservativeCpuManufacturers = conservative.getAsJsonArray("manufacturers")
                    .map { it.asString.lowercase() }
                    .toSet()
                conservativeCpuModels = conservative.getAsJsonArray("models")
                    .map { it.asString.lowercase() }
                conservativeCpuHardwareHints = conservative.getAsJsonArray("hardware_hints")
                    .map { it.asString.lowercase() }

                // Cache in KV for next boot
                KVUtils.putString(JSON_MANUFACTURERS_KEY,
                    conservativeCpuManufacturers!!.joinToString(","))
                KVUtils.putString(JSON_MODELS_KEY,
                    conservativeCpuModels!!.joinToString(","))
                KVUtils.putString(JSON_HARDWARE_HINTS_KEY,
                    conservativeCpuHardwareHints!!.joinToString(","))
                KVUtils.putBoolean(JSON_LOADED_KEY, true)

                jsonLoaded = true
                XLog.i(TAG, "Loaded conservative CPU lists from JSON asset")
                return
/** Returns true if device has enough free memory to run a second LiteRT-LM engine concurrently.
 *  Uses Runtime memory heuristics: requires >= 25% of max memory free.
 *  This is the "memory gate" for fast engine loading.
 */
    fun canRunSecondEngine(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val freeMemory = runtime.freeMemory()
        val usedPercent = 100.0 * (maxMemory - freeMemory) / maxMemory
        val sufficient = freeMemory >= (maxMemory * 0.25)  // Need 25% free
        XLog.v(TAG, "Memory gate: free=$freeMemory/${maxMemory} bytes (${"%.1f".format(100 - usedPercent)}% free), sufficient=$sufficient")
        return sufficient
    }
}
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to load $ASSET_NAME: ${e.message}")
        }

        // Fall back to hardcoded values
        conservativeCpuManufacturers = HARDCODED_CONSERVATIVE_CPU_MANUFACTURERS
        conservativeCpuModels = HARDCODED_CONSERVATIVE_CPU_MODELS
        conservativeCpuHardwareHints = HARDCODED_CONSERVATIVE_CPU_HARDWARE_HINTS
        jsonLoaded = true
        XLog.i(TAG, "Using hardcoded conservative CPU lists (JSON unavailable)")
    }

    /** Get conservative CPU manufacturers list */
    fun getConservativeCpuManufacturers(): Set<String> {
        return conservativeCpuManufacturers ?: HARDCODED_CONSERVATIVE_CPU_MANUFACTURERS
    }

    /** Get conservative CPU models list */
    fun getConservativeCpuModels(): List<String> {
        return conservativeCpuModels ?: HARDCODED_CONSERVATIVE_CPU_MODELS
    }

    /** Get conservative CPU hardware hints list */
    fun getConservativeCpuHardwareHints(): List<String> {
        return conservativeCpuHardwareHints ?: HARDCODED_CONSERVATIVE_CPU_HARDWARE_HINTS
    }

    /** Check if JSON list was successfully loaded (vs hardcoded fallback) */
    fun wasJsonListLoaded(): Boolean = jsonLoaded && conservativeCpuManufacturers != null

    /** Returns true if device has enough free memory to run a second LiteRT-LM engine concurrently.
 *  Uses Runtime memory heuristics: requires >= 25% of max memory free.
 *  This is the "memory gate" for fast engine loading.
 */
    fun canRunSecondEngine(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val freeMemory = runtime.freeMemory()
        val usedPercent = 100.0 * (maxMemory - freeMemory) / maxMemory
        val sufficient = freeMemory >= (maxMemory * 0.25)  // Need 25% free
        XLog.v(TAG, "Memory gate: free=$freeMemory/${maxMemory} bytes (${"%.1f".format(100 - usedPercent)}% free), sufficient=$sufficient")
        return sufficient
    }

/**
 * Checks if the device has sufficient free memory to run a second LiteRT-LM engine concurrently.
 * Uses Runtime memory heuristics: requires >= 25% of max memory free.
 * This is the "memory gate" for fast engine loading.
 */
fun canRunSecondEngine(): Boolean {
    val runtime = Runtime.getRuntime()
    val maxMemory = runtime.maxMemory()
    val freeMemory = runtime.freeMemory()
    val usedPercent = 100.0 * (maxMemory - freeMemory) / maxMemory
    val sufficient = freeMemory >= (maxMemory * 0.25)  // Need 25% free
    XLog.v(TAG, "Memory gate: free=$freeMemory/${maxMemory} bytes (${"%.1f".format(100 - usedPercent)}% free), sufficient=$sufficient")
    return sufficient
}

fun shouldForceCpu(preferCpu: Boolean): Boolean {
    recoverPendingGpuCrashIfNeeded()
    maybeRearmVerifiedGpu()
    val forceCpu = preferCpu ||
        KVUtils.getLocalBackendPreference().equals("CPU", ignoreCase = true) ||
        isCpuSafeModeEnabled() ||
        shouldStartCpuConservatively()
    if (forceCpu && shouldStartCpuConservatively()) {
        XLog.w(TAG, "Using conservative CPU-first mode on ${deviceDescriptor()}")
    }
    return forceCpu
}

fun isCpuSafeModeEnabled(): Boolean {
    return KVUtils.getLocalCpuSafeDevice() == currentDeviceKey()
}

fun cpuSafeReason(): String = KVUtils.getLocalCpuSafeReason()

fun hasVerifiedGpuSuccess(): Boolean {
    return KVUtils.getLocalGpuVerifiedDevice() == currentDeviceKey() &&
        KVUtils.getLocalGpuVerifiedAt() > 0L
}

fun debugStateSummary(): String {
    val pendingDevice = KVUtils.getPendingLocalGpuInitDevice().ifBlank { "-" }
    val pendingModel = KVUtils.getPendingLocalGpuInitModel().ifBlank { "-" }
    val pendingAt = KVUtils.getPendingLocalGpuInitAt()
    val pendingPid = KVUtils.getPendingLocalGpuInitPid()
    val cpuSafeDevice = KVUtils.getLocalCpuSafeDevice().ifBlank { "-" }
    val gpuVerifiedDevice = KVUtils.getLocalGpuVerifiedDevice().ifBlank { "-" }
    val gpuVerifiedAt = KVUtils.getLocalGpuVerifiedAt()
    val backendPreference = KVUtils.getLocalBackendPreference().ifBlank { "-" }
    val reason = cpuSafeReason().ifBlank { "-" }
    val cpuSafeAt = KVUtils.getLocalCpuSafeAt()
    return buildString {
        append("device=")
        append(currentDeviceKey())
        append(", cpuSafe=")
        append(isCpuSafeModeEnabled())
        append(", cpuSafeDevice=")
        append(cpuSafeDevice)
        append(", backendPreference=")
        append(backendPreference)
        append(", reason=")
        append(reason)
        append(", cpuSafeAt=")
        append(cpuSafeAt)
        append(", gpuVerified=")
        append(hasVerifiedGpuSuccess())
        append(", gpuVerifiedDevice=")
        append(gpuVerifiedDevice)
        append(", gpuVerifiedAt=")
        append(gpuVerifiedAt)
        append(", conservativeCpu=")
        append(shouldStartCpuConservatively())
        append(", pendingDevice=")
        append(pendingDevice)
        append(", pendingModel=")
        append(pendingModel)
        append(", pendingAt=")
        append(pendingAt)
        append(", pendingPid=")
        append(pendingPid)
    }
}
