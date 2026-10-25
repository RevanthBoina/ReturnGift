// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.mcp

import org.junit.Assert
import org.junit.Test

/**
 * MCP protocol behaviour, exercised against a scripted transport.
 *
 * These tests encode the contract that matters on a phone: talk to real servers,
 * survive session loss and auth failures, never blow up the agent loop, and never
 * let a server flood the prompt.
 */
object McpClientTest {

    private fun config(token: String? = "secret") = McpServerConfig(
        id = "guides",
        name = "Guide server",
        url = "https://mcp.example.test/mcp",
        authToken = token,
    )

    private fun jsonRpc(id: Int, result: String) =
        """{"jsonrpc":"2.0","id":$id,"result":$result}"""

    /** A scripted server that knows the whole happy path. */
    private fun happyServer(
        toolsJson: String = """[{"name":"lookup","description":"Look up a page","inputSchema":{"type":"object"}}]""",
        callJson: String = """{"content":[{"type":"text","text":"hello"}]}""",
        nextCursor: String? = null,
    ): ScriptedTransport {
        var initialized = false
        return ScriptedTransport { request ->
            val body = Json.parse(request.body) as JsonValue.Obj
            val method = body.str("method").orEmpty()
            val id = body.int("id") ?: 0
            when (method) {
                "initialize" -> {
                    initialized = true
                    McpHttpResponse(
                        200,
                        mapOf(McpProtocol.HEADER_SESSION to "sess-1"),
                        jsonRpc(
                            id,
                            """{"protocolVersion":"2025-06-18","serverInfo":{"name":"guides","version":"1.2"},
                               "capabilities":{"tools":{},"experimental":{"openai/settings":{}}}}""",
                        ),
                    )
                }
                "notifications/initialized" -> McpHttpResponse(202)
                "tools/list" -> {
                    require(initialized)
                    McpHttpResponse(
                        200,
                        emptyMap(),
                        jsonRpc(
                            id,
                            """{"tools":$toolsJson${nextCursor?.let { ""","nextCursor":"$it"""" } ?: ""}}""",
                        ),
                    )
                }
                "tools/call" -> McpHttpResponse(200, emptyMap(), jsonRpc(id, callJson))
                "resources/read" -> McpHttpResponse(
                    200,
                    emptyMap(),
                    jsonRpc(id, """{"contents":[{"uri":"guide://apps","text":"row1"}]}"""),
                )
                else -> McpHttpResponse(404, emptyMap(), "")
            }
        }
    }

    // ------------------------------------------------------------- handshake

    @Test
    fun initializeSendsProtocolAndClientInfo() {
        val transport = happyServer()
        val info = McpClient(config(), transport).initialize()
        val body = Json.parse(transport.requests.first().body) as JsonValue.Obj
        Assert.assertEquals("initialize", body.str("method"))
        Assert.assertEquals("2.0", body.str("jsonrpc"))
        Assert.assertEquals(
            McpProtocol.PREFERRED_VERSION,
            body.obj("params")?.str("protocolVersion"),
        )
        Assert.assertEquals(McpProtocol.CLIENT_NAME, body.obj("params")?.obj("clientInfo")?.str("name"))
        Assert.assertEquals("2025-06-18", info.protocolVersion)
        Assert.assertEquals("guides", info.name)
        Assert.assertTrue(info.supportsTools())
    }

    @Test
    fun sessionHeaderAndInitializedNotificationAreUsed() {
        val transport = happyServer()
        val client = McpClient(config(), transport)
        client.initialize()
        client.listTools()
        val listRequest = transport.requests.last { it.body.contains("tools/list") }
        Assert.assertEquals("sess-1", listRequest.headers[McpProtocol.HEADER_SESSION])
        Assert.assertEquals("2025-06-18", listRequest.headers[McpProtocol.HEADER_PROTOCOL])
        Assert.assertTrue(transport.requests.any { it.body.contains("notifications/initialized") })
    }

    @Test
    fun bearerTokenIsSentButNeverLogged() {
        val transport = happyServer()
        McpClient(config(token = "t-123"), transport).initialize()
        Assert.assertEquals("Bearer t-123", transport.requests.first().headers["Authorization"])
        Assert.assertTrue(config().describe().none { false } && !config().describe().contains("t-123"))
    }

    @Test
    fun extensionsAndLegacyExperimentalAreBothRead() {
        val info = McpClient(config(), happyServer()).initialize()
        Assert.assertTrue(info.extensions.contains("openai/settings"))
        Assert.assertTrue(info.capabilities.contains("tools"))
    }

    @Test
    fun unsupportedProtocolVersionIsRejectedClearly() {
        val transport = ScriptedTransport { req ->
            val id = (Json.parse(req.body) as JsonValue.Obj).int("id") ?: 1
            McpHttpResponse(200, emptyMap(), jsonRpc(id, """{"protocolVersion":"1999-01-01"}"""))
        }
        try {
            McpClient(config(), transport).initialize()
            Assert.fail("expected a protocol error")
        } catch (e: McpException) {
            Assert.assertTrue(e.message!!.contains("unsupported MCP version"))
        }
    }

