// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec.micro

/**
 * Translates a completed tool call into a [MicroStepContext].
 *
 * Pure Kotlin (tool names/params in, context out) so the mapping is unit-tested
 * without an accessibility service or Android runtime.
 *
 * Package → surface mapping is heuristic by design: it only needs to be right
 * about the *kind* of field the agent just typed into, because that decides
 * whether the follow-up is Enter, Send or Search.
 */
object MicroStepBridge {

    private val CHAT_PACKAGES = listOf(
        "com.whatsapp", "org.telegram", "com.google.android.apps.messaging",
        "com.instagram.android", "com.google.android.apps.messaging", "com.discord",
        "com.slack", "com.viber.voip", "com.facebook.orca", "com.microsoft.teams",
    )

    private val SOCIAL_PACKAGES = listOf(
        "com.linkedin.android", "com.facebook.katana", "com.twitter.android",
        "com.zhiliaoapp.musically", "com.google.android.apps.nbu.smartcam",
    )

    private val EMAIL_PACKAGES = listOf(
        "com.google.android.gm", "com.microsoft.office.outlook", "com.yahoo.mobile.client.android.mail",
        "com.google.android.apps.docs", "org.k9.mail",
    )

    private const val PKG_PLAY = "com.android.vending"
    private const val PKG_YOUTUBE = "com.google.android.youtube"
    private const val PKG_MAPS = "com.google.android.apps.maps"
    private const val PKG_CHROME = "com.android.chrome"
    private const val PKG_CAMERA = "camera"
    private const val PKG_DIALER = "dialer"
    private const val PKG_CONTACTS = "contacts"
    private const val PKG_SETTINGS = "com.android.settings"
    private const val PKG_LAUNCHER = "launcher"

    /**
     * Package → semantic surface, used when the tool does not name one.
     * The curated [com.returngift.agent.agent.knowledge.AppUsageGuide] is consulted
     * first so an app's real flow (Chrome = address bar, LinkedIn = post composer…)
     * beats the generic package heuristics below.
     */
    fun surfaceForPackage(packageName: String?): AppSurface {
        val pkg = packageName?.lowercase().orEmpty()
        if (pkg.isEmpty()) return AppSurface.UNKNOWN
        com.returngift.agent.agent.knowledge.AppUsageGuide.surfaceFor(pkg)?.let { return it }
        return when {
            CHAT_PACKAGES.any { pkg.contains(it) } -> AppSurface.CHAT_COMPOSER
            SOCIAL_PACKAGES.any { pkg.contains(it) } -> AppSurface.SOCIAL_POST
            EMAIL_PACKAGES.any { pkg.contains(it) } -> AppSurface.EMAIL_COMPOSE
            pkg.contains(PKG_PLAY) -> AppSurface.PLAY_STORE
            pkg.contains(PKG_YOUTUBE) || pkg.contains("video") -> AppSurface.VIDEO
            pkg.contains(PKG_MAPS) -> AppSurface.MAPS
            pkg.contains(PKG_CHROME) || pkg.contains("browser") -> AppSurface.BROWSER_ADDRESS
            pkg.contains(PKG_CAMERA) -> AppSurface.CAMERA
            pkg.contains(PKG_DIALER) || pkg.contains("phone") -> AppSurface.DIALER
            pkg.contains(PKG_CONTACTS) -> AppSurface.SEARCH_FIELD
            pkg.contains(PKG_SETTINGS) -> AppSurface.SETTINGS
            pkg.contains(PKG_LAUNCHER) -> AppSurface.HOME
            pkg.contains("calendar") -> AppSurface.FORM
            pkg.contains("gallery") || pkg.contains("documentsui") || pkg.contains("files") ->
                AppSurface.FILE_PICKER
            pkg.contains("music") || pkg.contains("spotify") || pkg.contains("podcast") ->
                AppSurface.MEDIA
            else -> AppSurface.UNKNOWN
        }
    }

