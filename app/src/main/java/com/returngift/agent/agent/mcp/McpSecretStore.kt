// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.mcp

import com.returngift.agent.agent.knowledge.AppGuideStore
import com.returngift.agent.utils.XLog

/**
 * Encrypted-at-rest storage for MCP bearer tokens.
 *
 * Why this exists: a bearer token is a **reusable credential**, and the app's
 * default key-value store is plaintext. Writing it there means any backup, root
 * shell, or debuggable build exposes it. So the token never lives in a server row:
 * the row keeps only [McpServerConfig.id], and the secret is stored here as an
 * AES-GCM blob whose key never leaves the Android Keystore.
 *
 * The cipher is a seam ([SecretCipher]) for two reasons: the policy is unit-testable
 * on a plain JVM, and a device without the Keystore fails **closed** — a token that
 * cannot be encrypted is not stored at all (with an explicit error), rather than
 * silently written in the clear.
 */
object McpSecretStore {

    private const val TAG = "McpSecretStore"
    private const val KEY_PREFIX = "mcp_secret_v2_"

    /** Encrypt/decrypt primitive. Implementations must be authenticated (AEAD). */
    interface SecretCipher {
        /** True when the key material is protected by the platform (Keystore). */
        val isSecure: Boolean

        fun encrypt(plaintext: String): String

        /** Returns null when the blob cannot be decrypted (wrong key, corruption). */
        fun decrypt(blob: String): String?
    }

    /**
     * Fail-closed default: refuses to store anything. The app installs
     * [KeystoreSecretCipher] at startup; a JVM/test build keeps this one.
     */
    object UnavailableCipher : SecretCipher {
        override val isSecure: Boolean = false
        override fun encrypt(plaintext: String): String =
            throw IllegalStateException("Secure storage is unavailable on this build")
        override fun decrypt(blob: String): String? = null
    }

    internal var cipher: SecretCipher = UnavailableCipher

    /**
     * Storage seam. Null means "use the knowledge store's storage", which is how the
     * app runs and how tests can swap one fake for everything.
     */
    internal var storage: AppGuideStore.Storage? = null

    /** True when tokens can be stored safely right now. */
    val isAvailable: Boolean
        get() = cipher.isSecure

    /**
     * Store (or replace) the token for [serverId].
     *
     * @throws IllegalStateException when no secure cipher is installed — callers
     *         surface this to the user instead of degrading to plaintext.
     */
    fun put(serverId: String, token: String) {
        if (token.isBlank()) {
            remove(serverId)
            return
        }
        val blob = try {
            cipher.encrypt(token)
        } catch (e: Throwable) {
            XLog.w(TAG, "could not encrypt a token for $serverId", e)
            throw IllegalStateException(
                "Secure storage is unavailable, so the token was not saved.",
                e,
            )
        }
        sink().putString(keyOf(serverId), blob)
    }

    /** Stored token, or null when absent/undecryptable. Never throws. */
    fun get(serverId: String): String? {
        val blob = sink().getString(keyOf(serverId), "")
        if (blob.isBlank()) return null
        val plain = try {
            cipher.decrypt(blob)
        } catch (e: Throwable) {
            XLog.w(TAG, "could not decrypt the token for $serverId", e)
            null
        }
        if (plain == null) {
            XLog.w(TAG, "dropping an unreadable token for $serverId (will re-ask)")
            remove(serverId)
        }
        return plain
    }

    fun remove(serverId: String) {
        sink().putString(keyOf(serverId), "")
    }

    fun has(serverId: String): Boolean = sink().getString(keyOf(serverId), "").isNotBlank()

    /**
     * Move a legacy plaintext token out of a server row and into encrypted storage.
     *
     * @return true when the token is now stored safely (or there was none)
     */
    fun adoptLegacyToken(serverId: String, legacyToken: String?): Boolean {
        if (legacyToken.isNullOrBlank()) return true
        return try {
            put(serverId, legacyToken)
            XLog.i(TAG, "moved the token for $serverId into encrypted storage")
            true
        } catch (e: Throwable) {
            // Do not rewrite the row: keeping the old value is bad, losing the
            // user's credential silently is worse. The caller decides.
            XLog.w(TAG, "could not migrate the token for $serverId", e)
            false
        }
    }

    private fun sink(): AppGuideStore.Storage = storage ?: AppGuideStore.storage

    private fun keyOf(serverId: String): String = KEY_PREFIX + serverId
}
