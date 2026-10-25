// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.flow

import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog

/**
 * What we already know about an app's session, so the agent stops re-discovering
 * it on every task.
 *
 * Two facts are remembered per package:
 *  - **consent** — the cookie/terms prompt was accepted (30-day TTL). Until then
 *    the agent may tap the positive button; afterwards it should not expect one.
 *  - **signed in** — the app was seen signed in (7-day TTL). A missing flag is
 *    NOT proof of a login wall (the user may have just reinstalled the app), so
 *    it only ever *strengthens* the on-screen detection.
 *
 * Only booleans and timestamps are stored — never credentials, tokens or text
 * from the screen. The user can clear a package from Settings → App permissions
 * (the row Reset removes the policy; [forget] clears these facts too).
 */
object AppSessionMemory {

    private const val TAG = "AppSessionMemory"

    const val CONSENT_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000
    const val LOGIN_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000

    /** Storage seam so the logic is testable without MMKV. */
    interface Storage {
        fun getBoolean(key: String, defaultValue: Boolean): Boolean
        fun putBoolean(key: String, value: Boolean)
        fun getLong(key: String, defaultValue: Long): Long
        fun putLong(key: String, value: Long)
    }

    object KVStorage : Storage {
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
            if (KVUtils.isInitialized) KVUtils.getBoolean(key, defaultValue) else defaultValue

        override fun putBoolean(key: String, value: Boolean) {
            if (KVUtils.isInitialized) KVUtils.putBoolean(key, value)
        }

        override fun getLong(key: String, defaultValue: Long): Long =
            if (KVUtils.isInitialized) KVUtils.getLong(key, defaultValue) else defaultValue

        override fun putLong(key: String, value: Long) {
            if (KVUtils.isInitialized) KVUtils.putLong(key, value)
        }
    }

    internal var storage: Storage = KVStorage

    private fun now(): Long = System.currentTimeMillis()

    // ── Consent ─────────────────────────────────────────────────────────────

    fun consentAccepted(packageName: String, at: Long = now()): Boolean =
        isFresh(storage.getLong(consentKey(packageName), 0L), at, CONSENT_TTL_MS)

    fun markConsentAccepted(packageName: String, at: Long = now()) {
        if (packageName.isBlank()) return
        write { storage.putLong(consentKey(packageName), at) }
        XLog.i(TAG, "consent remembered for $packageName")
    }

    /** True when a consent prompt should be tapped without asking again. */
    fun shouldAcceptConsentSilently(packageName: String, at: Long = now()): Boolean =
        !consentAccepted(packageName, at)

    // ── Login ───────────────────────────────────────────────────────────────

    fun signedIn(packageName: String, at: Long = now()): Boolean =
        isFresh(storage.getLong(loginKey(packageName), 0L), at, LOGIN_TTL_MS)

    fun markSignedIn(packageName: String, at: Long = now()) {
        if (packageName.isBlank()) return
        write { storage.putLong(loginKey(packageName), at) }
    }

    fun markLoginWall(packageName: String) {
        if (packageName.isBlank()) return
        // Forget a stale "signed in" claim: the screen is authoritative.
        write { storage.putLong(loginKey(packageName), 0L) }
        XLog.i(TAG, "login wall observed for $packageName")
    }

    // ── Combined decision ───────────────────────────────────────────────────

    /**
     * Decide what to do with an on-screen [blocker] for [packageName].
     *
     * The screen always wins over memory: memory can only make a consent prompt
     * cheaper to clear, never cause a login wall to be ignored.
     */
    fun plan(blocker: SessionBlocker, packageName: String, at: Long = now()): RecoveryAction {
        if (blocker == SessionBlocker.NONE) return RecoveryAction.NONE
        if (blocker == SessionBlocker.LOGIN_REQUIRED) {
            markLoginWall(packageName)
            return RecoveryAction.ASK_USER
        }
        if (blocker == SessionBlocker.CONSENT_REQUIRED) {
            // The first time we see it, accept; afterwards the apps that show it
            // repeatedly get one retry, then the user is told.
            return if (!consentAccepted(packageName, at)) {
                RecoveryAction.TAP_CONSENT
            } else {
                RecoveryAction.RETRY_WITH_BACKOFF
            }
        }
        return SessionBlockerDetector.action(blocker)
    }

    fun forget(packageName: String) {
        if (packageName.isBlank()) return
        write {
            storage.putLong(consentKey(packageName), 0L)
            storage.putLong(loginKey(packageName), 0L)
        }
        XLog.i(TAG, "session facts cleared for $packageName")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun isFresh(timestamp: Long, at: Long, ttl: Long): Boolean {
        if (timestamp <= 0L) return false
        val age = at - timestamp
        return age >= 0 && age < ttl
    }

    private fun consentKey(pkg: String) = "flow_consent_at_${pkg.lowercase()}"
    private fun loginKey(pkg: String) = "flow_signed_in_at_${pkg.lowercase()}"

    private inline fun write(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            XLog.w(TAG, "session memory write failed", e)
        }
    }
}
