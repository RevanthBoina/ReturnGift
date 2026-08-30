package com.returngift.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.InputStream
import java.util.yaml.Yaml

class IntentsYamlSyncTest {

    @Test
    fun `YAML parses without error`() {
        val stream: InputStream = javaClass.classLoader.getResourceAsStream("assets/tier1/intents.yaml")
        assert(stream != null) { "tier1/intents.yaml not found on classpath" }
        val yaml = Yaml()
        val map = yaml.load<Any>(stream)
        assert(map is Map<*, *>) { "YAML did not parse to a Map" }
    }

    @Test
    fun `YAML contains all spec intent ids`() {
        // Read the spec file from repo root
        val specStream = javaClass.classLoader.getResourceAsStream("docs/specs/tier1-intent-matching.md")
        assert(specStream != null) { "spec file not found" }
        val specContent = String(specStream.bufferedReader().readText())
        
        // Extract intent ids from §3 of the spec (the precedence table)
        // Order 1: call, 2: send_message, 3: sms, 4: alarm, 4a: timer, 5: screenshot, 6: flashlight, 7: camera, 8: back / home, 9: open_url, 10: open_settings, 11: open_app
        val expectedIds = listOf(
            "call", "send_message", "sms", "alarm", "timer", 
            "screenshot", "flashlight", "camera", "back", "home", 
            "open_url", "open_settings", "open_app"
        )
        
        // Parse YAML
        val stream = javaClass.classLoader.getResourceAsStream("assets/tier1/intents.yaml")
        val yaml = java.io.Yaml()
        val map = yaml.load<Any>(stream) as Map<String, Any>
        
        // Get all intent ids from YAML
        val yamlIds = map.keys
        
        // Verify every spec intent id appears in YAML
        for (id in expectedIds) {
            assert(yamlIds.contains(id)) { "Intent '$id' from spec missing from YAML" }
        }
        
        // Verify YAML doesn't have extra ids not in spec (optional but good for sync)
        // Actually, the spec says "every intent id in §3 of docs/... appears in the YAML and vice versa"
        // So YAML should only contain these ids
        for (id in yamlIds) {
            assert(expectedIds.contains(id)) { "Intent '$id' in YAML not in spec §3" }
        }
    }

    @Test
    fun `golden corpus produces identical results`() {
        val stream = javaClass.classLoader.getResourceAsStream("tier1_golden_utterances.jsonl")
        assert(stream != null) { "golden corpus not found on test classpath" }
        
        val gotResults = mutableMapOf<String, String>()
        stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                val json = com.google.gson.JsonParser.parseString(line)
                val utterance = json.getAsJsonObject().get("utterance").asString
                val expected = json.getAsJsonObject().get("expected").asString
                val got = TaskParser.tier1Intent(utterance) ?: "FALLBACK"
                gotResults[utterance] = "$got (expected=$expected)"
            }
        }
        
        // Verify all utterances match expected
        stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                val json = com.google.gson.JsonParser.parseString(line)
                val utterance = json.getAsJsonObject().get("utterance").asString
                val expected = json.getAsJsonObject().get("expected").asString
                val got = gotResults[utterance]!!
                assertEquals("Gold corpus match for '$utterance'", expected, got)
            }
        }
    }
}