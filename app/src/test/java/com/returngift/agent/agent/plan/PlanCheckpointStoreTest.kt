// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.plan

import com.returngift.agent.agent.exec.micro.AppSurface
import com.returngift.agent.agent.knowledge.AppGuideStore
import org.junit.Assert
import org.junit.Before
import org.junit.Test

/**
 * Resume-or-abandon, never restart-and-claim-success.
 *
 * A checkpoint is only useful if it is (a) tied to the same request, (b) recent, and
 * (c) parseable against the plan the app rebuilt. Everything else must fall back to a
 * fresh plan instead of half-restoring a state nobody can reason about.
 */
class PlanCheckpointStoreTest {

    private class FakeStorage : AppGuideStore.Storage {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String, defaultValue: String): String = map[key] ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private lateinit var storage: FakeStorage
    private val t0 = 1_000_000L

    @Before
    fun setUp() {
        storage = FakeStorage()
        PlanCheckpointStore.storage = storage
    }

    private fun blueprintFor(): TaskBlueprint = PlanFirst.plan(
        "follow youtube on LinkedIn",
        PlanContext(
            apps = listOf(
                AppRef("LinkedIn", "com.example.social", AppSurface.SOCIAL_POST),
                AppRef("YouTube", "com.example.video", AppSurface.VIDEO),
            )
        ),
    ).blueprint

    private fun contract() = PlanContract.from("task-1", blueprintFor())

    /** A tool that can satisfy [action], so the happy path is driven honestly. */
    private fun toolFor(action: PlanAction): String =
        PlanContract.TOOL_ACTIONS.entries.firstOrNull { (_, actions) -> action in actions }?.key
            ?: error("no tool satisfies $action")

    private fun runHappyPath(c: PlanContract) {
        blueprintFor().steps.forEach { step ->
            // A VERIFY step is only satisfied by an observed outcome.
            c.observe(
                toolFor(step.action), true, "done: " + step.detail,
                verified = step.action == PlanAction.VERIFY,
            )
        }
    }

    // ── key ─────────────────────────────────────────────────────────────────

    @Test
    fun keyIgnoresCaseSpacingAndPunctuation() {
        Assert.assertEquals(
            PlanCheckpointStore.keyFor("Follow TechCrunch on LinkedIn"),
            PlanCheckpointStore.keyFor("  follow   techcrunch ON linkedin! "),
        )
    }

    @Test
    fun differentRequestsGetDifferentKeys() {
        Assert.assertNotEquals(
            PlanCheckpointStore.keyFor("follow TechCrunch"),
            PlanCheckpointStore.keyFor("follow The Verge"),
        )
    }

    // ── save / load ─────────────────────────────────────────────────────────

    @Test
    fun savedProgressResumes() {
        val contract = contract()
        val facts = blueprintFor()
        contract.observe("open_app", success = true, data = "LinkedIn home")
        PlanCheckpointStore.save("k", contract, t0)

        val restored = PlanCheckpointStore.load("k", facts, t0 + 1_000)
        Assert.assertNotNull(restored)
        Assert.assertEquals(contract.completedCount(), restored!!.completedCount())
        Assert.assertEquals(contract.current()?.number, restored.current()?.number)
        Assert.assertEquals(contract.checkpoint(), restored.checkpoint())
    }

    @Test
    fun staleCheckpointIsNotResumed() {
        val contract = contract()
        contract.observe("open_app", success = true, data = "ok")
        PlanCheckpointStore.save("k", contract, t0)
        Assert.assertNull(PlanCheckpointStore.load("k", blueprintFor(), t0 + PlanCheckpointStore.DEFAULT_TTL_MS + 1))
    }

    @Test
    fun otherRequestDoesNotResume() {
        val contract = contract()
        contract.observe("open_app", success = true, data = "ok")
        PlanCheckpointStore.save("key-a", contract, t0)
        Assert.assertNull(PlanCheckpointStore.load("key-b", blueprintFor(), t0 + 1_000))
    }

    @Test
    fun clockGoingBackwardsIsNotResumed() {
        // Restored backups can carry a future timestamp; resuming then would look
        // like progress that never happened.
        val contract = contract()
        contract.observe("open_app", success = true, data = "ok")
        PlanCheckpointStore.save("k", contract, t0 + 600_000)
        Assert.assertNull(PlanCheckpointStore.load("k", blueprintFor(), t0))
    }

    @Test
    fun unreadablePayloadStartsFresh() {
        storage.map["agent_plan_checkpoint_v1"] = "v1|k|$t0|garbage"
        Assert.assertNull(PlanCheckpointStore.load("k", blueprintFor(), t0 + 1_000))
    }

