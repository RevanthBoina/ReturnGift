// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec

/**
 * Screen-read gate (pure Kotlin, JVM-unit-testable).
 *
 * Core invariant: EVERY screen observation must have a specific decision or
 * verification purpose. If the next get_screen_info() call has no clearly
 * defined purpose, it must not execute. A read is PASSIVE when no device action
 * happened since the previous read AND the screen signature is unchanged —
 * passive re-reads of an unchanged screen are the token-burn pattern that
 * killed the OmniRoute Q&A sessions (3 identical diffs → watchdog hard stop,
 * ~211K tokens, 120s empty stream).
 */
class ScreenReadGate(
    private val maxReads: Int = ExecutionBudget.DEFAULT_MAX_SCREEN_READS,
    private val maxConsecutivePassiveReads: Int = ExecutionBudget.DEFAULT_MAX_CONSECUTIVE_PASSIVE_READS,
) {

    /** Why a read is being requested — every read must declare one. */
    enum class Purpose {
        /** Entering a new executor state (first look at a screen). */
        STATE_ENTRY,
        /** Verifying the effect of an action that just ran. */
        POST_ACTION_VERIFY,
        /** An action failed — one re-read to see what is actually on screen. */
        ACTION_FAILURE,
        /** A navigation/transition tool ran (open_app, switch_app, system_key). */
        UI_TRANSITION,
        /** Deterministic target resolution failed; AI escalation needs context. */
        RESOLUTION_ESCALATION,
    }

    sealed class Decision {
        object Allow : Decision()
        /** Denied — [guidance] is fed back instead of the screen tree. */
        data class Deny(val guidance: String) : Decision()
    }

    var totalReads = 0
        private set
    var consecutivePassiveReads = 0
        private set
    private var actionsSinceLastRead = 0
    private var lastSignature: Int? = null

    /** Record that a device action executed (resets the passive-read streak). */
    fun recordAction() {
        actionsSinceLastRead++
    }

    /**
     * Ask permission for a read.
     * @param purpose   why the read is needed
     * @param newSignature hash of the CURRENT screen state if cheaply known, else null
     */
    fun requestRead(purpose: Purpose, newSignature: Int? = null): Decision {
        if (totalReads >= maxReads) {
            return Decision.Deny(
                "Screen-read budget exhausted ($totalReads/$maxReads). " +
                    "Act on the information you already have or call finish with what is done."
            )
        }
        val unchanged = newSignature != null && newSignature == lastSignature
        val passive = actionsSinceLastRead == 0 && (unchanged || newSignature == null) && totalReads > 0
        // Purposes that always justify a read even when the screen looks unchanged:
        // post-action verification and action-failure diagnosis.
        val justified = purpose == Purpose.POST_ACTION_VERIFY ||
            purpose == Purpose.ACTION_FAILURE ||
            purpose == Purpose.UI_TRANSITION ||
            purpose == Purpose.RESOLUTION_ESCALATION
        if (passive && !justified) {
            if (consecutivePassiveReads + 1 > maxConsecutivePassiveReads) {
                return Decision.Deny(
                    "The screen has not changed since the last read and no action has run. " +
                        "Do NOT observe again — identify the target from the last screen " +
                        "description and act on it now (or call finish)."
                )
            }
        }
        return Decision.Allow
    }

    /** Record that a read actually happened (call after a successful read). */
    fun recordRead(signature: Int? = null) {
        // Passive = a re-read (there WAS a previous read) with no action in
        // between and no evidence of change. The first read is never passive.
        val wasPassive = actionsSinceLastRead == 0 && totalReads > 0 &&
            (signature == null || signature == lastSignature)
        totalReads++
        consecutivePassiveReads = if (wasPassive) consecutivePassiveReads + 1 else 0
        actionsSinceLastRead = 0
        if (signature != null) lastSignature = signature
    }
}
