// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec

import com.returngift.agent.agent.exec.TaskIntentClassifier.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskIntentClassifierTest {

    @Test
    fun `general knowledge question is KNOWLEDGE_QA`() {
        assertEquals(Intent.KNOWLEDGE_QA, TaskIntentClassifier.classify("What is the capital of France?").intent)
        assertEquals(Intent.KNOWLEDGE_QA, TaskIntentClassifier.classify("Explain how photosynthesis works").intent)
        assertEquals(Intent.KNOWLEDGE_QA, TaskIntentClassifier.classify("What's the difference between TCP and UDP?").intent)
    }

    @Test
    fun `bare question mark falls back to KNOWLEDGE_QA`() {
        assertEquals(Intent.KNOWLEDGE_QA, TaskIntentClassifier.classify("Why is the sky blue?").intent)
    }

    @Test
    fun `device verbs route to DEVICE_AUTOMATION`() {
        assertEquals(Intent.DEVICE_AUTOMATION, TaskIntentClassifier.classify("Open WhatsApp and send Mom a message").intent)
        assertEquals(Intent.DEVICE_AUTOMATION, TaskIntentClassifier.classify("Turn on bluetooth").intent)
        assertEquals(Intent.DEVICE_AUTOMATION, TaskIntentClassifier.classify("Post this on LinkedIn: hello world").intent)
    }

    @Test
    fun `external AI drive routes to EXTERNAL_AI_QUERY`() {
        assertEquals(Intent.EXTERNAL_AI_QUERY, TaskIntentClassifier.classify("Ask ChatGPT for a pasta recipe").intent)
        assertEquals(Intent.EXTERNAL_AI_QUERY, TaskIntentClassifier.classify("Generate an image of a fox using Gemini").intent)
    }

    @Test
    fun `vault queries route to VAULT_QUERY`() {
        assertEquals(Intent.VAULT_QUERY, TaskIntentClassifier.classify("What did I save about the trip?").intent)
        assertEquals(Intent.VAULT_QUERY, TaskIntentClassifier.classify("Read my notes on gardening").intent)
    }

    @Test
    fun `fresh-info cues route to WEB_RESEARCH`() {
        assertEquals(Intent.WEB_RESEARCH, TaskIntentClassifier.classify("Search the web for today's weather in Oslo").intent)
        assertEquals(Intent.WEB_RESEARCH, TaskIntentClassifier.classify("What's the latest news on SpaceX?").intent)
    }

    @Test
    fun `declarative fallback is KNOWLEDGE_QA not device`() {
        // The regression: declarative/ambiguous sentences must NOT enter UI automation.
        assertEquals(Intent.KNOWLEDGE_QA, TaskIntentClassifier.classify("Tell me about black holes").intent)
    }
}
