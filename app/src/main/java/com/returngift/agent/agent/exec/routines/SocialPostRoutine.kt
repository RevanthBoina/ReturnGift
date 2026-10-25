// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec.routines

import com.returngift.agent.agent.exec.DeterministicUiExecutor.Action
import com.returngift.agent.agent.exec.DeterministicUiExecutor.Spec
import com.returngift.agent.agent.exec.DeterministicUiExecutor.Step
import com.returngift.agent.agent.exec.DeterministicUiExecutor.VerifySpec
import com.returngift.agent.agent.exec.SelectorChain

/**
 * "Post <text> on <social app>" as ONE data-driven routine.
 *
 * There is deliberately no `LinkedInPostRoutine` (or any other per-app routine):
 * every social app needs the same four states — open the composer, type, publish,
 * verify — and only the *labels* differ. Those labels live in [Profile] rows, so:
 *
 *  - adding an app is a row, not a file;
 *  - a connector or a learned row can override a profile at runtime;
 *  - one test suite covers every app.
 *
 * Selector order follows the repo rule: text → content-desc → resource-id, with
 * coordinates never used here.
 */
object SocialPostRoutine {

    /**
     * @param packageName   Android package of the app
     * @param label         human label ("LinkedIn")
     * @param aliases       how the user spells it
     * @param composerHints labels that open the post composer
     * @param publishLabels labels that publish the post
     * @param successText   text (or null) that confirms publication
     * @param fieldHints    hints/descriptions for the text field
     */
    data class Profile(
        val packageName: String,
        val label: String,
        val aliases: List<String>,
        val composerHints: List<String>,
        val publishLabels: List<String>,
        val successText: String? = null,
        val fieldHints: List<String> = emptyList(),
        val composerResourceId: String? = null,
    ) {
        /** e.g. "linkedin_post", "x_post" — stable id for telemetry. */
        val routineId: String
            get() = packageName.substringAfterLast('.')
                .replace("android", "")
                .trim('.', '_')
                .ifBlank { label.lowercase() }
                .let { "${it}_post" }
    }

    /**
     * Built-in profiles. Rows only — no code paths keyed on a single app.
     * Kept intentionally small and conservative: a wrong label costs one wasted
     * step, a wrong resource-id costs a failed task, so resource-ids are only set
     * where they have been stable for several releases.
     */
    val profiles: List<Profile> = listOf(
        Profile(
            packageName = "com.linkedin.android",
            label = "LinkedIn",
            aliases = listOf("linkedin", "linked in"),
            composerHints = listOf("Start a post", "What do you want to talk about?"),
            publishLabels = listOf("Post"),
            successText = "Post successful",
            fieldHints = listOf("Post text", "Start writing", "What do you want to talk about?"),
            composerResourceId = "com.linkedin.android:id/share_box_start_post",
        ),
        Profile(
            packageName = "com.twitter.android",
            label = "X",
            aliases = listOf("x", "twitter", "on x"),
            composerHints = listOf("Post", "What's happening?", "What is happening?"),
            publishLabels = listOf("Post", "Tweet"),
            successText = "Your post was sent",
            fieldHints = listOf("Post text", "What's happening?"),
        ),
        Profile(
            packageName = "com.facebook.katana",
            label = "Facebook",
            aliases = listOf("facebook", "fb"),
            composerHints = listOf("What's on your mind?", "Create post"),
            publishLabels = listOf("Post"),
            successText = null,
            fieldHints = listOf("What's on your mind?"),
        ),
        Profile(
            packageName = "com.instagram.android",
            label = "Instagram",
            aliases = listOf("instagram", "insta", "ig"),
            composerHints = listOf("New post", "Create"),
            publishLabels = listOf("Share", "Post"),
            successText = "Post shared",
            fieldHints = listOf("Write a caption"),
        ),
    )

    /** All trigger verbs that mean "publish" in this context. */
    private val POST_VERBS = listOf("post", "share", "publish", "tweet")

    fun profileFor(packageName: String?): Profile? {
        val pkg = packageName?.trim()?.lowercase().orEmpty()
        if (pkg.isEmpty()) return null
        return profiles.firstOrNull { it.packageName.equals(pkg, ignoreCase = true) }
    }

    /** The profile a task refers to, or null when it is not a social-post request. */
    fun matchProfile(task: String): Profile? {
        val lower = task.lowercase()
        if (lower.isBlank()) return null
        val mentionsVerb = POST_VERBS.any { containsToken(lower, it) }
        if (!mentionsVerb) return null
        return profiles.firstOrNull { profile ->
            profile.aliases.any { containsToken(lower, it) } ||
                containsToken(lower, profile.label.lowercase())
        }
    }

