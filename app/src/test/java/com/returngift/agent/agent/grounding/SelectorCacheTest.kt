// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.grounding

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the [SelectorCache.hasValidFingerprint] read-only query that
 * FastRoundRouter uses to decide whether the current round can be safely
 * served by the small (fast) engine.
 */
class SelectorCacheTest {

    @Before
    fun setUp() {
        SelectorCache.clear()
    }

    @After
    fun tearDown() {
        SelectorCache.clear()
    }

    @Test fun `hasValidFingerprint true when tracked fingerprint matches`() {
        SelectorCache.updateFingerprint("com.example", 42L)
        assertTrue(SelectorCache.hasValidFingerprint("com.example", 42L))
    }

    @Test fun `hasValidFingerprint false when tracked fingerprint differs`() {
        SelectorCache.updateFingerprint("com.example", 42L)
        assertFalse(SelectorCache.hasValidFingerprint("com.example", 99L))
    }

    @Test fun `hasValidFingerprint false for unknown package`() {
        SelectorCache.updateFingerprint("com.example", 42L)
        assertFalse(SelectorCache.hasValidFingerprint("com.other", 42L))
    }

    @Test fun `hasValidFingerprint false when no package has been tracked`() {
        // Cold start — no entries at all.
        assertFalse(SelectorCache.hasValidFingerprint("com.example", 42L))
    }

    @Test fun `invalidatePackage drops the tracked fingerprint`() {
        SelectorCache.updateFingerprint("com.example", 42L)
        SelectorCache.invalidatePackage("com.example")
        assertFalse(SelectorCache.hasValidFingerprint("com.example", 42L))
    }
}
