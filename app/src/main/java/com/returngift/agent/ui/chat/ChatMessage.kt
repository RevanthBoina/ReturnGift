// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.ui.chat

data class ChatMessage(
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolSteps: List<ToolStep>? = null,
    val modelName: String? = null
) {
    enum class Role { USER, ASSISTANT, SYSTEM, TOOL_GROUP }
}

data class ToolStep(
    val toolName: String,
    val summary: String,
    val success: Boolean = false
)
