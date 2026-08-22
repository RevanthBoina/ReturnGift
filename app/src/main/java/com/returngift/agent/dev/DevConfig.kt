// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.dev

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.returngift.agent.ClawApplication
import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog

/**
 * Developer self-development configuration + secure storage.
 *
 * Holds the GitHub repository (owner/repo) and the fine-grained PAT the app uses to:
 *  - read/update source files via the Contents API and open PRs ([GitHubCodeEngine])
 *  - fetch the rolling dev prerelease APK for OTA self-update ([DevUpdateChecker])
 *
 * The PAT is stored in EncryptedSharedPreferences (AES-GCM, Tink-backed), NOT plaintext in
 * MMKV — it is a credential with write access to the user's repository.
 *
 * The repo owner/name and the dev-channel toggle are non-secret and stored in [KVUtils].
 */
object DevConfig {

    private const val TAG = "DevConfig"
    private const val SECURE_FILE = "returngift_dev_secrets.xml"
    private const val KEY_GITHUB_TOKEN = "github_pat"

    // Non-secret settings in MMKV
    const val KEY_DEV_REPO_OWNER = "dev_repo_owner"
    const val KEY_DEV_REPO_NAME = "dev_repo_name"
    const val KEY_DEV_CHANNEL_ENABLED = "dev_channel_enabled"

    private val securePrefs by lazy {
        try {
            val ctx = ClawApplication.instance
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                SECURE_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to open EncryptedSharedPreferences; dev features disabled", e)
            null
        }
    }

    // ---- GitHub repository (owner/repo) ----

    fun setRepo(owner: String, name: String) {
        KVUtils.putString(KEY_DEV_REPO_OWNER, owner.trim())
        KVUtils.putString(KEY_DEV_REPO_NAME, name.trim())
    }

    fun getRepoOwner(): String = KVUtils.getString(KEY_DEV_REPO_OWNER, "")
    fun getRepoName(): String = KVUtils.getString(KEY_DEV_REPO_NAME, "")

    /** "owner/repo" form used by the GitHub REST API path; blank until configured. */
    fun getRepoSlug(): String {
        val owner = getRepoOwner()
        val name = getRepoName()
        return if (owner.isBlank() || name.isBlank()) "" else "$owner/$name"
    }

    // ---- GitHub PAT (secret) ----

    fun setGithubToken(token: String) {
        try {
            securePrefs?.edit()?.putString(KEY_GITHUB_TOKEN, token.trim())?.apply()
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to store GitHub token", e)
        }
    }

    fun getGithubToken(): String {
        return try {
            securePrefs?.getString(KEY_GITHUB_TOKEN, "") ?: ""
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to read GitHub token", e)
            ""
        }
    }

    fun hasGithubToken(): Boolean = getGithubToken().isNotBlank()

    /** Whether the self-development features (code engine + dev OTA) are configured. */
    fun isConfigured(): Boolean = hasGithubToken() && getRepoName().isNotBlank()

    // ---- Dev OTA channel toggle ----

    fun setDevChannelEnabled(enabled: Boolean) {
        KVUtils.putBoolean(KEY_DEV_CHANNEL_ENABLED, enabled)
    }

    fun isDevChannelEnabled(): Boolean = KVUtils.getBoolean(KEY_DEV_CHANNEL_ENABLED, false)

    /** Wipe the stored token (used by a "Sign out / clear token" settings action). */
    fun clearGithubToken() {
        try {
            securePrefs?.edit()?.remove(KEY_GITHUB_TOKEN)?.apply()
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to clear GitHub token", e)
        }
    }
}