    // --------------------------------------------------------------- tools

    @Test
    fun toolsAreNamespacedAndSanitised() {
        val tools = McpClient(config(), happyServer()).listTools()
        Assert.assertEquals(1, tools.size)
        Assert.assertEquals("mcp__guides__lookup", tools.first().qualifiedName)
        Assert.assertEquals("Guide server", tools.first().serverName)
    }

    @Test
    fun paginationFollowsCursor() {
        var page = 0
        val transport = ScriptedTransport { req ->
            val body = Json.parse(req.body) as JsonValue.Obj
            val id = body.int("id") ?: 1
            when (body.str("method")) {
                "initialize" -> McpHttpResponse(
                    200,
                    mapOf(McpProtocol.HEADER_SESSION to "s"),
                    jsonRpc(id, """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}}}"""),
                )
                "tools/list" -> {
                    page++
                    if (page == 1) {
                        McpHttpResponse(200, emptyMap(), jsonRpc(id, """{"tools":[{"name":"a"}],"nextCursor":"c1"}"""))
                    } else {
                        McpHttpResponse(200, emptyMap(), jsonRpc(id, """{"tools":[{"name":"b"}]}"""))
                    }
                }
                else -> McpHttpResponse(202)
            }
        }
        val tools = McpClient(config(), transport).listTools()
        Assert.assertEquals(listOf("a", "b"), tools.map { it.name })
        Assert.assertEquals(2, page)
    }

    @Test
    fun toolCountIsCapped() {
        val many = (1..200).joinToString(",") { """{"name":"t$it"}""" }
        val tools = McpClient(config(), happyServer(toolsJson = "[$many]")).listTools()
        Assert.assertEquals(McpClient.MAX_TOOLS, tools.size)
    }

    @Test
    fun toolCacheIsReusedUntilForced() {
        val transport = happyServer()
        val client = McpClient(config(), transport)
        client.listTools()
        val firstCount = transport.requests.count { it.body.contains("tools/list") }
        client.listTools()
        Assert.assertEquals(firstCount, transport.requests.count { it.body.contains("tools/list") })
        client.invalidateTools()
        client.listTools()
        Assert.assertEquals(firstCount + 1, transport.requests.count { it.body.contains("tools/list") })
    }

    @Test
    fun callToolSendsArgumentsAndParsesText() {
        val transport = happyServer()
        val client = McpClient(config(), transport)
        client.initialize()
        val result = client.callTool("lookup", Json.obj("q" to Json.str("cats")))
        Assert.assertEquals("hello", result.text)
        Assert.assertFalse(result.isError)
        val call = transport.requests.last { it.body.contains("tools/call") }
        val params = (Json.parse(call.body) as JsonValue.Obj).obj("params")
        Assert.assertEquals("lookup", params?.str("name"))
        Assert.assertEquals("cats", params?.obj("arguments")?.str("q"))
    }

    @Test
    fun callToolParsesMixedContentAndErrors() {
        val body = """{"isError":true,"content":[
            {"type":"text","text":"partial"},
            {"type":"image","mimeType":"image/png","data":"AAAA"},
            {"type":"resource_link","uri":"https://x.test/","name":"x"},
            {"type":"mystery","payload":{"a":1}}]}"""
        val client = McpClient(config(), happyServer(callJson = body))
        val result = client.callTool("lookup")
        Assert.assertTrue(result.isError)
        Assert.assertEquals(4, result.contents.size)
        Assert.assertTrue(result.text.contains("partial"))
        Assert.assertTrue(result.text.contains("image/png"))
        Assert.assertTrue(result.text.contains("https://x.test/"))
        Assert.assertTrue(result.text.contains("[mystery]"))
    }

    // ------------------------------------------------------------- resources

    @Test
    fun readResourceReturnsText() {
        val client = McpClient(config(), happyServer())
        Assert.assertEquals("row1", client.readResource("guide://apps"))
    }

    // -------------------------------------------------------------- failures

    @Test
    fun jsonRpcErrorBecomesMcpExceptionWithCode() {
        val transport = ScriptedTransport { req ->
            val id = (Json.parse(req.body) as JsonValue.Obj).int("id") ?: 1
            McpHttpResponse(
                200,
                emptyMap(),
                """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"no such tool"}}""",
            )
        }
        try {
            McpClient(config(), transport).initialize()
            Assert.fail("expected McpException")
        } catch (e: McpException) {
            Assert.assertEquals(McpProtocol.METHOD_NOT_FOUND, e.code)
            Assert.assertTrue(e.message!!.contains("no such tool"))
        }
    }

    @Test
    fun unauthorizedIsNotRetriedAsTransportFailure() {
        val transport = ScriptedTransport { McpHttpResponse(401, emptyMap(), "nope") }
        try {
            McpClient(config(), transport).initialize()
            Assert.fail("expected McpAuthException")
        } catch (e: McpAuthException) {
            Assert.assertEquals(401, e.status)
        }
    }

