// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.skill

/**
 * Built-in deterministic skills for common, context-free actions.
 *
 * Design principle: only include skills that are SIMPLE, RELIABLE, and APP-AGNOSTIC.
 * Complex app-specific tasks (WhatsApp, Gmail, Camera, etc.) should go through
 * the LLM agent loop, which can adapt to different UI layouts and languages.
 *
 * These skills save 1-10 LLM inference rounds for routine actions.
 */
object BuiltInSkills {

    fun searchInApp() = Skill(
        id = "search_in_app",
        name = "Search in App",
        description = "Type a search query and submit it. Use when the task says 'search for' or 'find' in any app.",
        category = SkillCategory.INPUT,
        estimatedStepsSaved = 5,
        parameters = listOf(
            SkillParameter("query", "string", true, "The search query to type")
        ),
        // Trigger patterns removed — "search X" is too ambiguous for deterministic matching.
        // Agent loop handles search tasks better (understands app name vs query).
        triggerPatterns = listOf(
        ),
        steps = listOf(
            SkillStep("get_screen_info", description = "Check for search bar"),
            SkillStep("scroll_to_find", mapOf("text" to "Search"), description = "Find search icon/bar", optional = true),
            SkillStep("input_text", mapOf("text" to "{query}"), description = "Type query"),
            SkillStep("system_key", mapOf("key" to "enter"), description = "Submit search"),
            SkillStep("wait", mapOf("duration_ms" to "2000"), description = "Wait for results"),
        ),
        fallbackGoal = "The search bar should have '{query}' typed. Just press enter or submit."
    )

    fun submitForm() = Skill(
        id = "submit_form",
        name = "Submit Form",
        description = "Find and tap the Send/Submit/Save button. Use after typing a message or filling a form.",
        category = SkillCategory.INPUT,
        estimatedStepsSaved = 4,
        parameters = emptyList(),
        triggerPatterns = listOf("submit", "send message", "press send"),
        steps = listOf(
            SkillStep("get_screen_info", description = "Look for submit button"),
            SkillStep("scroll_to_find", mapOf("text" to "Send"), description = "Find submit button", optional = true),
            SkillStep("wait", mapOf("duration_ms" to "2000"), description = "Wait for submission"),
        ),
        fallbackGoal = "Find and tap the Send, Submit, or Save button on screen."
    )

    fun dismissPopup() = Skill(
        id = "dismiss_popup",
        name = "Dismiss Popup",
        description = "Close common popups, dialogs, and overlays. Use when a dialog blocks the task.",
        category = SkillCategory.DISMISS,
        estimatedStepsSaved = 3,
        parameters = emptyList(),
        triggerPatterns = listOf("dismiss", "close popup", "close dialog"),
        steps = listOf(
            SkillStep("get_screen_info", description = "Identify popup type"),
            SkillStep("scroll_to_find", mapOf("text" to "OK"), description = "Find OK", optional = true),
            SkillStep("scroll_to_find", mapOf("text" to "Got it"), description = "Find Got it", optional = true),
            SkillStep("scroll_to_find", mapOf("text" to "Close"), description = "Find Close", optional = true),
            SkillStep("scroll_to_find", mapOf("text" to "Dismiss"), description = "Find Dismiss", optional = true),
            SkillStep("scroll_to_find", mapOf("text" to "Cancel"), description = "Find Cancel", optional = true),
            SkillStep("scroll_to_find", mapOf("text" to "Skip"), description = "Find Skip", optional = true),
        ),
        fallbackGoal = "A popup or dialog is blocking. Find and tap the close/dismiss button."
    )

    fun scrollAndRead() = Skill(
        id = "scroll_and_read",
        name = "Scroll and Read",
        description = "Scroll down the page and collect all visible text content.",
        category = SkillCategory.GENERAL,
        estimatedStepsSaved = 10,
        parameters = listOf(
            SkillParameter("max_scrolls", "int", false, "Maximum number of scrolls", "5")
        ),
        triggerPatterns = listOf("read the page", "scroll and read", "read all content"),
        steps = listOf(
            SkillStep("get_screen_info", description = "Read current screen"),
            SkillStep("swipe", mapOf("direction" to "up"), description = "Scroll down"),
            SkillStep("get_screen_info", description = "Read after scroll"),
            SkillStep("swipe", mapOf("direction" to "up"), description = "Scroll down more"),
            SkillStep("get_screen_info", description = "Read after scroll"),
        ),
        fallbackGoal = "Scroll down the page and collect all visible text."
    )

