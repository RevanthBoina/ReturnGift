// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

/**
 * Maintainable configuration tables for mid-task interrupt detection.
 *
 * DESIGN RULE: never hardcode package names or window types inline in detection logic.
 * Add new entries here; the detector reads from these lists at runtime.
 *
 * Two categories:
 *   AUTO_DISMISSABLE  — benign, non-blocking. Dismiss silently and continue task.
 *   PAUSE_AND_CONFIRM — user must confirm before the task resumes.
 */
object InterruptConfig {

    // ── Package names whose windows are AUTO_DISMISSABLE ─────────────────────
    // These are known system/OEM UI surfaces that do not require user action.
    val AUTO_DISMISSABLE_PACKAGES: Set<String> = setOf(
        "com.android.systemui",                    // status bar, notification shade, pip controls
        "com.google.android.inputmethod.latin",    // Gboard suggestion bar
        "com.samsung.android.honeyboard",          // Samsung keyboard suggestion bar
        "com.baidu.input",                         // Baidu IME
        "com.iflytek.inputmethod",                 // iFlytek IME
        "com.sohu.inputmethod.sogou",              // Sogou IME
        "com.android.internal.app.ChooserActivity",// Share sheet (auto-dismiss by going back)
        "android",                                 // Framework toasts
        "com.google.android.deskclock"             // Clock alarm banner (non-interactive)
    )

    // ── Package names whose windows always trigger PAUSE_AND_CONFIRM ─────────
    // Any window from these packages halts the task.
    val PAUSE_AND_CONFIRM_PACKAGES: Set<String> = setOf(
        "com.android.permissioncontroller",        // Runtime permission dialogs (Android 10+)
        "com.google.android.permissioncontroller", // Google variant
        "com.android.packageinstaller",            // Pre-10 permission dialogs
        "com.samsung.android.permissioncontroller",// Samsung variant
        "com.miui.securitycenter",                 // MIUI permission overlay
        "com.android.server.telecom",              // Incoming call screen
        "com.samsung.android.incallui",            // Samsung in-call UI
        "com.google.android.dialer",               // Pixel dialer incoming call
        "com.miui.incallui",                       // MIUI in-call
        "android.app.AlertDialog"                  // System alert dialogs (not window pkg, kept for text matching)
    )

    // ── Node text patterns that indicate PAUSE_AND_CONFIRM regardless of package ─
    // Match against visible node text in the accessibility tree.
    val PAUSE_TEXT_PATTERNS: List<Regex> = listOf(
        Regex("allow .* to", RegexOption.IGNORE_CASE),           // "Allow App to access..."
        Regex("grant .* permission", RegexOption.IGNORE_CASE),
        Regex("^(allow|deny|don.t allow)\$", RegexOption.IGNORE_CASE),
        Regex("incoming call", RegexOption.IGNORE_CASE),
        Regex("answer.*call|decline.*call", RegexOption.IGNORE_CASE),
        Regex("slide to answer", RegexOption.IGNORE_CASE),
        Regex("battery optimization", RegexOption.IGNORE_CASE),
        Regex("unknown source", RegexOption.IGNORE_CASE),
        Regex("install.*app.*anyway", RegexOption.IGNORE_CASE)
    )

    // ── Node text patterns for AUTO_DISMISSABLE toasts / banners ─────────────
    val AUTO_DISMISS_TEXT_PATTERNS: List<Regex> = listOf(
        Regex("^copied to clipboard\$", RegexOption.IGNORE_CASE),
        Regex("^screenshot (saved|captured)\$", RegexOption.IGNORE_CASE),
        Regex("^no internet connection\$", RegexOption.IGNORE_CASE),
        Regex("^(done|saved|sent)\$", RegexOption.IGNORE_CASE)
    )

    // ── Android window types that always mean PAUSE_AND_CONFIRM ──────────────
    // Maps to AccessibilityWindowInfo.TYPE_* constants.
    // TYPE_ACCESSIBILITY_OVERLAY = 4, TYPE_INPUT_METHOD = 2, TYPE_SYSTEM = 3,
    // TYPE_APPLICATION = 1. We pause on anything that is TYPE_SYSTEM (3) and not
    // in the known-benign package list.
    const val WINDOW_TYPE_SYSTEM = 3
    const val WINDOW_TYPE_INPUT_METHOD = 2
}
