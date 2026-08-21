package com.returngift.agent.agent.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObserveStallGuardTest {

    @Test
    fun actionResetsIdleCounter() {
        val guard = ObserveStallGuard()
        assertEquals(ObserveStallGuard.Verdict.OK, guard.recordRound(madeAction = false, screenHash = 100))
        assertEquals(ObserveStallGuard.Verdict.OK, guard.recordRound(madeAction = false, screenHash = 100))
        // One action round clears the streak, so the next idle round is not a hint.
        assertEquals(ObserveStallGuard.Verdict.OK, guard.recordRound(madeAction = true, screenHash = 100))
        assertEquals(ObserveStallGuard.Verdict.OK, guard.recordRound(madeAction = false, screenHash = 100))
        assertEquals(ObserveStallGuard.Verdict.HINT, guard.recordRound(madeAction = false, screenHash = 100))
    }

    @Test
    fun screenChangeResetsIdleCounter() {
        val guard = ObserveStallGuard()
        guard.recordRound(madeAction = false, screenHash = 100)
        assertEquals(ObserveStallGuard.Verdict.OK, guard.recordRound(madeAction = false, screenHash = 100))
        // Screen changed -> progress, streak cleared even without an action.
        assertEquals(ObserveStallGuard.Verdict.OK, guard.recordRound(madeAction = false, screenHash = 200))
        assertEquals(ObserveStallGuard.Verdict.OK, guard.recordRound(madeAction = false, screenHash = 200))
        assertEquals(ObserveStallGuard.Verdict.HINT, guard.recordRound(madeAction = false, screenHash = 200))
    }

    @Test
    fun hintAtTwoIdleRounds() {
        val guard = ObserveStallGuard()
        guard.recordRound(madeAction = false, screenHash = 42)
        assertEquals(ObserveStallGuard.Verdict.OK, guard.recordRound(madeAction = false, screenHash = 42))
        assertEquals(ObserveStallGuard.Verdict.HINT, guard.recordRound(madeAction = false, screenHash = 42))
    }

    @Test
    fun hintInjectedOnlyOncePerStreak() {
        val guard = ObserveStallGuard()
        guard.recordRound(madeAction = false, screenHash = 42)
        guard.recordRound(madeAction = false, screenHash = 42)
        assertEquals(ObserveStallGuard.Verdict.HINT, guard.recordRound(madeAction = false, screenHash = 42))
        // 3rd consecutive idle round: hint already injected, not repeated.
        assertEquals(ObserveStallGuard.Verdict.OK, guard.recordRound(madeAction = false, screenHash = 42))
    }

    @Test
    fun abortAtFourIdleRounds() {
        val guard = ObserveStallGuard()
        guard.recordRound(madeAction = false, screenHash = 42)
        guard.recordRound(madeAction = false, screenHash = 42)
        guard.recordRound(madeAction = false, screenHash = 42)
        guard.recordRound(madeAction = false, screenHash = 42)
        assertEquals(ObserveStallGuard.Verdict.ABORT, guard.recordRound(madeAction = false, screenHash = 42))
        // Stays aborted while the stall continues.
        assertEquals(ObserveStallGuard.Verdict.ABORT, guard.recordRound(madeAction = false, screenHash = 42))
    }

    @Test
    fun zeroHashCountsAsIdle() {
        val guard = ObserveStallGuard()
        // hash 0 = unknown screen; must never be treated as progress.
        assertEquals(ObserveStallGuard.Verdict.OK, guard.recordRound(madeAction = false, screenHash = 0))
        assertEquals(ObserveStallGuard.Verdict.HINT, guard.recordRound(madeAction = false, screenHash = 0))
        assertEquals(ObserveStallGuard.Verdict.OK, guard.recordRound(madeAction = false, screenHash = 0))
        assertEquals(ObserveStallGuard.Verdict.ABORT, guard.recordRound(madeAction = false, screenHash = 0))
    }

    @Test
    fun tokenBurnReproduction_observeOnlyLoopAbortsBeforeBudgetBurn() {
        // Reproduces the production pattern: the model called get_screen_info every
        // round on an unchanged screen and burned 121.8K / 74.1K tokens. The guard
        // must abort after hintThreshold..abortThreshold idle rounds, not at 200K tokens.
        val guard = ObserveStallGuard()
        val screenHash = "com.example.app/home screen node tree".hashCode()
        val verdicts = (1..10).map { guard.recordRound(madeAction = false, screenHash = screenHash) }
        assertEquals(1, verdicts.count { it == ObserveStallGuard.Verdict.HINT })
        assertTrue(verdicts.count { it == ObserveStallGuard.Verdict.ABORT } >= 1)
        assertEquals(ObserveStallGuard.Verdict.ABORT, verdicts.last())
        // First abort happens at the abortThreshold-th consecutive idle round.
        assertEquals(
            ObserveStallGuard.Verdict.ABORT,
            verdicts[ObserveStallGuard().abortThreshold - 1 + 1]
        )
    }

    @Test
    fun customThresholdsAreHonored() {
        val guard = ObserveStallGuard(hintThreshold = 1, abortThreshold = 2)
        guard.recordRound(madeAction = false, screenHash = 7) // first non-zero hash registers the screen
        assertEquals(ObserveStallGuard.Verdict.HINT, guard.recordRound(madeAction = false, screenHash = 7))
        assertEquals(ObserveStallGuard.Verdict.ABORT, guard.recordRound(madeAction = false, screenHash = 7))
    }

    @Test
    fun resetClearsState() {
        val guard = ObserveStallGuard()
        repeat(3) { guard.recordRound(madeAction = false, screenHash = 9) }
        guard.reset()
        assertEquals(ObserveStallGuard.Verdict.OK, guard.recordRound(madeAction = false, screenHash = 9))
    }
}
