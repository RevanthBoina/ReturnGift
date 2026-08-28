// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

import com.returngift.agent.agent.skill.YamlSkillLoader

/**
 * A5: the hardcoded GLOBAL_BLOCKLIST_PATTERNS must never drift from the YAML source of
 * truth (`assets/skill_library/skills/send_message.yaml` → safety.blocklist_patterns).
 * Set-equality fails loudly when either side changes without syncing the other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SafetyInterceptorBlocklistSyncTest {

    @Test
    fun `global blocklist matches the send_message YAML blocklist`() {
        val skills = YamlSkillLoader.loadAll(RuntimeEnvironment.getApplication())
        val sendMessage = skills.firstOrNull { it.skillId == "send_message" }
        assertNotNull("send_message YAML not found in assets", sendMessage)
        assertEquals(
            "drift detected — align SafetyInterceptor.GLOBAL_BLOCKLIST_PATTERNS or the YAML",
            sendMessage!!.safety.blocklistPatterns.toSet(),
            SafetyInterceptor.GLOBAL_BLOCKLIST_PATTERNS.toSet(),
        )
    }
}
