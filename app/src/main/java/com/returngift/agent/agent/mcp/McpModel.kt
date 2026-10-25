// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.mcp

/**
 * MCP (Model Context Protocol) client model — pure Kotlin, no Android.
 *
 * Scope, deliberately narrow: what an on-device agent needs to *use* remote tools,
 * not to host a server. That is the `initialize` → `tools/list` → `tools/call`
 * path plus `resources/read` for knowledge.
 *
 * Forward-compatibility is a design requirement, not a nicety: MCP adds
 * capabilities over time (this repo learned that from `openai/mcp-extensions`,
 * where servers advertise `capabilities["extensions"]` — or the legacy
 * `capabilities["experimental"]` — and clients that reject unknown keys break on
 * the next spec revision). Unknown capability keys, unknown content types and
 * unknown notification methods are therefore *ignored*, never fatal.
 */

object McpProtocol {
    /** Version this client prefers when opening a session. */
    const val PREFERRED_VERSION = "2025-06-18"

    /** Versions this client understands; a server may pick any of them. */
    val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

    const val HEADER_SESSION = "Mcp-Session-Id"
    const val HEADER_PROTOCOL = "MCP-Protocol-Version"

    const val CLIENT_NAME = "ReturnGift"
    const val CLIENT_VERSION = "1.0"

    /** JSON-RPC error codes used by MCP. */
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    /** How many pages of `tools/list` we will follow before giving up. */
    const val MAX_TOOL_PAGES = 8

    /** Versions this client has been exercised against (conformance matrix). */
    val CONFORMANCE_TESTED = listOf("2025-06-18")
}

/**
 * Hard bounds on everything a remote server can put into our memory or our prompt.
 *
 * A server is an **untrusted principal**: it can return a 40 MB body, a thousand
 * content blocks, or one block of 10 MB of text. None of that may reach the model
 * unmetered.
 */
object McpLimits {
    /** Largest HTTP body we will read (bytes, UTF-8). */
    const val MAX_BODY_BYTES = 2 * 1024 * 1024

    /** Largest number of content blocks kept from one `tools/call`. */
    const val MAX_CONTENT_ITEMS = 64

    /** Largest per-block text kept. */
    const val MAX_BLOCK_CHARS = 16_000

    /** Largest total text handed to the model from one call. */
    const val MAX_RESULT_CHARS = 24_000

    /** Largest encoded row/tool JSON we will parse. */
    const val MAX_JSON_CHARS = 1 * 1024 * 1024
}

/** A configured MCP server (a row — never a per-server class). */
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val authToken: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 20_000L,
) {
    val isHttp: Boolean get() = url.startsWith("https://") || url.startsWith("http://")

    private val host: String
        get() = url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()

    /**
     * Loopback / emulator host. Cleartext is only defensible here: the traffic never
     * leaves the device (or the emulator's host bridge).
     */
    val isLoopback: Boolean
        get() = host in setOf("localhost", "127.0.0.1", "::1", "[::1]", "10.0.2.2")

    /** True when the token would travel under TLS. */
    val isSecureTransport: Boolean
        get() = url.startsWith("https://") || (url.startsWith("http://") && isLoopback)

    /** Row shown in settings/tests; the token is never included. */
    fun describe(): String = "$name ($url)" + if (enabled) "" else " [disabled]"

    /**
     * Validation shared by the settings screen and the store, so a bad row can
     * never reach the network layer (and the user gets the same message both ways).
     *
     * Cleartext to a non-loopback host is rejected outright: the bearer token in
     * [authToken] is reusable and would be readable by anything on the path.
     */
    fun validationError(): String? = when {
        id.isBlank() -> "A server id is required."
        url.isBlank() -> "A server URL is required."
        !isHttp -> "The URL must start with https:// (http:// only for localhost)."
        !isSecureTransport ->
            "Plain HTTP would send your token unencrypted. Use https:// — " +
                "http:// is accepted only for localhost / the emulator."
        timeoutMs <= 0L -> "The timeout must be positive."
        else -> null
    }
}

