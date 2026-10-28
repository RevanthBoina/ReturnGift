// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.routing

import org.junit.Assert
import org.junit.Test

/**
 * Trimming is the cheap half of "count every input": the app can see the prompt will
 * not fit *before* paying for a failing call. The rules that matter are what must
 * never be dropped — the system prompt and the newest turn.
 */
class ContextTrimmerTest {

    private fun items(vararg spec: Triple<Int, Int, Boolean>): List<ContextTrimmer.Item> =
        spec.map { (index, chars, droppable) -> ContextTrimmer.Item(index, chars, droppable) }

    @Test
    fun nothingToDropWhenThePromptFits() {
        Assert.assertEquals(emptySet<Int>(), ContextTrimmer.plan(items(Triple(0, 100, true)), 0))
        Assert.assertEquals(emptySet<Int>(), ContextTrimmer.plan(items(Triple(0, 100, true)), -5))
    }

    @Test
    fun oldestDroppableItemsGoFirst() {
        val plan = ContextTrimmer.plan(
            items(Triple(0, 40, true), Triple(1, 40, true), Triple(2, 40, true)),
            excessChars = 50,
        )
        Assert.assertEquals(setOf(0, 1), plan)
    }

    @Test
    fun stopsAsSoonAsEnoughIsFreed() {
        val plan = ContextTrimmer.plan(
            items(Triple(0, 1_000, true), Triple(1, 1_000, true), Triple(2, 1_000, true)),
            excessChars = 900,
        )
        Assert.assertEquals(setOf(0), plan)
    }

    @Test
    fun protectedItemsAreSkippedNotCounted() {
        val plan = ContextTrimmer.plan(
            items(Triple(0, 10_000, false), Triple(1, 500, true)),
            excessChars = 400,
        )
        Assert.assertEquals(setOf(1), plan)
        Assert.assertFalse(plan.contains(0))
    }

    @Test
    fun noDroppableItemMeansNothingIsDropped() {
        Assert.assertEquals(
            emptySet<Int>(),
            ContextTrimmer.plan(items(Triple(0, 10_000, false), Triple(1, 10_000, false)), 5_000),
        )
    }

    // ── classification ──────────────────────────────────────────────────────

    @Test
    fun systemPromptIsNeverDroppable() {
        val plan = ContextTrimmer.classify(listOf("system", "user"), listOf(50_000, 100))
        Assert.assertFalse(plan.first { it.index == 0 }.droppable)
    }

    @Test
    fun newestMessagesAreKept() {
        val kinds = listOf("system", "tool", "assistant", "tool", "user")
        val plan = ContextTrimmer.classify(kinds, List(kinds.size) { 100 }, keepTail = 2)
        Assert.assertFalse(plan.first { it.index == 3 }.droppable)
        Assert.assertFalse(plan.first { it.index == 4 }.droppable)
        Assert.assertTrue(plan.first { it.index == 1 }.droppable)
        Assert.assertTrue(plan.first { it.index == 2 }.droppable)
    }

    @Test
    fun theLatestUserTurnIsAlwaysKept() {
        // Even a stale user turn in the middle (multi-turn) is kept if it is the last
        // user instruction — the task is defined there.
        val kinds = listOf("system", "user", "tool", "assistant", "tool")
        val plan = ContextTrimmer.classify(kinds, List(kinds.size) { 100 }, keepTail = 1)
        Assert.assertFalse(plan.first { it.index == 1 }.droppable)
    }

    @Test
    fun unknownMessageKindsAreNotDroppable() {
        val plan = ContextTrimmer.classify(listOf("system", "weird", "user"), listOf(10, 10, 10))
        Assert.assertFalse(plan.first { it.index == 1 }.droppable)
    }

    @Test
    fun shortListsAreUnaffectedByKeepTail() {
        val plan = ContextTrimmer.classify(listOf("tool"), listOf(10), keepTail = 3)
        Assert.assertFalse(plan.single().droppable)
    }

    @Test
    fun missingSizesCountAsZeroButStillDrop() {
        val plan = ContextTrimmer.classify(listOf("system", "tool", "user"), listOf(10))
        Assert.assertEquals(3, plan.size)
        Assert.assertEquals(0, plan[1].chars)
    }

    // ── end to end with the profiler ────────────────────────────────────────

    @Test
    fun trimmingMeasurablyFreesTheRequiredCharacters() {
        val facts = RequestProfiler.Facts(systemChars = 20_000, toolResultChars = 200_000, messageCount = 12)
        val context = 40_000
        val excess = RequestProfiler.excessChars(facts, context)
        Assert.assertTrue(excess > 0)

        val plan = ContextTrimmer.plan(
            ContextTrimmer.classify(
                kinds = listOf("system") + List(10) { "tool" } + listOf("user"),
                sizes = listOf(20_000) + List(10) { 20_000 } + listOf(200),
                keepTail = 2,
            ),
            excessChars = excess,
        )
        val freed = plan.size * 20_000
        Assert.assertTrue("freed=$freed excess=$excess", freed >= excess)
    }
}
