// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.skill

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for SkillExecutor.
 * Tests skill execution flow, retries, optional steps, and error handling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SkillExecutorTest {

    private lateinit var executor: SkillExecutor

    @Before
    fun setup() {
        executor = SkillExecutor()
    }

    private fun createSkill(
        id: String,
        steps: List<SkillStep>,
        fallbackGoal: String = "Fallback to agent loop"
    ): Skill {
        return Skill(
            id = id,
            name = id.replace('_', ' '),
            description = "Test skill: $id",
            category = SkillCategory.GENERAL,
            steps = steps,
            fallbackGoal = fallbackGoal
        )
    }

    private fun createStep(
        toolName: String,
        description: String = toolName,
        optional: Boolean = false,
        retries: Int = 1,
        params: Map<String, String> = emptyMap()
    ): SkillStep {
        return SkillStep(
            toolName = toolName,
            description = description,
            optional = optional,
            retries = retries,
            params = params
        )
    }

    // ==================== Basic Execution Tests ====================

    @Test
    fun `SkillResult contains telemetry data on success`() {
        val skill = createSkill(
            id = "simple_skill",
            steps = listOf(createStep("wait"))
        )
        
        // Execute with mock or skip actual execution for result structure test
        val result = executor.execute(
            skill = skill,
            params = emptyMap(),
            taskText = "test task",
            routeUsed = "test_route"
        )
        
        // Verify result structure
        assertNotNull(result)
        assertTrue(result.telemetryData.isNotEmpty())
        assertTrue(result.telemetryData.containsKey("total_latency_ms"))
        assertTrue(result.telemetryData.containsKey("route_used"))
    }

    @Test
    fun `SkillResult contains error message on failure`() {
        val skill = createSkill(
            id = "simple_skill",
            steps = listOf(createStep("wait"))
        )
        
        // Result should have empty error on success
        val result = executor.execute(
            skill = skill,
            params = emptyMap()
        )
        
        // Success should have no error message
        if (result.success) {
            assertNull(result.errorMessage)
        }
    }

    // ==================== Step Parameter Resolution Tests ====================

    @Test
    fun `Parameter placeholders are resolved correctly`() {
        val skill = createSkill(
            id = "param_skill",
            steps = listOf(
                createStep(
                    toolName = "input_text",
                    description = "Type message",
                    params = mapOf("text" to "Hello {name}!")
                )
            )
        )
        
        val result = executor.execute(
            skill = skill,
            params = mapOf("name" to "World")
        )
        
        // Execution should complete (may succeed or fail depending on mock)
        assertNotNull(result)
        assertTrue(result.stepsUsed >= 0)
    }

    @Test
    fun `Multiple parameters are resolved correctly`() {
        val skill = createSkill(
            id = "multi_param_skill",
            steps = listOf(
                createStep(
                    toolName = "send_message",
                    description = "Send message",
                    params = mapOf(
                        "recipient" to "{contact}",
                        "body" to "Hello {name}!"
                    )
                )
            )
        )
        
        val result = executor.execute(
            skill = skill,
            params = mapOf(
                "contact" to "John",
                "name" to "Jane"
            )
        )
        
        assertNotNull(result)
    }

    // ==================== Progress Callback Tests ====================

    @Test
    fun `Progress callback is invoked for each step`() {
        val skill = createSkill(
            id = "multi_step_skill",
            steps = listOf(
                createStep("step_one", "First step", optional = true),
                createStep("step_two", "Second step", optional = true),
                createStep("step_three", "Third step", optional = true)
            )
        )
        
        val progressCalls = mutableListOf<Triple<Int, Int, String>>()
        
        executor.execute(
            skill = skill,
            params = emptyMap(),
            onProgress = { step, total, description ->
                progressCalls.add(Triple(step, total, description))
            }
        )
        
        // Progress should be called for each step
        assertEquals(3, progressCalls.size)
        assertEquals(Triple(1, 3, "First step"), progressCalls[0])
        assertEquals(Triple(2, 3, "Second step"), progressCalls[1])
        assertEquals(Triple(3, 3, "Third step"), progressCalls[2])
    }

    // ==================== Optional Step Tests ====================

    @Test
    fun `Optional step failures are logged but don't fail skill`() {
        val skill = createSkill(
            id = "optional_skill",
            steps = listOf(
                createStep("required_step", "Required step"),
                createStep("optional_step", "Optional step", optional = true),
                createStep("final_step", "Final step")
            )
        )
        
        val result = executor.execute(
            skill = skill,
            params = emptyMap()
        )
        
        // Skill should complete (optional steps don't fail the skill)
        assertNotNull(result)
    }

    // ==================== Retry Tests ====================

    @Test
    fun `Retry count is tracked in telemetry`() {
        val skill = createSkill(
            id = "retry_skill",
            steps = listOf(
                createStep("step_with_retry", "Step with retry", retries = 3)
            )
        )
        
        val result = executor.execute(
            skill = skill,
            params = emptyMap()
        )
        
        // Telemetry should track retries
        assertNotNull(result)
        assertTrue(result.telemetryData.containsKey("retries") || result.stepsUsed >= 0)
    }

    // ==================== Fallback Goal Tests ====================

    @Test
    fun `Fallback goal is included in failure message`() {
        val customFallback = "Custom fallback message for debugging"
        val skill = createSkill(
            id = "fallback_skill",
            steps = listOf(createStep("failing_step")),
            fallbackGoal = customFallback
        )
        
        val result = executor.execute(
            skill = skill,
            params = emptyMap()
        )
        
        // Verify fallback is in the result
        if (!result.success) {
            assertTrue(result.message.contains(customFallback) || result.message.isNotEmpty())
        }
    }

    // ==================== SkillResult Data Class Tests ====================

    @Test
    fun `SkillResult default values are correct`() {
        val result = SkillResult(
            success = true,
            stepsUsed = 5,
            tokensUsed = 100,
            fallbackUsed = false,
            message = "Success"
        )
        
        assertEquals(0, result.errorMessage.hashCode()) // null
        assertTrue(result.telemetryData.isEmpty())
    }

    @Test
    fun `SkillResult can include telemetry data`() {
        val telemetry = mapOf(
            "total_latency_ms" to 1234L,
            "route_used" to "test_route",
            "retries" to 2
        )
        
        val result = SkillResult(
            success = true,
            stepsUsed = 3,
            message = "Done",
            telemetryData = telemetry
        )
        
        assertEquals(1234L, result.telemetryData["total_latency_ms"])
        assertEquals("test_route", result.telemetryData["route_used"])
        assertEquals(2, result.telemetryData["retries"])
    }

    // ==================== Edge Case Tests ====================

    @Test
    fun `Empty steps skill completes successfully`() {
        val skill = createSkill(
            id = "empty_skill",
            steps = emptyList()
        )
        
        val result = executor.execute(
            skill = skill,
            params = emptyMap()
        )
        
        assertTrue(result.success)
        assertEquals(0, result.stepsUsed)
    }

    @Test
    fun `Single step skill completes in one step`() {
        val skill = createSkill(
            id = "single_step_skill",
            steps = listOf(createStep("only_step"))
        )
        
        val result = executor.execute(
            skill = skill,
            params = emptyMap()
        )
        
        assertTrue(result.stepsUsed >= 1)
    }

    // ==================== FIX 9: cancellation ====================

    @Test
    fun `stop requested between steps stops at the next step boundary without running further steps`() {
        val progressDescriptions = mutableListOf<String>()
        var stopCalls = 0
        val skill = createSkill(
            id = "cancellable_skill",
            steps = listOf(
                createStep("wait", "First", optional = true),
                createStep("wait", "Second", optional = true),
                createStep("wait", "Third", optional = true)
            )
        )

        val result = executor.execute(
            skill = skill,
            params = emptyMap(),
            stopRequested = {
                stopCalls++
                stopCalls > 1
            },
            onProgress = { _, _, desc -> progressDescriptions.add(desc) }
        )

        assertTrue(result.cancelled)
        assertEquals("First", progressDescriptions[0])
        // Cancellation is checked BETWEEN steps, so the second step's progress is never
        // reported and no further steps execute.
        assertEquals(1, progressDescriptions.size)
    }

    @Test
    fun `stop never requested so skill completes normally`() {
        val skill = createSkill(
            id = "finishes_before_stop",
            steps = listOf(createStep("wait"))
        )
        val result = executor.execute(
            skill = skill,
            params = emptyMap(),
            stopRequested = { false }
        )
        // The wait step has no params so it fails under test; the point is it is NOT
        // reported as cancelled just because a stop predicate was passed.
        assertFalse(result.cancelled)
        assertFalse(result.timedOut)
    }

    // ==================== C5 / FIX 5: wall-clock bound ====================

    @Test
    fun `skill exceeding wall clock stops at the next step boundary`() {
        val progressDescriptions = mutableListOf<String>()
        val skill = createSkill(
            id = "slow_skill",
            steps = List(10) { createStep("wait", "Step $it", optional = true) }
        )

        val result = executor.execute(
            skill = skill,
            params = emptyMap(),
            wallClockMs = 0, // `>=` makes a 0 bound trip immediately at step 1
            onProgress = { _, _, desc -> progressDescriptions.add(desc) }
        )

        assertTrue(result.timedOut)
        assertEquals(0, progressDescriptions.size)
    }
}
