// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.mcp

import com.returngift.agent.tool.ToolRegistry
import com.returngift.agent.tool.impl.McpToolProxy
import com.returngift.agent.utils.XLog
import kotlin.concurrent.thread

/**
 * Puts MCP tools into [ToolRegistry] so the agent loop, the safety interceptor and
 * the prompt builder all see them like any other tool.
 *
 * Two-phase on purpose:
 *  - [registerCached] is synchronous and network-free; it runs whenever the registry
 *    is rebuilt, so a server that is offline cannot delay a task.
 *  - [refreshAsync] does the network work once, in the background, at app start.
 */
object McpToolRegistrar {

    private const val TAG = "McpToolRegistrar"

    @Volatile
    private var refreshing = false

    /** Register whatever tools are already known. Safe to call often. */
    fun registerCached() {
        val tools = runCatching { McpRuntime.allTools() }.getOrDefault(emptyList())
        if (tools.isEmpty()) return
        tools.forEach { def ->
            runCatching {
                ToolRegistry.register(McpToolProxy(def))
            }.onFailure { XLog.w(TAG, "could not register ${def.qualifiedName}", it) }
        }
        XLog.i(TAG, "registered ${tools.size} MCP tool(s)")
    }

    /** Connect enabled servers off the main thread, then publish their tools. */
    fun refreshAsync() {
        if (refreshing) return
        refreshing = true
        thread(name = "mcp-refresh", isDaemon = true) {
            try {
                val count = McpRuntime.refresh()
                if (count > 0) {
                    registerCached()
                    XLog.i(TAG, "MCP ready: $count tool(s)")
                }
            } catch (e: Throwable) {
                XLog.w(TAG, "MCP refresh failed; continuing without remote tools", e)
            } finally {
                refreshing = false
            }
        }
    }
}