/** One tool advertised by a server. */
data class McpToolDef(
    val serverId: String,
    val serverName: String,
    val name: String,
    val title: String? = null,
    val description: String = "",
    val inputSchema: JsonValue? = null,
    /**
     * Server-provided annotations (`readOnlyHint`, `destructiveHint`, …).
     * According to the MCP spec these are **hints, not authority** — the policy in
     * `McpToolPolicy` treats them as one input, never as permission.
     */
    val annotations: JsonValue? = null,
) {
    /**
     * Name as the agent sees it. Namespaced so two servers may expose the same
     * tool name without colliding, and sanitised because the model-facing tool
     * name must match `[A-Za-z0-9_-]`.
     */
    val qualifiedName: String
        get() = "mcp__${sanitize(serverId)}__${sanitize(name)}"

    private fun sanitize(raw: String): String =
        raw.trim().lowercase().map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("")

    /** One-line description for prompts (never a wall of schema text). */
    fun shortDescription(maxChars: Int = 120): String {
        val base = description.ifBlank { title ?: name }.replace(Regex("\\s+"), " ").trim()
        return if (base.length <= maxChars) base else base.take(maxChars - 1).trimEnd() + "\u2026"
    }
}

/** One content block returned by `tools/call`. */
sealed class McpContent {
    data class Text(val text: String) : McpContent()
    data class Image(val mimeType: String, val base64: String) : McpContent()
    data class Link(val uri: String, val text: String?) : McpContent()

    /** A content type this client does not render — kept rather than dropped. */
    data class Other(val type: String, val raw: JsonValue) : McpContent()

    /** Flatten to text for the agent loop / knowledge connectors (bounded). */
    fun asPromptText(): String {
        val raw = when (this) {
            is Text -> text
            is Image -> "[image $mimeType, ${base64.length} b64 chars]"
            is Link -> if (text.isNullOrBlank()) uri else "$text ($uri)"
            is Other -> "[$type]"
        }
        return if (raw.length <= McpLimits.MAX_BLOCK_CHARS) {
            raw
        } else {
            raw.take(McpLimits.MAX_BLOCK_CHARS) + "\n[… truncated ${raw.length - McpLimits.MAX_BLOCK_CHARS} chars]"
        }
    }
}

/** Result of `tools/call`. */
data class McpCallResult(
    val isError: Boolean,
    val contents: List<McpContent>,
    val structured: JsonValue? = null,
) {
    /** Model-facing text: bounded in total, never the whole of a hostile response. */
    val text: String
        get() {
            val joined = contents.joinToString("\n") { it.asPromptText() }
            return if (joined.length <= McpLimits.MAX_RESULT_CHARS) {
                joined
            } else {
                joined.take(McpLimits.MAX_RESULT_CHARS) +
                    "\n[… truncated ${joined.length - McpLimits.MAX_RESULT_CHARS} chars]"
            }
        }
}

/** Server identity + negotiated capabilities. */
data class McpServerInfo(
    val protocolVersion: String,
    val name: String,
    val version: String,
    /** Capability keys the server advertised (unknown ones included verbatim). */
    val capabilities: Set<String>,
    /** Extension keys (newer spec) or legacy `experimental` keys. */
    val extensions: Set<String>,
) {
    fun supportsTools(): Boolean = capabilities.contains("tools")
    fun supportsResources(): Boolean = capabilities.contains("resources")
}

class McpException(
    message: String,
    val code: Int = McpProtocol.INTERNAL_ERROR,
    val serverId: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Transport-level failure (DNS, TLS, timeout) — always retryable elsewhere. */
class McpTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The server rejected our credentials; retrying without new auth is pointless. */
class McpAuthException(message: String, val status: Int) : Exception(message)

/** Raw HTTP request/response pair the client speaks through. */
data class McpHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
    val timeoutMs: Long,
    val method: String = "POST",
)

data class McpHttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    val isSuccess: Boolean get() = status in 200..299
}

/**
 * The HTTP seam. Kept as an interface so the protocol layer can be tested without
 * a network and so a future transport (stdio bridge, WebSocket) is a drop-in.
 */
interface McpTransport {
    fun send(request: McpHttpRequest): McpHttpResponse
}

/** In-memory transport for tests: records requests and replays a script. */
class ScriptedTransport(
    private val handler: (McpHttpRequest) -> McpHttpResponse,
) : McpTransport {
    val requests = mutableListOf<McpHttpRequest>()

    override fun send(request: McpHttpRequest): McpHttpResponse {
        requests += request
        return handler(request)
    }

    fun lastBody(): JsonValue.Obj = Json.parse(requests.last().body) as JsonValue.Obj
}
