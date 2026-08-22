// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.checkpoint

import com.returngift.agent.agent.knowledge.KBManager
import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Task checkpoint store — M3A-style resumable memory.
 *
 * android_world's m3a.py makes the model write a one-line summary of each step into its
 * prompt history; the history itself is the resumable memory. Here the loop's per-step
 * action history (tool name + ok/fail + error) is persisted to the vault when a task is
 * CANCELLED mid-flight (the only terminal outcome where unfinished state is worth
 * keeping — clean completions write nothing), and re-injected as prompt context when the
 * user resumes with "resume"/"continue".
 *
 * Storage: the checkpoint is a normal vault note (notes/<slug>-draft.md) so the user can
 * see and edit it; a KV pointer (checkpoint_latest = "<path>|<taskText>") marks the
 * most recent interrupted task.
 */
object TaskCheckpointStore {

    private const val TAG = "TaskCheckpointStore"
    private const val KV_LATEST = "checkpoint_latest"
    private const val MAX_STEPS = 50

    data class Checkpoint(
        val path: String,
        val taskText: String,
        val timestamp: Long,
        val steps: List<String>,
    )

    /** Pure slug for a task text — lowercase, alnum+hyphen, max 48 chars. */
    fun slugify(taskText: String): String {
        val slug = taskText.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(48)
            .trim('-')
        return slug.ifEmpty { "task" }
    }

    /**
     * Pure resume-intent match: the user typed exactly (or starts with) a resume word.
     * Kept deliberately narrow — a sentence that merely mentions "resume" must not
     * hijack an unrelated task.
     */
    fun isResumeIntent(text: String): Boolean =
        Regex("^(resume|continue|weiter|fortsetzen)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(text.trim())

    /** Markdown body of a checkpoint note. Pure. */
    fun renderMarkdown(taskText: String, timestamp: Long, steps: List<String>): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timestamp))
        val sb = StringBuilder()
        sb.appendLine("# Interrupted task checkpoint")
        sb.appendLine()
        sb.appendLine("- **Task**: $taskText")
        sb.appendLine("- **Interrupted**: $date")
        sb.appendLine("- **Resume**: type \"resume\" in chat")
        sb.appendLine()
        sb.appendLine("## Step history")
        sb.appendLine()
        if (steps.isEmpty()) {
            sb.appendLine("(no steps executed)")
        } else {
            steps.forEach { sb.appendLine("- $it") }
        }
        return sb.toString()
    }

    /** Prompt context prepended when the task resumes — mirrors M3A's {history} injection. */
    fun renderPromptContext(checkpoint: Checkpoint): String {
        val sb = StringBuilder()
        sb.appendLine("RESUME CONTEXT — this task was interrupted earlier and is being resumed.")
        sb.appendLine("Do NOT redo steps that already succeeded unless their effect is gone.")
        sb.appendLine("Previous step history (oldest first):")
        checkpoint.steps.takeLast(MAX_STEPS).forEach { sb.appendLine("- $it") }
        return sb.toString()
    }

    /** Persist a checkpoint for an interrupted task. Returns the vault path, or null on failure. */
    fun write(taskText: String, steps: List<String>): String? {
        val path = "notes/${slugify(taskText)}-draft.md"
        val timestamp = System.currentTimeMillis()
        val result = KBManager.write(
            path,
            mapOf(
                "type" to "checkpoint",
                "date" to SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp)),
                "task" to taskText.take(120),
            ),
            renderMarkdown(taskText, timestamp, steps),
        )
        return result.fold(
            onSuccess = {
                KVUtils.putString(KV_LATEST, "$path|$taskText")
                XLog.i(TAG, "checkpoint written: $path (${steps.size} steps)")
                path
            },
            onFailure = {
                XLog.e(TAG, "checkpoint write failed", it)
                null
            },
        )
    }

    /** The parked checkpoint, or null if none / its file was deleted. */
    fun peek(): Checkpoint? {
        val raw = KVUtils.getString(KV_LATEST, "")
        if (raw.isEmpty()) return null
        val path = raw.substringBefore('|')
        val taskText = raw.substringAfter('|', "")
        val file = KBManager.absoluteFile(path)
        if (!file.exists()) {
            KVUtils.remove(KV_LATEST)
            return null
        }
        val steps = parseSteps(KBManager.read(path).getOrNull() ?: "")
        return Checkpoint(path, taskText, file.lastModified(), steps)
    }

    /** True when [text] is exactly a resume keyword (regardless of checkpoint presence). */
    fun isResumeKeyword(text: String): Boolean = isResumeIntent(text)

    /** If [text] is a resume intent, consume and return the parked checkpoint, else null. */
    fun consumeIfResumeIntent(text: String): Checkpoint? {
        if (!isResumeIntent(text)) return null
        val checkpoint = peek() ?: return null
        KVUtils.remove(KV_LATEST)
        return checkpoint
    }

    /**
     * Pure matcher behind [clearIfTaskMatches]: true when the stored "slug|taskText"
     * checkpoint belongs to the given (possibly RESUME CONTEXT-suffixed) task text.
     */
    fun checkpointMatches(storedRaw: String, taskText: String): Boolean {
        if (storedRaw.isEmpty()) return false
        val storedSlug = storedRaw.substringBefore('|', "")
        val storedTaskText = storedRaw.substringAfter('|', "")
        return storedSlug == slugify(taskText) ||
            (storedTaskText.isNotEmpty() && taskText.contains(storedTaskText))
    }

    /**
     * Clear the parked checkpoint when its task completed cleanly (avoid stale resumes).
     * Matches on the SLUG, not the raw text — a resumed task carries the RESUME CONTEXT
     * suffix and would otherwise never match its own checkpoint, leaving a stale
     * RESUME CHAT hint behind.
     */
    fun clearIfTaskMatches(taskText: String) {
        val raw = KVUtils.getString(KV_LATEST, "")
        if (checkpointMatches(raw, taskText)) {
            KVUtils.remove(KV_LATEST)
            XLog.i(TAG, "checkpoint cleared for completed task: ${raw.substringBefore('|', "")}")
        }
    }

    private fun parseSteps(markdown: String): List<String> {
        val section = markdown.substringAfter("## Step history", "")
        return section.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") && !it.startsWith("- **") }
            .map { it.removePrefix("- ") }
            .toList()
    }
}
