package com.returngift.agent.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBackendHealthTest {

    // Reference to hardcoded constants for test assertions
    private val expectedManufacturers = setOf("xiaomi", "redmi", "poco")
    private val expectedModels = listOf(
        "xiaomi 15",
        "mi 15",
        "galaxy z fold4",
        "sm-f936",
        "z flip7",
        "sm-f766",
    )
    private val expectedHints = listOf(
        "mt",
        "mediatek",
        "dimensity",
    )

    @Test
    fun `hardcoded conservative CPU manufacturers match expected values`() {
        // Verify the fallback list contains expected manufacturers
        val manufacturers = LocalBackendHealth.getConservativeCpuManufacturers()
        assertTrue(manufacturers.contains("xiaomi"))
        assertTrue(manufacturers.contains("redmi"))
        assertTrue(manufacturers.contains("poco"))
    }

    @Test
    fun `hardcoded conservative CPU models match expected values`() {
        // Verify the fallback list contains expected models
        val models = LocalBackendHealth.getConservativeCpuModels()
        assertTrue(models.any { it.contains("xiaomi 15") })
        assertTrue(models.any { it.contains("galaxy z fold4") })
        assertTrue(models.any { it.contains("sm-f936") })
    }

    @Test
    fun `hardcoded conservative CPU hardware hints match expected values`() {
        // Verify the fallback list contains expected hardware hints
        val hints = LocalBackendHealth.getConservativeCpuHardwareHints()
        assertTrue(hints.contains("mt"))
        assertTrue(hints.contains("mediatek"))
        assertTrue(hints.contains("dimensity"))
    }

    @Test
    fun `getConservativeCpuManufacturers returns hardcoded fallback when not loaded`() {
        // Before JSON load, should return hardcoded values
        val manufacturers = LocalBackendHealth.getConservativeCpuManufacturers()
        assertEquals(expectedManufacturers, manufacturers)
    }

    @Test
    fun `getConservativeCpuModels returns hardcoded fallback when not loaded`() {
        // Before JSON load, should return hardcoded values
        val models = LocalBackendHealth.getConservativeCpuModels()
        assertEquals(expectedModels, models)
    }

    @Test
    fun `getConservativeCpuHardwareHints returns hardcoded fallback when not loaded`() {
        // Before JSON load, should return hardcoded values
        val hints = LocalBackendHealth.getConservativeCpuHardwareHints()
        assertEquals(expectedHints, hints)
    }

    @Test
    fun `conservative CPU logic uses manufacturer from list`() {
        // xiaomi should trigger conservative mode
        assertTrue(
            LocalBackendHealth.shouldConservativelyForceCpu(
                manufacturer = "xiaomi",
                model = "random model",
                hardware = "somehardware",
                hasVerifiedGpuSuccess = false,
                isCpuSafeModeEnabled = false,
            )
        )
    }

    @Test
    fun `conservative CPU logic uses model from list`() {
        // Galaxy Z Fold4 model should trigger conservative mode
        assertTrue(
            LocalBackendHealth.shouldConservativelyForceCpu(
                manufacturer = "samsung",
                model = "galaxy z fold4",
                hardware = "somehardware",
                hasVerifiedGpuSuccess = false,
                isCpuSafeModeEnabled = false,
            )
        )
    }

    @Test
    fun `conservative CPU logic uses hardware hint from list`() {
        // MediaTek hardware should trigger conservative mode
        assertTrue(
            LocalBackendHealth.shouldConservativelyForceCpu(
                manufacturer = "google",
                model = "pixel 9",
                hardware = "mt6989",
                hasVerifiedGpuSuccess = false,
                isCpuSafeModeEnabled = false,
            )
        )
    }

    @Test
    fun `conservative CPU logic does not trigger for unknown devices`() {
        // Unknown manufacturer/model/hardware should not trigger conservative mode
        assertFalse(
            LocalBackendHealth.shouldConservativelyForceCpu(
                manufacturer = "google",
                model = "pixel 9",
                hardware = "tensor",
                hasVerifiedGpuSuccess = false,
                isCpuSafeModeEnabled = false,
            )
        )
    }

    @Test
    fun `promotes pending gpu init crash on same device within age window`() {
        val now = 1_000_000L
        assertTrue(
            LocalBackendHealth.shouldPromotePendingGpuCrash(
                currentDeviceKey = "device-a",
                pendingDeviceKey = "device-a",
                pendingAtMs = now - 5_000L,
                pendingPid = 0,
                nowMs = now,
            )
        )
    }

    @Test
    fun `does not promote pending gpu init crash for another device`() {
        val now = 1_000_000L
        assertFalse(
            LocalBackendHealth.shouldPromotePendingGpuCrash(
                currentDeviceKey = "device-a",
                pendingDeviceKey = "device-b",
                pendingAtMs = now - 5_000L,
                pendingPid = 0,
                nowMs = now,
            )
        )
    }

    @Test
    fun `does not promote stale pending gpu init crash`() {
        val now = 1_000_000L
        assertFalse(
            LocalBackendHealth.shouldPromotePendingGpuCrash(
                currentDeviceKey = "device-a",
                pendingDeviceKey = "device-a",
                pendingAtMs = now - 100_000L,
                pendingPid = 0,
                nowMs = now,
                maxAgeMs = 10_000L,
            )
        )
    }

    @Test
    fun `conservative cpu applies to xiaomi before gpu is verified`() {
        assertTrue(
            LocalBackendHealth.shouldConservativelyForceCpu(
                manufacturer = "xiaomi",
                model = "xiaomi 15",
                hardware = "kalama",
                hasVerifiedGpuSuccess = false,
                isCpuSafeModeEnabled = false,
            )
        )
    }

    @Test
    fun `conservative cpu applies to mediatek style hardware before gpu is verified`() {
        assertTrue(
            LocalBackendHealth.shouldConservativelyForceCpu(
                manufacturer = "vivo",
                model = "vivo y27",
                hardware = "mt6989",
                hasVerifiedGpuSuccess = false,
                isCpuSafeModeEnabled = false,
            )
        )
    }

    @Test
    fun `conservative cpu applies to fold4 model before gpu is verified`() {
        assertTrue(
            LocalBackendHealth.shouldConservativelyForceCpu(
                manufacturer = "samsung",
                model = "sm-f936b",
                hardware = "qcom",
                hasVerifiedGpuSuccess = false,
                isCpuSafeModeEnabled = false,
            )
        )
    }

    @Test
    fun `conservative cpu does not apply after gpu is verified`() {
        assertFalse(
            LocalBackendHealth.shouldConservativelyForceCpu(
                manufacturer = "xiaomi",
                model = "xiaomi 15",
                hardware = "kalama",
                hasVerifiedGpuSuccess = true,
                isCpuSafeModeEnabled = false,
            )
        )
    }

    @Test
    fun `rearms verified gpu after stale cpu safe quarantine`() {
        val now = 200_000_000L
        assertTrue(
            LocalBackendHealth.shouldRearmVerifiedGpu(
                isCpuSafeModeEnabled = true,
                hasVerifiedGpuSuccess = true,
                hasPendingGpuInitMarker = false,
                cpuSafeReason = "gpu_init_crash: gemma-4-E4B-it.litertlm: previous GPU engine init died before cleanup",
                cpuSafeAtMs = now - 90_000_000L,
                nowMs = now,
                cooldownMs = 1_000L,
            )
        )
    }

    @Test
    fun `does not rearm verified gpu during fresh cpu safe quarantine`() {
        val now = 2_000_000L
        assertFalse(
            LocalBackendHealth.shouldRearmVerifiedGpu(
                isCpuSafeModeEnabled = true,
                hasVerifiedGpuSuccess = true,
                hasPendingGpuInitMarker = false,
                cpuSafeReason = "gpu_init_crash: gemma-4-E4B-it.litertlm: previous GPU engine init died before cleanup",
                cpuSafeAtMs = now - 500L,
                nowMs = now,
                cooldownMs = 1_000L,
            )
        )
    }

    @Test
    fun `does not rearm verified gpu for non crash cpu safe reason`() {
        val now = 200_000_000L
        assertFalse(
            LocalBackendHealth.shouldRearmVerifiedGpu(
                isCpuSafeModeEnabled = true,
                hasVerifiedGpuSuccess = true,
                hasPendingGpuInitMarker = false,
                cpuSafeReason = "gpu_failure: gemma-4-E4B-it.litertlm: OpenCL init failed",
                cpuSafeAtMs = now - 90_000_000L,
                nowMs = now,
                cooldownMs = 1_000L,
            )
        )
    }
}
