// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.knowledge

import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog

/**
 * Runtime app knowledge: rows learned from tasks or delivered by a connector.
 *
 * This is the only writable half of the app guide. Built-in rows stay in
 * [AppUsageGuide] (compiled in with the repo); anything the device discovers or a
 * connector updates lands here, and [mergeWith] decides who wins.
 *
 * Nothing in this class knows about a specific app — adding one is data.
 */
object AppGuideStore {

    private const val TAG = "AppGuideStore"
    private const val KEY_PREFIX = "app_guide_v1_"
    private const val INDEX_KEY = "app_guide_v1_index"

    /** Storage seam so the merge rules are unit-testable without MMKV. */
    interface Storage {
        fun getString(key: String, defaultValue: String): String
        fun putString(key: String, value: String)
        fun remove(key: String)
    }

    object KVStorage : Storage {
        override fun getString(key: String, defaultValue: String): String =
            if (KVUtils.isInitialized) KVUtils.getString(key, defaultValue) else defaultValue

        override fun putString(key: String, value: String) {
            if (KVUtils.isInitialized) KVUtils.putString(key, value)
        }

        override fun remove(key: String) {
            if (KVUtils.isInitialized) KVUtils.remove(key)
        }
    }

    internal var storage: Storage = KVStorage

    /** Hard cap: the prompt only ever shows a handful, and KV must stay small. */
    const val MAX_ROWS = 64

    // ── Read ────────────────────────────────────────────────────────────────

    fun all(): List<AppGuide> =
        readIndex().mapNotNull { pkg -> get(pkg) }

    fun get(packageName: String): AppGuide? {
        val key = keyOf(packageName) ?: return null
        return try {
            AppGuideCodec.decode(storage.getString(key, ""))
        } catch (e: Throwable) {
            XLog.w(TAG, "could not read guide for $packageName", e)
            null
        }
    }

    fun isEmpty(): Boolean = readIndex().isEmpty()

    // ── Write ───────────────────────────────────────────────────────────────

    /**
     * Store [guide]; an older row for the same package is replaced only when the
     * new one wins the merge (see [AppGuide.beats]).
     *
     * @return true when the store changed
     */
    fun upsert(guide: AppGuide): Boolean {
        val key = keyOf(guide.packageName) ?: return false
        val existing = get(guide.packageName)
        val next = existing?.mergedWith(guide) ?: guide
        val before = existing?.let { AppGuideCodec.encode(it) }
        val after = AppGuideCodec.encode(next)
        if (before == after) return false
        return try {
            storage.putString(key, after)
            if (existing == null) addToIndex(guide.packageName)
            XLog.i(TAG, "stored ${guide.source} guide for ${guide.packageName}")
            true
        } catch (e: Throwable) {
            XLog.w(TAG, "could not store guide for ${guide.packageName}", e)
            false
        }
    }

    fun upsertAll(guides: List<AppGuide>): Int = guides.count { upsert(it) }

    fun remove(packageName: String): Boolean {
        val key = keyOf(packageName) ?: return false
        return try {
            storage.remove(key)
            removeFromIndex(packageName)
            true
        } catch (e: Throwable) {
            XLog.w(TAG, "could not remove guide for $packageName", e)
            false
        }
    }

    fun clear() {
        try {
            readIndex().forEach { storage.remove(keyOf(it) ?: return@forEach) }
            storage.putString(INDEX_KEY, "")
            XLog.i(TAG, "app guide store cleared")
        } catch (e: Throwable) {
            XLog.w(TAG, "could not clear the guide store", e)
        }
    }

    /** Distinct packages currently stored. */
    fun packages(): List<String> = readIndex()

    // ── Merge ───────────────────────────────────────────────────────────────

    /**
     * Merge compiled-in [builtins] with the stored rows.
     *
     * Precedence per package: LEARNED > CONNECTOR > BUILTIN, then newest, then
     * highest confidence. Aliases are unioned so a learned row never loses the
     * spellings the user already expects.
     */
    fun mergeWith(builtins: List<AppGuide>): List<AppGuide> {
        val merged = LinkedHashMap<String, AppGuide>()
        builtins.forEach { guide ->
            val id = guide.identity()
            merged[id] = merged[id]?.mergedWith(guide) ?: guide
        }
        all().forEach { guide ->
            val id = guide.identity()
            merged[id] = merged[id]?.mergedWith(guide) ?: guide
        }
        return merged.values.toList()
    }

    // ── Index (small, explicit, no reflection over KV keys) ─────────────────

    private fun readIndex(): List<String> {
        val raw = try {
            storage.getString(INDEX_KEY, "")
        } catch (e: Throwable) {
            XLog.w(TAG, "could not read the guide index", e)
            return emptyList()
        }
        if (raw.isBlank()) return emptyList()
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun addToIndex(packageName: String) {
        val index = readIndex()
        if (index.any { it.equals(packageName, ignoreCase = true) }) return
        val next = (index + packageName).take(MAX_ROWS)
        storage.putString(INDEX_KEY, next.joinToString("\n"))
    }

    private fun removeFromIndex(packageName: String) {
        val next = readIndex().filterNot { it.equals(packageName, ignoreCase = true) }
        storage.putString(INDEX_KEY, next.joinToString("\n"))
    }

    private fun keyOf(packageName: String?): String? {
        val pkg = packageName?.trim()?.lowercase().orEmpty()
        if (pkg.isEmpty()) return null
        return KEY_PREFIX + pkg
    }
}
