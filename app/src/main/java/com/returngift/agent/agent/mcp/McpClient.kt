// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.mcp

/**
 * A minimal but complete MCP *client* for the on-device agent.
 *
 * Protocol shape (JSON-RPC 2.0 over Streamable HTTP):
 *
 * ```
 * initialize            → protocolVersion, serverInfo, capabilities/experimental
 * notifications/initialized (no reply)
 * tools/list  (+cursor) → tool definitions
 * tools/call            → content blocks (text / image / resource_link / …)
 * resources/read        → resource contents (used as a knowledge source)
 * ```
 *
 * Design notes that matter in practice:
 *  - **Session-aware**: `Mcp-Session-Id` is captured from `initialize` and echoed.
 *    A `404` after a session existed means the server forgot us (restart, idle
 *    timeout) — we re-initialize once and retry instead of failing the task.
 *  - **Forward-compatible**: unknown capability keys, content types and
 *    notifications are ignored. Servers add them constantly.
 *  - **Bounded**: tool pagination and tool counts are capped; a hostile or buggy
 *    server can never grow the prompt without limit.
 *  - **Testable**: all I/O goes through [McpTransport], so the protocol is
 *    exercised deterministically in JVM tests.
 */
class McpClient(
    private val config: McpServerConfig,
    private val transport: McpTransport,
    private val log: (String) -> Unit = {},
) {

    private var nextId = 1
    private var sessionId: String? = null
    private var negotiatedVersion: String? = null
    private var initialized = false
    private var cachedTools: List<McpToolDef>? = null
    /** Bumped by every `tools/list_changed`; makes the next list refetch. */
    private var changeNotifications = 0

    val serverId: String get() = config.id

    /** Handshake. Safe to call twice — the second call is a no-op. */
    fun initialize(): McpServerInfo {
        if (initialized) return requireNotNull(serverInfo) { "initialized without server info" }
        require(config.isHttp) { "MCP server ${config.id} has no http(s) URL" }

        val params = Json.obj(
            "protocolVersion" to Json.str(McpProtocol.PREFERRED_VERSION),
            // Empty on purpose: we advertise only what we actually implement.
            // (The first version advertised `roots` but never answered
            // `roots/list`, which is a false claim to the server.)
            "capabilities" to JsonValue.Obj(),
            "clientInfo" to Json.obj(
                "name" to Json.str(McpProtocol.CLIENT_NAME),
                "version" to Json.str(McpProtocol.CLIENT_VERSION),
            ),
        )
        val result = sendRequest("initialize", params, allowReinit = false).asObject("initialize result")
        val version = result.str("protocolVersion")
            ?: throw McpException("initialize returned no protocolVersion", serverId = config.id)
        if (version !in McpProtocol.SUPPORTED_VERSIONS) {
            throw McpException(
                "server negotiated unsupported MCP version '$version' " +
                    "(supported: ${McpProtocol.SUPPORTED_VERSIONS.joinToString()})",
                serverId = config.id,
            )
        }
        negotiatedVersion = version
        if (version !in McpProtocol.CONFORMANCE_TESTED) {
            // Honest reporting: we accept it, but only the tested revision is what
            // this client was verified against — say so instead of implying parity.
            log(
                "${config.id} negotiated MCP $version (supported; conformance-tested: " +
                    McpProtocol.CONFORMANCE_TESTED.joinToString() + ")"
            )
        }

        val serverInfoObj = result.obj("serverInfo")
        val caps = result.obj("capabilities")
        val capabilityKeys = caps?.fields?.keys.orEmpty()
        // Newer spec: capabilities["extensions"]; older servers used "experimental".
        val extensions = buildSet {
            caps?.obj("extensions")?.fields?.keys?.let { addAll(it) }
            caps?.obj("experimental")?.fields?.keys?.let { addAll(it) }
        }

        val info = McpServerInfo(
            protocolVersion = version,
            name = serverInfoObj?.str("name") ?: config.name,
            version = serverInfoObj?.str("version") ?: "",
            capabilities = capabilityKeys,
            extensions = extensions,
        )
        serverInfo = info
        initialized = true
        log("initialized ${config.id}: ${info.name} ${info.version} (MCP $version, caps=${info.capabilities})")

        // Required by the spec; failure here must not kill an otherwise good session.
        runCatching { sendNotification("notifications/initialized") }
            .onFailure { log("initialized notification failed for ${config.id}: ${it.message}") }

        if (info.extensions.isNotEmpty()) {
            log("server ${config.id} advertises extensions: ${info.extensions.joinToString()}")
        }
        return info
    }

    private var serverInfo: McpServerInfo? = null

    fun serverInfo(): McpServerInfo? = serverInfo

    /** Version negotiated with the server (null before `initialize`). */
    fun negotiatedVersion(): String? = negotiatedVersion

    /** All tools, following pagination cursors. Cached until [force] or invalidation. */
    fun listTools(force: Boolean = false): List<McpToolDef> {
        cachedTools?.let { if (!force) return it }
        ensureInitialized()
        val changesBefore = changeNotifications
        val tools = mutableListOf<McpToolDef>()
        var cursor: String? = null
        var pages = 0
        while (pages < McpProtocol.MAX_TOOL_PAGES) {
            pages++
            val params = if (cursor.isNullOrEmpty()) JsonValue.Obj() else Json.obj("cursor" to Json.str(cursor))
            val result = sendRequest("tools/list", params).asObject("tools/list result")
            result.arr("tools")?.items?.forEach { item ->
                val tool = item as? JsonValue.Obj ?: return@forEach
                val name = tool.str("name") ?: return@forEach
                tools += McpToolDef(
                    serverId = config.id,
                    serverName = config.name,
                    name = name,
                    title = tool.str("title"),
                    description = tool.str("description").orEmpty(),
                    inputSchema = tool["inputSchema"],
                    annotations = tool["annotations"],
                )
            }
            cursor = result.str("nextCursor")
            if (cursor.isNullOrEmpty()) break
        }
        val capped = tools.take(MAX_TOOLS)
        // A list_changed that arrived *during* this request means what we just read
        // may already be out of date: keep the result for this call, refetch next time.
        cachedTools = if (changeNotifications != changesBefore) null else capped
        log("tools/list ${config.id}: ${capped.size} tool(s)")
        return capped
    }

    /** Invoke a tool. Throws [McpException] on protocol errors, never crashes the loop. */
    fun callTool(name: String, arguments: JsonValue.Obj = JsonValue.Obj()): McpCallResult {
        ensureInitialized()
        val params = Json.obj("name" to Json.str(name), "arguments" to arguments)
        val result = sendRequest("tools/call", params).asObject("tools/call result")
        val contents = mutableListOf<McpContent>()
        // A server is untrusted: cap how much of its response we keep.
        result.arr("content")?.items?.take(McpLimits.MAX_CONTENT_ITEMS)?.forEach { block ->
            val obj = block as? JsonValue.Obj ?: return@forEach
            contents += when (obj.str("type")) {
                "text" -> McpContent.Text(obj.str("text").orEmpty())
                "image" -> McpContent.Image(
                    mimeType = obj.str("mimeType").orEmpty(),
                    base64 = obj.str("data").orEmpty(),
                )
                "resource_link" -> McpContent.Link(obj.str("uri").orEmpty(), obj.str("name"))
                "resource" -> (obj.obj("resource")?.str("text"))?.let { McpContent.Text(it) }
                    ?: McpContent.Other("resource", obj)
                else -> McpContent.Other(obj.str("type").orEmpty(), obj)
            }
        }
        return McpCallResult(
            isError = result.bool("isError") == true,
            contents = contents,
            structured = result["structuredContent"],
        )
    }

    /** Read a resource as text (used by the knowledge connectors). */
    fun readResource(uri: String): String? {
        ensureInitialized()
        val result = sendRequest("resources/read", Json.obj("uri" to Json.str(uri)))
            .asObject("resources/read result")
        val contents = result.arr("contents")?.items.orEmpty()
        return contents.asSequence()
            .mapNotNull { (it as? JsonValue.Obj)?.str("text") }
            .firstOrNull()
    }

    /** Invalidate cached tool definitions (e.g. after `list_changed`). */
    fun invalidateTools() {
        cachedTools = null
    }

    /** Best-effort session teardown; never throws. */
    fun close() {
        val id = sessionId ?: return
        runCatching {
            transport.send(
                McpHttpRequest(
                    url = config.url,
                    headers = baseHeaders(),
                    body = "",
                    timeoutMs = 3_000L,
                    method = "DELETE",
                )
            )
        }
        sessionId = null
        initialized = false
        cachedTools = null
        log("closed session $id for ${config.id}")
    }

    // ----------------------------------------------------------------- plumbing

    private fun ensureInitialized() {
        if (!initialized) initialize()
    }

    private fun sendNotification(method: String) {
        val body = Json.obj(
            "jsonrpc" to Json.str("2.0"),
            "method" to Json.str(method),
        )
        transport.send(
            McpHttpRequest(config.url, baseHeaders(), Json.write(body), config.timeoutMs)
        )
    }

    private fun sendRequest(
        method: String,
        params: JsonValue,
        allowReinit: Boolean = true,
    ): JsonValue {
        val id = nextId++
        val body = Json.obj(
            "jsonrpc" to Json.str("2.0"),
            "id" to Json.num(id),
            "method" to Json.str(method),
            "params" to params,
        )
        val response = try {
            transport.send(McpHttpRequest(config.url, baseHeaders(), Json.write(body), config.timeoutMs))
        } catch (e: McpTransportException) {
            throw e
        } catch (e: Throwable) {
            throw McpTransportException("MCP request '$method' to ${config.id} failed: ${e.message}", e)
        }

        if (response.status == 401 || response.status == 403) {
            throw McpAuthException(
                "MCP server ${config.id} rejected the credentials (HTTP ${response.status})",
                response.status,
            )
        }
        // Expired session: the server forgot us. Re-handshake once, retry once.
        if (response.status == 404 && allowReinit && initialized && sessionId != null) {
            log("session expired for ${config.id} (404) — re-initializing once")
            sessionId = null
            initialized = false
            cachedTools = null
            initialize()
            return sendRequest(method, params, allowReinit = false)
        }
        if (!response.isSuccess) {
            throw McpException(
                "MCP ${config.id} returned HTTP ${response.status}: ${response.body.take(200)}",
                serverId = config.id,
            )
        }
        response.header(McpProtocol.HEADER_SESSION)?.let { if (it.isNotBlank()) sessionId = it }

        val message = extractResponseMessage(response, id)
            ?: return JsonValue.Obj()
        message["error"]?.let { error ->
            val obj = error as? JsonValue.Obj
            throw McpException(
                obj?.str("message") ?: "unknown MCP error",
                code = obj?.int("code") ?: McpProtocol.INTERNAL_ERROR,
                serverId = config.id,
            )
        }
        return message["result"] ?: JsonValue.Obj()
    }

    /**
     * Pull the JSON-RPC response for [id] out of a plain-JSON or SSE body.
     * Streamable HTTP servers may answer with `text/event-stream`.
     */
    private fun extractResponseMessage(response: McpHttpResponse, id: Int): JsonValue.Obj? {
        val body = response.body.trim()
        if (body.isEmpty()) return null
        var lastMessage: JsonValue.Obj? = null
        for (obj in parseMessages(body)) {
            // Notifications have no id; apply the ones we understand, ignore the rest.
            if (obj["id"] == null && obj["method"] != null) {
                handleNotification(obj.str("method").orEmpty())
                continue
            }
            lastMessage = obj
            if (obj.int("id") == id) return obj
        }
        return lastMessage
    }

    /**
     * Decode the body into JSON-RPC messages.
     *
     * Server responses in the wild come in three shapes: a single JSON object,
     * `text/event-stream` frames, and (from a few gateways) concatenated objects
     * separated by newlines. All three are accepted; anything unparseable is
     * logged and skipped rather than failing the call.
     */
    private fun parseMessages(body: String): List<JsonValue.Obj> {
        val isSse = body.startsWith("data:") || body.startsWith("event:") || body.contains("\ndata:")
        val payloads: List<String> = if (isSse) {
            body.lineSequence()
                .filter { it.trimStart().startsWith("data:") }
                .map { it.trimStart().removePrefix("data:").trim() }
                .filter { it.isNotEmpty() && it != "[DONE]" }
                .toList()
        } else {
            val whole = runCatching { Json.parse(body) }.getOrNull()
            if (whole != null) listOf(body) else splitConcatenatedObjects(body)
        }
        return payloads.mapNotNull { payload ->
            try {
                Json.parse(payload) as? JsonValue.Obj
            } catch (e: McpJsonException) {
                log("ignoring unparseable MCP payload from ${config.id}: ${e.message}")
                null
            }
        }
    }

    /** Split `{..}{..}` / newline-joined objects without a full parser (ignores braces in strings). */
    private fun splitConcatenatedObjects(text: String): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inString = false
        var escape = false
        for (c in text) {
            when {
                escape -> { current.append(c); escape = false }
                c == '\\' && inString -> { current.append(c); escape = true }
                c == '"' -> { current.append(c); inString = !inString }
                inString -> current.append(c)
                c == '{' -> { depth++; current.append(c) }
                c == '}' -> {
                    depth--
                    current.append(c)
                    if (depth == 0) { chunks += current.toString(); current.clear() }
                }
                depth > 0 -> current.append(c)
            }
        }
        return chunks
    }

    private fun handleNotification(method: String) {
        when (method) {
            "notifications/tools/list_changed" -> {
                cachedTools = null
                changeNotifications++
                log("${config.id} announced tools/list_changed — cache invalidated")
            }
            else -> log("ignoring MCP notification '$method' from ${config.id}")
        }
    }

    private fun baseHeaders(): MutableMap<String, String> = mutableMapOf<String, String>().apply {
        put("Content-Type", "application/json")
        // Streamable HTTP servers may reply with SSE: accept both.
        put("Accept", "application/json, text/event-stream")
        put("User-Agent", "${McpProtocol.CLIENT_NAME}/${McpProtocol.CLIENT_VERSION}")
        config.authToken?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        sessionId?.takeIf { it.isNotBlank() }?.let { put(McpProtocol.HEADER_SESSION, it) }
        negotiatedVersion?.let { put(McpProtocol.HEADER_PROTOCOL, it) }
        putAll(config.extraHeaders)
    }

    private fun JsonValue.asObject(what: String): JsonValue.Obj =
        this as? JsonValue.Obj
            ?: throw McpException("$what was not a JSON object", serverId = config.id)

    companion object {
        /** Hard cap: no server can flood the prompt with tools. */
        const val MAX_TOOLS = 48
    }
}
