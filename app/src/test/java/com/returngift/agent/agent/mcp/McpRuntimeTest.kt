// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.mcp

import com.returngift.agent.agent.exec.micro.AppSurface
import com.returngift.agent.agent.knowledge.AppGuide
import com.returngift.agent.agent.knowledge.AppGuideCodec
import com.returngift.agent.agent.knowledge.AppGuideStore
import com.returngift.agent.agent.knowledge.AppKnowledge
import com.returngift.agent.agent.knowledge.GuideSource
import org.junit.Assert
import org.junit.Test

/**
 * Store + runtime behaviour: rows persist, a dead server never poisons the rest,
 * tool names route back to the right server, and an MCP resource can update app
 * knowledge without a release.
 */
object McpRuntimeTest {

    /** In-memory storage seam: no MMKV, no Android. */
    private class FakeStorage : AppGuideStore.Storage {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String, defaultValue: String): String = map[key] ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** Secrets need a cipher; tests use a reversible stand-in for the Keystore. */
    private object FakeCipher : McpSecretStore.SecretCipher {
        override val isSecure: Boolean = true
        override fun encrypt(plaintext: String): String = "enc:" + plaintext.reversed()
        override fun decrypt(blob: String): String? =
            if (blob.startsWith("enc:")) blob.removePrefix("enc:").reversed() else null
    }

    /** Tokens live in their own store; tests give it a fake so nothing touches MMKV. */
    private val secretStorage = FakeStorage()

    @org.junit.Before
    fun setUpSecrets() {
        McpSecretStore.cipher = FakeCipher
        McpSecretStore.storage = secretStorage
        secretStorage.map.clear()
    }

    private fun row(id: String, name: String = id) = McpServerConfig(
        id = id,
        name = name,
        url = "https://$id.example.test/mcp",
        authToken = "tok-$id",
    )

    private fun toolsServer(prefix: String, tools: List<String>): ScriptedTransport {
        return ScriptedTransport { request ->
            val body = Json.parse(request.body) as JsonValue.Obj
            val id = body.int("id") ?: 1
            val requested = body.obj("params")?.str("name").orEmpty()
            val result = when (body.str("method")) {
                "initialize" -> """{"protocolVersion":"2025-06-18","capabilities":{"tools":{},"resources":{}}}"""
                "tools/list" -> """{"tools":[${tools.joinToString(",") { """{"name":"$prefix$it"}""" }}]}"""
                "tools/call" -> """{"content":[{"type":"text","text":"$prefix$requested-ok"}]}"""
                "resources/read" -> """{"contents":[{"uri":"u","text":"rows"}]}"""
                else -> ""
            }
            if (result.isEmpty()) McpHttpResponse(202)
            else McpHttpResponse(
                200,
                if (body.str("method") == "initialize") mapOf(McpProtocol.HEADER_SESSION to "s") else emptyMap(),
                """{"jsonrpc":"2.0","id":$id,"result":$result}""",
            )
        }
    }

    // ------------------------------------------------------------------ store

    @Test
    fun storeRoundTripsRows() {
        McpServerStore.storage = FakeStorage()
        McpServerStore.clear()
        Assert.assertTrue(McpServerStore.upsert(row("alpha", "Alpha tools")))
        val loaded = McpServerStore.get("alpha")
        Assert.assertNotNull(loaded)
        Assert.assertEquals("Alpha tools", loaded!!.name)
        Assert.assertEquals("tok-alpha", loaded.authToken)
        Assert.assertTrue(loaded.enabled)
        Assert.assertEquals(1, McpServerStore.all().size)
    }

    @Test
    fun storeTogglesEnableWithoutLosingTheRow() {
        McpServerStore.storage = FakeStorage()
        McpServerStore.clear()
        McpServerStore.upsert(row("alpha"))
        Assert.assertTrue(McpServerStore.setEnabled("alpha", false))
        Assert.assertTrue(McpServerStore.enabled().isEmpty())
        Assert.assertEquals(1, McpServerStore.all().size)
        Assert.assertFalse(McpServerStore.setEnabled("missing", false))
    }

