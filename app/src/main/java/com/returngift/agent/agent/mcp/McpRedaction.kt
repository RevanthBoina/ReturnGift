// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.mcp

/**
 * Keeps credentials out of logs, error strings and the chat transcript.
 *
 * A bearer token has a habit of escaping through the least suspicious path: an HTTP
 * library that includes request headers in its exception message, a server that
 * echoes the `Authorization` value in a JSON-RPC error, a debug line someone added
 * while chasing a 401. Once the token is in the log file or a message the model
 * reads, rotating it is the only fix.
 *
 * Redaction is best-effort by design — it is a seatbelt, not the reason the token is
 * safe (that is [McpSecretStore]). It removes the two shapes tokens actually appear
 * in: `Bearer <token>` and `token=…` / `"api_key": "…"` pairs.
 */
object McpRedaction {

    const val MASK = "***"

    private val BEARER = Regex(
        "(?i)\\b(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}",
    )

    private val PAIR = Regex(
        "(?i)([\"']?(?:access_token|refresh_token|id_token|token|api[_-]?key|apikey|" +
            "secret|password|passwd|authorization)[\"']?\\s*[=:]\\s*)" +
            "(\"[^\"]*\"|'[^']*'|[^\\s,;}&\"']+)",
    )

    /** [text] with credential-shaped substrings masked. Safe on null-ish/blank input. */
    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val afterBearer = BEARER.replace(text) { match -> match.groupValues[1] + MASK }
        return PAIR.replace(afterBearer) { match -> match.groupValues[1] + MASK }
    }
}
