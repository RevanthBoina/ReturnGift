// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.knowledge

import com.returngift.agent.agent.exec.micro.AppSurface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The app-knowledge pipeline: built-in rows + connector rows + task-learned rows.
 *
 * Nothing here names a real app as its subject — every fixture is synthetic, so the
 * same tests hold for whatever apps the registry ships with tomorrow.
 */
class AppKnowledgeTest {

    private class FakeStorage : AppGuideStore.Storage {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String, defaultValue: String) = values[key] ?: defaultValue
        override fun putString(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }

    private lateinit var storage: FakeStorage

    private val pkg = "com.example.notes"
    private val builtin = AppGuide(
        packageName = pkg,
        label = "Notes",
        surface = AppSurface.SEARCH_FIELD,
        aliases = listOf("notes", "note app"),
        tips = listOf("Tap the search box → type → Enter."),
    )

    @Before
    fun setUp() {
        storage = FakeStorage()
        AppGuideStore.storage = storage
        AppKnowledge.reset()
        AppKnowledge.register(StoreKnowledgeSource)
        AppGuideLearner.reset()
    }

    @After
    fun tearDown() {
        AppGuideStore.storage = AppGuideStore.KVStorage
        AppKnowledge.reset()
        AppGuideLearner.reset()
    }

    // ── codec ───────────────────────────────────────────────────────────────

    @Test
    fun `codec round-trips every field`() {
        val row = builtin.copy(
            source = GuideSource.LEARNED,
            updatedAt = 1_700_000_000_000L,
            confidence = 0.6,
            searchLabels = listOf("Search notes"),
        )
        val decoded = AppGuideCodec.decode(AppGuideCodec.encode(row))
        assertEquals(row, decoded)
    }

    @Test
    fun `codec refuses malformed payloads instead of inventing rows`() {
        assertNull(AppGuideCodec.decode(null))
        assertNull(AppGuideCodec.decode(""))
        assertNull(AppGuideCodec.decode("garbage"))
        assertNull(AppGuideCodec.decode("v1\u001f\u001fNotes"))
        // A separator smuggled into user text cannot corrupt the row.
        val hostile = builtin.copy(label = "Notes\u001f\u001e")
        assertEquals("Notes", AppGuideCodec.decode(AppGuideCodec.encode(hostile))?.label)
    }

    @Test
    fun `codec decodes a connector payload of several rows`() {
        val rows = listOf(
            builtin,
            builtin.copy(packageName = "com.example.tasks", label = "Tasks"),
        )
        val decoded = AppGuideCodec.decodeAll(AppGuideCodec.encodeAll(rows))
        assertEquals(2, decoded.size)
        assertEquals(listOf(pkg, "com.example.tasks"), decoded.map { it.packageName })
    }

    // ── merge precedence ────────────────────────────────────────────────────

    @Test
    fun `learned rows beat connector rows which beat built-ins`() {
        val builtinRow = builtin
        val connectorRow = builtin.copy(
            tips = listOf("connector tip"),
            source = GuideSource.CONNECTOR,
            updatedAt = 10,
        )
        val learnedRow = builtin.copy(
            tips = listOf("learned tip"),
            source = GuideSource.LEARNED,
            updatedAt = 5,
        )

        assertEquals(connectorRow.tips, builtinRow.mergedWith(connectorRow).tips)
        assertEquals(learnedRow.tips, builtinRow.mergedWith(learnedRow).tips)
        assertEquals(learnedRow.tips, connectorRow.mergedWith(learnedRow).tips)
    }

    @Test
    fun `merge unions aliases so a learned row never loses known spellings`() {
        val learnedRow = builtin.copy(
            source = GuideSource.LEARNED,
            aliases = listOf("mynotes"),
        )
        val merged = builtin.mergedWith(learnedRow)
        assertEquals(setOf("notes", "note app", "mynotes"), merged.aliases.toSet())
        assertEquals("winner's spellings come first", "mynotes", merged.aliases.first())
    }

