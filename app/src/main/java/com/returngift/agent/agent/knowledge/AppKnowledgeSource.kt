// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.knowledge

import com.returngift.agent.utils.XLog

/**
 * A pluggable source of app knowledge.
 *
 * The repo must not need a code change when an app's UI shifts or a new app shows
 * up, so knowledge arrives through implementations of this interface instead of
 * per-app classes:
 *
 *  - [BuiltinKnowledgeSource]  the compiled-in rows in [AppUsageGuide]
 *  - [StoreKnowledgeSource]    rows learned on device (`AppGuideStore`)
 *  - [ConnectorKnowledgeSource] rows pushed by an MCP connector (see below)
 *
 * ## MCP connector shape
 *
 * An MCP server that exposes app knowledge maps onto a source trivially: take the
 * tool/resource payload (the compact rows produced by [AppGuideCodec.encodeAll]),
 * feed it to [ConnectorKnowledgeSource], and register it with
 * [AppKnowledge.register]. Nothing else in the agent changes — the prompt, the
 * permission card and the micro-step planner all read the merged result.
 *
 * Implementations must be cheap and side-effect free: [guides] is called while
 * building the system prompt.
 */
interface AppKnowledgeSource {
    /** Stable id for logs/diagnostics, e.g. "builtin", "store", "mcp:app-guides". */
    val id: String

    /** Current rows from this source; empty (never thrown) when unavailable. */
    fun guides(): List<AppGuide>
}

/** Rows compiled into the app with the repo. */
class BuiltinKnowledgeSource(
    private val provider: () -> List<AppGuide>,
) : AppKnowledgeSource {
    override val id: String = "builtin"
    override fun guides(): List<AppGuide> = provider()
}

/** Rows learned on device or cached from a connector. */
object StoreKnowledgeSource : AppKnowledgeSource {
    override val id: String = "store"
    override fun guides(): List<AppGuide> = AppGuideStore.all()
}

/**
 * Adapter for a remote knowledge service (MCP connector, vendor feed, internal
 * endpoint). The payload is fetched lazily by [fetch] and decoded with
 * [AppGuideCodec]; a failed fetch leaves previously cached rows untouched.
 *
 * @param connectorId e.g. "mcp:app-guides"
 * @param fetch       returns the raw payload, or null when the connector is
 *                    offline / not configured
 */
class ConnectorKnowledgeSource(
    override val id: String,
    private val fetch: () -> String?,
) : AppKnowledgeSource {

    private var cached: List<AppGuide> = emptyList()

    override fun guides(): List<AppGuide> = cached

    /**
     * Pull the connector payload and cache it.
     * @return number of rows now known from this connector
     */
    fun refresh(): Int {
        val payload = try {
            fetch()
        } catch (e: Throwable) {
            XLog.w("AppKnowledge", "$id refresh failed", e)
            null
        } ?: return cached.size
        val rows = AppGuideCodec.decodeAll(payload)
            .map { it.copy(source = GuideSource.CONNECTOR) }
            .filter { it.packageName.isNotBlank() }
        if (rows.isNotEmpty()) cached = rows
        XLog.i("AppKnowledge", "$id delivered ${rows.size} guides")
        return cached.size
    }
}

/**
 * The single read path for app knowledge.
 *
 * Order is intentional: [sources] are merged in the order given and per-package
 * precedence still follows [GuideSource.rank], so a connector can update a
 * built-in row without a release and a task-learned row can override both.
 */
object AppKnowledge {

    private const val TAG = "AppKnowledge"

    private val sources = mutableListOf<AppKnowledgeSource>(
        // Task-learned / connector-cached rows always participate.
        StoreKnowledgeSource,
    )

    /** Register (or replace) a source. Idempotent by [AppKnowledgeSource.id]. */
    @Synchronized
    fun register(source: AppKnowledgeSource) {
        sources.removeAll { it.id == source.id }
        sources.add(source)
        XLog.i(TAG, "registered app knowledge source '${source.id}' (${sources.size} total)")
    }

    @Synchronized
    fun unregister(id: String) {
        sources.removeAll { it.id == id }
    }

    @Synchronized
    fun sourceIds(): List<String> = sources.map { it.id }

    /** Rows from every registered source, merged per package. */
    fun guides(builtins: List<AppGuide> = AppUsageGuide.builtinGuides()): List<AppGuide> {
        val merged = LinkedHashMap<String, AppGuide>()
        fun add(guide: AppGuide) {
            val id = guide.identity()
            if (id.isEmpty()) return
            merged[id] = merged[id]?.mergedWith(guide) ?: guide
        }
        builtins.forEach(::add)
        snapshot().forEach { source ->
            val rows = try {
                source.guides()
            } catch (e: Throwable) {
                XLog.w(TAG, "source '${source.id}' failed; skipping", e)
                emptyList()
            }
            rows.forEach(::add)
        }
        return merged.values.toList()
    }

    /** Persist connector rows so they survive a restart. */
    fun persist(source: AppKnowledgeSource) {
        AppGuideStore.upsertAll(source.guides())
    }

    @Synchronized
    fun reset() {
        sources.clear()
    }

    @Synchronized
    private fun snapshot(): List<AppKnowledgeSource> = sources.toList()
}
