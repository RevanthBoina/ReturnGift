// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.session

import com.returngift.agent.utils.XLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages persistent application and browser sessions.
 *
 * Inspired by Browser Use's CDP session & tab target management:
 * - Tracks opened apps, browser tabs, authenticated states, and current screen hashes.
 * - Prevents opening fresh tabs/sessions repeatedly (e.g. repeated ChatGPT tabs for image creation).
 * - Enables reuse of already authenticated user sessions.
 */
object AppSessionManager {

    private const val TAG = "AppSessionManager"

    data class AppSession(
        val packageName: String,
        var lastUrl: String? = null,
        var isAuthenticated: Boolean = false,
        var activeTabTitle: String? = null,
        var lastScreenHash: String = "",
        var lastAccessedAt: Long = System.currentTimeMillis()
    )

    val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.opera.browser",
        "com.sec.android.app.sbrowser"
    )

    private val activeSessions = ConcurrentHashMap<String, AppSession>()

    /**
     * Record an app opening event.
     */
    fun trackAppOpen(packageName: String) {
        val session = activeSessions.getOrPut(packageName) {
            AppSession(packageName = packageName)
        }
        session.lastAccessedAt = System.currentTimeMillis()
        XLog.d(TAG, "Tracked app session: $packageName")
    }

    /**
     * Update browser URL, tab, or authentication state.
     */
    fun updateBrowserState(
        packageName: String,
        url: String?,
        tabTitle: String?,
        isAuthenticated: Boolean,
        screenHash: String = ""
    ) {
        val session = activeSessions.getOrPut(packageName) {
            AppSession(packageName = packageName)
        }
        if (url != null) session.lastUrl = url
        if (tabTitle != null) session.activeTabTitle = tabTitle
        session.isAuthenticated = isAuthenticated
        session.lastScreenHash = screenHash
        session.lastAccessedAt = System.currentTimeMillis()
        XLog.d(TAG, "Updated browser session [$packageName]: url=$url, tab=$tabTitle, auth=$isAuthenticated")
    }

    /**
     * Check if an app is a recognized web browser.
     */
    fun isBrowser(packageName: String): Boolean {
        return packageName in BROWSER_PACKAGES
    }

    /**
     * Check if an app already has an active session that can be reused.
     */
    fun hasExistingSession(packageName: String): Boolean {
        val session = activeSessions[packageName] ?: return false
        val isRecent = System.currentTimeMillis() - session.lastAccessedAt < 30 * 60 * 1000L // 30 mins
        return isRecent
    }

    /**
     * Get the active session for an app.
     */
    fun getActiveSession(packageName: String): AppSession? {
        return activeSessions[packageName]
    }

    /**
     * Check if a specific URL or web domain already has an open session.
     */
    fun findExistingTab(urlDomain: String): AppSession? {
        return activeSessions.values.firstOrNull { session ->
            session.lastUrl?.contains(urlDomain, ignoreCase = true) == true ||
            session.activeTabTitle?.contains(urlDomain, ignoreCase = true) == true
        }
    }

    /**
     * Prune expired sessions older than TTL.
     */
    fun cleanExpiredSessions(ttlMs: Long = 30 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - ttlMs
        activeSessions.entries.removeIf { it.value.lastAccessedAt < cutoff }
    }

    /**
     * Get active session context summary for prompt injection.
     */
    fun getSessionSummary(): String {
        cleanExpiredSessions()
        if (activeSessions.isEmpty()) return ""
        return buildString {
            append("## Active App & Browser Sessions:\n")
            activeSessions.values.forEach { s ->
                val tab = s.activeTabTitle?.let { " (Tab: $it)" } ?: ""
                val url = s.lastUrl?.let { " [URL: $it]" } ?: ""
                val auth = if (s.isAuthenticated) " [Authenticated]" else ""
                append("- App: ${s.packageName}$tab$url$auth\n")
            }
            append("\n")
        }
    }

    /**
     * Clear sessions (e.g. on explicit reset).
     */
    fun clearAll() {
        activeSessions.clear()
    }
}
