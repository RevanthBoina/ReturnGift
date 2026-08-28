package com.returngift.agent.agent

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Golden-corpus gate for the Tier-1 deterministic layer.
 *
 * Reads `fixtures/tier1_golden_utterances.jsonl` (repo root, on the unit-test classpath
 * via build.gradle.kts sourceSets) and asserts every utterance resolves to the expected
 * intent token — or FALLBACK when it must go to Tier 2/3. This runs under testDebugUnitTest,
 * so CI enforces the whole corpus on every PR. P0/P1: adding an intent without adding its
 * corpus rows fails this gate.
 */
@RunWith(Parameterized::class)
class TaskParserGoldenCorpusTest(private val input: String, private val expected: String) {

    @Test
    fun `utterance routes as expected`() {
        val got = TaskParser.tier1Intent(input) ?: "FALLBACK"
        assertEquals("expected=${expected} for '$input'", expected, got)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} -> {1}")
        fun data(): List<Array<Any>> {
            val stream = javaClass.classLoader
                ?.getResourceAsStream("tier1_golden_utterances.jsonl")
                ?: error("golden corpus resource not found on test classpath")
            return stream.bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.map { line ->
                    val json = JsonParser.parseString(line).asJsonObject
                    arrayOf<Any>(
                        json.get("utterance").asString,
                        json.get("expected").asString
                    )
                }.toList()
            }
        }
    }
}