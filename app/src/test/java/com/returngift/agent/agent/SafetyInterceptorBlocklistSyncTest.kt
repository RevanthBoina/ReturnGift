// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * A5: the hardcoded GLOBAL_BLOCKLIST_PATTERNS must never drift from the YAML source of
 * truth (`assets/skill_library/skills/send_message.yaml` → safety.blocklist_patterns).
 * Set-equality fails loudly when either side changes without syncing the other.
 *
 * Reads the file from the repository instead of the app's assets: this is a pure-JVM
 * test, and the asset packer is not part of a unit-test run — the first version asked
 * Robolectric for assets and concluded the skill did not exist.
 */
class SafetyInterceptorBlocklistSyncTest {

    private val skillsDir: File by lazy {
        val cwd = File(System.getProperty("user.dir") ?: "")
        // The test task runs from app/, so the repository root is its parent.
        val root = if (File(cwd.parentFile, "app/src/main/assets").exists()) cwd.parentFile else cwd
        File(root, "app/src/main/assets/skill_library/skills")
    }

    @Test
    fun `global blocklist matches the send_message YAML blocklist`() {
        val file = File(skillsDir, "send_message.yaml")
        assertTrue("send_message.yaml not found at ${file.absolutePath}", file.exists())

        val map = Yaml().load<Map<String, Any>>(file.readText())
        val safety = map["safety"] as? Map<*, *>
        val patterns = (safety?.get("blocklist_patterns") as? List<*>)
            ?.map { it.toString() }
            ?.toSet()
            .orEmpty()

        assertEquals(
            "drift detected — align SafetyInterceptor.GLOBAL_BLOCKLIST_PATTERNS or the YAML",
            patterns,
            SafetyInterceptor.GLOBAL_BLOCKLIST_PATTERNS.toSet(),
        )
    }

    /** The whole library must parse: one malformed flow list silently disables a skill. */
    @Test
    fun `every skill file parses and carries its recovery rules`() {
        val files = skillsDir.listFiles { f -> f.name.endsWith(".yaml") }.orEmpty()
        assertTrue("no skill files found in ${skillsDir.absolutePath}", files.isNotEmpty())
        val yaml = Yaml()
        for (file in files) {
            val map = yaml.load<Map<String, Any>>(file.readText())
            assertTrue("${file.name} has no skill_id", map["skill_id"] != null)
            val recovery = map["recovery"] as? List<*>
            if (recovery != null) {
                for (rule in recovery) {
                    val ruleMap = rule as Map<*, *>
                    assertTrue(
                        "${file.name} has a recovery rule without an 'on' key: $rule",
                        ruleMap["on"] is String && (ruleMap["on"] as String).isNotEmpty(),
                    )
                }
            }
        }
    }
}