    fun copyScreenText() = Skill(
        id = "copy_screen_text",
        name = "Copy Screen Text",
        description = "Extract all visible text from the current screen.",
        category = SkillCategory.GENERAL,
        estimatedStepsSaved = 3,
        parameters = emptyList(),
        triggerPatterns = listOf("copy text", "extract text", "read screen"),
        steps = listOf(
            SkillStep("get_screen_info", description = "Read screen content"),
        ),
        fallbackGoal = "Read and return all visible text on the screen."
    )

    fun acceptPermission() = Skill(
        id = "accept_permission",
        name = "Accept Permission",
        description = "Accept permission dialogs, terms, or consent popups. Use when a dialog asks to Allow, Accept, or Agree.",
        category = SkillCategory.DISMISS,
        estimatedStepsSaved = 3,
        parameters = emptyList(),
        triggerPatterns = listOf("accept permission", "allow permission", "grant access"),
        steps = listOf(
            SkillStep("get_screen_info", description = "Check for permission dialog"),
            SkillStep("scroll_to_find", mapOf("text" to "Allow"), description = "Find Allow", optional = true),
            SkillStep("scroll_to_find", mapOf("text" to "While using the app"), description = "Find While using", optional = true),
            SkillStep("scroll_to_find", mapOf("text" to "Accept"), description = "Find Accept", optional = true),
            SkillStep("scroll_to_find", mapOf("text" to "Agree"), description = "Find Agree", optional = true),
            SkillStep("scroll_to_find", mapOf("text" to "Continue"), description = "Find Continue", optional = true),
        ),
        fallbackGoal = "A permission or consent dialog appeared. Find and tap the Allow/Accept/Agree button."
    )

    fun swipeGesture() = Skill(
        id = "swipe_gesture",
        name = "Swipe Screen",
        description = "Swipe the screen in a direction. Use for Tinder swipes, Instagram stories, carousels, or any swipeable content.",
        category = SkillCategory.NAVIGATION,
        estimatedStepsSaved = 2,
        parameters = listOf(
            SkillParameter("direction", "string", true, "Direction: left, right, up, or down")
        ),
        // Trigger patterns removed — "scroll down" without context is ambiguous,
        // causes wasted tokens on non-scrollable pages. Agent loop handles better.
        triggerPatterns = listOf(),
        steps = listOf(
            SkillStep("swipe", mapOf("direction" to "{direction}"), description = "Swipe {direction}"),
            SkillStep("wait", mapOf("duration_ms" to "1000"), description = "Wait for animation"),
        ),
        fallbackGoal = "Swipe the screen {direction}."
    )

    fun goBack() = Skill(
        id = "go_back",
        name = "Go Back",
        description = "Press the back button to return to the previous screen.",
        category = SkillCategory.NAVIGATION,
        estimatedStepsSaved = 1,
        parameters = emptyList(),
        triggerPatterns = listOf("go back", "press back", "navigate back"),
        steps = listOf(
            SkillStep("system_key", mapOf("key" to "back"), description = "Press back"),
            SkillStep("wait", mapOf("duration_ms" to "1000"), description = "Wait for transition"),
        ),
        fallbackGoal = "Press the back button."
    )

    fun waitForContent() = Skill(
        id = "wait_for_content",
        name = "Wait for Content",
        description = "Wait for new content to appear on screen (loading, AI response, page update). Polls up to 15 seconds.",
        category = SkillCategory.GENERAL,
        estimatedStepsSaved = 5,
        parameters = emptyList(),
        triggerPatterns = listOf("wait for content", "wait for loading", "wait for response"),
        steps = listOf(
            SkillStep("get_screen_info", description = "Capture initial screen state"),
            SkillStep("wait", mapOf("duration_ms" to "3000"), description = "Wait 3s"),
            SkillStep("get_screen_info", description = "Check for new content"),
            SkillStep("wait", mapOf("duration_ms" to "3000"), description = "Wait 3s more"),
            SkillStep("get_screen_info", description = "Check again"),
            SkillStep("wait", mapOf("duration_ms" to "3000"), description = "Wait 3s more"),
            SkillStep("get_screen_info", description = "Final check"),
        ),
        fallbackGoal = "Wait for new content to appear on the screen. Check every 3 seconds."
    )
}
