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

    fun currentDeviceKey(): String {
        val fingerprint = Build.FINGERPRINT?.trim().orEmpty()
        if (fingerprint.isNotEmpty()) return fingerprint
        return listOf(Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.HARDWARE)
            .filter { !it.isNullOrBlank() }
            .joinToString("|")
    }

    fun shouldForceCpu(preferCpu: Boolean): Boolean {
        LocalBackendHealth.recoverPendingGpuCrashIfNeeded()
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

    fun debugForceCpuSafe(reason: String = "debug") {
        enableCpuSafeMode(reason)
    }

    fun debugClearCpuSafeMode() {
        KVUtils.clearLocalCpuSafeMode()
        if (KVUtils.getLocalBackendPreference().equals("CPU", ignoreCase = true)) {
            KVUtils.setLocalBackendPreference("")
        }
    }

    fun debugClearGpuVerified() {
        KVUtils.clearLocalGpuVerified()
    }

    fun debugMarkPendingGpuInit(modelPath: String) {
        markGpuInitStarted(modelPath)
    }

    fun debugClearPendingGpuInit() {
        KVUtils.clearPendingLocalGpuInit()
    }

    fun noteRecoverableGpuFailure(modelPath: String, error: Throwable?) {
        val reason = buildReason("gpu_failure", modelPath, error?.message)
        enableCpuSafeMode(reason)
        KVUtils.clearPendingLocalGpuInit()
        XLog.w(TAG, "GPU backend marked unsafe for this device: $reason")
    }

    fun noteGpuInitSuccess(modelPath: String) {
        KVUtils.setLocalGpuVerifiedDevice(currentDeviceKey())
        KVUtils.setLocalGpuVerifiedAt(System.currentTimeMillis())
        KVUtils.clearPendingLocalGpuInit()
        XLog.i(TAG, "GPU backend verified healthy for ${modelPath.substringAfterLast('/')}")
    }

    fun markGpuInitStarted(modelPath: String) {
        KVUtils.setPendingLocalGpuInitDevice(currentDeviceKey())
        KVUtils.setPendingLocalGpuInitModel(modelPath)
        KVUtils.setPendingLocalGpuInitAt(System.currentTimeMillis())
        KVUtils.setPendingLocalGpuInitPid(Process.myPid())
        XLog.i(TAG, "Marked GPU init pending for ${modelPath.substringAfterLast('/')}")
    }

    fun markGpuInitFinished() {
        KVUtils.clearPendingLocalGpuInit()
    }

    fun recoverPendingGpuCrashIfNeeded(): Boolean {
        val pendingDevice = KVUtils.getPendingLocalGpuInitDevice()
        val pendingAt = KVUtils.getPendingLocalGpuInitAt()
        val pendingPid = KVUtils.getPendingLocalGpuInitPid()
        if (!shouldPromotePendingGpuCrash(currentDeviceKey(), pendingDevice, pendingAt, pendingPid, System.currentTimeMillis())) {
            return false
        }

        val modelPath = KVUtils.getPendingLocalGpuInitModel()
        val reason = buildReason("gpu_init_crash", modelPath, "previous GPU engine init died before cleanup")
        enableCpuSafeMode(reason)
        KVUtils.clearPendingLocalGpuInit()
        XLog.w(TAG, "Recovered pending GPU init crash; forcing CPU-safe mode for this device")
        return true
    }

    internal fun shouldPromotePendingGpuCrash(
        currentDeviceKey: String,
        pendingDeviceKey: String?,
        pendingAtMs: Long,
        pendingPid: Int,
        nowMs: Long,
        maxAgeMs: Long = CRASH_MARKER_MAX_AGE_MS,
    ): Boolean {
        if (pendingDeviceKey.isNullOrBlank()) return false
        if (pendingDeviceKey != currentDeviceKey) return false
        if (pendingAtMs <= 0L) return false
        if (pendingPid > 0 && pendingPid == Process.myPid()) return false
        return nowMs - pendingAtMs <= maxAgeMs
    }

    private fun enableCpuSafeMode(reason: String) {
        val now = System.currentTimeMillis()
        KVUtils.setLocalCpuSafeDevice(currentDeviceKey())
        KVUtils.setLocalCpuSafeReason(reason)
        KVUtils.setLocalCpuSafeAt(now)
        KVUtils.setLocalBackendPreference("CPU")
    }

    private fun maybeRearmVerifiedGpu(nowMs: Long = System.currentTimeMillis()) {
        if (!shouldRearmVerifiedGpu(
                isCpuSafeModeEnabled = isCpuSafeModeEnabled(),
                hasVerifiedGpuSuccess = hasVerifiedGpuSuccess(),
                hasPendingGpuInitMarker = hasPendingGpuInitMarker(),
                cpuSafeReason = cpuSafeReason(),
                cpuSafeAtMs = KVUtils.getLocalCpuSafeAt(),
                nowMs = nowMs,
            )) {
            return
        }

        XLog.w(
            TAG,
            "Re-arming verified GPU backend after stale CPU-safe quarantine on ${deviceDescriptor()}",
        )
        KVUtils.clearLocalCpuSafeMode()
        if (KVUtils.getLocalBackendPreference().equals("CPU", ignoreCase = true)) {
            KVUtils.setLocalBackendPreference("")
        }
    }

    private fun shouldStartCpuConservatively(): Boolean {
        val manufacturer = Build.MANUFACTURER?.trim()?.lowercase().orEmpty()
        val model = Build.MODEL?.trim()?.lowercase().orEmpty()
        val hardware = Build.HARDWARE?.trim()?.lowercase().orEmpty()
        return shouldConservativelyForceCpu(
            manufacturer = manufacturer,
            model = model,
            hardware = hardware,
            hasVerifiedGpuSuccess = hasVerifiedGpuSuccess(),
            isCpuSafeModeEnabled = isCpuSafeModeEnabled(),
        )
    }

    private fun deviceDescriptor(): String {
        return listOf(Build.MANUFACTURER, Build.MODEL, Build.HARDWARE)
            .filter { !it.isNullOrBlank() }
            .joinToString(" / ")
    }

    fun debugDeviceDescriptor(): String = deviceDescriptor()

    fun isConservativeCpuModeSuggested(): Boolean = shouldStartCpuConservatively()

    fun hasPendingGpuInitMarker(): Boolean {
        return shouldPromotePendingGpuCrash(
            currentDeviceKey = currentDeviceKey(),
            pendingDeviceKey = KVUtils.getPendingLocalGpuInitDevice(),
            pendingAtMs = KVUtils.getPendingLocalGpuInitAt(),
            pendingPid = KVUtils.getPendingLocalGpuInitPid(),
            nowMs = System.currentTimeMillis(),
        )
    }

    internal fun shouldConservativelyForceCpu(
        manufacturer: String,
        model: String,
        hardware: String,
        hasVerifiedGpuSuccess: Boolean,
        isCpuSafeModeEnabled: Boolean,
    ): Boolean {
        if (hasVerifiedGpuSuccess) return false
        if (isCpuSafeModeEnabled) return false
        val manufacturers = getConservativeCpuManufacturers()
        val models = getConservativeCpuModels()
        val hints = getConservativeCpuHardwareHints()
        if (manufacturer in manufacturers) return true
        if (models.any { model.contains(it) }) return true
        return hints.any { hint ->
            hardware.contains(hint) || model.contains(hint)
        }
    }

    private fun buildReason(prefix: String, modelPath: String, detail: String?): String {
        val modelName = modelPath.substringAfterLast('/')
        return listOf(prefix, modelName, detail?.take(120))
            .filter { !it.isNullOrBlank() }
            .joinToString(": ")
    }

    internal fun shouldRearmVerifiedGpu(
        isCpuSafeModeEnabled: Boolean,
        hasVerifiedGpuSuccess: Boolean,
        hasPendingGpuInitMarker: Boolean,
        cpuSafeReason: String,
        cpuSafeAtMs: Long,
        nowMs: Long,
        cooldownMs: Long = VERIFIED_GPU_CPU_SAFE_RETRY_COOLDOWN_MS,
    ): Boolean {
        if (!isCpuSafeModeEnabled) return false
        if (!hasVerifiedGpuSuccess) return false
        if (hasPendingGpuInitMarker) return false
        if (!cpuSafeReason.startsWith("gpu_init_crash")) return false
        if (cpuSafeAtMs <= 0L) return false
        return nowMs - cpuSafeAtMs >= cooldownMs
    }
}
