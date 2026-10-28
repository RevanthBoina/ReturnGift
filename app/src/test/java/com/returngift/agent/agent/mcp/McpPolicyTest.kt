// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.mcp

import com.returngift.agent.agent.knowledge.AppGuideStore
import org.junit.Assert
import org.junit.Before
import org.junit.Test

/**
 * The trust model's durable half: per-server trust, per-tool allow/deny, and the
 * promise that a stored policy can never *grant* more than the cautious default when
 * it is unreadable.
 *
 * These are the decisions a user makes once and expects to hold: "this server is
 * fine for reads", "never run `delete_*`". If they reset on restart, the safety
 * prompt becomes noise the user learns to tap through.
 */
class McpPolicyTest {

    private class FakeStorage : AppGuideStore.Storage {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String, defaultValue: String): String = map[key] ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private lateinit var storage: FakeStorage

    @Before
    fun setUp() {
        storage = FakeStorage()
        McpPolicyStore.storage = storage
        McpToolPolicy.settings = McpToolPolicy.Settings()
    }

    private fun tool(
        name: String = "lookup",
        serverId: String = "guides",
        annotations: JsonValue? = null,
    ) = McpToolDef(
        serverId = serverId,
        serverName = "Guide server",
        name = name,
        description = "does a thing",
        annotations = annotations,
    )

    // ── codec ───────────────────────────────────────────────────────────────

    @Test
    fun roundTripPreservesEveryField() {
        val settings = McpToolPolicy.Settings(
            autoApproveReads = false,
            autoApproveWrites = true,
            enabled = false,
            denyPatterns = setOf("delete", "wipe"),
            allowedTools = setOf("gmail__send_draft"),
            trustedServers = setOf("srv_a"),
            blockedServers = setOf("srv_b"),
        )
        Assert.assertEquals(settings, McpPolicyStore.decode(McpPolicyStore.encode(settings)))
    }

    @Test
    fun defaultSettingsRoundTrip() {
        val defaults = McpToolPolicy.Settings()
        Assert.assertEquals(defaults, McpPolicyStore.decode(McpPolicyStore.encode(defaults)))
    }

    @Test
    fun encodedFormIsInspectable() {
        val raw = McpPolicyStore.encode(McpToolPolicy.Settings(autoApproveWrites = true))
        Assert.assertTrue(raw, raw.startsWith("v1|"))
        Assert.assertTrue(raw, raw.contains("writes=1"))
        Assert.assertTrue(raw, raw.contains("reads=1"))
    }

    @Test
    fun emptyBlobMeansDefault() {
        Assert.assertEquals(McpToolPolicy.Settings(), McpPolicyStore.decode(""))
    }

    @Test
    fun unknownVersionMeansDefault() {
        Assert.assertEquals(
            McpToolPolicy.Settings(),
            McpPolicyStore.decode("v0|writes=1|trusted=*"),
        )
    }

    @Test
    fun corruptBlobCannotGrantPermissions() {
        // Garbage must fall back to the cautious default — never to "everything on".
        val decoded = McpPolicyStore.decode("v1|writes=maybe|reads=|trusted=;;;|blocked=,,,")
        val defaults = McpToolPolicy.Settings()
        Assert.assertEquals(defaults.autoApproveWrites, decoded.autoApproveWrites)
        Assert.assertEquals(defaults.autoApproveReads, decoded.autoApproveReads)
        Assert.assertEquals(emptySet<String>(), decoded.trustedServers)
        Assert.assertEquals(emptySet<String>(), decoded.blockedServers)
    }

    @Test
    fun namesAreNormalisedToLowercase() {
        val decoded = McpPolicyStore.decode("v1|deny=Delete, WIPE |trusted=SRV_A")
        Assert.assertTrue(decoded.denyPatterns.contains("delete"))
        Assert.assertTrue(decoded.denyPatterns.contains("wipe"))
        Assert.assertTrue(decoded.trustedServers.contains("srv_a"))
    }

    // ── persistence ─────────────────────────────────────────────────────────

    @Test
    fun saveThenLoadSurvivesRestart() {
        McpPolicyStore.save(McpToolPolicy.Settings(autoApproveWrites = true, trustedServers = setOf("srv_a")))
        // Simulate a restart: in-memory policy gone, storage intact.
        McpToolPolicy.settings = McpToolPolicy.Settings()
        val loaded = McpPolicyStore.install()
        Assert.assertTrue(loaded.autoApproveWrites)
        Assert.assertEquals(setOf("srv_a"), loaded.trustedServers)
        Assert.assertEquals(loaded, McpToolPolicy.settings)
    }

    @Test
    fun loadWithoutStoredPolicyIsCautiousDefault() {
        Assert.assertEquals(McpToolPolicy.Settings(), McpPolicyStore.load())
    }

    @Test
    fun savingMakesItLiveImmediately() {
        McpPolicyStore.save(McpToolPolicy.Settings(autoApproveReads = false))
        Assert.assertFalse(McpToolPolicy.settings.autoApproveReads)
    }

