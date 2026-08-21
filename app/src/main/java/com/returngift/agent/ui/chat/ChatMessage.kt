// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.ui.chat

import androidx.compose.runtime.Immutable

@Immutable
data class ChatMessage(
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolSteps: List<ToolStep>? = null,
    val modelName: String? = null,
    val isEdited: Boolean = false,
    /** Vault-relative path when this message announces a saved artifact (SYSTEM role). */
    val artifactPath: String? = null,
    /** MIME type of [artifactPath] (KBManager.mimeOf) — drives preview + open intent. */
    val artifactMime: String? = null,
    val id: String = java.util.UUID.randomUUID().toString(),
) {
    enum class Role { USER, ASSISTANT, SYSTEM, TOOL_GROUP }
}

@Immutable
data class ToolStep(
    val toolName: String,
    val summary: String,
    val success: Boolean = false
)
