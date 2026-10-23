// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.knowledge

import com.returngift.agent.agent.exec.micro.AppSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App usage guide — the shared source of truth for "how does the agent use this
 * app", consumed by the system prompt and by Settings → App permissions.
 */
class AppUsageGuideTest {

    @Test
    fun `the three apps called out in the request are guided`() {
        val chrome = AppUsageGuide.forPackage("com.android.chrome")
        val linkedin = AppUsageGuide.forPackage("com.linkedin.android")
        val youtube = AppUsageGuide.forPackage("com.google.android.youtube")

        assertNotNull(chrome)
        assertNotNull(linkedin)
        assertNotNull(youtube)
        assertEquals(AppSurface.BROWSER_ADDRESS, chrome!!.surface)
        assertEquals(AppSurface.SOCIAL_POST, linkedin!!.surface)
        assertEquals(AppSurface.VIDEO, youtube!!.surface)
        assertTrue(chrome.tips.any { it.contains("address bar", ignoreCase = true) })
        assertTrue(linkedin.tips.any { it.contains("Post", ignoreCase = true) })
        assertTrue(youtube.tips.any { it.contains("search", ignoreCase = true) })
    }

    @Test
    fun `every guide has a label, an alias, a tip and a known surface`() {
        assertTrue("guide list should not be empty", AppUsageGuide.all().size >= 10)
        AppUsageGuide.all().forEach { guide ->
            assertTrue("${guide.packageName} needs a label", guide.label.isNotBlank())
            assertTrue("${guide.packageName} needs an alias", guide.aliases.isNotEmpty())
            assertTrue("${guide.packageName} needs a tip", guide.tips.isNotEmpty())
            assertTrue("${guide.packageName} has an unknown surface", guide.surface != AppSurface.UNKNOWN)
            assertTrue("${guide.packageName} hint blank", guide.shortHint.isNotBlank())
        }
        assertEquals(
            "duplicate guided packages",
            AppUsageGuide.all().size,
            AppUsageGuide.all().map { it.packageName }.distinct().size,
        )
    }

    @Test
    fun `package lookup tolerates variants and unknown packages`() {
        assertEquals("com.android.chrome", AppUsageGuide.forPackage("com.android.chrome")?.packageName)
        assertEquals("com.android.chrome", AppUsageGuide.forPackage("COM.ANDROID.CHROME")?.packageName)
        assertNull(AppUsageGuide.forPackage("com.example.unknown"))
        assertNull(AppUsageGuide.forPackage(""))
        assertNull(AppUsageGuide.forPackage(null))
        assertFalse(AppUsageGuide.isKnownApp("com.example.unknown"))
        assertTrue(AppUsageGuide.isKnownApp("com.linkedin.android"))
    }

    @Test
    fun `task matching finds apps named in the request`() {
        val task = "Post on LinkedIn about the release, then check YouTube"
        val matched = AppUsageGuide.matchingTask(task).map { it.packageName }
        assertTrue(matched.contains("com.linkedin.android"))
        assertTrue(matched.contains("com.google.android.youtube"))
        assertFalse(matched.contains("com.android.chrome"))
    }

    @Test
    fun `task matching respects word boundaries`() {
        // "chrome" inside another word must not match
        assertTrue(AppUsageGuide.matchingTask("read this chromebook review").isEmpty())
        // plural/partial words do not match either
        assertTrue(AppUsageGuide.matchingTask("the youtuber said hi").isEmpty())
        assertEquals(
            listOf("com.android.chrome"),
            AppUsageGuide.matchingTask("open Chrome please").map { it.packageName },
        )
    }

    @Test
    fun `prompt section lists apps named in the task with their steps`() {
        val section = AppUsageGuide.promptSection(
            task = "Open Chrome and search for espresso machines",
            installedPackages = listOf("com.android.chrome", "com.linkedin.android"),
        )
        assertTrue(section.contains("App usage guide"))
        assertTrue(section.contains("Chrome"))
        assertTrue(section.contains("address bar"))
        assertFalse("apps not named and not first should not be added", section.contains("com.linkedin.android"))
    }

    @Test
    fun `prompt section falls back to installed guided apps`() {
        val section = AppUsageGuide.promptSection(
            task = "Take a note for me",
            installedPackages = listOf("com.google.android.youtube", "com.example.notguided"),
        )
        assertTrue(section.contains("YouTube"))
        assertFalse(section.contains("com.example.notguided"))
    }

    @Test
    fun `prompt section is capped and empty when nothing applies`() {
        val section = AppUsageGuide.promptSection(
            task = "do something",
            installedPackages = AppUsageGuide.all().map { it.packageName },
            maxApps = 2,
        )
        // Header + at most two bullet lines
        assertEquals(2, section.lines().count { it.startsWith("- **") })

        val empty = AppUsageGuide.promptSection(task = "what is 2+2?", installedPackages = emptyList())
        assertEquals("", empty)
    }

    @Test
    fun `surface lookup matches the micro-step planner expectations`() {
        assertEquals(AppSurface.BROWSER_ADDRESS, AppUsageGuide.surfaceFor("com.android.chrome"))
        assertEquals(AppSurface.SEARCH_FIELD, AppUsageGuide.surfaceFor("com.google.android.contacts"))
        assertEquals(AppSurface.CHAT_COMPOSER, AppUsageGuide.surfaceFor("com.whatsapp"))
        assertNull(AppUsageGuide.surfaceFor("com.example.unknown"))
    }
}
