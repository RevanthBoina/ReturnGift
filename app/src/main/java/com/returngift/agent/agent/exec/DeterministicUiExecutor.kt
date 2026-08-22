// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.returngift.agent.agent.grounding.SemanticTargetResolver
import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.utils.XLog

/**
 * Bounded deterministic UI executor — the controller half of the two-layer
 * architecture.
 *
 * State machine:
 *   START → CHECK_TARGET_APP → OPEN_TARGET_APP → FIND_TARGET → PERFORM_ACTION
 *     → VERIFY_ACTION → (next step | DONE)
 *   failures → RECOVER → RETRY_CURRENT_STATE (≤2 per state) → terminal FAIL
 *
 * The controller owns state, selectors, actions, retries, budgets and
 * verification. The AI layer is invoked ONLY through [escalator] when
 * deterministic target resolution fails, and must return a usable selector —
 * it never drives the loop.
 *
 * Terminal outcomes: SUCCESS / FAILED_TARGET_NOT_FOUND / FAILED_ACTION /
 * FAILED_VERIFICATION / TIMEOUT / BUDGET_EXCEEDED, always with a concise
 * reason and the execution-state snapshot. A watchdog/budget trigger STOPS
 * and reports — it never auto-restarts the workflow.
 */
class DeterministicUiExecutor(
    private val budget: ExecutionBudget = ExecutionBudget(),
    private val readGate: ScreenReadGate = ScreenReadGate(),
    private val escalator: Escalator? = null,
    /** Cooperative abort (user pressed Stop). Checked at every state boundary. */
    private val shouldAbort: () -> Boolean = { false },
) {

    /** Thrown on cooperative abort; the caller maps it to its cancel path. */
    class AbortedException internal constructor() : Exception("task cancelled by user")

    /** AI reasoning seam — invoked only when deterministic resolution fails. */
    fun interface Escalator {
        /**
         * @param screenDump current screen tree (already read under the gate)
         * @param hint       what the controller is looking for
         * @return a selector chain the controller can execute, or null
         */
        fun proposeSelector(screenDump: String, hint: String): SelectorChain?
    }

    enum class State {
        START, CHECK_TARGET_APP, OPEN_TARGET_APP,
        FIND_TARGET, PERFORM_ACTION, VERIFY_ACTION,
        RECOVER, RETRY_CURRENT_STATE, DONE, FAILED,
    }

    enum class Action { TAP, INPUT_TEXT, PRESS_BACK, WAIT_SETTLED }

    /** Minimal verification — check only what success requires. */
    data class VerifySpec(
        /** Text that must be present (or absent) on screen after the action. */
        val textAppears: String? = null,
        val textDisappears: String? = null,
        /** Foreground package that must be active after the action. */
        val foregroundPackage: String? = null,
    )

    data class Step(
        val name: String,
        val target: SelectorChain?,
        val action: Action,
        /** Text for INPUT_TEXT. */
        val inputText: String? = null,
        /** Clear the field deterministically before typing (no clipboard). */
        val clearBeforeInput: Boolean = true,
        val verify: VerifySpec? = null,
    )

    data class Spec(
        val taskLabel: String,
        /** Package to open first; null = stay in the current foreground app. */
        val targetPackage: String? = null,
        val steps: List<Step>,
    )

    private val stateTrace = mutableListOf<String>()
    private var state = State.START

    fun execute(spec: Spec): ExecReport {
        val service = ClawAccessibilityService.getInstance()
            ?: return report(ExecOutcome.FAILED_ACTION, "accessibility service not running")
        enter(State.START)
        try {
            if (spec.targetPackage != null) {
                enter(State.CHECK_TARGET_APP)
                while (!service.isForeground(spec.targetPackage)) {
                    if (shouldAbort()) throw AbortedException()
                    enter(State.OPEN_TARGET_APP)
                    budget.recordAction()?.let { return budgetReport(it) }
                    readGate.recordAction()
                    val launch = service.openAppForeground(spec.targetPackage, 8_000)
                    if (launch.success) break
                    // Bounded retry of THIS state only — never a workflow restart.
                    failRecoverable(
                        State.OPEN_TARGET_APP, ExecOutcome.FAILED_ACTION,
                        "could not open ${spec.targetPackage}: ${launch.error}"
                    ) ?: return report(
                        ExecOutcome.FAILED_ACTION,
                        "open ${spec.targetPackage} failed after retries: ${launch.error}"
                    )
                    budget.check()?.let { return budgetReport(it) }
                }
            }

            for (step in spec.steps) {
                if (shouldAbort()) throw AbortedException()
                budget.check()?.let { return budgetReport(it) }
                val stepReport = executeStep(service, step) ?: continue
                return stepReport
            }

            enter(State.DONE)
            return report(ExecOutcome.SUCCESS, "${spec.taskLabel} completed")
        } catch (a: AbortedException) {
            throw a
        } catch (e: Exception) {
            XLog.e(TAG, "executor crashed in state $state", e)
            return report(ExecOutcome.FAILED_ACTION, "unexpected error in $state: ${e.message}")
        }
    }

    /** @return null to continue with the next step, or a terminal report. */
    private fun executeStep(service: ClawAccessibilityService, step: Step): ExecReport? {
        while (true) {
            if (shouldAbort()) throw AbortedException()
            budget.check()?.let { return budgetReport(it) }

            // FIND_TARGET — resolution against the live tree; node IDs never reused.
            val resolved = if (step.target != null) {
                enter(State.FIND_TARGET)
                resolveTarget(service, step)
            } else null

            if (step.target != null && resolved == null) {
                val recovered = failRecoverable(
                    State.FIND_TARGET, ExecOutcome.FAILED_TARGET_NOT_FOUND,
                    "target not found for step '${step.name}'"
                )
                if (recovered == null) {
                    return report(
                        ExecOutcome.FAILED_TARGET_NOT_FOUND,
                        "target not found for step '${step.name}' after retries"
                    )
                }
                continue
            }

            // PERFORM_ACTION — act immediately on the identified target.
            enter(State.PERFORM_ACTION)
            budget.recordAction()?.let { return budgetReport(it) }
            readGate.recordAction()
            val ok = performAction(service, step, resolved)
            if (!ok) {
                val recovered = failRecoverable(
                    State.PERFORM_ACTION, ExecOutcome.FAILED_ACTION,
                    "action ${step.action} failed for step '${step.name}'"
                )
                if (recovered == null) {
                    return report(
                        ExecOutcome.FAILED_ACTION,
                        "action ${step.action} failed for step '${step.name}' after retries"
                    )
                }
                continue
            }

            // VERIFY_ACTION — minimum state necessary, one read under the gate.
            if (step.verify != null) {
                enter(State.VERIFY_ACTION)
                val verified = verify(service, step.verify)
                if (!verified) {
                    val recovered = failRecoverable(
                        State.VERIFY_ACTION, ExecOutcome.FAILED_VERIFICATION,
                        "verification failed for step '${step.name}'"
                    )
                    if (recovered == null) {
                        return report(
                            ExecOutcome.FAILED_VERIFICATION,
                            "verification failed for step '${step.name}' after retries"
                        )
                    }
                    continue
                }
            }
            return null // step done → next step
        }
    }

    /**
     * Deterministic resolution over the selector chain (text → content-desc →
     * resource-id → a11y-props/class → coordinates). On failure: ONE gated
     * re-read + bounded AI escalation (≤2 per run). Never reuses node IDs.
     */
    private fun resolveTarget(
        service: ClawAccessibilityService,
        step: Step,
    ): SemanticTargetResolver.ResolvedTarget? {
        val chain = step.target ?: return null
        for (candidate in chain.orderedCandidates()) {
            val td = SemanticTargetResolver.TargetDescription(
                text = candidate.takeIf { it.kind == SelectorChain.Candidate.Kind.TEXT }?.value,
                contentDesc = candidate.takeIf { it.kind == SelectorChain.Candidate.Kind.CONTENT_DESC }?.value,
                resourceId = candidate.takeIf { it.kind == SelectorChain.Candidate.Kind.RESOURCE_ID }?.value,
                viewClass = candidate.takeIf { it.kind == SelectorChain.Candidate.Kind.A11Y_PROPS }?.value,
                x = chain.x.takeIf { candidate.kind == SelectorChain.Candidate.Kind.COORDINATES },
                y = chain.y.takeIf { candidate.kind == SelectorChain.Candidate.Kind.COORDINATES },
                exactText = false,
                exactDesc = false,
            )
            SemanticTargetResolver.resolve(td)?.let { return it }
        }

        // Deterministic resolution failed → bounded AI escalation with one read.
        val esc = escalator ?: return null
        budget.recordEscalation()?.let {
            XLog.w(TAG, "escalation budget exhausted for '${step.name}'")
            return null
        }
        val dump = gatedRead(service, ScreenReadGate.Purpose.RESOLUTION_ESCALATION) ?: return null
        val proposed = esc.proposeSelector(dump, "step '${step.name}': ${step.action}")
            ?: return null
        XLog.i(TAG, "escalation proposed selector for '${step.name}': $proposed")
        // Execute the AI's selector deterministically — the AI never acts itself.
        for (candidate in proposed.orderedCandidates()) {
            val td = SemanticTargetResolver.TargetDescription(
                text = candidate.takeIf { it.kind == SelectorChain.Candidate.Kind.TEXT }?.value,
                contentDesc = candidate.takeIf { it.kind == SelectorChain.Candidate.Kind.CONTENT_DESC }?.value,
                resourceId = candidate.takeIf { it.kind == SelectorChain.Candidate.Kind.RESOURCE_ID }?.value,
                viewClass = candidate.takeIf { it.kind == SelectorChain.Candidate.Kind.A11Y_PROPS }?.value,
                x = proposed.x.takeIf { candidate.kind == SelectorChain.Candidate.Kind.COORDINATES },
                y = proposed.y.takeIf { candidate.kind == SelectorChain.Candidate.Kind.COORDINATES },
                exactText = false,
                exactDesc = false,
            )
            SemanticTargetResolver.resolve(td)?.let { return it }
        }
        return null
    }

    private fun performAction(
        service: ClawAccessibilityService,
        step: Step,
        resolved: SemanticTargetResolver.ResolvedTarget?,
    ): Boolean {
        return when (step.action) {
            Action.TAP -> resolved?.let {
                service.performTap(it.centerX, it.centerY)
            } ?: false
            Action.INPUT_TEXT -> {
                val node = resolved?.node ?: return false
                val text = step.inputText ?: return false
                // Deterministic input: tap the composer → wait for editable focus →
                // optional explicit clear → set text → verify the field content.
                // No clipboard state involved.
                service.performTap(resolved.centerX, resolved.centerY)
                if (!service.waitForEditableFocus(3_000)) return false
                service.requestKeyboardForFocused()
                if (step.clearBeforeInput) {
                    val focused = currentFocusedEditable(service) ?: node
                    service.setNodeText(focused, "")
                }
                val target = currentFocusedEditable(service) ?: node
                if (!service.setNodeText(target, text)) return false
                val entered = service.getFocusedEditableText()
                entered != null && entered.contains(text.take(24))
            }
            Action.PRESS_BACK -> service.pressBack()
            Action.WAIT_SETTLED -> {
                try { Thread.sleep(1_000) } catch (_: InterruptedException) { return false }
                true
            }
        }
    }

    private fun currentFocusedEditable(service: ClawAccessibilityService) = try {
        val root = service.rootInActiveWindow
        root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable }
    } catch (_: Exception) {
        null
    }

    /** Verify only the minimum state necessary; at most one gated read. */
    private fun verify(service: ClawAccessibilityService, spec: VerifySpec): Boolean {
        spec.foregroundPackage?.let { pkg ->
            if (!service.waitForForeground(pkg, 4_000)) return false
        }
        if (spec.textAppears == null && spec.textDisappears == null) return true
        val dump = gatedRead(service, ScreenReadGate.Purpose.POST_ACTION_VERIFY) ?: return false
        spec.textAppears?.let { if (!dump.contains(it, ignoreCase = true)) return false }
        spec.textDisappears?.let { if (dump.contains(it, ignoreCase = true)) return false }
        return true
    }

    /**
     * The ONLY way the executor reads the screen: declared purpose, gate
     * decision, budget accounting. A denied read returns null — the caller
     * must proceed with the information it already has.
     */
    private fun gatedRead(
        service: ClawAccessibilityService,
        purpose: ScreenReadGate.Purpose,
    ): String? {
        val decision = readGate.requestRead(purpose)
        if (decision is ScreenReadGate.Decision.Deny) {
            XLog.w(TAG, "read denied ($purpose): ${decision.guidance}")
            return null
        }
        budget.recordScreenRead()?.let {
            XLog.w(TAG, "read blocked by budget: ${it.detail}")
            return null
        }
        val tree = try { service.getScreenTree() } catch (_: Exception) { null } ?: return null
        readGate.recordRead(tree.hashCode())
        return tree
    }

    /**
     * Bounded recovery: RECOVER → RETRY_CURRENT_STATE, at most
     * [ExecutionBudget] retries per state. Returns non-null while a retry is
     * still allowed; null = budget spent → caller terminates.
     */
    private fun failRecoverable(
        failedState: State,
        outcome: ExecOutcome,
        reason: String,
    ): Unit? {
        XLog.w(TAG, "$failedState failed (→ $outcome if unrecoverable): $reason")
        enter(State.RECOVER)
        val breach = budget.recordRetry(failedState.name)
        if (breach != null) {
            XLog.w(TAG, "retry budget for $failedState exhausted: ${breach.detail}")
            return null
        }
        enter(State.RETRY_CURRENT_STATE)
        return Unit
    }

    private fun budgetReport(breach: ExecutionBudget.Breach): ExecReport {
        val outcome = when (breach.violation) {
            ExecutionBudget.Violation.TIMEOUT -> ExecOutcome.TIMEOUT
            else -> ExecOutcome.BUDGET_EXCEEDED
        }
        return report(outcome, breach.detail)
    }

    private fun enter(next: State) {
        state = next
        stateTrace.add("${next.name}@${budget.elapsedMs()}ms")
        XLog.d(TAG, "state → $next")
    }

    private fun report(outcome: ExecOutcome, reason: String): ExecReport {
        val r = ExecReport(
            outcome = outcome,
            reason = reason,
            screenReads = readGate.totalReads,
            actions = budget.actions,
            escalations = budget.escalations,
            elapsedMs = budget.elapsedMs(),
            stateTrace = stateTrace.toList(),
        )
        XLog.i(TAG, "terminal: ${r.toSummary()}")
        return r
    }

    companion object {
        private const val TAG = "DeterministicUiExecutor"
        private val MAP_TYPE = object : TypeToken<Map<String, Any?>>() {}.type

        /** Parse an AI escalation reply (JSON object in any surrounding prose). */
        fun parseEscalationResponse(gson: Gson, raw: String): SelectorChain? {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return try {
                val map: Map<String, Any?> = gson.fromJson(raw.substring(start, end + 1), MAP_TYPE)
                SelectorChain.fromEscalationJson(map)
            } catch (e: Exception) {
                null
            }
        }
    }
}
