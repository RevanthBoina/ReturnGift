// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.tracker

import com.returngift.agent.utils.KVUtils

/**
 * Local, boring counters: which channel carried the task, which engine took it, which
 * routine or skill ran, how many model calls it cost.
 *
 * Nothing is sent anywhere and nothing branches on these numbers — they exist so that
 * "which channels do I actually use" and "which repeated task still hits the model"
 * are answered by data instead of a guess. Cheap enough to call on a hot path.
 */
object UsageCounters {

    private const val PREFIX = "usage_"

    fun bump(group: String, name: String) {
        if (!KVUtils.isInitialized) return
        val key = PREFIX + group + ":" + name.lowercase()
        runCatching { KVUtils.putInt(key, KVUtils.getInt(key, 0) + 1) }
    }

    /** All counters as "group:name" → count, biggest first. */
    fun all(): List<Pair<String, Int>> {
        if (!KVUtils.isInitialized) return emptyList()
        return KVUtils.getAllKeys()
            .filter { it.startsWith(PREFIX) }
            .map { it.removePrefix(PREFIX) to KVUtils.getInt(it, 0) }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    }

    fun clear() {
        if (!KVUtils.isInitialized) return
        KVUtils.getAllKeys().filter { it.startsWith(PREFIX) }.forEach { KVUtils.remove(it) }
    }
}