    /**
     * Build the context for a completed tool call.
     *
     * @param toolName         executed tool ("input_text", "tap", "system_key"…)
     * @param params           tool parameters as sent by the model
     * @param success          tool result success
     * @param foregroundPackage package in front *after* the action
     * @param keyboardVisible  accessibility-based keyboard visibility (best effort)
     * @param submitVisible    a Send/Search affordance was visible on screen
     * @param screenChanged    the loop's ActionVerifier outcome
     * @param loading          a progress indicator was visible
     * @param dialogVisible    a modal dialog (not a permission dialog) is on screen
     * @param textEntered      the text that was actually typed (defaults to params["text"])
     * @param screenText       visible text of the screen after the action (only fetched
     *                         by the loop when the action failed or nothing changed);
     *                         used to recognise login walls, consent prompts and
     *                         throttling instead of retrying blindly
     */
    fun observe(
        toolName: String,
        params: Map<String, Any>,
        success: Boolean,
        foregroundPackage: String? = null,
        keyboardVisible: Boolean = false,
        submitVisible: Boolean = false,
        screenChanged: Boolean = true,
        loading: Boolean = false,
        resultsVisible: Boolean = false,
        dialogVisible: Boolean = false,
        textEntered: String? = null,
        label: String? = null,
        stepIndex: Int = 0,
        screenText: String = "",
    ): MicroStepContext {
        val tool = toolName.lowercase()
        val pkg = foregroundPackage.orEmpty()
        val text = textEntered ?: params["text"]?.toString().orEmpty()
        val tappedLabel = label
            ?: params["text"]?.toString()
            ?: params["label"]?.toString()
            ?: params["description"]?.toString()
            ?: params["content_desc"]?.toString()
            ?: params["node_text"]?.toString()
            ?: ""
        val surface = surfaceForPackage(pkg).let { base ->
            when {
                // Typing into Chrome's omnibox is an address-bar edit; the package
                // alone cannot tell a search page from the URL bar.
                base == AppSurface.BROWSER_ADDRESS -> AppSurface.BROWSER_ADDRESS
                base == AppSurface.SEARCH_FIELD -> base
                base == AppSurface.UNKNOWN && looksLikeSearch(tappedLabel, params) -> AppSurface.SEARCH_FIELD
                base == AppSurface.UNKNOWN && looksLikeChat(tappedLabel) -> AppSurface.CHAT_COMPOSER
                base == AppSurface.UNKNOWN && looksLikeForm(params) -> AppSurface.FORM
                // Social apps report their feed/composer surface for the whole app,
                // but typing into their search box is a search, not a post.
                base == AppSurface.SOCIAL_POST && looksLikeSearch(tappedLabel, params) ->
                    AppSurface.SEARCH_FIELD
                else -> base
            }
        }

        val action = when (tool) {
            "input_text", "type_text", "set_text" -> LastAction.TYPED_TEXT
            "open_app", "switch_app" -> LastAction.OPENED_APP
            "system_key" -> if (params["key"]?.toString()?.lowercase() == "enter") {
                LastAction.PRESSED_ENTER
            } else {
                LastAction.PRESSED_KEY
            }
            "tap", "tap_node", "find_and_tap", "send_message", "repeat_actions" -> LastAction.TAPPED
            "long_press" -> LastAction.LONG_PRESSED
            "swipe", "scroll_to_find" -> LastAction.SWIPED
            "wait" -> LastAction.WAITED
            "get_screen_info", "take_screenshot", "find_node_info", "get_foreground_app" -> LastAction.OBSERVED
            "ask_user" -> LastAction.NO_ACTION
            "finish" -> LastAction.NO_ACTION
            else -> LastAction.NO_ACTION
        }

        val affordance = when {
            surface == AppSurface.CHAT_COMPOSER && submitVisible -> SubmitAffordance.SEND_BUTTON
            surface == AppSurface.CHAT_COMPOSER -> SubmitAffordance.ENTER_KEY
            surface == AppSurface.SEARCH_FIELD && submitVisible -> SubmitAffordance.SEARCH_BUTTON
            surface == AppSurface.SEARCH_FIELD -> SubmitAffordance.ENTER_KEY
            else -> SubmitAffordance.NONE
        }

        return MicroStepContext(
            lastAction = if (success) action else LastAction.NO_ACTION,
            surface = surface,
            packageName = pkg,
            text = text,
            key = params["key"]?.toString().orEmpty(),
            label = tappedLabel,
            keyboardVisible = keyboardVisible,
            submitAffordance = affordance,
            dialogVisible = dialogVisible,
            permissionDialogVisible = false,
            loading = loading,
            screenChanged = screenChanged,
            resultsVisible = resultsVisible,
            fieldEmpty = text.isBlank(),
            blocker = com.returngift.agent.agent.flow.SessionBlockerDetector.detect(screenText),
            stepIndex = stepIndex,
        )
    }

    private fun looksLikeSearch(label: String, params: Map<String, Any>): Boolean {
        val l = label.lowercase()
        return l.contains("search") || params.containsKey("query")
    }

    private fun looksLikeChat(label: String): Boolean {
        val l = label.lowercase()
        return l.contains("message") || l.contains("type a message") || l.contains("chat")
    }

    private fun looksLikeForm(params: Map<String, Any>): Boolean =
        params.containsKey("field") || params.containsKey("form")
}
