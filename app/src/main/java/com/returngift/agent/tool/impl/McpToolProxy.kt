// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool.impl

import com.returngift.agent.agent.mcp.Json
import com.returngift.agent.agent.mcp.JsonValue
import com.returngift.agent.agent.mcp.McpRuntime
import com.returngift.agent.agent.mcp.McpToolDef
import com.returngift.agent.tool.BaseTool
import com.returngift.agent.tool.ToolParameter
import com.returngift.agent.tool.ToolResult
import com.returngift.agent.utils.XLog

/**
 * Exposes one MCP tool to the agent loop as a normal device tool.
 *
 * The proxy is deliberately thin: schema → [ToolParameter]s, arguments → JSON,
 * result → [ToolResult]. Everything protocol-specific stays in `agent/mcp`, so a
 * server that misbehaves yields a tool error, not a crashed task.
 */
class McpToolProxy(private val def: McpToolDef) : BaseTool() {

    companion object {
        private const val TAG = "McpToolProxy"
        private val ALLOWED_TYPES = setOf("string", "integer", "number", "boolean", "object", "array")
    }

    override fun getName(): String = def.qualifiedName

    override fun getParameters(): List<ToolParameter> = parametersFromSchema(def.inputSchema)

    override fun execute(params: @JvmSuppressWildcards Map<String, Any>): ToolResult {
        return try {
            val result = McpRuntime.call(def.qualifiedName, toJsonObject(params))
            if (result.isError) {
                ToolResult.error(result.text.ifBlank { "MCP tool '${def.name}' reported an error" })
            } else {
                ToolResult.success(result.text.ifBlank { "ok (no content returned by ${def.name})" })
            }
        } catch (e: Exception) {
            XLog.w(TAG, "MCP call '${def.qualifiedName}' failed", e)
            ToolResult.error("MCP tool '${def.name}' failed: ${e.message}")
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
            ToolParameter(
                name = name,
                type = type,
                description = spec?.str("description").orEmpty(),
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
