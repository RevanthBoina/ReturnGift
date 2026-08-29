// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.server

import android.content.Context
import com.returngift.agent.BuildConfig
import com.returngift.agent.agent.Tier1Telemetry
import com.returngift.agent.channel.ChannelManager
import com.returngift.agent.tool.ToolRegistry
import com.returngift.agent.tool.ToolResult
import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD

/**
 * LAN HTTP configuration server
 * Provides an H5 page for configuring channel keys in a desktop browser
 * Requires pairing token for all /api/* endpoints.
 */
class ConfigServer(
    private val context: Context,
    port: Int = PORT
) : NanoHTTPD("127.0.0.1", port) {

    companion object {
        private const val TAG = "ConfigServer"
        const val PORT = 9527
        private const val MIME_HTML = "text/html"
        private const val MIME_JSON = "application/json"
    }

    private val gson = Gson()

    /**
     * Verify the pairing token against requests to /api/* endpoints.
     * Token is accepted via query param `token` or header `X-Server-Token`.
     */
    private fun verifyToken(session: IHTTPSession): Boolean {
        val provided = session.headers["x-server-token"]
            ?: session.parms["token"]
        return ServerTokenStore.verifyToken(provided)
    }

    /**
     * Determine whether the requesting client should see full secret values
     * (requires ?reveal=1 in the query string).
     */
    private fun isReveal(session: IHTTPSession): Boolean {
        return session.parms["reveal"] == "1"
    }

    /**
     * Log a closed-vocabulary reveal event.
     * kind is one of: llm, discord, telegram
     */
    private fun logReveal(kind: String) {
        XLog.w(TAG, "lan: secret revealed $kind")
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // Token verification for all /api/* endpoints
        if (uri.startsWith("/api/")) {
            if (!verifyToken(session)) {
                return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, MIME_JSON,
                    """{"code":401,"message":"unauthorized"}"""
                )
            }
        }

        return try {
            when {
                (uri == "/" || uri == "/index.html") && method == Method.GET -> serveHtml()
                uri == "/api/channels" && method == Method.GET -> handleGetChannels(session)
                uri == "/api/channels" && method == Method.POST -> handlePostChannels(session)
                uri == "/api/llm" && method == Method.GET -> handleGetLlm(session)
                uri == "/api/llm" && method == Method.POST -> handlePostLlm(session)
                uri == "/debug.html" && method == Method.GET && BuildConfig.DEBUG -> serveDebugHtml()
                uri == "/api/debug/tools" && method == Method.GET && BuildConfig.DEBUG -> handleGetTools()
                uri == "/api/debug/telemetry" && method == Method.GET && BuildConfig.DEBUG -> handleGetTelemetry()
                uri == "/api/debug/execute" && method == Method.POST && BuildConfig.DEBUG -> handleExecuteTool(session)
                uri == "/api/debug/screen-full" && method == Method.GET && BuildConfig.DEBUG -> handleGetScreenFull()
                uri.startsWith("/api/debug/file") && method == Method.GET && BuildConfig.DEBUG -> handleServeFile(session)
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_JSON,
                    """{"code":-1,"message":"not found"}"""
                )
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Server error: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_JSON,
                """{"code":-1,"message":"${e.message}"}"""
            )
        }
    }

    private fun serveHtml(): Response {
        val inputStream = context.assets.open("web/index.html")
        val html = inputStream.bufferedReader().use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, html)
    }

    private fun handleGetChannels(session: IHTTPSession): Response {
        val llmKey = KVUtils.getLlmApiKey()
        val discordToken = KVUtils.getDiscordBotToken()
        val telegramToken = KVUtils.getTelegramBotToken()

        val discordShown = if (isReveal(session)) discordToken else maskForDisplay(discordToken)
        val telegramShown = if (isReveal(session)) telegramToken else maskForDisplay(telegramToken)
        val llmShown = if (isReveal(session)) llmKey else maskForDisplay(llmKey)

        val data = JsonObject().apply {
            addProperty("discordBotToken", discordShown)
            addProperty("telegramBotToken", telegramShown)
            addProperty("llmApiKey", llmShown)
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    private fun handlePostChannels(session: IHTTPSession): Response {
        // NanoHTTPD requires parseBody before reading POST body
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"invalid json"}"""
            )
        }

        var reinitDiscord = false
        var reinitTelegram = false

        // Discord config
        if (json.has("discordBotToken")) {
            val value = json.get("discordBotToken").asString
            if (!isMaskedValue(value)) {
                KVUtils.setDiscordBotToken(value)
                reinitDiscord = true
                if (isReveal(session)) logReveal("discord")
            }
        }

        // Telegram config
        if (json.has("telegramBotToken")) {
            val value = json.get("telegramBotToken").asString
            if (!isMaskedValue(value)) {
                KVUtils.setTelegramBotToken(value)
                reinitTelegram = true
                if (isReveal(session)) logReveal("telegram")
            }
        }

        // Re-initialize the corresponding channel
        if (reinitDiscord) {
            ChannelManager.reinitDiscordFromStorage()
        }
        if (reinitTelegram) {
            ChannelManager.reinitTelegramFromStorage()
        }

        // Notify Settings page to refresh binding status
        if (reinitDiscord || reinitTelegram) {
            ConfigServerManager.notifyConfigChanged()
        }

        val result = JsonObject().apply {
            addProperty("code", 0)
            addProperty("message", "ok")
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    private fun handleGetLlm(session: IHTTPSession): Response {
        val apiKey = KVUtils.getLlmApiKey()
        val llmShown = if (isReveal(session)) apiKey else maskForDisplay(apiKey)

        val data = JsonObject().apply {
            addProperty("llmApiKey", llmShown)
            addProperty("llmBaseUrl", KVUtils.getLlmBaseUrl())
            addProperty("llmModelName", KVUtils.getLlmModelName())
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    private fun handlePostLlm(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"invalid json"}"""
            )
        }

        if (json.has("llmApiKey")) {
            val value = json.get("llmApiKey").asString
            if (!isMaskedValue(value)) {
                KVUtils.setLlmApiKey(value)
                if (isReveal(session)) logReveal("llm")
            }
        }
        if (json.has("llmBaseUrl")) {
            KVUtils.setLlmBaseUrl(json.get("llmBaseUrl").asString)
        }
        if (json.has("llmModelName")) {
            val value = json.get("llmModelName").asString.trim()
            KVUtils.setLlmModelName(if (value.isEmpty()) "" else value)
        }

        ConfigServerManager.notifyConfigChanged()

        val result = JsonObject().apply {
            addProperty("code", 0)
            addProperty("message", "ok")
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    // ==================== Debug (DEBUG builds only) ====================
    
    private fun handleGetScreenFull(): Response {
        val service = com.returngift.agent.service.ClawAccessibilityService.getInstance()
            ?: return newFixedLengthResponse(
                Response.Status.OK, MIME_JSON,
                """{"code":-1,"message":"Accessibility service is not running"}"""
            )
        val tree = service.screenTreeFull
        val data = JsonObject().apply {
            addProperty("success", tree != null)
            addProperty("data", tree ?: "")
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    private fun serveDebugHtml(): Response {
        val inputStream = context.assets.open("web/debug.html")
        val html = inputStream.bufferedReader().use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, html)
    }

    private fun handleGetTools(): Response {
        val tools = ToolRegistry.getAllTools()
        val arr = JsonArray()
        for (tool in tools) {
            val obj = JsonObject().apply {
                addProperty("name", tool.getName())
                addProperty("displayName", tool.getDisplayName())
                addProperty("description", tool.getDescription())
                val params = JsonArray()
                for (p in tool.getParameters()) {
                    params.add(JsonObject().apply {
                        addProperty("name", p.name)
                        addProperty("type", p.type)
                        addProperty("description", p.description)
                        addProperty("required", p.isRequired)
                    })
                }
                add("parameters", params)
            }
            arr.add(obj)
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", arr)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    /**
     * Phase 4: aggregate Tier-1 telemetry for the debug page. Only fixed-vocabulary
     * counters are exposed (intent names) — never raw utterance text, and nothing leaves
     * the device (this endpoint is DEBUG-only and served over the local config server).
     */
    private fun handleGetTelemetry(): Response {
        val data = JsonObject().apply {
            addProperty("tier1_total", KVUtils.getInt(Tier1Telemetry.KEY_TOTAL))
            addProperty("tier3_fallback_total", KVUtils.getInt(Tier1Telemetry.KEY_FALLBACK_TIER3))
            val hits = JsonObject()
            for (intent in Tier1Telemetry.intents) {
                hits.addProperty(intent, KVUtils.getInt("${Tier1Telemetry.KEY_HIT_PREFIX}$intent"))
            }
            add("hits", hits)
            val fp = JsonObject()
            for (intent in Tier1Telemetry.intents) {
                fp.addProperty(intent, KVUtils.getInt("${Tier1Telemetry.KEY_FP_PREFIX}$intent"))
            }
            add("false_positives", fp)
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    private fun handleExecuteTool(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"invalid json"}"""
            )
        }

        val toolName = json.get("tool")?.asString ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, MIME_JSON,
            """{"code":-1,"message":"missing tool name"}"""
        )

        val params = mutableMapOf<String, Any>()
        try {
            json.getAsJsonObject("params")?.entrySet()?.forEach { (key, value) ->
                when {
                    value.isJsonNull -> {}
                    !value.isJsonPrimitive -> params[key] = value.toString()
                    value.asJsonPrimitive.isNumber -> params[key] = value.asNumber
                    value.asJsonPrimitive.isBoolean -> params[key] = value.asBoolean
                    else -> params[key] = value.asString
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Debug param parse error: ${e.message}")
        }

        XLog.d(TAG, "Debug execute: $toolName params=$params")

        val toolResult = try {
            ToolRegistry.executeTool(toolName, params)
        } catch (e: Exception) {
            XLog.e(TAG, "Debug execute error", e)
            ToolResult.error("Exception: ${e.message}")
        }

        val data = JsonObject().apply {
            addProperty("success", toolResult.isSuccess)
            addProperty("data", toolResult.data)
            addProperty("error", toolResult.error)
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
    }

    private fun handleServeFile(session: IHTTPSession): Response {
        val path = session.parms["path"] ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, MIME_JSON,
            """{"code":-1,"message":"missing path param"}"""
        )
        // Security check: only allow access to files inside the cache directory
        val cacheDir = context.cacheDir.absolutePath
        val file = java.io.File(path)
        if (!file.exists() || !file.absolutePath.startsWith(cacheDir)) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_JSON,
                """{"code":-1,"message":"file not found or access denied"}"""
            )
        }
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
        return newFixedLengthResponse(Response.Status.OK, mime, file.inputStream(), file.length())
    }

    /**
     * Mask: show only last 4 characters, replace the rest with *
     * Used for default display; full value only with ?reveal=1
     */
    private fun maskForDisplay(secret: String): String {
        if (secret.isEmpty()) return ""
        if (secret.length <= 4) return secret
        return "*".repeat(secret.length - 4) + secret.takeLast(4)
    }

    /**
     * Check whether a value has been masked (contains *)
     */
    private fun isMaskedValue(value: String): Boolean {
        return value.contains("*")
    }
}