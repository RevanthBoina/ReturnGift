// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.ui.chat

import android.content.Context
import com.returngift.agent.utils.KVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns persisted conversation identity and markdown-backed history operations.
 *
 * This keeps Activity code focused on UI wiring instead of direct KV/markdown glue.
 */
class ConversationStore(
    private val context: Context
) {

    companion object {
        private const val CURRENT_CONVERSATION_ID_KEY = "CURRENT_CONVERSATION_ID"

        private fun newConversationId(): String = "chat_${System.currentTimeMillis()}"
    }

    data class SessionSnapshot(
        val conversationId: String,
        val messages: List<ChatMessage>,
        val conversations: List<ChatHistoryManager.ConversationSummary>
    )

    var currentConversationId: String = KVUtils.getString(CURRENT_CONVERSATION_ID_KEY, "")
        .takeIf { it.isNotEmpty() }
        ?: newConversationId()
        private set

    suspend fun refreshSidebar(): List<ChatHistoryManager.ConversationSummary> = withContext(Dispatchers.IO) {
        return@withContext ChatHistoryManager.listConversations(context)
    }

    suspend fun restoreLastConversation(): SessionSnapshot? = withContext(Dispatchers.IO) {
        val conversations = refreshSidebar()
        val match = conversations.firstOrNull { it.id == currentConversationId } ?: return@withContext null
        return@withContext SessionSnapshot(
            conversationId = currentConversationId,
            messages = ChatHistoryManager.load(match.file),
            conversations = conversations
        )
    }

    suspend fun saveCurrent(messages: List<ChatMessage>, modelName: String): List<ChatHistoryManager.ConversationSummary> = withContext(Dispatchers.IO) {
        ChatHistoryManager.save(context, currentConversationId, messages, modelName)
        persistCurrentConversationId()
        return@withContext refreshSidebar()
    }

    suspend fun startNewConversation(
        currentMessages: List<ChatMessage>,
        modelName: String
    ): SessionSnapshot = withContext(Dispatchers.IO) {
        saveCurrent(currentMessages, modelName)
        currentConversationId = newConversationId()
        persistCurrentConversationId()
        return@withContext SessionSnapshot(
            conversationId = currentConversationId,
            messages = emptyList(),
            conversations = refreshSidebar()
        )
    }

    suspend fun openConversation(
        target: ChatHistoryManager.ConversationSummary,
        currentMessages: List<ChatMessage>,
        modelName: String
    ): SessionSnapshot = withContext(Dispatchers.IO) {
        saveCurrent(currentMessages, modelName)
        currentConversationId = target.id
        persistCurrentConversationId()
        return@withContext SessionSnapshot(
            conversationId = currentConversationId,
            messages = ChatHistoryManager.load(target.file),
            conversations = refreshSidebar()
        )
    }

    suspend fun renameConversation(
        target: ChatHistoryManager.ConversationSummary,
        newTitle: String
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext ChatHistoryManager.rename(target.file, newTitle)
    }

    suspend fun deleteConversation(target: ChatHistoryManager.ConversationSummary): Boolean = withContext(Dispatchers.IO) {
        return@withContext ChatHistoryManager.delete(target.file)
    }

    private fun persistCurrentConversationId() {
        KVUtils.putString(CURRENT_CONVERSATION_ID_KEY, currentConversationId)
    }
}
