// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.plan

/**
 * The plan as an **enforced, observable contract** — the missing half of plan-first.
 *
 * Before this, a plan was shown to the user and then reduced to one prompt line; the
 * model could ignore it, skip the verification step, or declare success without the
 * evidence the plan promised. Here the plan becomes state:
 *
 *  - every tool call is mapped to the step it satisfies ([gate]),
 *  - a successful call moves the contract forward and records **evidence** ([observe]),
 *  - `finish` is refused while a step that requires evidence is unmet ([completion]),
 *  - the state is serialisable so a process death does not silently replay a
 *    non-idempotent action ([checkpoint] / [fromCheckpoint]).
 *
 * Modes, because enforcement must not brick a phone:
 *  - [Mode.GUARDED] (default): off-plan actions are allowed and recorded; only
 *    completion is gated (the promise the user actually saw: the steps *and* the
 *    check at the end).
 *  - [Mode.STRICT]: a call that does not belong to the current step is refused.
 *
 * Pure Kotlin, injectable clock, no Android — so the whole state machine is tested.
 */
class PlanContract private constructor(
    val taskId: String,
    val blueprint: TaskBlueprint,
    val mode: Mode,
    private val states: MutableList<StepState>,
    val offPlanCalls: MutableList<String>,
    val approvals: MutableSet<String>,
    val riskClass: RiskClass,
) {

    enum class Mode { GUARDED, STRICT }

    enum class StepStatus { PENDING, ACTIVE, DONE, BLOCKED, FAILED, SKIPPED }

    enum class RiskClass {
        /** Read-only or benign. */
        NORMAL,

        /** Changes something the user may care about (post, follow, message). */
        SENSITIVE,

        /** Cannot be undone (delete, pay, transfer, cancel). */
        IRREVERSIBLE,
    }

    data class StepState(
        val number: Int,
        val action: PlanAction,
        val detail: String,
        val status: StepStatus = StepStatus.PENDING,
        val evidence: String? = null,
        val note: String? = null,
        /** Steps that promise an outcome must be DONE, not merely skipped. */
        val requiresEvidence: Boolean = false,
        val completedAtMs: Long = 0L,
    )

    sealed class Gate {
        data object Allow : Gate()
        data class Deny(val reason: String) : Gate()
        data class Ask(val reason: String) : Gate()
    }

    data class Completion(
        val canComplete: Boolean,
        val missing: List<String>,
        val message: String,
    ) {
        companion object {
            val OK = Completion(true, emptyList(), "")
        }
    }

    // ── construction ────────────────────────────────────────────────────────

    fun states(): List<StepState> = states.toList()

    fun current(): StepState? = states.firstOrNull { it.status == StepStatus.ACTIVE }
        ?: states.firstOrNull { it.status == StepStatus.PENDING }

    fun completedCount(): Int = states.count { it.status == StepStatus.DONE }

    fun isComplete(): Boolean = states.all {
        it.status == StepStatus.DONE || (it.status == StepStatus.SKIPPED && !it.requiresEvidence)
    }

    // ── gate ────────────────────────────────────────────────────────────────

    /**
     * Decide whether [toolName] may run now.
     *
     * `finish` is the one tool that is actually blocked: completion is a claim about
     * evidence, and claims are checked. Everything else is recorded (GUARDED) or
     * refused (STRICT).
     */
    fun gate(toolName: String, params: Map<String, Any> = emptyMap()): Gate {
        if (toolName == FINISH_TOOL) {
            val verdict = completion()
            return if (verdict.canComplete) {
                Gate.Allow
            } else {
                Gate.Deny(verdict.message)
            }
        }
        // An approval step needs the user's word before the action it guards.
        val approval = states.firstOrNull {
            it.action == PlanAction.ASK_USER &&
                it.status != StepStatus.DONE &&
                it.status != StepStatus.SKIPPED
        }
        if (approval != null && !approvals.contains(approval.number.toString())) {
            return Gate.Ask("The plan asks for your approval first: ${approval.detail}")
        }
        if (mode == Mode.STRICT && toolName !in OBSERVATION_TOOLS) {
            val target = actionsFor(toolName)
            val current = current()
            if (target.isNotEmpty() && current != null && current.action !in target) {
                return Gate.Deny(
                    "Off-plan action '$toolName': step ${current.number} is " +
                        "\"${current.detail}\" — do that first (or say the UI differs)."
                )
            }
        }
        return Gate.Allow
    }

    fun recordApproval(stepNumber: Int) {
        approvals += stepNumber.toString()
    }

    /**
     * The approval step currently waiting on the user, if any.
     *
     * Exposed so the caller that shows the prompt knows *which* step it unlocked —
     * recording the approval against the wrong number would silently gate the next
     * action too.
     */
    fun pendingApproval(): StepState? = states.firstOrNull {
        it.action == PlanAction.ASK_USER &&
            it.status != StepStatus.DONE &&
            it.status != StepStatus.SKIPPED
    }

    // ── observation ─────────────────────────────────────────────────────────

    /**
     * Record the outcome of a tool call and advance the contract.
     *
     * @return the step this call affected, or null when the call belongs to no step
     */
    fun observe(toolName: String, success: Boolean, data: String?, nowMs: Long = System.currentTimeMillis()): StepState? {
        if (toolName == FINISH_TOOL) return null
        val actions = actionsFor(toolName)
        if (actions.isEmpty()) {
            offPlanCalls += toolName
            return null
        }
        // Retry first: a FAILED step is still the step to satisfy, so a retry must
        // be able to complete it instead of being recorded as an off-plan call.
        val index = states.indexOfFirst { it.status == StepStatus.FAILED && it.action in actions }
            .takeIf { it >= 0 }
            ?: states.indexOfFirst { it.status == StepStatus.ACTIVE && it.action in actions }
                .takeIf { it >= 0 }
            ?: states.indexOfFirst { it.status == StepStatus.PENDING && it.action in actions }
        if (index < 0) {
            offPlanCalls += toolName
            return null
        }

        // A model that jumps ahead (never typing the search, but tapping the result)
        // is not wrong, just different: mark the steps it bypassed SKIPPED with a
        // note, so the record stays truthful instead of pretending they happened.
        for (i in 0 until index) {
            val skipped = states[i]
            if (skipped.status == StepStatus.PENDING || skipped.status == StepStatus.ACTIVE) {
                states[i] = skipped.copy(
                    status = StepStatus.SKIPPED,
                    note = "skipped by '$toolName'",
                )
            }
        }

        val step = states[index]
        val evidence = data?.trim()?.take(EVIDENCE_MAX_CHARS)
        states[index] = if (success) {
            step.copy(
                status = StepStatus.DONE,
                evidence = evidence ?: "ok",
                completedAtMs = nowMs,
            )
        } else {
            // A failed action stays where it is: the same step is retried, and the
            // contract does not pretend the plan advanced.
            step.copy(status = StepStatus.FAILED, note = evidence ?: "failed")
        }
        if (states[index].status == StepStatus.DONE) {
            // The next pending step becomes the active one.
            states.indexOfFirst { it.status == StepStatus.PENDING }.let { next ->
                if (next >= 0) states[next] = states[next].copy(status = StepStatus.ACTIVE)
            }
        }
        return states[index]
    }

    /** Mark a step blocked by the environment (login wall, missing permission, …). */
    fun block(stepNumber: Int, reason: String) {
        val index = states.indexOfFirst { it.number == stepNumber }
        if (index >= 0) states[index] = states[index].copy(status = StepStatus.BLOCKED, note = reason)
    }

    // ── completion ──────────────────────────────────────────────────────────

    /**
     * Can the agent honestly report success?
     *
     * A step that promises an outcome (opening a result, tapping Follow, verifying
     * the button) must be DONE with evidence. Skipped or failed ones are listed, so
     * the refusal message tells the model exactly what is missing.
     */
    fun completion(): Completion {
        if (states.isEmpty()) return Completion.OK
        val missing = states.filter { step ->
            when (step.status) {
                StepStatus.DONE -> false
                StepStatus.SKIPPED -> step.requiresEvidence
                StepStatus.PENDING, StepStatus.ACTIVE, StepStatus.BLOCKED, StepStatus.FAILED -> true
            }
        }
        if (missing.isEmpty()) return Completion.OK
        val list = missing.joinToString("; ") { step ->
            "step ${step.number} (${step.detail}) is ${step.status.name.lowercase()}"
        }
        return Completion(
            canComplete = false,
            missing = missing.map { it.detail },
            message = "Not finished yet — $list. " +
                "Do those steps and confirm what you see, or say plainly what blocked you.",
        )
    }

    // ── checkpoint ──────────────────────────────────────────────────────────

    /** Compact, one-line serialisation for crash/interruption recovery. */
    fun checkpoint(): String = buildString {
        append("v1|").append(encode(taskId)).append('|').append(mode.name).append('|')
        append(riskClass.name).append('|')
        append(approvals.joinToString(",")).append('|')
        states.forEachIndexed { index, step ->
            if (index > 0) append(';')
            append(step.number).append(':').append(step.status.name)
            if (step.requiresEvidence) append('!')
            step.evidence?.let { append(':').append(encode(it)) }
        }
    }

    /** Everything the user/model needs in one line (logs, diagnostics). */
    fun summary(): String = buildString {
        append("$taskId ${riskClass.name} ${completedCount()}/${states.size} done")
        current()?.let { append("; current=${it.number}:${it.detail}") }
        if (offPlanCalls.isNotEmpty()) append("; off-plan=").append(offPlanCalls.joinToString())
        if (!isComplete()) append("; ").append(completion().message)
    }

    private fun encode(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("|", "\\p")
        .replace(";", "\\s")
        .replace(":", "\\c")
        .replace("\n", " ")

    private fun decode(raw: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < raw.length) {
            val c = raw[index]
            if (c == '\\' && index + 1 < raw.length) {
                when (raw[index + 1]) {
                    'p' -> out.append('|')
                    's' -> out.append(';')
                    'c' -> out.append(':')
                    '\\' -> out.append('\\')
                    else -> out.append(raw[index + 1])
                }
                index += 2
            } else {
                out.append(c)
                index++
            }
        }
        return out.toString()
    }

    companion object {
        const val FINISH_TOOL = "finish"
        private const val EVIDENCE_MAX_CHARS = 160

        /**
         * Tools that only *look* at the screen. STRICT mode must never refuse them:
         * checking is how the agent notices that the UI differs from the plan.
         */
        val OBSERVATION_TOOLS = setOf("get_screen_info", "take_screenshot", "wait", "get_foreground_app")

        /** Tap-shaped steps a tap-shaped tool can satisfy (declared before use). */
        private val TAP_LIKE = setOf(
            PlanAction.TAP,
            PlanAction.FILTER,
            PlanAction.OPEN_RESULT,
            PlanAction.SEND,
        )

        /**
         * Tools that can satisfy a plan action.
         *
         * Grouped by capability, not by app: any tap-shaped tool can satisfy any
         * tap-shaped step, which is exactly what keeps this app-agnostic.
         */
        val TOOL_ACTIONS: Map<String, Set<PlanAction>> = mapOf(
            "open_app" to setOf(PlanAction.OPEN_APP),
            "switch_app" to setOf(PlanAction.OPEN_APP),
            "input_text" to setOf(PlanAction.INPUT_TEXT, PlanAction.SEARCH),
            "system_key" to setOf(PlanAction.SEND, PlanAction.SEARCH),
            "tap_node" to TAP_LIKE,
            "find_and_tap" to TAP_LIKE,
            "tap" to TAP_LIKE,
            "long_press" to TAP_LIKE,
            "scroll_to_find" to setOf(PlanAction.OPEN_RESULT, PlanAction.FILTER),
            "send_message" to setOf(PlanAction.SEND, PlanAction.INPUT_TEXT),
            "get_screen_info" to setOf(PlanAction.VERIFY),
            "take_screenshot" to setOf(PlanAction.VERIFY),
        )

        fun actionsFor(toolName: String): Set<PlanAction> = TOOL_ACTIONS[toolName].orEmpty()

        /**
         * Build the contract for a blueprint.
         *
         * @param riskClass derived from the request text by the caller; when omitted
         *        the capability decides (publishing/messaging → SENSITIVE).
         */
        fun from(
            taskId: String,
            blueprint: TaskBlueprint,
            mode: Mode = Mode.GUARDED,
            riskClass: RiskClass = riskFor(blueprint),
        ): PlanContract {
            val states = blueprint.steps
                .sortedBy { it.number }
                .map { step ->
                    StepState(
                        number = step.number,
                        action = step.action,
                        detail = step.detail,
                        status = StepStatus.PENDING,
                        requiresEvidence = REQUIRES_EVIDENCE.contains(step.action),
                    )
                }
                .toMutableList()
            states.firstOrNull()?.let { states[0] = it.copy(status = StepStatus.ACTIVE) }
            return PlanContract(
                taskId = taskId,
                blueprint = blueprint,
                mode = mode,
                states = states,
                offPlanCalls = mutableListOf(),
                approvals = mutableSetOf(),
                riskClass = riskClass,
            )
        }

        /** Actions whose promise is an *observed* outcome, not an input. */
        val REQUIRES_EVIDENCE = setOf(
            PlanAction.OPEN_APP,
            PlanAction.OPEN_RESULT,
            PlanAction.TAP,
            PlanAction.FILTER,
            PlanAction.SEND,
            PlanAction.VERIFY,
        )

        fun riskFor(blueprint: TaskBlueprint): RiskClass = when (blueprint.intent.capability) {
            PlanCapability.FOLLOW_ENTITY,
            PlanCapability.UNFOLLOW_ENTITY,
            PlanCapability.SEND_MESSAGE,
            PlanCapability.POST_UPDATE,
            -> RiskClass.SENSITIVE

            PlanCapability.OPEN_ONLY,
            PlanCapability.SEARCH_AND_OPEN,
            PlanCapability.PLAY_MEDIA,
            PlanCapability.DEVICE_TOGGLE,
            PlanCapability.WEB_RESEARCH,
            PlanCapability.NONE,
            -> RiskClass.NORMAL
        }

        /** Restore from [checkpoint]; null when the payload is unreadable. */
        fun fromCheckpoint(
            text: String,
            blueprint: TaskBlueprint,
            riskClass: RiskClass = riskFor(blueprint),
        ): PlanContract? {
            val parts = text.split('|')
            if (parts.size < 6) return null
            if (parts[0] != "v1") return null
            val mode = Mode.entries.firstOrNull { it.name == parts[2] } ?: Mode.GUARDED
            val risk = RiskClass.entries.firstOrNull { it.name == parts[3] } ?: riskClass
            val approvals = parts[4].split(',').mapNotNull { it.toIntOrNull() }.map { it.toString() }
            val states = parts[5].split(';').mapNotNull { chunk ->
                val pieces = chunk.split(':')
                val number = pieces.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val statusRaw = pieces.getOrNull(1).orEmpty()
                val requiresEvidence = statusRaw.endsWith("!")
                val statusName = statusRaw.removeSuffix("!")
                val status = StepStatus.entries.firstOrNull { it.name == statusName }
                    ?: return@mapNotNull null
                val source = blueprint.steps.firstOrNull { it.number == number }
                    ?: return@mapNotNull null
                StepState(
                    number = number,
                    action = source.action,
                    detail = source.detail,
                    status = status,
                    evidence = pieces.getOrNull(2)?.let { unescape(it) },
                    requiresEvidence = requiresEvidence || REQUIRES_EVIDENCE.contains(source.action),
                )
            }.toMutableList()
            if (states.isEmpty()) return null
            val contract = PlanContract(
                taskId = unescape(parts[1]),
                blueprint = blueprint,
                mode = mode,
                states = states,
                offPlanCalls = mutableListOf(),
                approvals = approvals.toMutableSet(),
                riskClass = risk,
            )
            return contract
        }

        private fun unescape(raw: String): String = raw
            .replace("\\p", "|")
            .replace("\\s", ";")
            .replace("\\c", ":")
            .replace("\\\\", "\\")
    }
}
