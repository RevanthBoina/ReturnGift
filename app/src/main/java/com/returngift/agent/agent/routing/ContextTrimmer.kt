// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.routing

/**
 * Decides *what to drop* when a prompt will not fit.
 *
 * The review's routing complaint was about honesty: the estimate now counts every
 * serialised part, which means the app can also see — before spending a round trip —
 * that the conversation no longer fits. Overflowing at the provider costs a failure
 * (and sometimes money); trimming locally costs nothing and keeps the tail of the
 * conversation, which is the part the task depends on.
 *
 * The policy is deliberately dull and explainable:
 *  - **never** drop the system prompt (it holds the safety rules);
 *  - never drop the newest message or the newest user turn;
 *  - drop the *oldest* droppable items first (old screen dumps are the cheapest loss);
 *  - stop as soon as enough characters are gone.
 *
 * Pure arithmetic on sizes, so it is testable without langchain4j message types.
 */
object ContextTrimmer {

    /** One serialised message, as the caller measured it. */
    data class Item(
        val index: Int,
        val chars: Int,
        /** True when dropping this message cannot break the conversation's shape. */
        val droppable: Boolean,
    )

    /**
     * Indices to drop so that at least [excessChars] characters are removed.
     *
     * @return an empty set when nothing in [items] is droppable
     */
    fun plan(items: List<Item>, excessChars: Int): Set<Int> {
        if (excessChars <= 0) return emptySet()
        val dropped = mutableSetOf<Int>()
        var freed = 0
        for (item in items.sortedBy { it.index }) {
            if (freed >= excessChars) break
            if (!item.droppable) continue
            dropped += item.index
            freed += item.chars
        }
        return dropped
    }

    /**
     * Classify messages for [plan] without knowing the concrete message classes.
     *
     * @param kinds one label per message, oldest first: `system`, `user`, `assistant`,
     *        `tool`, or anything else
     * @param sizes character count of each message
     * @param keepTail how many of the newest messages are never dropped
     */
    fun classify(
        kinds: List<String>,
        sizes: List<Int>,
        keepTail: Int = 2,
    ): List<Item> {
        val lastUser = kinds.indexOfLast { it == "user" }
        return kinds.indices.map { index ->
            val kind = kinds[index].lowercase()
            val recent = index >= kinds.size - keepTail
            val droppable = !recent &&
                index != lastUser &&
                kind != "system" &&
                (kind == "tool" || kind == "assistant")
            Item(index = index, chars = sizes.getOrElse(index) { 0 }, droppable = droppable)
        }
    }
}