    @Test
    fun `same source same timestamp keeps the higher confidence`() {
        val low = builtin.copy(confidence = 0.2)
        val high = builtin.copy(confidence = 0.9)
        assertTrue(kotlin.math.abs(0.9 - low.mergedWith(high).confidence) < 0.0001)

    }

    // ── store ───────────────────────────────────────────────────────────────

    @Test
    fun `store upsert is case-insensitive and keeps one row per package`() {
        assertTrue(AppGuideStore.upsert(builtin))
        assertTrue(AppGuideStore.upsert(builtin.copy(packageName = "COM.EXAMPLE.NOTES", source = GuideSource.LEARNED)))
        assertEquals(1, AppGuideStore.packages().size)
        assertEquals(1, AppGuideStore.all().size)
    }

    @Test
    fun `store ignores blank package names`() {
        assertFalse(AppGuideStore.upsert(builtin.copy(packageName = " ")))
        assertTrue(AppGuideStore.isEmpty())
    }

    @Test
    fun `store remove and clear are honest`() {
        AppGuideStore.upsert(builtin)
        assertTrue(AppGuideStore.remove(pkg))
        assertTrue(AppGuideStore.isEmpty())

        AppGuideStore.upsert(builtin)
        AppGuideStore.clear()
        assertTrue(AppGuideStore.isEmpty())
    }

    @Test
    fun `mergeWith prefers the stored row but keeps the built-in when there is none`() {
        val other = builtin.copy(packageName = "com.example.tasks", label = "Tasks")
        AppGuideStore.upsert(builtin.copy(tips = listOf("learned"), source = GuideSource.LEARNED, updatedAt = 9))

        val merged = AppGuideStore.mergeWith(listOf(builtin, other))
        assertEquals(2, merged.size)
        assertEquals(listOf("learned"), merged.first { it.packageName == pkg }.tips)
        assertEquals(builtin.tips, merged.first { it.packageName == other.packageName }.tips)
    }

    // ── connectors (MCP seam) ───────────────────────────────────────────────

    @Test
    fun `a connector can add an app and override a built-in without a release`() {
        val connector = ConnectorKnowledgeSource("mcp:app-guides") {
            AppGuideCodec.encodeAll(
                listOf(
                    AppGuide(
                        packageName = "com.example.hr",
                        label = "HR Portal",
                        surface = AppSurface.FORM,
                        aliases = listOf("hr", "hr portal"),
                        tips = listOf("Open Profile → Time off → Request."),
                    ),
                    builtin.copy(tips = listOf("Connector says: use the magnifier.")),
                )
            )
        }
        assertEquals(2, connector.refresh())
        AppKnowledge.register(connector)

        val effective = AppKnowledge.guides(listOf(builtin))
        assertEquals(2, effective.size)
        val hr = effective.first { it.packageName == "com.example.hr" }
        assertEquals(GuideSource.CONNECTOR, hr.source)
        assertEquals(AppSurface.FORM, hr.surface)
        assertEquals(
            listOf("Connector says: use the magnifier."),
            effective.first { it.packageName == pkg }.tips,
        )
    }

    @Test
    fun `a failing connector is skipped and never poisons the prompt`() {
        val broken = ConnectorKnowledgeSource("mcp:broken") { error("connection refused") }
        assertEquals(0, broken.refresh())
        AppKnowledge.register(broken)

        val effective = AppKnowledge.guides(listOf(builtin))
        assertEquals(1, effective.size)
        assertEquals(builtin.tips, effective.first().tips)
    }

    @Test
    fun `connector rows survive a restart through the store`() {
        val connector = ConnectorKnowledgeSource("mcp:app-guides") {
            AppGuideCodec.encodeAll(listOf(builtin.copy(packageName = "com.example.hr", label = "HR")))
        }
        connector.refresh()
        AppKnowledge.persist(connector)
        assertEquals(listOf("com.example.hr"), AppGuideStore.packages())
    }

