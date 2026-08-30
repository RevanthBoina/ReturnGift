// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth-table for the pure [LocalBackendHealth.shouldAllowFastEngine] decision.
 * The wrapper that takes a Context is exercised in device QA — this unit test
 * pins the threshold math.
 */
class LocalBackendHealthFastEngineTest {

    @Test fun `rejects when device RAM below minimum for fast engine`() {
        // 4GB device, plenty of free RAM — still rejected because 4 < 6.
        val allowed = LocalBackendHealth.shouldAllowFastEngine(
            deviceRamGb = 4,
            availableMemoryMb = 2000,
            minRamForFastGb = 6,
            thresholdPct = 70,
        )
        assertFalse(allowed)
    }

    @Test fun `accepts 8GB device with comfortable free RAM`() {
        val allowed = LocalBackendHealth.shouldAllowFastEngine(
            deviceRamGb = 8,
            availableMemoryMb = 8000,
            minRamForFastGb = 6,
            thresholdPct = 70,
        )
        assertTrue(allowed)
    }

    @Test fun `rejects 8GB device when free RAM is low`() {
        // 8GB total, 2GB free → 25% free < 70% threshold.
        val allowed = LocalBackendHealth.shouldAllowFastEngine(
            deviceRamGb = 8,
            availableMemoryMb = 2000,
            minRamForFastGb = 6,
            thresholdPct = 70,
        )
        assertFalse(allowed)
    }

    @Test fun `rejects at the threshold boundary when free RAM is below`() {
        // 6GB device, 2GB free → 33% free < 70%. Device RAM OK but memory tight.
        val allowed = LocalBackendHealth.shouldAllowFastEngine(
            deviceRamGb = 6,
            availableMemoryMb = 2000,
            minRamForFastGb = 6,
            thresholdPct = 70,
        )
        assertFalse(allowed)
    }

    @Test fun `accepts at the threshold boundary when free RAM is exactly at threshold`() {
        // 8GB device, 5880MB free → 71% (rounded down 5875MB → 71%) ≥ 70%.
        val allowed = LocalBackendHealth.shouldAllowFastEngine(
            deviceRamGb = 8,
            availableMemoryMb = 5875,
            minRamForFastGb = 6,
            thresholdPct = 70,
        )
        assertTrue(allowed)
    }

    @Test fun `rejects when available memory reports zero`() {
        // Edge: ActivityManager returned 0 (denied perm or stale). The policy is
        // conservative — 0 free means we cannot verify the threshold, so reject.
        val allowed = LocalBackendHealth.shouldAllowFastEngine(
            deviceRamGb = 8,
            availableMemoryMb = 0,
            minRamForFastGb = 6,
            thresholdPct = 70,
        )
        assertFalse(allowed)
    }
}
