package com.returngift.agent.server

import com.returngift.agent.utils.KVUtils
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object ServerTokenStore {

    private const val TOKEN_KEY = "lan_server_token"
    private const val TOKEN_BYTES = 32

    private val secureRandom = SecureRandom()
    private val base64UrlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return base64UrlEncoder.encodeToString(bytes)
    }

    fun getToken(): String? = KVUtils.getString(TOKEN_KEY, null)

    fun verifyToken(provided: String?): Boolean {
        if (provided.isNullOrEmpty()) return false
        val stored = getToken()
        if (stored == null) return false
        val storedBytes = stored.toByteArray(StandardCharsets.UTF_8)
        val providedBytes = provided.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(storedBytes, providedBytes)
    }

    fun storeToken(token: String) {
        KVUtils.putString(TOKEN_KEY, token)
    }
}
