// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.mcp

import com.returngift.agent.utils.XLog

/**
 * The agent-facing side of MCP: connect every enabled server, keep a bounded tool
 * index, expose it to the prompt, and route a call back to the server that owns it.
 *
 * Failure policy is deliberate. A broken connection must never damage the task:
 * every server is refreshed inside `runCatching`, a failure is logged and skipped,
 * and the remaining servers (plus all local tools) keep working. MCP is an
 * enhancement, not a dependency.
 */
object McpRuntime {

    private const val TAG = "McpRuntime"

    /** Seam for tests: swaps the HTTP transport for a scripted one. */
    interface ClientFactory {
        fun create(config: McpServerConfig): McpClient
    }

    private var factory: ClientFactory = object : ClientFactory {
        override fun create(config: McpServerConfig): McpClient =
            McpClient(config, HttpMcpTransport())
    }

    private val clients = LinkedHashMap<String, McpClient>()
    private var tools: List<McpToolDef> = emptyList()

    /**
     * Exposed tool name → the server's **original** tool name.
     *
     * The exposed name is sanitised (`mcp__<server>__<tool>` may only contain
     * `[A-Za-z0-9_-]`), so it cannot be split back into the original name: a server
     * tool called `search/v2` would otherwise be called as `search_v2` and fail.
     * Dispatch therefore goes through this map, never through string surgery.
     */
    private var routes: Map<String, Route> = emptyMap()

    internal data class Route(val serverId: String, val toolName: String)

    /** Qualified names that collided (two tools sanitising to the same name). */
    private var collisions: List<String> = emptyList()

    /** Diagnostics/tests: names dropped because they collided after sanitising. */
    @Synchronized
    fun collisions(): List<String> = collisions

    internal fun setFactoryForTest(newFactory: ClientFactory) {
        factory = newFactory
        reset()
    }

    @Synchronized
    fun reset() {
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
        tools = emptyList()
        routes = emptyMap()
        collisions = emptyList()
    }

    /**
     * Connect every enabled server and rebuild the tool index.
     *
     * @return number of tools discovered across all servers
     */
    @Synchronized
    fun refresh(servers: List<McpServerConfig> = McpServerStore.enabled()): Int {
        val discovered = mutableListOf<McpToolDef>()
        val alive = mutableSetOf<String>()
        servers.filter { it.enabled && it.isHttp }.forEach { config ->
            val client = clients.getOrPut(config.id) { factory.create(config) }
            alive += config.id
            try {
                val info = client.initialize()
                if (info.supportsTools()) {
                    discovered += client.listTools(force = true)
                } else {
                    XLog.i(TAG, "${config.id} exposes no tools (caps=${info.capabilities})")
                }
            } catch (e: McpAuthException) {
                XLog.w(TAG, "MCP ${config.id} auth failed: ${McpRedaction.redact(e.message)}")
            } catch (e: Throwable) {
                // Keep the previously discovered tools for this server, if any.
                XLog.w(TAG, "MCP ${config.id} unavailable: ${e.message}")
                discovered += tools.filter { it.serverId == config.id }
            }
        }
        // Build the exact dispatch table from what the servers actually returned,
        // BEFORE de-duplication, so two distinct tools that sanitise to the same
        // exposed name are reported instead of silently collapsed.
        val newRoutes = LinkedHashMap<String, Route>()
        val clashes = mutableListOf<String>()
        discovered.forEach { def ->
            val route = Route(def.serverId, def.name)
            val previous = newRoutes.putIfAbsent(def.qualifiedName, route)
            if (previous != null && previous != route) clashes += def.qualifiedName
        }
        tools = McpToolIndex.dedupe(discovered)
        routes = newRoutes
        collisions = clashes
        if (clashes.isNotEmpty()) {
            XLog.w(TAG, "MCP tool name collisions (first wins): ${clashes.joinToString()}")
        }
        // Forget clients for servers that were removed or disabled.
        clients.keys.filterNot { it in alive }.forEach { id ->
            runCatching { clients.remove(id)?.close() }
        }
        XLog.i(TAG, "MCP refresh: ${tools.size} tool(s) from ${alive.size} server(s)")
        return tools.size
    }

    /** All known tools (bounded index), ranked for [task]. */
    @Synchronized
    fun toolsFor(task: String, limit: Int = McpToolIndex.DEFAULT_LIMIT): List<McpToolDef> =
        McpToolIndex.select(tools, task, limit)

    @Synchronized
    fun allTools(): List<McpToolDef> = tools

    /** Prompt block for the current task; empty when no server is connected. */
    @Synchronized
    fun promptSection(task: String, limit: Int = McpToolIndex.DEFAULT_LIMIT): String =
        McpToolIndex.promptSection(toolsFor(task, limit), limit)

    /**
     * Execute a namespaced MCP tool (`mcp__<server>__<tool>`).
     * Throws [McpException] only for protocol errors; callers translate to tool errors.
     */
    fun call(qualifiedName: String, arguments: JsonValue.Obj = JsonValue.Obj()): McpCallResult {
        val route = synchronized(this) { routes[qualifiedName] }
            ?: throw McpException(
                "unknown MCP tool '$qualifiedName' — it is not among the tools discovered " +
                    "from the connected servers"
            )
        val client = synchronized(this) { clients[route.serverId] }
            ?: throw McpException("MCP server '${route.serverId}' is not connected")
        // Exact original name: no re-derivation from the sanitised exposed name.
        return client.callTool(route.toolName, arguments)
    }

    @Synchronized
    fun connectedServerIds(): List<String> = clients.keys.toList()

    /** Connected client for [serverId], if any (used by the knowledge bridge). */
    @Synchronized
    fun clientFor(serverId: String): McpClient? = clients[serverId]

    /** True when at least one server is connected and tools are known. */
    @Synchronized
    fun isActive(): Boolean = tools.isNotEmpty()

    /** Register a row and connect it immediately (settings "Add connection" path). */
    fun addServer(config: McpServerConfig): Boolean {
        if (!McpServerStore.upsert(config)) {
            XLog.w(TAG, "server cap reached (${McpServerStore.MAX_SERVERS})")
            return false
        }
        refresh()
        return true
    }

    fun removeServer(id: String) {
        McpServerStore.remove(id)
        synchronized(this) {
            tools = tools.filterNot { it.serverId == id }
            routes = routes.filterValues { it.serverId != id }
            runCatching { clients.remove(id)?.close() }
        }
    }
}
