// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.mcp

import com.returngift.agent.agent.knowledge.AppGuideStore
import com.returngift.agent.utils.XLog

/**
 * Persisted MCP server rows — the user-facing "Connections" list.
 *
 * Same storage seam as the app-guide store, so this is testable off-device and the
 * whole feature can be exercised without MMKV. Rows are data: adding a connection
 * is a UI action, never a code change.
 */
object McpServerStore {

    private const val TAG = "McpServerStore"
    private const val KEY_INDEX = "mcp_servers_v1_index"
    private const val KEY_PREFIX = "mcp_server_v1_"

    /** Cap: the agent's prompt and the settings list both stay small. */
    const val MAX_SERVERS = 16

    internal var storage: AppGuideStore.Storage = AppGuideStore.KVStorage

    fun all(): List<McpServerConfig> =
        readIndex().mapNotNull { id -> get(id) }.sortedBy { it.name.lowercase() }

    fun enabled(): List<McpServerConfig> = all().filter { it.enabled }

    fun get(id: String): McpServerConfig? {
        val raw = storage.getString(KEY_PREFIX + id, "")
        if (raw.isBlank()) return null
        return try {
            decode(raw)
        } catch (e: Throwable) {
            XLog.w(TAG, "could not read MCP server $id", e)
            null
        }
    }

    /** Insert or replace a row; returns false when the cap is reached. */
    fun upsert(config: McpServerConfig): Boolean {
        config.validationError()?.let { throw IllegalArgumentException(it) }
        val existing = all().map { it.id }.toMutableList()
        if (config.id !in existing && existing.size >= MAX_SERVERS) return false
        storage.putString(KEY_PREFIX + config.id, encode(config))
        if (config.id !in existing) {
            existing += config.id
            storage.putString(KEY_INDEX, existing.joinToString("\n"))
        }
        return true
    }

    fun remove(id: String) {
        val remaining = readIndex().filterNot { it == id }
        storage.putString(KEY_INDEX, remaining.joinToString("\n"))
        storage.putString(KEY_PREFIX + id, "")
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val row = get(id) ?: return false
        return upsert(row.copy(enabled = enabled))
    }

    fun clear() {
        readIndex().forEach { storage.putString(KEY_PREFIX + it, "") }
        storage.putString(KEY_INDEX, "")
    }

    // ── codec ───────────────────────────────────────────────────────────────

    private fun readIndex(): List<String> =
        storage.getString(KEY_INDEX, "")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    internal fun encode(config: McpServerConfig): String = Json.write(
        Json.obj(
            "id" to Json.str(config.id),
            "name" to Json.str(config.name),
            "url" to Json.str(config.url),
            "enabled" to Json.bool(config.enabled),
            "authToken" to Json.str(config.authToken),
            "timeoutMs" to Json.num(config.timeoutMs.toInt()),
            "headers" to JsonValue.Obj(
                config.extraHeaders.entries.associate { it.key to Json.str(it.value) }
            ),
        )
    )

    internal fun decode(raw: String): McpServerConfig {
        val obj = Json.parse(raw) as? JsonValue.Obj
            ?: throw McpJsonException("MCP server row is not an object")
        val id = obj.str("id") ?: throw McpJsonException("MCP server row has no id")
        return McpServerConfig(
            id = id,
            name = obj.str("name") ?: id,
            url = obj.str("url").orEmpty(),
            enabled = obj.bool("enabled") ?: true,
            authToken = obj.str("authToken")?.takeIf { it.isNotBlank() },
            timeoutMs = obj.int("timeoutMs")?.toLong() ?: 20_000L,
            extraHeaders = obj.obj("headers")?.fields
                ?.mapNotNull { (key, value) -> (value as? JsonValue.Str)?.let { key to it.value } }
                ?.toMap()
                .orEmpty(),
        )
    }
}
