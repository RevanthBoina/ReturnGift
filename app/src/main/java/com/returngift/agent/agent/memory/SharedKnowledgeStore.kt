// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.returngift.agent.ClawApplication
import com.returngift.agent.utils.XLog
import java.util.UUID

/**
 * Shared persistent knowledge store across ALL chat sessions.
 *
 * Inspired by OpenHands' persistent state and Browser Use's MessageManager memory:
 * - Stores user preferences, frequently accessed entities, authenticated states, and task facts.
 * - Unlike short-term chat context (per conversation), this knowledge is persistent and cross-chat.
 * - Dynamically injected into agent system prompt based on semantic relevance to current task.
 */
object SharedKnowledgeStore {

    private const val TAG = "SharedKnowledgeStore"
    private const val DB_NAME = "shared_knowledge.db"
    private const val DB_VERSION = 1
    private const val TABLE_KNOWLEDGE = "shared_knowledge"

    enum class Category {
        USER_PREFERENCE,
        APP_STATE,
        AUTH_STATE,
        TASK_FACT,
        ENTITY
    }

    data class KnowledgeItem(
        val id: String = UUID.randomUUID().toString(),
        val category: Category,
        val key: String,
        val value: String,
        val confidence: Float = 1.0f,
        val sourceTask: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        val lastUsedAt: Long = System.currentTimeMillis(),
        val useCount: Int = 1
    )

    private class DbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_KNOWLEDGE (
                    id TEXT PRIMARY KEY,
                    category TEXT NOT NULL,
                    key_name TEXT NOT NULL,
                    value_text TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    source_task TEXT,
                    created_at INTEGER NOT NULL,
                    last_used_at INTEGER NOT NULL,
                    use_count INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_knowledge_cat ON $TABLE_KNOWLEDGE(category)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_knowledge_key ON $TABLE_KNOWLEDGE(key_name)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Future schema migrations
        }
    }

    private var dbHelper: DbHelper? = null

    private fun getDb(): SQLiteDatabase {
        if (dbHelper == null) {
            synchronized(this) {
                if (dbHelper == null) {
                    dbHelper = DbHelper(ClawApplication.instance)
                }
            }
        }
        return dbHelper!!.writableDatabase
    }

    /**
     * Store or update a piece of knowledge across chats.
     */
    fun remember(
        category: Category,
        key: String,
        value: String,
        sourceTask: String = "",
        confidence: Float = 1.0f
    ) {
        try {
            val db = getDb()
            val now = System.currentTimeMillis()
            
            // Check existing
            val cursor = db.query(
                TABLE_KNOWLEDGE,
                arrayOf("id", "use_count"),
                "category = ? AND key_name = ?",
                arrayOf(category.name, key.trim()),
                null, null, null
            )
            
            val cv = ContentValues().apply {
                put("category", category.name)
                put("key_name", key.trim())
                put("value_text", value.trim())
                put("confidence", confidence)
                put("source_task", sourceTask)
                put("last_used_at", now)
            }

            if (cursor.moveToFirst()) {
                val id = cursor.getString(0)
                val count = cursor.getInt(1) + 1
                cv.put("use_count", count)
                db.update(TABLE_KNOWLEDGE, cv, "id = ?", arrayOf(id))
                XLog.d(TAG, "Updated shared knowledge: [$category] $key = $value (count: $count)")
            } else {
                val newId = UUID.randomUUID().toString()
                cv.put("id", newId)
                cv.put("created_at", now)
                cv.put("use_count", 1)
                db.insertWithOnConflict(TABLE_KNOWLEDGE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                XLog.d(TAG, "Stored new shared knowledge: [$category] $key = $value")
            }
            cursor.close()
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to store knowledge: ${e.message}", e)
        }
    }

    /**
     * Recall relevant knowledge items based on user prompt query tokens.
     */
    fun recall(query: String, maxResults: Int = 5): List<KnowledgeItem> {
        val results = mutableListOf<KnowledgeItem>()
        try {
            val db = getDb()
            val queryTokens = query.lowercase().split("\\s+".toRegex()).filter { it.length > 2 }
            
            val cursor = db.query(
                TABLE_KNOWLEDGE,
                null,
                null, null, null, null,
                "use_count DESC, last_used_at DESC",
                "50"
            )

            while (cursor.moveToNext() && results.size < maxResults) {
                val id = cursor.getString(cursor.getColumnIndexOrThrow("id"))
                val catStr = cursor.getString(cursor.getColumnIndexOrThrow("category"))
                val key = cursor.getString(cursor.getColumnIndexOrThrow("key_name"))
                val value = cursor.getString(cursor.getColumnIndexOrThrow("value_text"))
                val confidence = cursor.getFloat(cursor.getColumnIndexOrThrow("confidence"))
                val sourceTask = cursor.getString(cursor.getColumnIndexOrThrow("source_task")) ?: ""
                val createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
                val lastUsedAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_used_at"))
                val useCount = cursor.getInt(cursor.getColumnIndexOrThrow("use_count"))

                val item = KnowledgeItem(
                    id = id,
                    category = try { Category.valueOf(catStr) } catch (_: Exception) { Category.TASK_FACT },
                    key = key,
                    value = value,
                    confidence = confidence,
                    sourceTask = sourceTask,
                    createdAt = createdAt,
                    lastUsedAt = lastUsedAt,
                    useCount = useCount
                )

                // Match against query keywords or high-confidence user preferences
                val keyLower = key.lowercase()
                val valLower = value.lowercase()
                val isRelevant = item.category == Category.USER_PREFERENCE ||
                        queryTokens.any { token -> keyLower.contains(token) || valLower.contains(token) }

                if (isRelevant) {
                    results.add(item)
                }
            }
            cursor.close()
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to recall knowledge: ${e.message}", e)
        }
        return results
    }

    /**
     * Build system prompt context block from relevant shared knowledge.
     */
    fun getRelevantContext(query: String, maxTokens: Int = 300): String {
        val items = recall(query, maxResults = 8)
        if (items.isEmpty()) return ""

        return buildString {
            append("## Persistent Knowledge & User Preferences (Shared across chats):\n")
            items.forEach { item ->
                append("- [${item.category.name}] ${item.key}: ${item.value}\n")
            }
            append("\n")
        }
    }

    /**
     * Mark an item as used to reinforce its recency and frequency score.
     */
    fun recordUsed(key: String) {
        try {
            val db = getDb()
            db.execSQL(
                "UPDATE $TABLE_KNOWLEDGE SET use_count = use_count + 1, last_used_at = ? WHERE key_name = ?",
                arrayOf(System.currentTimeMillis(), key.trim())
            )
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to record knowledge usage: ${e.message}")
        }
    }
}