    fun matches(task: String): Boolean = matchProfile(task) != null

    /**
     * Extract the body to publish.
     *
     * Handles `post "text" on LinkedIn`, `share this on X: text`,
     * `post on LinkedIn saying text` and the plain `post <text> to <app>` form.
     */
    fun extractPostText(task: String, profile: Profile? = matchProfile(task)): String? {
        val text = task.trim()
        if (text.isEmpty()) return null
        val quoted = Regex("\"([^\"]{3,2000})\"|'([^']{3,2000})'").find(text)
        if (quoted != null) {
            val body = (quoted.groupValues[1].ifBlank { quoted.groupValues[2] }).trim()
            if (body.length >= 3) return body
        }
        val names = (profile?.aliases.orEmpty() + listOfNotNull(profile?.label?.lowercase()))
            .filter { it.isNotBlank() }
            .sortedByDescending { it.length }
        val verb = POST_VERBS.joinToString("|")
        val nameAlt = if (names.isEmpty()) "" else names.joinToString("|") { Regex.escape(it) }
        val patterns = buildList {
            if (nameAlt.isNotEmpty()) {
                add(Regex("(?i)(?:${verb})\\s+(?:a\\s+|an\\s+|the\\s+)?(?:post|status|update|tweet)?\\s*(?:saying|that|with)?\\s*[:\\-]?\\s*(.+?)\\s+(?:on|to|in)\\s+(?:$nameAlt)\\b"))
                add(Regex("(?i)(?:$nameAlt)\\b\\s*(?:saying|that|with)?\\s*[:\\-]?\\s*(.+)$"))
            }
            add(Regex("(?i)(?:${verb})\\s+(?:a\\s+|an\\s+|the\\s+)?(?:post|status|update|tweet)?\\s*(?:saying|that|with)?\\s*[:\\-]?\\s*(.+)$"))
        }
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val body = cleanBody(match.groupValues[1], profile)
            if (body != null) return body
        }
        return null
    }

    /**
     * Reject fragments that are structure, not content: "post on <app>" produces
     * "on linkedin", which must trigger the clarification flow instead of being
     * published as a post body.
     */
    private fun cleanBody(raw: String, profile: Profile?): String? {
        val body = raw.trim().trim('"', '\'', '.', ',', ':')
        if (body.length < 3) return null
        val lower = body.lowercase()
        if (lower.startsWith("on ") || lower.startsWith("in ") ||
            lower.startsWith("to ") || lower.startsWith("at ")
        ) {
            return null
        }
        val names = (profile?.aliases.orEmpty() + listOfNotNull(profile?.label?.lowercase()))
            .filter { it.isNotBlank() }
        if (names.any { it == lower }) return null
        return body
    }

    /**
     * Build the bounded-executor spec: open composer → type → publish → verify.
     * A profile without a success text still verifies the foreground package.
     */
    fun buildSpec(postText: String, profile: Profile): Spec = Spec(
        taskLabel = "${profile.label} post",
        targetPackage = profile.packageName,
        steps = listOf(
            Step(
                name = "open composer",
                target = SelectorChain(
                    text = profile.composerHints.firstOrNull(),
                    contentDesc = profile.composerHints.firstOrNull(),
                    resourceId = profile.composerResourceId,
                ),
                action = Action.TAP,
                verify = VerifySpec(foregroundPackage = profile.packageName),
                waitForMs = 6000,
            ),
            Step(
                name = "enter post text",
                target = SelectorChain(
                    viewClass = "android.widget.EditText",
                    contentDesc = profile.fieldHints.firstOrNull(),
                    text = profile.fieldHints.getOrNull(1) ?: profile.fieldHints.firstOrNull(),
                ),
                action = Action.INPUT_TEXT,
                inputText = postText,
                clearBeforeInput = true,
                waitForMs = 6000,
            ),
            Step(
                name = "publish",
                target = SelectorChain(
                    text = profile.publishLabels.firstOrNull(),
                    contentDesc = profile.publishLabels.firstOrNull(),
                    resourceId = null,
                ),
                action = Action.TAP,
            ),
            Step(
                name = "verify published",
                target = null,
                action = Action.WAIT_SETTLED,
                verify = VerifySpec(
                    foregroundPackage = profile.packageName,
                    textAppears = profile.successText,
                ),
            ),
        ),
    )

    private fun containsToken(haystack: String, needle: String): Boolean {
        if (needle.isBlank()) return false
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            val before = if (index == 0) ' ' else haystack[index - 1]
            val afterIndex = index + needle.length
            val after = if (afterIndex >= haystack.length) ' ' else haystack[afterIndex]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            index = haystack.indexOf(needle, index + 1)
        }
        return false
    }
}
