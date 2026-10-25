// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec.routines

import com.returngift.agent.agent.exec.StructuredRoutineRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Post <text> on <social app>" — one routine, many apps.
 *
 * Every assertion loops over [SocialPostRoutine.profiles] instead of naming an
 * app, so adding a profile extends the coverage automatically and no app can be
 * special-cased without this suite noticing.
 */
class SocialPostRoutineTest {

    @Test
    fun `every profile builds a complete open - type - publish - verify spec`() {
        assertTrue("profiles must not be empty", SocialPostRoutine.profiles.isNotEmpty())
        SocialPostRoutine.profiles.forEach { profile ->
            val spec = SocialPostRoutine.buildSpec("hello world", profile)
            assertEquals("[${profile.label}] target package", profile.packageName, spec.targetPackage)
            assertEquals("[${profile.label}] four states", 4, spec.steps.size)
            assertEquals("[${profile.label}] open composer", "open composer", spec.steps[0].name)
            assertEquals("[${profile.label}] type", "enter post text", spec.steps[1].name)
            assertEquals("[${profile.label}] publish", "publish", spec.steps[2].name)
            assertEquals("[${profile.label}] verify", "verify published", spec.steps[3].name)
            assertEquals("[${profile.label}] text is passed through", "hello world", spec.steps[1].inputText)
            assertTrue("[${profile.label}] task label names the app", spec.taskLabel.contains(profile.label))
        }
    }

    @Test
    fun `no profile relies on coordinates`() {
        SocialPostRoutine.profiles.forEach { profile ->
            SocialPostRoutine.buildSpec("x y z", profile).steps
                .mapNotNull { it.target }
                .forEach { chain ->
                    assertNull("[${profile.label}] coordinates are last resort only", chain.x)
                    assertNull("[${profile.label}] coordinates are last resort only", chain.y)
                }
        }
    }

    @Test
    fun `each profile is matched by its own aliases`() {
        SocialPostRoutine.profiles.forEach { profile ->
            profile.aliases.forEach { alias ->
                val task = "post \"shipped v2\" on $alias"
                assertEquals(
                    "[$alias] should resolve to ${profile.packageName}",
                    profile.packageName,
                    SocialPostRoutine.matchProfile(task)?.packageName,
                )
            }
        }
    }

    @Test
    fun `a task without a publish verb never matches`() {
        SocialPostRoutine.profiles.forEach { profile ->
            assertNull(
                "[${profile.label}] reading the feed is not posting",
                SocialPostRoutine.matchProfile("open ${profile.aliases.first()}"),
            )
        }
        assertNull(SocialPostRoutine.matchProfile(""))
    }

    @Test
    fun `post text is extracted from the common phrasings`() {
        SocialPostRoutine.profiles.forEach { profile ->
            val name = profile.aliases.first()
            assertEquals(
                "[$name] quoted body",
                "shipped v2",
                SocialPostRoutine.extractPostText("post \"shipped v2\" on $name", profile),
            )
            assertEquals(
                "[$name] plain body",
                "shipped v2",
                SocialPostRoutine.extractPostText("post shipped v2 on $name", profile),
            )
            assertEquals(
                "[$name] app-first phrasing",
                "shipped v2",
                SocialPostRoutine.extractPostText("$name saying shipped v2", profile),
            )
        }
    }

    @Test
    fun `too-short text is rejected rather than posted`() {
        SocialPostRoutine.profiles.forEach { profile ->
            assertNull(SocialPostRoutine.extractPostText("post on ${profile.aliases.first()}", profile))
        }
    }

    @Test
    fun `the registry asks for the missing text instead of guessing`() {
        val profile = SocialPostRoutine.profiles.first()
        val match = StructuredRoutineRegistry.match("post on ${profile.aliases.first()}")
        assertTrue("a post request must match a routine", match != null)
        assertTrue("no text → clarification spec", match!!.spec.steps.isEmpty())
        assertEquals(profile.packageName, match.spec.targetPackage)
        assertTrue(
            "the question must name the app",
            match.askPrompt.contains(profile.label),
        )
        assertEquals(profile.routineId, match.routineId)
    }

    @Test
    fun `a fully specified post request becomes an executable routine`() {
        val profile = SocialPostRoutine.profiles.first()
        val match = StructuredRoutineRegistry.match("post \"shipped v2\" on ${profile.aliases.first()}")
        assertTrue(match != null)
        assertEquals(4, match!!.spec.steps.size)
        assertFalse(match.spec.steps.isEmpty())
    }

    @Test
    fun `routine ids are unique`() {
        val ids = SocialPostRoutine.profiles.map { it.routineId }
        assertEquals("routine ids must be unique", ids.size, ids.distinct().size)
        assertTrue(ids.all { it.endsWith("_post") })
    }
}