    @Test
    fun trustingAServerClearsItsBlock() {
        McpPolicyStore.save(McpToolPolicy.Settings(blockedServers = setOf("srv_a")))
        val next = McpPolicyStore.setServerTrusted("srv_a", true)
        Assert.assertTrue(next.trustedServers.contains("srv_a"))
        Assert.assertFalse(next.blockedServers.contains("srv_a"))
        Assert.assertEquals(next, McpPolicyStore.load())
    }

    @Test
    fun blockingAServerDropsItsTrust() {
        McpPolicyStore.save(McpToolPolicy.Settings(trustedServers = setOf("srv_a")))
        val next = McpPolicyStore.setServerBlocked("srv_a", true)
        Assert.assertTrue(next.blockedServers.contains("srv_a"))
        Assert.assertFalse(next.trustedServers.contains("srv_a"))
    }

    // ── decision semantics ──────────────────────────────────────────────────

    @Test
    fun blockedServerDeniesEvenReadTools() {
        val decision = McpToolPolicy.decide(
            tool(name = "list_files"),
            McpToolPolicy.Settings(blockedServers = setOf("guides")),
        )
        Assert.assertTrue(decision is McpToolPolicy.Decision.Deny)
    }

    @Test
    fun blockedServerBeatsAnExplicitToolAllow() {
        // The block was added later; a stale allow must not win.
        val decision = McpToolPolicy.decide(
            tool(name = "lookup"),
            McpToolPolicy.Settings(
                blockedServers = setOf("guides"),
                allowedTools = setOf("lookup"),
            ),
        )
        Assert.assertTrue(decision is McpToolPolicy.Decision.Deny)
    }

    @Test
    fun denyPatternBeatsAnExplicitToolAllow() {
        val decision = McpToolPolicy.decide(
            tool(name = "update_note"),
            McpToolPolicy.Settings(
                denyPatterns = setOf("update_note"),
                allowedTools = setOf("update_note"),
            ),
        )
        Assert.assertTrue(decision is McpToolPolicy.Decision.Deny)
    }

    @Test
    fun trustedServerRunsReadsWhenGlobalReadsAreOff() {
        val settings = McpToolPolicy.Settings(autoApproveReads = false, trustedServers = setOf("guides"))
        val decision = McpToolPolicy.decide(tool(name = "list_files"), settings)
        Assert.assertEquals(McpToolPolicy.Decision.Allow, decision)
    }

    @Test
    fun untrustedServerStillAsksWhenGlobalReadsAreOff() {
        val settings = McpToolPolicy.Settings(autoApproveReads = false)
        val decision = McpToolPolicy.decide(tool(name = "list_files"), settings)
        Assert.assertTrue(decision is McpToolPolicy.Decision.Ask)
    }

    @Test
    fun trustDoesNotAutoApproveWrites() {
        val settings = McpToolPolicy.Settings(trustedServers = setOf("guides"))
        val decision = McpToolPolicy.decide(tool(name = "create_issue"), settings)
        Assert.assertTrue(decision is McpToolPolicy.Decision.Ask)
    }

    @Test
    fun explicitAllowLiftsAWritePrompt() {
        val settings = McpToolPolicy.Settings(allowedTools = setOf("update_note"))
        Assert.assertEquals(McpToolPolicy.Decision.Allow, McpToolPolicy.decide(tool(name = "update_note"), settings))
    }

    @Test
    fun explicitAllowNeverLiftsADestructivePrompt() {
        // Standing approval to "update a draft" is not approval to send it.
        val settings = McpToolPolicy.Settings(
            allowedTools = setOf("send_message"),
            autoApproveWrites = true,
        )
        val decision = McpToolPolicy.decide(tool(name = "send_message"), settings)
        Assert.assertTrue(decision is McpToolPolicy.Decision.Ask)
    }

    @Test
    fun qualifiedNameMatchingWorksForAllowsAndDenies() {
        val allowed = McpToolPolicy.Settings(allowedTools = setOf("mcp__guides__update_note"))
        Assert.assertEquals(
            McpToolPolicy.Decision.Allow,
            McpToolPolicy.decide(tool(name = "update_note"), allowed),
        )
        val denied = McpToolPolicy.Settings(denyPatterns = setOf("mcp__guides__delete_note"))
        Assert.assertTrue(
            McpToolPolicy.decide(tool(name = "delete_note"), denied) is McpToolPolicy.Decision.Deny
        )
    }

    @Test
    fun masterSwitchStillWinsOverTrustAndAllow() {
        val settings = McpToolPolicy.Settings(
            enabled = false,
            trustedServers = setOf("guides"),
            allowedTools = setOf("lookup"),
        )
        Assert.assertTrue(McpToolPolicy.decide(tool(), settings) is McpToolPolicy.Decision.Deny)
    }
}
