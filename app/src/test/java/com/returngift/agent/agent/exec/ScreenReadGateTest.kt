// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenReadGateTest {

    @Test
    fun `first read is always allowed`() {
        val gate = ScreenReadGate()
        assertTrue(gate.requestRead(ScreenReadGate.Purpose.STATE_ENTRY, 100) is ScreenReadGate.Decision.Allow)
    }

    @Test
    fun `passive re-reads of unchanged screen are denied after the cap`() {
        val gate = ScreenReadGate()
        gate.requestRead(ScreenReadGate.Purpose.STATE_ENTRY, 100)
        gate.recordRead(100)
        // No action since last read, same signature → passive.
        gate.requestRead(ScreenReadGate.Purpose.STATE_ENTRY, 100)
        gate.recordRead(100)
        gate.requestRead(ScreenReadGate.Purpose.STATE_ENTRY, 100)
        gate.recordRead(100)
        // Third consecutive passive read (cap = 2) must be denied.
        val decision = gate.requestRead(ScreenReadGate.Purpose.STATE_ENTRY, 100)
        assertTrue(decision is ScreenReadGate.Decision.Deny)
        assertTrue((decision as ScreenReadGate.Decision.Deny).guidance.contains("act", ignoreCase = true))
    }

    @Test
    fun `an action between reads resets the passive streak`() {
        val gate = ScreenReadGate()
        repeat(2) {
            gate.requestRead(ScreenReadGate.Purpose.STATE_ENTRY, 100)
            gate.recordRead(100)
        }
        gate.recordAction()
        val decision = gate.requestRead(ScreenReadGate.Purpose.POST_ACTION_VERIFY, 100)
        assertTrue(decision is ScreenReadGate.Decision.Allow)
    }

    @Test
    fun `verify and failure purposes are always justified while budget remains`() {
        val gate = ScreenReadGate()
        repeat(3) {
            gate.requestRead(ScreenReadGate.Purpose.POST_ACTION_VERIFY, 100)
            gate.recordRead(100)
        }
        // Verification reads are justified even when passive-looking.
        assertTrue(gate.requestRead(ScreenReadGate.Purpose.POST_ACTION_VERIFY, 100) is ScreenReadGate.Decision.Allow)
        assertTrue(gate.requestRead(ScreenReadGate.Purpose.ACTION_FAILURE, 100) is ScreenReadGate.Decision.Allow)
    }

    @Test
    fun `total read budget is enforced`() {
        val gate = ScreenReadGate(maxReads = 3)
        repeat(3) {
            gate.recordAction()
            gate.requestRead(ScreenReadGate.Purpose.POST_ACTION_VERIFY, 100L)
            gate.recordRead(100L)
        }
        gate.recordAction()
        val decision = gate.requestRead(ScreenReadGate.Purpose.POST_ACTION_VERIFY, 99L)
        assertTrue(decision is ScreenReadGate.Decision.Deny)
        assertTrue((decision as ScreenReadGate.Decision.Deny).guidance.contains("budget", ignoreCase = true))
    }
}
