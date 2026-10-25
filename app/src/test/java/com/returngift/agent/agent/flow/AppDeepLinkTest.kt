// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deep links: the shortcut that removes the in-app search ladder. */
class AppDeepLinkTest {

    @Test
    fun `linkedin search uri is built and encoded`() {
        assertEquals(
            "linkedin://search?keywords=zip",
            AppDeepLinks.searchUri("com.linkedin.android", "zip"),
        )
        assertEquals(
            "linkedin://search?keywords=machine%20learning%20jobs",
            AppDeepLinks.searchUri("com.linkedin.android", "machine learning jobs"),
        )
        // Symbols that would break a URI are encoded, never injected raw.
        assertEquals(
            "linkedin://search?keywords=a%26b%3Fc%23d",
            AppDeepLinks.searchUri("com.linkedin.android", "a&b?c#d"),
        )
    }

    @Test
    fun `linkedin search is best effort while youtube and play store are documented`() {
        assertEquals(
            LinkConfidence.BEST_EFFORT,
            AppDeepLinks.forPackage("com.linkedin.android")?.confidence,
        )
        assertEquals(
            LinkConfidence.DOCUMENTED,
            AppDeepLinks.forPackage("com.google.android.youtube")?.confidence,
        )
        assertEquals(
            LinkConfidence.DOCUMENTED,
            AppDeepLinks.forPackage("com.android.vending")?.confidence,
        )
    }

    @Test
    fun `blank query never produces a uri`() {
        assertNull(AppDeepLinks.searchUri("com.linkedin.android", ""))
        assertNull(AppDeepLinks.searchUri("com.linkedin.android", "   "))
        assertNull(AppDeepLinks.searchUri(null, "zip"))
    }

    @Test
    fun `unknown and case-insensitive packages are handled`() {
        assertNull(AppDeepLinks.forPackage("com.example.unknown"))
        assertNull(AppDeepLinks.forPackage(null))
        assertNull(AppDeepLinks.searchUri("com.example.unknown", "zip"))
        assertEquals(
            "com.linkedin.android",
            AppDeepLinks.forPackage("COM.LINKEDIN.ANDROID")?.packageName,
        )
    }

    @Test
    fun `apps without a search entry point report no search support`() {
        assertFalse(AppDeepLinks.supportsSearch("com.instagram.android"))
        assertNull(AppDeepLinks.searchUri("com.instagram.android", "zip"))
        assertTrue(AppDeepLinks.supportsSearch("com.google.android.youtube"))
        assertEquals("instagram://app", AppDeepLinks.homeUri("com.instagram.android"))
    }

    @Test
    fun `prompt hints separate documented from best-effort links`() {
        val documented = AppDeepLinks.promptHintFor("com.google.android.youtube", "zip")
        assertTrue(documented.contains("vnd.youtube://results?search_query=zip"))
        assertTrue(documented.contains("Use it first"))

        val bestEffort = AppDeepLinks.promptHintFor("com.linkedin.android", "zip")
        assertTrue(bestEffort.contains("linkedin://search?keywords=zip"))
        assertTrue(bestEffort.contains("Best effort"))

        assertEquals("", AppDeepLinks.promptHintFor("com.example.unknown", "zip"))
    }

    @Test
    fun `prompt section lists only apps that have a link`() {
        val section = AppDeepLinks.promptSection(
            packages = listOf("com.linkedin.android", "com.example.unknown"),
            query = "zip",
        )
        assertTrue(section.contains("Direct entry points"))
        assertTrue(section.contains("linkedin://search?keywords=zip"))
        assertFalse(section.contains("com.example.unknown"))

        assertEquals("", AppDeepLinks.promptSection(listOf("com.example.unknown"), "zip"))
    }

    @Test
    fun `query hint extracts the search term from real requests`() {
        assertEquals("zip", AppDeepLinks.queryHint("open LinkedIn and search 'zip'"))
        assertEquals("zip", AppDeepLinks.queryHint("search \"zip\" on LinkedIn"))
        assertEquals("zip", AppDeepLinks.queryHint("open LinkedIn and search for zip on LinkedIn"))
        assertEquals("espresso machines", AppDeepLinks.queryHint("search espresso machines in Maps"))
        assertNull(AppDeepLinks.queryHint("open LinkedIn"))
        assertNull(AppDeepLinks.queryHint("search it on LinkedIn"))
        assertNull(AppDeepLinks.queryHint(""))
    }
}
