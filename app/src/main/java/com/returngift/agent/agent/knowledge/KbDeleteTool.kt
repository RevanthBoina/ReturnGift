// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.knowledge

import com.returngift.agent.tool.BaseTool
import com.returngift.agent.tool.ToolParameter
import com.returngift.agent.tool.ToolResult

/**
 * kb_delete — removes a file from the vault (notes, plans, images, todos). Only
 * usable when the user explicitly asked to delete; KBManager.delete is traversal-safe
 * (resolve() strips ".."), so only vault files can be touched.
 *
 * Registered once in ToolRegistry.registerCommonTools() — tool registration is global
 * (LangChain4jToolBridge iterates getAllTools() with no channel filtering), so this is
 * automatically exposed to both local and cloud agent loops.
 */
class KbDeleteTool : BaseTool() {

    override fun getName() = "kb_delete"

    override fun getDisplayName() = "Delete Vault File"

    override fun getDescriptionEN() =
        "Delete a file from the knowledge base vault (notes, images, todos). " +
        "Only call this when the user explicitly asked to delete a stored file. " +
        "Reports the deleted path, or an honest error if the file does not exist."

    override fun getDescriptionCN() =
        "從知識庫刪除檔案（筆記、圖片、待辦）。僅在使用者明確要求刪除時呼叫。" +
        "回傳已刪除路徑；若檔案不存在則誠實回報錯誤。"

    override fun getParameters() = listOf(
        ToolParameter(
            "path", "string",
            "File path relative to vault root, e.g. 'notes/plan.md' or 'images/gemini-cat.png'",
            true
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        return try {
            val path = requireString(params, "path")
            KBManager.delete(path).fold(
                onSuccess = { ToolResult.success(it) },
                onFailure = { ToolResult.error(it.message ?: "kb_delete failed") }
            )
        } catch (e: IllegalArgumentException) {
            ToolResult.error("kb_delete: missing required param — ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("kb_delete error: ${e.message}")
        }
    }
}
