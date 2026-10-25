// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.mcp

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM cipher backed by the Android Keystore.
 *
 * The key is generated inside the Keystore (`AndroidKeyStore`) and is never
 * exportable: the app can ask it to decrypt, but no backup, root shell or debugger
 * dump yields the raw key. That is what makes storing an MCP bearer token here
 * materially different from storing it in the default key-value file.
 *
 * Not unit-tested on the JVM — it needs a device/emulator Keystore. The *policy*
 * around it (`McpSecretStore`) is what the tests exercise, through the
 * [McpSecretStore.SecretCipher] seam.
 *
 * Requires API 23+ (minSdk is 28, so always available).
 */
class KeystoreSecretCipher(
    private val keyAlias: String = DEFAULT_ALIAS,
) : McpSecretStore.SecretCipher {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_ALIAS = "returngift_mcp_token_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12

        /** Prefix so an old/foreign blob is recognisable instead of silently mis-parsed. */
        private const val PREFIX = "v1:"
    }

    override val isSecure: Boolean = true

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(iv.size + body.size)
        System.arraycopy(iv, 0, packed, 0, iv.size)
        System.arraycopy(body, 0, packed, iv.size, body.size)
        return PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    override fun decrypt(blob: String): String? {
        if (!blob.startsWith(PREFIX)) return null
        val packed = try {
            Base64.decode(blob.removePrefix(PREFIX), Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (packed.size <= IV_BYTES) return null
        val iv = packed.copyOfRange(0, IV_BYTES)
        val body = packed.copyOfRange(IV_BYTES, packed.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Throwable) {
            // Wrong key after a restore, corrupted blob, or the key was invalidated.
            null
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No user-authentication requirement: the agent pulls this in the
                // background during a task, when there is no prompt available.
                .build()
        )
        return generator.generateKey()
    }
}
