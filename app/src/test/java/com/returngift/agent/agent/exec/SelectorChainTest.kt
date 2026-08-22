// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectorChainTest {

    @Test
    fun `candidates are ordered semantic-first coordinates-last`() {
        val chain = SelectorChain(
            text = "Post",
            contentDesc = "Publish post",
            resourceId = "com.app:id/post",
            viewClass = "android.widget.Button",
            x = 100, y = 200,
        )
        val kinds = chain.orderedCandidates().map { it.kind }
        assertEquals(
            listOf(
                SelectorChain.Candidate.Kind.TEXT,
                SelectorChain.Candidate.Kind.CONTENT_DESC,
                SelectorChain.Candidate.Kind.RESOURCE_ID,
                SelectorChain.Candidate.Kind.A11Y_PROPS,
                SelectorChain.Candidate.Kind.COORDINATES,
            ),
            kinds
        )
    }

    @Test
    fun `missing candidates are skipped`() {
        val kinds = SelectorChain(text = "OK").orderedCandidates().map { it.kind }
        assertEquals(listOf(SelectorChain.Candidate.Kind.TEXT), kinds)
    }

    @Test
    fun `empty chain is rejected`() {
        try {
            SelectorChain()
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected — at least one selector is required
        }
    }

    @Test
    fun `escalation json parses tolerant keys`() {
        val chain = SelectorChain.fromEscalationJson(
            mapOf("text" to "Send", "resource_id" to "com.app:id/send", "x" to 10, "y" to 20)
        )
        assertEquals("Send", chain?.text)
        assertEquals("com.app:id/send", chain?.resourceId)
        assertEquals(10, chain?.x)
    }

    @Test
    fun `escalation json with nothing usable returns null`() {
        assertNull(SelectorChain.fromEscalationJson(mapOf("text" to "  ")))
        assertNull(SelectorChain.fromEscalationJson(emptyMap()))
    }
}
