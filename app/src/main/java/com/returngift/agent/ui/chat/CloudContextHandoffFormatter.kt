// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.ui.chat

/**
 * Formats the visible chat transcript into the subset that Cloud task handoff should inherit.
 *
 * Operational shell noise (monitor status, permission prompts, progress logs) must stay
 * isolated from the conversational context that gets handed to the Cloud task agent.
 */
object CloudContextHandoffFormatter {

    /**
     * Read-back default for legacy messages without an explicit [ChatMessage.source]:
     * the source is inferred from the role. USER rows are never model-authored, so they
     * must default to USER (not MODEL) even when a handoff algorithm would be tempted to
     * treat an off-screen row as model output.
     */
    fun effectiveSource(message: ChatMessage): ChatMessage.Source {
        message.source?.let { return it }
        return when (message.role) {
            ChatMessage.Role.USER -> ChatMessage.Source.USER
            ChatMessage.Role.ASSISTANT -> ChatMessage.Source.MODEL
            ChatMessage.Role.SYSTEM -> ChatMessage.Source.SYSTEM_NOTICE
            ChatMessage.Role.TOOL_GROUP -> ChatMessage.Source.TOOL_RESULT
        }
    }

    fun conversationLines(messages: List<ChatMessage>): List<String> {
        return messages.mapNotNull { message ->
            val content = message.content.trim()
            if (content.isEmpty() || content == "...") {
                return@mapNotNull null
            }

            when (effectiveSource(message)) {
                ChatMessage.Source.USER -> "User: $content"
                ChatMessage.Source.MODEL -> "Assistant: $content"
                // A visible untrusted delimiter: externally-sourced content (web-fetch
                // results, vault research) must never be confused with a user instruction
                // or model output when the transcript is handed to the cloud agent.
                ChatMessage.Source.UNTRUSTED -> "[untrusted] $content"
                // Raw SYSTEM notices and tool-step rows stay out of the conversational
                // handoff entirely.
                ChatMessage.Source.SYSTEM_NOTICE, ChatMessage.Source.TOOL_RESULT -> null
            }
        }
    }
}
