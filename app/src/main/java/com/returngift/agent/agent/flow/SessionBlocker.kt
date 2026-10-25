// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.flow

/**
 * Why a flow stopped making progress.
 *
 * "Open LinkedIn → search 'zip'" fails in a small number of recognisable ways,
 * and each one wants a different response. Detecting the *class* of blocker —
 * rather than retrying blindly — is what turns a silent failure into either an
 * automatic recovery or an honest message to the user.
 *
 * Pure Kotlin (no Android, no I/O) so every branch is unit-testable.
 */
enum class SessionBlocker {
    NONE,

    /** A sign-in wall: credentials (or the user) are required. Never auto-filled. */
    LOGIN_REQUIRED,

    /** "Choose an account" / "Use another account" sheet. */
    ACCOUNT_CHOOSER,

    /** Cookie / terms / privacy consent. Safe to accept once, per app. */
    CONSENT_REQUIRED,

    /** Human verification. Only a person can clear it. */
    CAPTCHA_REQUIRED,

    /** "Too many requests" / temporarily throttled. Retry later with backoff. */
    RATE_LIMITED,

    /** No connectivity / request failed. Retry with backoff. */
    NETWORK_ERROR,
    ;

    /** Only the user can clear these. */
    val needsUser: Boolean
        get() = this == LOGIN_REQUIRED || this == ACCOUNT_CHOOSER || this == CAPTCHA_REQUIRED

    /** Worth retrying automatically after a growing delay. */
    val retryable: Boolean
        get() = this == RATE_LIMITED || this == NETWORK_ERROR

    /** Can be cleared by tapping one button, no credentials involved. */
    val autoDismissable: Boolean
        get() = this == CONSENT_REQUIRED
}

/** What the loop should do about a blocker. */
enum class RecoveryAction {
    NONE,
    /** Tap the positive consent button, then continue. */
    TAP_CONSENT,
    /** Wait using [FlowRetry] and try again. */
    RETRY_WITH_BACKOFF,
    /** Hand the task back to the user with a message and a way in. */
    ASK_USER,
    /** Stop the task and report honestly (no silent "done"). */
    STOP_AND_REPORT,
}

/**
 * Recognises blockers from whatever text is on screen.
 *
 * Matching is deliberately conservative: a single ambiguous word ("sign in"
 * inside a feed post) never triggers a login wall — the login signals must come
 * from two separate families, or from an unambiguous one ("forgot password").
 */
object SessionBlockerDetector {

    // Unambiguous on their own: they only exist on an authentication screen.
    private val LOGIN_STRONG = listOf(
        "forgot password", "reset password", "continue with google",
        "continue with facebook", "continue with apple", "sign in with",
        "log in with", "new to linkedin", "join linkedin", "create an account",
        "email or phone", "enter your password", "show password",
    )
    // Ambiguous on their own: need a second family to count.
    private val LOGIN_MEDIUM = listOf(
        "sign in", "sign-in", "log in", "login", "password", "username",
        "email address", "email", "phone number",
    )

    private val ACCOUNT_CHOOSER = listOf(
        "choose an account", "select an account", "use another account",
        "add another account", "accounts on this device",
    )

    private val CONSENT = listOf(
        "accept all", "accept cookies", "accept all cookies", "i agree",
        "agree and continue", "accept and continue", "terms of service",
        "privacy policy", "cookie preferences", "manage cookies",
    )

    private val CAPTCHA = listOf(
        "captcha", "verify you are human", "verify you're human", "i'm not a robot",
        "unusual activity", "security check", "complete the verification",
        "confirm you are not a robot",
    )

    private val RATE_LIMIT = listOf(
        "too many requests", "rate limit", "rate-limit", "try again later",
        "slow down", "temporarily blocked", "please wait a few minutes",
        "you've reached the limit", "you have reached the limit",
    )

