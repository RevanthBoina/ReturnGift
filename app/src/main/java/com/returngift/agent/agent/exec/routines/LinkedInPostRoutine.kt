// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec.routines

import com.returngift.agent.agent.exec.DeterministicUiExecutor.Action
import com.returngift.agent.agent.exec.DeterministicUiExecutor.Spec
import com.returngift.agent.agent.exec.DeterministicUiExecutor.Step
import com.returngift.agent.agent.exec.DeterministicUiExecutor.VerifySpec
import com.returngift.agent.agent.exec.SelectorChain

/**
 * Reference workflow for the bounded executor: LinkedIn posting.
 *
 * DETECT → ACT → VERIFY → MOVE ON. Normal execution needs ~2 screen reads
 * total (one verification read after publishing, plus at most one discovery
 * read if a selector misses), never an indefinite observe loop:
 *   open app → tap composer → deterministic input → tap Post → one verify read.
 */
object LinkedInPostRoutine {

    private const val LINKEDIN_PKG = "com.linkedin.android"

    private val TRIGGER = Regex(
        "\\b(post|share|publish)\\b.*\\b(linkedin|linked in)\\b|\\blinkedin\\b.*\\b(post|share|publish)\\b"
    )

    fun matches(task: String): Boolean = TRIGGER.containsMatchIn(task.lowercase())

    /** Extract the post body: text after "post"/"share" on LinkedIn markers. */
    fun extractPostText(task: String): String? {
        val patterns = listOf(
            Regex("(?i)linkedin\\s*(?:saying|that|with|saying that)?\\s*[:\"']?\\s*(.+)$"),
            Regex("(?i)(?:post|share|publish)\\s+(?:on\\s+)?linkedin\\s*[:\"']?\\s*(.+)$"),
        )
        for (p in patterns) {
            val m = p.find(task.trim())
            if (m != null) {
                val body = m.groupValues[1].trim().trim('"', '\'')
                if (body.length >= 3) return body
            }
        }
        return null
    }

    fun buildSpec(postText: String): Spec = Spec(
        taskLabel = "LinkedIn post",
        targetPackage = LINKEDIN_PKG,
        steps = listOf(
            // 1. Open the composer — semantic text/desc first, coordinates last.
            Step(
                name = "open composer",
                target = SelectorChain(
                    text = "Start a post",
                    contentDesc = "Start a post",
                    resourceId = "$LINKEDIN_PKG:id/share_box_start_post",
                ),
                action = Action.TAP,
                verify = VerifySpec(foregroundPackage = LINKEDIN_PKG),
            ),
            // 2. Deterministic input: focus → clear → set text → field-content verify.
            Step(
                name = "enter post text",
                target = SelectorChain(
                    contentDesc = "Post text",
                    viewClass = "android.widget.EditText",
                ),
                action = Action.INPUT_TEXT,
                inputText = postText,
                clearBeforeInput = true,
            ),
            // 3. Publish.
            Step(
                name = "tap Post",
                target = SelectorChain(
                    text = "Post",
                    contentDesc = "Post",
                    resourceId = "$LINKEDIN_PKG:id/share_placeholder",
                ),
                action = Action.TAP,
            ),
            // 4. ONE verification read: LinkedIn confirms with a "Post successful"
            // snackbar; that single signal is the minimum state needed.
            Step(
                name = "verify published",
                target = null,
                action = Action.WAIT_SETTLED,
                verify = VerifySpec(
                    foregroundPackage = LINKEDIN_PKG,
                    textAppears = "Post successful",
                ),
            ),
        ),
    )
}
