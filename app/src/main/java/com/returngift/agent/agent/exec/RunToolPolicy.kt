// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec

/**
 * Per-intent tool policy (pure Kotlin, JVM-unit-testable).
 *
 * The execution-layer fix for UI-scraped Q&A: the tool list the model SEES and
 * the tools the loop will EXECUTE are both derived from the classified intent.
 * A knowledge question physically cannot call get_screen_info — the spec list
 * hides it and the execution gate rejects it with guidance.
 */
object RunToolPolicy {

    /** Tools every task may use regardless of intent. */
    private val ALWAYS_ALLOWED = setOf("finish", "ask_user")

    /** Observation / device-interaction tools — device automation only. */
    private val DEVICE_ONLY_TOOLS = setOf(
        "get_screen_info", "take_screenshot", "tap", "tap_node", "long_press",
        "find_and_tap", "input_text", "scroll_to_find", "open_app", "switch_app",
        "system_key", "get_foreground_app", "get_notifications", "clipboard",
        "phone_long_press", "send_message", "import_download",
    )

    private val KNOWLEDGE_TOOLS = ALWAYS_ALLOWED + setOf(
        "kb_read", "kb_search", "web_search", "web_fetch", "kb_write", "kb_append",
    )

    private val VAULT_TOOLS = ALWAYS_ALLOWED + setOf(
        "kb_read", "kb_search", "kb_list", "kb_write", "kb_append", "kb_delete",
    )

    private val WEB_RESEARCH_TOOLS = ALWAYS_ALLOWED + setOf(
        "web_search", "web_fetch", "kb_read", "kb_search", "kb_write", "kb_append",
    )

    /**
     * @return the allowed tool names for [intent], or null = unrestricted
     * (device automation + external-AI tasks keep the full tool set).
     */
    fun allowedTools(intent: TaskIntentClassifier.Intent): Set<String>? = when (intent) {
        TaskIntentClassifier.Intent.KNOWLEDGE_QA -> KNOWLEDGE_TOOLS
        TaskIntentClassifier.Intent.VAULT_QUERY -> VAULT_TOOLS
        TaskIntentClassifier.Intent.WEB_RESEARCH -> WEB_RESEARCH_TOOLS
        TaskIntentClassifier.Intent.EXTERNAL_AI_QUERY -> null
        TaskIntentClassifier.Intent.DEVICE_AUTOMATION -> null
    }

    /**
     * @return null when [toolName] may execute under [intent]; otherwise the
     * user/model-facing block reason (fed back into the loop as guidance).
     */
    fun blockReason(intent: TaskIntentClassifier.Intent, toolName: String): String? {
        val allowed = allowedTools(intent) ?: return null
        if (toolName in allowed) return null
        val guidance = when (intent) {
            TaskIntentClassifier.Intent.KNOWLEDGE_QA ->
                "answer directly from knowledge, or use kb_search/web_search if you need facts"
            TaskIntentClassifier.Intent.VAULT_QUERY ->
                "use kb_read/kb_search to answer from the vault"
            TaskIntentClassifier.Intent.WEB_RESEARCH ->
                "use web_search/web_fetch to gather the information"
            else -> "this tool is not available for this task type"
        }
        return "Tool '$toolName' is not available for this task type " +
            "(${intent.name.lowercase()}): $guidance. Do NOT inspect the screen — " +
            "there is nothing on it that answers the user's question."
    }
}