    @Test
    fun storeRemovesRows() {
        McpServerStore.storage = FakeStorage()
        McpServerStore.clear()
        McpServerStore.upsert(row("alpha"))
        McpServerStore.upsert(row("beta"))
        McpServerStore.remove("alpha")
        Assert.assertEquals(listOf("beta"), McpServerStore.all().map { it.id })
        Assert.assertNull(McpServerStore.get("alpha"))
    }

    @Test
    fun storeCapsServerCount() {
        McpServerStore.storage = FakeStorage()
        McpServerStore.clear()
        repeat(McpServerStore.MAX_SERVERS) { McpServerStore.upsert(row("s$it")) }
        Assert.assertFalse(McpServerStore.upsert(row("overflow")))
        Assert.assertEquals(McpServerStore.MAX_SERVERS, McpServerStore.all().size)
    }

    @Test
    fun storeSurvivesMalformedRow() {
        val storage = FakeStorage()
        storage.map["mcp_server_v1_broken"] = "{not json"
        storage.map["mcp_servers_v1_index"] = "broken"
        McpServerStore.storage = storage
        Assert.assertTrue(McpServerStore.all().isEmpty())
    }

    @Test
    fun validationRejectsBadRows() {
        Assert.assertNotNull(row("ok").copy(url = "").validationError())
        Assert.assertNotNull(row("ok").copy(url = "stdio:npx foo").validationError())
        Assert.assertNotNull(row("ok").copy(id = " ").validationError())
        Assert.assertNull(row("ok").validationError())
    }

    @Test
    fun storeRefusesInvalidRows() {
        McpServerStore.storage = FakeStorage()
        McpServerStore.clear()
        try {
            McpServerStore.upsert(row("bad").copy(url = "not-a-url"))
            Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            Assert.assertTrue(e.message!!.contains("http"))
        }
    }

    @Test
    fun describeNeverLeaksTheToken() {
        Assert.assertFalse(row("alpha").describe().contains("tok-alpha"))
    }

    // ---------------------------------------------------------------- runtime

    @Test
    fun refreshDiscoversAndIndexesTools() {
        val transports = mapOf(
            "alpha" to toolsServer("a_", listOf("search", "fetch")),
            "beta" to toolsServer("b_", listOf("send")),
        )
        McpRuntime.setFactoryForTest(object : McpRuntime.ClientFactory {
            override fun create(config: McpServerConfig) =
                McpClient(config, transports.getValue(config.id))
        })
        val count = McpRuntime.refresh(listOf(row("alpha"), row("beta")))
        Assert.assertEquals(3, count)
        Assert.assertTrue(McpRuntime.isActive())
        Assert.assertEquals(
            setOf("mcp__alpha__a_search", "mcp__alpha__a_fetch", "mcp__beta__b_send"),
            McpRuntime.toolsFor("search for a page").map { it.qualifiedName }.toSet(),
        )
        Assert.assertTrue(McpRuntime.promptSection("send a message").contains("## Connected tools (MCP)"))
        McpRuntime.reset()
    }

    @Test
    fun brokenServerDoesNotBreakTheOthers() {
        val good = toolsServer("g_", listOf("search"))
        McpRuntime.setFactoryForTest(object : McpRuntime.ClientFactory {
            override fun create(config: McpServerConfig): McpClient = when (config.id) {
                "good" -> McpClient(config, good)
                else -> McpClient(config, ScriptedTransport { McpHttpResponse(500, emptyMap(), "boom") })
            }
        })
        val count = McpRuntime.refresh(listOf(row("good"), row("bad")))
        Assert.assertEquals(1, count)
        Assert.assertEquals(listOf("mcp__good__g_search"), McpRuntime.allTools().map { it.qualifiedName })
        McpRuntime.reset()
    }

    @Test
    fun callsRouteToTheOwningServer() {
        val alpha = toolsServer("a_", listOf("search"))
        McpRuntime.setFactoryForTest(object : McpRuntime.ClientFactory {
            override fun create(config: McpServerConfig) = McpClient(config, alpha)
        })
        McpRuntime.refresh(listOf(row("alpha")))
        val result = McpRuntime.call("mcp__alpha__a_search", Json.obj("q" to Json.str("x")))
        Assert.assertEquals("a_a_search-ok", result.text)
        try {
            McpRuntime.call("mcp__missing__x")
            Assert.fail("expected McpException")
        } catch (e: McpException) {
            Assert.assertTrue(e.message!!.contains("unknown MCP tool"))
        }
        McpRuntime.reset()
    }

