// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.mcp

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streamable-HTTP transport for MCP, on plain [HttpURLConnection].
 *
 * No new dependency: the app already ships HTTP stacks for other features, and the
 * protocol layer must stay usable from a pure-Kotlin module. The transport is a
 * thin shim — all protocol behaviour lives in [McpClient], where it is testable.
 *
 * Threading: [send] blocks (the agent loop is already off the main thread). The
 * caller owns the timeout via [McpHttpRequest.timeoutMs].
 */
class HttpMcpTransport : McpTransport {

    override fun send(request: McpHttpRequest): McpHttpResponse {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                this.requestMethod = request.method
                connectTimeout = request.timeoutMs.toInt()
                readTimeout = request.timeoutMs.toInt()
                instanceFollowRedirects = false
                useCaches = false
                request.headers.forEach { (key, value) -> setRequestProperty(key, value) }
                if (request.method != "POST" && request.method != "PUT") {
                    // GET/DELETE carry no entity; HttpURLConnection rejects bodies for them.
                    doOutput = false
                } else {
                    doOutput = true
                    setFixedLengthStreamingMode(request.body.toByteArray(Charsets.UTF_8).size)
                }
            }

            if (request.method == "POST" || request.method == "PUT") {
                connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input -> readBounded(input) }.orEmpty()

            val headers = buildMap {
                connection.headerFields.forEach { (key, values) ->
                    if (key != null && values != null) put(key, values.joinToString(", "))
                }
            }
            return McpHttpResponse(status, headers, body)
        } catch (e: IOException) {
            throw McpTransportException("MCP transport error for ${request.url}: ${e.message}", e)
        } catch (e: SecurityException) {
            // Missing INTERNET permission or cleartext blocked by network policy.
            throw McpTransportException("MCP transport blocked for ${request.url}: ${e.message}", e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Read at most [McpLimits.MAX_BODY_BYTES].
     *
     * Without this, a buggy or hostile server could make the app allocate an
     * arbitrarily large string on a worker thread. A truncated body is reported by
     * the JSON layer as a parse error, which is the honest outcome.
     */
    private fun readBounded(input: java.io.InputStream): String {
        val buffer = CharArray(8 * 1024)
        val out = StringBuilder()
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            while (out.length < McpLimits.MAX_BODY_BYTES) {
                val read = reader.read(buffer, 0, buffer.size)
                if (read <= 0) break
                out.appendRange(buffer, 0, read)
            }
        }
        return if (out.length >= McpLimits.MAX_BODY_BYTES) {
            // Deliberately invalid JSON tail: never hand a clipped body to the parser
            // as if it were complete.
            out.append("\u0000truncated")
            out.toString()
        } else {
            out.toString()
        }
    }
}