    @Test
    fun `registering the same connector twice does not duplicate rows`() {
        val connector = ConnectorKnowledgeSource("mcp:app-guides") { AppGuideCodec.encodeAll(listOf(builtin)) }
        connector.refresh()
        AppKnowledge.register(connector)
        AppKnowledge.register(connector)
        assertEquals(listOf("mcp:app-guides"), AppKnowledge.sourceIds().filter { it.startsWith("mcp:") })
    }

    // ── learning from tasks ────────────────────────────────────────────────

    private fun observeSuccess(label: String = "Search", surface: AppSurface = AppSurface.SEARCH_FIELD) =
        AppGuideLearner.observe(
            AppGuideLearner.Observation(
                packageName = pkg,
                toolName = "input_text",
                surface = surface,
                success = true,
                label = label,
                typedText = "invoice",
                now = 1_000L,
            )
        )

    @Test
    fun `a single successful run does not rewrite knowledge`() {
        assertNull("one observation is not evidence", observeSuccess())
        assertTrue(AppGuideStore.isEmpty())
    }

    @Test
    fun `repeated successful runs produce a learned row`() {
        var learned: AppGuide? = null
        repeat(AppGuideLearner.MIN_EVIDENCE) { learned = observeSuccess() }
        assertEquals(GuideSource.LEARNED, learned?.source)
        assertEquals(pkg, learned?.packageName)
        assertEquals(AppSurface.SEARCH_FIELD, learned?.surface)
        assertTrue("learned row must say what to do", learned!!.tips.first().contains("Enter"))
        assertEquals(1, AppGuideStore.all().size)
    }

    @Test
    fun `failed or no-op actions teach nothing`() {
        repeat(AppGuideLearner.MIN_EVIDENCE) {
            AppGuideLearner.observe(
                AppGuideLearner.Observation(
                    packageName = pkg,
                    toolName = "input_text",
                    surface = AppSurface.SEARCH_FIELD,
                    success = false,
                    label = "Search",
                    typedText = "invoice",
                )
            )
        }
        assertEquals(0, AppGuideLearner.evidenceCount(pkg, AppSurface.SEARCH_FIELD))
        assertTrue(AppGuideStore.isEmpty())
    }

    @Test
    fun `taps and empty text are ignored`() {
        AppGuideLearner.observe(
            AppGuideLearner.Observation(
                packageName = pkg, toolName = "tap", surface = AppSurface.LIST,
                success = true, typedText = "x",
            )
        )
        AppGuideLearner.observe(
            AppGuideLearner.Observation(
                packageName = pkg, toolName = "input_text", surface = AppSurface.SEARCH_FIELD,
                success = true, typedText = "",
            )
        )
        assertTrue(AppGuideStore.isEmpty())
    }

    @Test
    fun `learning enriches a curated row instead of replacing its tips`() {
        // Only surfaces the learner actually models, otherwise there is nothing to test.
        val known = AppUsageGuide.builtinGuides().firstOrNull {
            it.surface == AppSurface.SEARCH_FIELD || it.surface == AppSurface.CHAT_COMPOSER
        } ?: return

        run {
            AppGuideLearner.reset()
            repeat(AppGuideLearner.MIN_EVIDENCE) {
                AppGuideLearner.observe(
                    AppGuideLearner.Observation(
                        packageName = known.packageName,
                        toolName = "input_text",
                        surface = known.surface,
                        success = true,
                        label = "Filter",
                        typedText = "abc",
                        now = 2_000L,
                    )
                )
            }
            val stored = AppGuideStore.get(known.packageName)
            assertTrue("curated tips must be preserved", stored?.tips == known.tips)
            assertTrue("observed label becomes evidence", stored?.searchLabels?.contains("Filter") == true)
        }
    }

    @Test
    fun `learned rows are visible to the guide and the prompt`() {
        repeat(AppGuideLearner.MIN_EVIDENCE) { observeSuccess(label = "Find a note") }
        val effective = AppUsageGuide.all().first { it.packageName == pkg }
        assertEquals(GuideSource.LEARNED, effective.source)
        assertTrue(AppUsageGuide.isKnownApp(pkg))
        assertTrue(effective.searchLabels.contains("Find a note"))
    }
}