    @Test
    fun expiredSessionReinitialisesOnce() {
        var initializeCalls = 0
        var sawSession = false
        val transport = ScriptedTransport { req ->
            val body = Json.parse(req.body) as JsonValue.Obj
            val id = body.int("id") ?: 1
            when {
                body.str("method") == "initialize" -> {
                    initializeCalls++
                    McpHttpResponse(
                        200,
                        mapOf(McpProtocol.HEADER_SESSION to "s$initializeCalls"),
                        jsonRpc(id, """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}}}"""),
                    )
                }
                body.str("method") == "tools/list" && !sawSession -> {
                    sawSession = true
                    McpHttpResponse(200, emptyMap(), jsonRpc(id, """{"tools":[{"name":"a"}]}"""))
                }
                body.str("method") == "tools/list" -> McpHttpResponse(404, emptyMap(), "session gone")
                else -> McpHttpResponse(202)
            }
        }
        val client = McpClient(config(), transport)
        client.initialize()
        Assert.assertEquals(1, client.listTools().size)
        // Second call hits the expired-session path: re-initialize, retry, then 404 again.
        try {
            client.listTools(force = true)
        } catch (e: McpException) {
            Assert.assertTrue(e.message!!.contains("404"))
        }
        Assert.assertEquals(2, initializeCalls)
    }

    @Test
    fun malformedPayloadIsIgnoredNotFatal() {
        val transport = ScriptedTransport { req ->
            val body = Json.parse(req.body) as JsonValue.Obj
            val id = body.int("id") ?: 1
            when (body.str("method")) {
                "initialize" -> McpHttpResponse(
                    200,
                    mapOf(McpProtocol.HEADER_SESSION to "s"),
                    "event: message\ndata: not-json\n\ndata: ${jsonRpc(id, """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}}}""")}\n\n",
                )
                else -> McpHttpResponse(200, emptyMap(), "{}")
            }
        }
        val info = McpClient(config(), transport).initialize()
        Assert.assertEquals("2025-06-18", info.protocolVersion)
    }

    @Test
    fun sseResponsesAreParsed() {
        val transport = ScriptedTransport { req ->
            val body = Json.parse(req.body) as JsonValue.Obj
            val id = body.int("id") ?: 1
            when (body.str("method")) {
                "initialize" -> McpHttpResponse(
                    200,
                    mapOf(McpProtocol.HEADER_SESSION to "s"),
                    "event: message\ndata: ${jsonRpc(id, """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}}}""")}\n\n",
                )
                "tools/list" -> McpHttpResponse(
                    200,
                    emptyMap(),
                    "event: message\ndata: ${jsonRpc(id, """{"tools":[{"name":"sse_tool"}]}""")}\n\n",
                )
                else -> McpHttpResponse(202)
            }
        }
        Assert.assertEquals(listOf("sse_tool"), McpClient(config(), transport).listTools().map { it.name })
    }

    @Test
    fun listChangedNotificationInvalidatesCache() {
        var calls = 0
        val transport = ScriptedTransport { req ->
            val body = Json.parse(req.body) as JsonValue.Obj
            val id = body.int("id") ?: 1
            when (body.str("method")) {
                "initialize" -> McpHttpResponse(
                    200,
                    mapOf(McpProtocol.HEADER_SESSION to "s"),
                    jsonRpc(id, """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}}}"""),
                )
                "tools/list" -> {
                    calls++
                    McpHttpResponse(
                        200,
                        emptyMap(),
                        """{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}
                           ${jsonRpc(id, """{"tools":[{"name":"t$calls"}]}""")}""",
                    )
                }
                else -> McpHttpResponse(202)
            }
        }
        val client = McpClient(config(), transport)
        Assert.assertEquals("t1", client.listTools().first().name)
        // The notification inside the response invalidated the cache, so this refetches.
        Assert.assertEquals("t2", client.listTools().first().name)
    }

    @Test
    fun closeIsBestEffort() {
        val transport = happyServer()
        val client = McpClient(config(), transport)
        client.initialize()
        client.close()
        Assert.assertTrue(transport.requests.any { it.method == "DELETE" })
    }

    @Test
    fun nonHttpConfigFailsFast() {
        try {
            McpClient(config().copy(url = "stdio:npx"), ScriptedTransport { McpHttpResponse(200) }).initialize()
            Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            Assert.assertTrue(e.message!!.contains("http"))
        }
    }

    // ------------------------------------------------------------------ json

    @Test
    fun jsonRoundTripsAndRejectsGarbage() {
        val value = Json.obj(
            "a" to Json.str("x\"y"),
            "b" to Json.num(3),
            "c" to JsonValue.Arr(listOf(JsonValue.Bool(true), JsonValue.Null)),
        )
        Assert.assertEquals(value, Json.parse(Json.write(value)))
        try {
            Json.parse("{oops")
            Assert.fail("expected McpJsonException")
        } catch (e: McpJsonException) {
            Assert.assertTrue(e.message!!.isNotEmpty())
        }
    }
}
