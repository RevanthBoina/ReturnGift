// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool.impl

import com.returngift.agent.agent.mcp.Json
import com.returngift.agent.agent.mcp.JsonValue
import com.returngift.agent.agent.mcp.McpRedaction
import com.returngift.agent.agent.mcp.McpResultFormatter
import com.returngift.agent.agent.mcp.McpRuntime
import com.returngift.agent.agent.mcp.McpToolDef
import com.returngift.agent.agent.mcp.McpToolPolicy
import com.returngift.agent.agent.clarify.ClarificationManager
import com.returngift.agent.tool.BaseTool
import com.returngift.agent.tool.ToolParameter
import com.returngift.agent.tool.ToolResult
import com.returngift.agent.utils.XLog

/**
 * Exposes one MCP tool to the agent loop as a normal device tool — behind the trust
 * policy.
 *
 * Flow: classify (read / write / destructive) → decide (allow / ask / deny) → only
 * then call. An ASK parks the loop on the same clarification bridge `ask_user` uses,
 * so an approval is a real user decision in the chat, not a prompt-level hope.
 *
 * Output is fenced as untrusted data ([McpResultFormatter]) before the model sees it.
 */
class McpToolProxy(private val def: McpToolDef) : BaseTool() {

    companion object {
        private const val TAG = "McpToolProxy"
        private val ALLOWED_TYPES = setOf("string", "integer", "number", "boolean", "object", "array")

        /**
         * Seam for tests/hosts: `(question) -> true` approves.
         * Default answers through the chat clarification card, and treats a timeout
         * or cancellation as **no**.
         */
        @Volatile
        var confirmer: (String) -> Boolean = { question ->
            val answer = try {
                ClarificationManager.request(question, listOf("Allow", "Cancel"), false)
            } catch (e: Exception) {
                XLog.w("McpToolProxy", "approval dialog failed; denying", e)
                null
            }
            answer?.trim()?.startsWith("Allow", ignoreCase = true) == true ||
                answer?.trim()?.startsWith("Yes", ignoreCase = true) == true
        }
    }

    override fun getName(): String = def.qualifiedName

    override fun getParameters(): List<ToolParameter> = parametersFromSchema(def.inputSchema)

    override fun execute(params: @JvmSuppressWildcards Map<String, Any>): ToolResult {
        val arguments = toJsonObject(params)
        val preview = Json.write(arguments)

        when (val decision = McpToolPolicy.decide(def)) {
            is McpToolPolicy.Decision.Deny ->
                return ToolResult.error("Blocked: ${decision.reason}")

            is McpToolPolicy.Decision.Ask -> {
                val question = McpToolPolicy.approvalQuestion(def, preview)
                if (!confirmer(question)) {
                    return ToolResult.error(
                        "Not run: approval for '${def.name}' (${def.serverName}) was declined."
                    )
                }
            }

            McpToolPolicy.Decision.Allow -> Unit
        }

        return try {
            val result = McpRuntime.call(def.qualifiedName, arguments)
            val text = result.text
            if (result.isError) {
                ToolResult.error(
                    McpResultFormatter.format(def.serverName, def.name, isError = true, body = text)
                )
            } else {
                ToolResult.success(
                    McpResultFormatter.format(
                        def.serverName,
                        def.name,
                        isError = false,
                        body = text.ifBlank { "ok (no content returned by ${def.name})" },
                    )
                )
            }
        } catch (e: Exception) {
            XLog.w(TAG, "MCP call '${def.qualifiedName}' failed", e)
            ToolResult.error("MCP tool '${def.name}' failed: ${McpRedaction.redact(e.message)}")
        }
    }

    // ── schema → parameters ─────────────────────────────────────────────────

    private fun parametersFromSchema(schema: JsonValue?): List<ToolParameter> {
        val obj = schema as? JsonValue.Obj ?: return emptyList()
        val properties = obj.obj("properties") ?: return emptyList()
        val required = obj.arr("required")?.items
            ?.mapNotNull { (it as? JsonValue.Str)?.value }
            ?.toSet()
            .orEmpty()
        return properties.fields.map { (name, raw) ->
            val spec = raw as? JsonValue.Obj
            val type = spec?.str("type")?.takeIf { it in ALLOWED_TYPES } ?: "string"
            val enumHint = spec?.arr("enum")?.items
                ?.mapNotNull { (it as? JsonValue.Str)?.value }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(" | ")
            val description = buildString {
                append(spec?.str("description").orEmpty())
                if (enumHint != null) {
                    if (isNotEmpty()) append(' ')
                    append("One of: ").append(enumHint).append('.')
                }
            }
            ToolParameter(
                name = name,
                type = type,
                description = description,
                isRequired = name in required,
            )
        }
    }

    // ── arguments → JSON ────────────────────────────────────────────────────

    private fun toJsonObject(params: Map<String, Any>): JsonValue.Obj =
        JsonValue.Obj(params.entries.associate { (key, value) -> key to toJson(value) })

    private fun toJson(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Int -> JsonValue.Num(value.toDouble())
        is Long -> JsonValue.Num(value.toDouble())
        is Float -> JsonValue.Num(value.toDouble())
        is Double -> JsonValue.Num(value)
        is Number -> JsonValue.Num(value.toDouble())
        is Map<*, *> -> JsonValue.Obj(
            value.entries.mapNotNull { (k, v) -> (k as? String)?.let { it to toJson(v) } }.toMap()
        )
        is Iterable<*> -> JsonValue.Arr(value.map { toJson(it) })
        is Array<*> -> JsonValue.Arr(value.map { toJson(it) })
        else -> JsonValue.Str(value.toString())
    }

    /** Kept for tests/diagnostics: the exact JSON-RPC arguments we would send. */
    internal fun previewArguments(params: Map<String, Any>): String = Json.write(toJsonObject(params))
}
