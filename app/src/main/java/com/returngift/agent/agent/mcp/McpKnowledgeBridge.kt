// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.mcp

import com.returngift.agent.agent.knowledge.AppKnowledge
import com.returngift.agent.agent.knowledge.AppKnowledgeSource
import com.returngift.agent.agent.knowledge.ConnectorKnowledgeSource

/**
 * MCP → app-knowledge bridge.
 *
 * An MCP server that publishes app guides as a resource (payload encoded with
 * `AppGuideCodec.encodeAll`) becomes a first-class knowledge source: no release,
 * no per-app file, and connector rows outrank built-ins for the same package.
 *
 * The bridge deliberately reuses [ConnectorKnowledgeSource] — the same class the
 * repo already tests — so "knowledge from an MCP connector" is one code path, not
 * a parallel implementation.
 */
object McpKnowledgeBridge {

    /** Source id used for the knowledge registry, e.g. `mcp:guides`. */
    fun sourceId(serverId: String): String = "mcp:$serverId"

    /**
     * Build (but do not register) a knowledge source backed by [serverId].
     *
     * @param resourceUri MCP resource holding the encoded rows
     */
    fun source(serverId: String, resourceUri: String): AppKnowledgeSource =
        ConnectorKnowledgeSource(sourceId(serverId)) {
            val client = McpRuntime.clientFor(serverId) ?: return@ConnectorKnowledgeSource null
            client.readResource(resourceUri)
        }

    /**
     * Build, pull the rows once, and register.
     *
     * Refreshing before registering matters: [AppKnowledge] reads sources while
     * building the system prompt, and an empty first prompt would look like the
     * connector "has nothing" until the next task.
     */
    fun register(serverId: String, resourceUri: String): String {
        val source = source(serverId, resourceUri)
        (source as? ConnectorKnowledgeSource)?.refresh()
        AppKnowledge.register(source)
        return source.id
    }

    fun unregister(serverId: String) = AppKnowledge.unregister(sourceId(serverId))
}