    private val NETWORK = listOf(
        "no internet", "check your connection", "network error", "connection lost",
        "unable to connect", "offline", "something went wrong, try again",
        "couldn't refresh", "could not refresh", "check your network",
    )

    /**
     * Classify what is on screen.
     *
     * @param screenText everything visible (tree dump / OCR), any case
     * @param appLabel   app name used only for logging by callers
     */
    fun detect(screenText: String, appLabel: String = ""): SessionBlocker {
        if (screenText.isBlank()) return SessionBlocker.NONE
        val text = screenText.lowercase()

        // Order matters: the most specific, most actionable blocker wins.
        if (anyOf(text, CAPTCHA)) return SessionBlocker.CAPTCHA_REQUIRED
        if (anyOf(text, RATE_LIMIT)) return SessionBlocker.RATE_LIMITED
        if (anyOf(text, ACCOUNT_CHOOSER)) return SessionBlocker.ACCOUNT_CHOOSER
        if (anyOf(text, CONSENT) && !anyOf(text, LOGIN_STRONG)) {
            // A cookie banner can sit on top of a signed-in feed; a real auth
            // screen is reported as LOGIN_REQUIRED instead.
            return SessionBlocker.CONSENT_REQUIRED
        }
        if (isLoginWall(text)) return SessionBlocker.LOGIN_REQUIRED
        if (anyOf(text, NETWORK)) return SessionBlocker.NETWORK_ERROR
        return SessionBlocker.NONE
    }

    /** True when the screen contains an authentication wall. */
    fun isLoginWall(screenText: String): Boolean {
        if (screenText.isBlank()) return false
        val text = screenText.lowercase()
        if (anyOf(text, LOGIN_STRONG)) return true
        return countFamilies(text, LOGIN_MEDIUM) >= 2
    }

    /** What to do about [blocker]. */
    fun action(blocker: SessionBlocker): RecoveryAction = when (blocker) {
        SessionBlocker.NONE -> RecoveryAction.NONE
        SessionBlocker.CONSENT_REQUIRED -> RecoveryAction.TAP_CONSENT
        SessionBlocker.RATE_LIMITED, SessionBlocker.NETWORK_ERROR -> RecoveryAction.RETRY_WITH_BACKOFF
        SessionBlocker.LOGIN_REQUIRED, SessionBlocker.ACCOUNT_CHOOSER -> RecoveryAction.ASK_USER
        SessionBlocker.CAPTCHA_REQUIRED -> RecoveryAction.STOP_AND_REPORT
    }

    /**
     * User-facing message. Short, states what happened, what the user can do and
     * whether anything was changed — the three things a silent failure lacks.
     */
    fun message(blocker: SessionBlocker, appLabel: String = "the app"): String = when (blocker) {
        SessionBlocker.NONE -> ""
        SessionBlocker.LOGIN_REQUIRED ->
            "Login required — open $appLabel and sign in, then ask me again. Nothing was sent or changed."
        SessionBlocker.ACCOUNT_CHOOSER ->
            "$appLabel is asking which account to use. Pick an account in $appLabel, then ask me again. Nothing was sent."
        SessionBlocker.CAPTCHA_REQUIRED ->
            "$appLabel wants a human verification. Complete it in $appLabel, then ask me to retry. Nothing was sent."
        SessionBlocker.CONSENT_REQUIRED ->
            "Accepting the cookie/terms prompt in $appLabel so I can continue."
        SessionBlocker.RATE_LIMITED ->
            "$appLabel is limiting requests right now. I will wait and retry."
        SessionBlocker.NETWORK_ERROR ->
            "Could not reach $appLabel. Checking the connection and retrying."
    }

    private fun anyOf(text: String, needles: List<String>): Boolean =
        needles.any { text.contains(it) }

    /**
     * How many of the ambiguous login words appear. Counting distinct words (not
     * occurrences) keeps a single repeated word from looking like a login wall.
     */
    private fun countFamilies(text: String, needles: List<String>): Int =
        needles.count { text.contains(it) }
}
