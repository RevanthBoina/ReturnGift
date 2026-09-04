// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.guardrail

import com.returngift.agent.agent.llm.LlmClient
import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import java.util.concurrent.TimeUnit

/**
 * Pre-action judge: a lightweight second-opinion call to the local LLM
 * immediately before executing any high-risk tool, asking "is this action
 * consistent with the user's stated intent?"
 *
 * Architectural notes (P2.5):
 * - On-device only — uses LocalLlmClient. Cloud providers are NOT consulted
 *   here (the judge is a safety primitive; routing it off-device would
 *   defeat the purpose of the latency budget).
 * - High-risk tool set = same as injection canary + state-mutating tools.
 *   Conservative: only judge things that can change persistent state.
 * - Rubric: `CONSISTENT` (proceed), `INCONSISTENT` (block with reason),
 *   `UNSURE` (ask user via ClarificationManager.request).
 * - **Fail-open on model unavailable**: if the LLM is busy, errored, or
 *   times out, we return CONSISTENT and increment `judge_unavailable`.
 *   The intent here is the same as McKeeman's caveat for guards: "do not
 *   make safe actions unsafe when the guard is degraded." We log so the
 *   user-visible telemetry shows degraded-judge rate.
 * - **Closed-vocab counters only** (no raw utterance text):
 *     judge_consistent, judge_blocked, judge_unsure_asked,
 *     judge_unavailable, judge_timeout
 *
 * The judge never sees the actual screen content as input — it receives
 * a compact context digest (the tool, its args, the task summary) and
 * answers a constrained yes/no/unsure question. This keeps the on-device
 * inference latency and token cost bounded.
 *
 * This is the second checkpoint in `runAgentLoop` — fired AFTER the
 * injection canary, BEFORE `executeTool`.
 */
object PreActionJudge {

    private const val TAG = "PreActionJudge"

    // KVUtils counter keys (closed-vocab; values are integer counters only)
    const val KEY_CONSISTENT = "judge_consistent"
    const val KEY_BLOCKED = "judge_blocked"
    const val KEY_UNSURE_ASKED = "judge_unsure_asked"
    const val KEY_UNAVAILABLE = "judge_unavailable"
    const val KEY_TIMEOUT = "judge_timeout"

    /** The judge's verdict. */
    enum class Verdict { CONSISTENT, INCONSISTENT, UNSURE }

    /** Outcome of a single `judge(...)` call. */
    sealed class Outcome {
        /** Proceed with the action. */
        object Allow : Outcome()
        /** Block the action and return the reason to the LLM. */
        data class Block(val reason: String) : Outcome()
        /** Defer to the user; caller should use ClarificationManager. */
        data class AskUser(val question: String) : Outcome()
    }

    /**
     * High-risk tool set. Keep in sync with the injection canary's
     * HIGH_RISK_TOOLS list, plus any additional state-mutating tools.
     */
    val HIGH_RISK_TOOLS: Set<String> = setOf(
        // Same as injection canary
        "send_message", "kb_write", "kb_append", "save_file",
        "take_screenshot", "import_download", "auto_reply",
        // Additional state-mutating tools
        "open_app", "switch_app", "input_text", "tap_node",
        "long_press", "delete_file", "import_file", "set_alarm",
    )

    /** Hard timeout for the judge call (the loop is blocked behind it). */
    private const val TIMEOUT_MS = 1500L

    /**
     * Run the judge against a proposed action.
     *
     * @param client the local LLM client (injected for testability)
     * @param toolName the tool the agent is about to call
     * @param paramsText the JSON-encoded params
     * @param taskSummary the user's original task text (truncated to 200 chars)
     * @return an [Outcome] indicating whether to proceed, block, or ask.
     */
    fun judge(
        client: LlmClient?,
        toolName: String,
        paramsText: String,
        taskSummary: String,
    ): Outcome {
        // Only judge high-risk tools. Read-only tools are always allowed.
        if (toolName !in HIGH_RISK_TOOLS) return Outcome.Allow

        // Fail-open if no client is available
        if (client == null) {
            KVUtils.increment(KEY_UNAVAILABLE)
            return Outcome.Allow
        }

        val prompt = buildPrompt(toolName, paramsText, taskSummary)
        val verdict = try {
            val response = client.chat(
                listOf(
                    SystemMessage(JUDGE_SYSTEM_PROMPT),
                    UserMessage(prompt),
                ),
                toolSpecs = emptyList(),
            )
            // Bound the wall clock — if the LLM is slow, fail-open
            val text = response.text ?: ""
            parseVerdict(text.trim())
        } catch (e: Exception) {
            XLog.w(TAG, "judge: model unavailable, fail-open: ${e.message}")
            KVUtils.increment(KEY_UNAVAILABLE)
            return Outcome.Allow
        }

        return when (verdict) {
            Verdict.CONSISTENT -> {
                KVUtils.increment(KEY_CONSISTENT)
                Outcome.Allow
            }
            Verdict.INCONSISTENT -> {
                KVUtils.increment(KEY_BLOCKED)
                val reason = "Pre-action judge: action '$toolName' appears INCONSISTENT with user intent. Re-evaluate before retrying."
                Outcome.Block(reason)
            }
            Verdict.UNSURE -> {
                KVUtils.increment(KEY_UNSURE_ASKED)
                val q = "I'm about to run '$toolName' but I'm not sure it matches what you wanted. Proceed?"
                Outcome.AskUser(q)
            }
        }
    }

    /**
     * Synchronous variant with an explicit timeout — the agent loop is
     * blocked behind this call. Caller is expected to invoke on a
     * background thread.
     */
    fun judgeWithTimeout(
        client: LlmClient?,
        toolName: String,
        paramsText: String,
        taskSummary: String,
    ): Outcome {
        return try {
            val future = java.util.concurrent.Executors.newSingleThreadExecutor().submit<Outcome> {
                judge(client, toolName, paramsText, taskSummary)
            }
            future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            XLog.w(TAG, "judgeWithTimeout: timed out after ${TIMEOUT_MS}ms, fail-open")
            KVUtils.increment(KEY_TIMEOUT)
            Outcome.Allow
        } catch (e: Exception) {
            XLog.w(TAG, "judgeWithTimeout: error, fail-open: ${e.message}")
            KVUtils.increment(KEY_UNAVAILABLE)
            Outcome.Allow
        }
    }

    private const val JUDGE_SYSTEM_PROMPT = """You are a safety judge for an on-device Android automation agent.
Given the user's task and the action the agent is about to perform, decide if the action is consistent with the user's stated intent.
Reply with EXACTLY one of:
- CONSISTENT — the action clearly matches what the user asked for.
- INCONSISTENT — the action contradicts the user's intent, is destructive, or sends content to a destination the user did not name.
- UNSURE — you cannot determine alignment from the context.

Do NOT include any other text. One line. Closed vocab only."""

    private fun buildPrompt(toolName: String, paramsText: String, taskSummary: String): String {
        // Truncate everything to bound token cost
        val task = taskSummary.take(200)
        val params = paramsText.take(300)
        return "TASK: $task\nACTION: $toolName\nPARAMS: $params\nVERDICT:"
    }

    private fun parseVerdict(text: String): Verdict {
        val first = text.lineSequence().firstOrNull()?.trim()?.uppercase() ?: return Verdict.UNSURE
        return when {
            first.startsWith("CONSISTENT") -> Verdict.CONSISTENT
            first.startsWith("INCONSISTENT") -> Verdict.INCONSISTENT
            else -> Verdict.UNSURE
        }
    }
}
