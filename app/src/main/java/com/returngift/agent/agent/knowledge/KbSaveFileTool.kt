// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.knowledge

import android.util.Base64
import com.returngift.agent.tool.BaseTool
import com.returngift.agent.tool.ToolParameter
import com.returngift.agent.tool.ToolResult

/**
 * save_file — persists BINARY content into the vault, the counterpart to kb_write's
 * text-only writes. The model supplies base64-encoded bytes plus a path whose
 * extension determines the MIME type (KBManager.mimeOf).
 *
 * Registered once in ToolRegistry.registerCommonTools() — tool registration is global
 * (LangChain4jToolBridge iterates getAllTools() with no channel filtering), so this is
 * automatically exposed to both local and cloud agent loops.
 */
class KbSaveFileTool : BaseTool() {

    override fun getName() = "save_file"

    override fun getDisplayName() = "Save File"

    override fun getDescriptionEN() =
        "Save a binary file into the knowledge base vault (screenshots, images, PDFs, any " +
        "non-text artifact). Provide the content base64-encoded and a path whose extension " +
        "matches the real format (e.g. 'screenshots/shot.png', 'exports/report.pdf'). " +
        "For plain text or Markdown notes use kb_write instead."

    override fun getDescriptionCN() =
        "將二進制文件保存到知識庫（截圖、圖片、PDF等非文本內容）。內容需base64編碼，" +
        "路徑擴展名需與真實格式一致。純文本或Markdown筆記請使用kb_write。"

    override fun getParameters() = listOf(
        ToolParameter(
            "path", "string",
            "File path relative to vault root, e.g. 'screenshots/2026-08-21-home.png' — the extension determines the MIME type",
            true
        ),
        ToolParameter(
            "content_base64", "string",
            "File content, base64-encoded (standard alphabet, no line wrapping required)",
            true
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        return try {
            val path = requireString(params, "path")
            val encoded = requireString(params, "content_base64")
            if (encoded.length > MAX_BASE64_CHARS) {
                return ToolResult.error(
                    "save_file: content too large (${encoded.length} base64 chars, max $MAX_BASE64_CHARS)"
                )
            }
            val bytes = try {
                Base64.decode(encoded, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                return ToolResult.error("save_file: content_base64 is not valid base64 — ${e.message}")
            }
            val result = KBManager.saveBytes(path, bytes)
            result.fold(
                onSuccess = { ToolResult.success(it) },
                onFailure = { ToolResult.error(it.message ?: "save_file failed") }
            )
        } catch (e: IllegalArgumentException) {
            ToolResult.error("save_file: missing required param — ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("save_file error: ${e.message}")
        }
    }

    companion object {
        /** ~6 MB of binary — larger payloads don't belong in an LLM tool call. */
        private const val MAX_BASE64_CHARS = 8_000_000
    }
}