    @Test
    fun unknownVersionStartsFresh() {
        storage.map["agent_plan_checkpoint_v1"] = "v9|k|$t0|v1|x|GUARDED|NORMAL||1:DONE"
        Assert.assertNull(PlanCheckpointStore.load("k", blueprintFor(), t0 + 1_000))
    }

    @Test
    fun emptyStorageMeansNoResume() {
        Assert.assertNull(PlanCheckpointStore.load("k", blueprintFor(), t0))
    }

    @Test
    fun finishedPlanIsNotAResumePoint() {
        val contract = contract()
        runHappyPath(contract)
        // Even if something is left over (skipped), a complete plan must not resume.
        Assert.assertTrue(contract.isComplete())
        PlanCheckpointStore.save("k", contract, t0)
        Assert.assertNull(PlanCheckpointStore.load("k", blueprintFor(), t0 + 1_000))
        Assert.assertNull(storage.map["agent_plan_checkpoint_v1"])
    }

    @Test
    fun clearingRemovesTheSlot() {
        PlanCheckpointStore.save("k", contract(), t0)
        PlanCheckpointStore.clear()
        Assert.assertNull(PlanCheckpointStore.load("k", blueprintFor(), t0 + 1_000))
    }

    // ── the rebuilt plan must be the plan that was checkpointed ─────────────

    @Test
    fun aChangedStepDetailIsNotResumed() {
        val contract = contract()
        contract.observe("open_app", success = true, data = "ok")
        PlanCheckpointStore.save("k", contract, t0)

        val drifted = blueprintFor().let { bp ->
            bp.copy(steps = bp.steps.mapIndexed { i, step ->
                if (i == 1) step.copy(detail = "Search \"someone else\"") else step
            })
        }
        Assert.assertNull(PlanCheckpointStore.load("k", drifted, t0 + 1_000))
    }

    @Test
    fun aChangedStepActionIsNotResumed() {
        val contract = contract()
        contract.observe("open_app", success = true, data = "ok")
        PlanCheckpointStore.save("k", contract, t0)

        val drifted = blueprintFor().let { bp ->
            bp.copy(steps = bp.steps.mapIndexed { i, step ->
                if (i == 1) step.copy(action = PlanAction.TAP) else step
            })
        }
        Assert.assertNull(PlanCheckpointStore.load("k", drifted, t0 + 1_000))
    }

    @Test
    fun aChangedStepCountIsNotResumed() {
        val contract = contract()
        contract.observe("open_app", success = true, data = "ok")
        PlanCheckpointStore.save("k", contract, t0)

        val shorter = blueprintFor().let { bp -> bp.copy(steps = bp.steps.dropLast(1)) }
        Assert.assertNull(PlanCheckpointStore.load("k", shorter, t0 + 1_000))
    }

    @Test
    fun aResumeDoesNotCarryTheEarlierApproval() {
        // OWNER DECISION (applied): approvals are not reused after a restart.
        val contract = contract()
        contract.recordApproval(2)
        PlanCheckpointStore.save("k", contract, t0)
        val restored = PlanCheckpointStore.load("k", blueprintFor(), t0 + 1_000)!!
        Assert.assertFalse(restored.approvals.contains("2"))
    }

    @Test
    fun theKeyIsAFingerprintNotATruncatedHashCode() {
        // 16 hex chars of SHA-256 rather than Integer.toHexString(hashCode()): the old
        // key was 32 bits, where unrelated requests start colliding in aggregate.
        val a = "follow TechCrunch on LinkedIn"
        val b = "follow The Verge on YouTube"
        Assert.assertNotEquals(PlanCheckpointStore.keyFor(a), PlanCheckpointStore.keyFor(b))
        Assert.assertEquals(16, PlanCheckpointStore.keyFor(a).length)
        Assert.assertTrue(PlanCheckpointStore.keyFor(a).all { it in "0123456789abcdef" })
    }

    @Test
    fun aPayloadWrittenBeforeThePlanFingerprintStillLoads() {
        // 6-field payload (written before the digest existed): the structural check
        // alone decides, so an upgrade in the middle of a task does not lose the resume.
        val contract = contract()
        contract.observe("open_app", success = true, data = "ok")
        val legacyBody = contract.checkpoint().substringBeforeLast('|')
        storage.map["agent_plan_checkpoint_v1"] = "v1|k|$t0|$legacyBody"
        Assert.assertNotNull(PlanCheckpointStore.load("k", blueprintFor(), t0 + 1_000))
    }
}
