// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.artifact

/**
 * Per-task artifact contract — the enforcement half of "Deliverable Honesty"
 * (AgentConfig Rule 11 is the advisory half).
 *
 * Inferred from the raw task text at loop start ([fromTask]), it records which
 * deliverable the task must produce, tracks kb_write/kb_append artifacts actually
 * persisted during the loop, and blocks a dishonest or premature finish via
 * [maybeBlockFinish] — same house pattern as DirectDeviceDataGuard/InAppSearchGuard:
 * a blocked finish returns a correction string that is fed back to the model as a
 * ToolResult error, so the loop continues instead of completing on a false claim.
 *
 * Two contract kinds:
 * - [DeliverableType.MARKDOWN_NOTE]: the user asked to save something (note/plan/list/
 *   todo). finish() is rejected until at least one kb artifact was actually written.
 * - [DeliverableType.EXTERNAL_ARTIFACT]: the user asked for a binary/external
 *   deliverable (PDF, PPT, website, …) that this build cannot produce. finish() is
 *   rejected if the summary CLAIMS the artifact was created; an honest finish (saved a
 *   Markdown alternative, or plainly stated it cannot be produced) is accepted.
 */
internal class ArtifactContract private constructor(
    private val expected: DeliverableType?,
) {

    enum class DeliverableType { MARKDOWN_NOTE, EXTERNAL_ARTIFACT }

    /** Vault-relative paths of artifacts actually persisted during this task. */
    private val savedArtifacts = mutableListOf<String>()

    /** Record a knowledge-base tool outcome. Called from the agent loop after execution. */
    fun recordKbToolResult(toolName: String, resultData: String?) {
        val path = extractKbPath(toolName, resultData) ?: extractWebFetchPath(toolName, resultData)
        if (path != null && path !in savedArtifacts) savedArtifacts += path
    }

    fun buildPromptSection(): String {
        val type = expected ?: return ""
        return when (type) {
            DeliverableType.MARKDOWN_NOTE -> "\n\n" + """
                ## Task Guard: Artifact Contract
                This task requires SAVING a note/list/plan into the vault.
                finish(summary) will be REJECTED until a kb_write / kb_append / save_file call has actually succeeded in this task.
                After saving, name the exact vault path in finish(summary).
            """.trimIndent()
            DeliverableType.EXTERNAL_ARTIFACT -> "\n\n" + """
                ## Task Guard: Artifact Contract
                This task asks for a binary/external deliverable (PDF, presentation, website, or document).
                Complex renderings (real PDFs, PPTX decks, websites) still cannot be produced on-device — but binary
                files CAN now be persisted via save_file (base64 content), e.g. screenshots/ images under the vault.
                finish(summary) will be REJECTED if it claims a PDF/PPT/website/document was created while nothing was saved.
                Honest completions: save the content as a Markdown note (kb_write) or binary file (save_file) and name
                its exact vault path, or state plainly that the binary artifact cannot be produced on-device.
            """.trimIndent()
        }
    }

    /**
     * Returns a correction message when finish(summary) violates the contract,
     * or null when the finish is honest/satisfied.
     */
    fun maybeBlockFinish(summary: String): String? {
        val type = expected ?: return null
        return when (type) {
            DeliverableType.MARKDOWN_NOTE ->
                if (savedArtifacts.isEmpty()) {
                    "[System Guard] FINISH REJECTED. This task requires saving a note, but no " +
                        "kb_write/kb_append call has succeeded yet. Save the content with kb_write " +
                        "(e.g. path notes/<name>.md), then call finish naming the exact vault path."
                } else null
            DeliverableType.EXTERNAL_ARTIFACT ->
                // Block only pure hallucination: a claim with NO artifact actually saved.
                // Once a Markdown alternative exists in the vault, mentioning the binary
                // format (e.g. "open the Vault to export it as a PDF") is honest guidance.
                if (savedArtifacts.isEmpty() && claimsBinaryArtifact(summary)) {
                    "[System Guard] FINISH REJECTED. Your summary claims a PDF/presentation/website/" +
                        "document was created or saved — you cannot produce binary files on-device. " +
                        "Either save the content as a Markdown note via kb_write and finish naming the " +
                        "exact vault path, or finish honestly stating the deliverable cannot be produced here."
                } else null
        }
    }

    /**
     * Blocks a text-only completion (no finish tool) that violates the contract —
     * same enforcement as [maybeBlockFinish] for models that answer directly.
     */
    fun shouldBlockTextOnlyCompletion(responseText: String): Boolean =
        expected != null && maybeBlockFinish(responseText) != null

    fun buildCompletionCorrection(): String =
        maybeBlockFinish("") ?: "[System Guard] Continue the task instead of stopping."

    companion object {
        private val CREATION_VERB = Regex(
            """\b(make|create|prepare|build|generate|design|produce|draft|put together|set up)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val BINARY_NOUN = Regex(
            """\b(pdf|pptx?|powerpoint|presentation|slide ?deck|slides|deck|web ?site|web ?page|landing page|docx?|word document|excel|xlsx|spreadsheet|brochure)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val SAVE_NOTE_INTENT = Regex(
            """\b(save|write down|jot down|note down|take a note|make a note|keep a note|add (a )?to ?do|remember this|log (this|it))\b""" +
                """|\b(save|store|keep)\b[^.!?]{0,60}\b(note|notes|plan|list|vault|summary|minutes)\b""",
            RegexOption.IGNORE_CASE,
        )
        // Claim patterns for finish summaries, e.g. "I've prepared the PDF",
        // "your presentation is ready", "the website has been created", "PDF saved".
        private val CLAIM_PATTERN = Regex(
            """\b(i('ve| have)?\s+(created|made|prepared|generated|built|saved|downloaded|finished)|""" +
                """(has|have) been (created|made|prepared|generated|built|saved|downloaded)|""" +
                """is (ready|done|complete|saved|generated|prepared))\b[^.!?]{0,80}""" +
                """\b(pdf|pptx?|powerpoint|presentation|slide ?deck|slides|deck|web ?site|web ?page|landing page|docx?|word document|excel|xlsx|spreadsheet|brochure)\b""" +
                """|\b(pdf|pptx?|powerpoint|presentation|slide ?deck|slides|deck|web ?site|web ?page|landing page|docx?|word document|excel|xlsx|spreadsheet|brochure)\b[^.!?]{0,80}""" +
                """\b(has been (created|made|prepared|generated|built|saved|downloaded)|is (ready|done|complete|saved|generated|prepared))\b""",
            RegexOption.IGNORE_CASE,
        )

        /** Infer the contract for a task; expected == null means no enforcement. */
        fun fromTask(taskText: String): ArtifactContract {
            val expected = when {
                CREATION_VERB.containsMatchIn(taskText) && BINARY_NOUN.containsMatchIn(taskText) ->
                    DeliverableType.EXTERNAL_ARTIFACT
                SAVE_NOTE_INTENT.containsMatchIn(taskText) ->
                    DeliverableType.MARKDOWN_NOTE
                else -> null
            }
            return ArtifactContract(expected)
        }

        /**
         * Extract the vault-relative path from a successful kb_write/kb_append result
         * ("Written: <path>" / "Appended to: <path>" — the formats produced by KBManager).
         * web_fetch(save_to_vault=true) appends "Saved to vault: <path>" to its result
         * and is treated the same — it is a real persisted artifact.
         */
        fun extractKbPath(toolName: String, resultData: String?): String? {
            val data = resultData ?: return null
            val prefix = when (toolName) {
                "kb_write" -> "Written: "
                "kb_append" -> "Appended to: "
                "save_file" -> "Written: "
                "take_screenshot" -> return extractScreenshotPath(data)
                else -> return null
            }
            if (!data.startsWith(prefix)) return null
            return data.removePrefix(prefix).trim().takeIf { it.isNotEmpty() }
        }

        /**
         * take_screenshot(save_to_vault=true) succeeds with "<cache path>\nSaved to vault: <path>"
         * — the trailer is the persisted artifact the user can actually see.
         */
        private fun extractScreenshotPath(data: String): String? {
            val marker = "\nSaved to vault: "
            val idx = data.indexOf(marker)
            if (idx < 0) return null
            return data.substring(idx + marker.length).lineSequence().firstOrNull()
                ?.trim()?.takeIf { it.isNotEmpty() }
        }

        /** Extract the vault path from a web_fetch result's "Saved to vault: <path>" trailer. */
        fun extractWebFetchPath(toolName: String, resultData: String?): String? {
            if (toolName != "web_fetch") return null
            val data = resultData ?: return null
            val marker = "\nSaved to vault: "
            val idx = data.indexOf(marker)
            if (idx < 0) return null
            return data.substring(idx + marker.length).trim().takeIf { it.isNotEmpty() }
        }

        internal fun claimsBinaryArtifact(summary: String): Boolean =
            CLAIM_PATTERN.containsMatchIn(summary)
    }
}