    @Test
    fun disabledServersAreNotContacted() {
        var created = 0
        McpRuntime.setFactoryForTest(object : McpRuntime.ClientFactory {
            override fun create(config: McpServerConfig): McpClient {
                created++
                return McpClient(config, toolsServer("x_", listOf("a")))
            }
        })
        McpRuntime.refresh(listOf(row("off").copy(enabled = false)))
        Assert.assertEquals(0, created)
        McpRuntime.reset()
    }

    @Test
    fun indexDedupesAndCaps() {
        val defs = (1..40).map {
            McpToolDef("s", "S", "tool$it", description = "does thing $it")
        } + McpToolDef("s", "S", "tool1", description = "duplicate")
        val selected = McpToolIndex.select(defs, "thing 7", limit = 5)
        Assert.assertEquals(5, selected.size)
        Assert.assertEquals(5, McpToolIndex.dedupe(defs).size.coerceAtMost(5))
        Assert.assertEquals(40, McpToolIndex.dedupe(defs).size)
    }

    @Test
    fun relevancePutsTheMatchingToolFirst() {
        val defs = listOf(
            McpToolDef("s", "S", "weather", description = "forecast for a city"),
            McpToolDef("s", "S", "invoice", description = "create a billing invoice"),
        )
        Assert.assertEquals("weather", McpToolIndex.select(defs, "weather in guntur", limit = 1).first().name)
        Assert.assertEquals("invoice", McpToolIndex.select(defs, "create invoice", limit = 1).first().name)
    }

    // ------------------------------------------------------- knowledge bridge

    @Test
    fun mcpResourceBecomesAppKnowledge() {
        val payload = AppGuideCodec.encodeAll(
            listOf(
                AppGuide(
                    packageName = "com.example.connector",
                    label = "Connector app",
                    surface = AppSurface.SEARCH_FIELD,
                    tips = listOf("tap the search field, type, press enter"),
                    source = GuideSource.CONNECTOR,
                    searchLabels = listOf("Search"),
                )
            )
        )
        val transport = ScriptedTransport { request ->
            val body = Json.parse(request.body) as JsonValue.Obj
            val id = body.int("id") ?: 1
            val requested = body.obj("params")?.str("name").orEmpty()
            val result = when (body.str("method")) {
                "initialize" -> """{"protocolVersion":"2025-06-18","capabilities":{"tools":{},"resources":{}}}"""
                "resources/read" ->
                    """{"contents":[{"uri":"guide://apps","text":${Json.write(Json.str(payload))}}]}"""
                else -> ""
            }
            if (result.isEmpty()) McpHttpResponse(202)
            else McpHttpResponse(
                200,
                if (body.str("method") == "initialize") mapOf(McpProtocol.HEADER_SESSION to "s") else emptyMap(),
                """{"jsonrpc":"2.0","id":$id,"result":$result}""",
            )
        }
        McpRuntime.setFactoryForTest(object : McpRuntime.ClientFactory {
            override fun create(config: McpServerConfig) = McpClient(config, transport)
        })
        McpRuntime.refresh(listOf(row("guides")))

        Assert.assertEquals("mcp:guides", McpKnowledgeBridge.register("guides", "guide://apps"))
        Assert.assertTrue(AppKnowledge.sourceIds().contains("mcp:guides"))
        val merged = AppKnowledge.guides()
        val fromConnector = merged.firstOrNull { it.packageName == "com.example.connector" }
        Assert.assertNotNull(fromConnector)
        Assert.assertEquals(GuideSource.CONNECTOR, fromConnector!!.source)

        McpKnowledgeBridge.unregister("guides")
        Assert.assertFalse(AppKnowledge.sourceIds().contains("mcp:guides"))
        McpRuntime.reset()
    }
}
