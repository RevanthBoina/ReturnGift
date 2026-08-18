// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.ui.chat

import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog
import org.json.JSONArray

object QuickTaskRepository {

    private const val TAG = "QuickTaskRepo"
    private const val KEY_CUSTOM_CLOUD_TASKS = "custom_quick_tasks_cloud_v1"
    private const val KEY_CUSTOM_LOCAL_TASKS = "custom_quick_tasks_local_v1"

    val DEFAULT_CLOUD_ONLY_TASKS = listOf(
        "🌐 Open Reddit and search for returngift",
        "🎬 Search YouTube for funny cat fails",
        "📦 Install Telegram from Play Store",
        "🐦 Check what's trending on Twitter and tell me",
        "💬 Check my latest WhatsApp chat and summarize it",
        "📋 Copy the latest email subject and Google it",
        "📧 Write an email saying I'll be late today",
    )

    val DEFAULT_REASONING_TASKS = listOf(
        "📵 Check my notifications — anything important?",
        "📋 Read my clipboard and explain what it says",
        "🧹 Check my storage and apps — what can I delete?",
        "🔔 Read my notifications and summarize",
        "🔋 Check my battery and tell me if I need to charge",
    )

    val DEFAULT_DETERMINISTIC_TASKS = listOf(
        "💬 Send hi to Mom on WhatsApp",
        "📱 What apps do I have?",
        "🌡️ How hot is my phone?",
        "🔵 Is bluetooth on?",
        "🔋 How much battery left?",
        "📞 Call Mom",
        "💾 How much storage do I have?",
        "📲 What Android version am I running?",
    )

    fun getTasks(isLocalModel: Boolean): List<String> {
        val custom = loadCustomTasks(isLocalModel)
        if (custom != null) {
            return custom
        }
        return if (isLocalModel) {
            DEFAULT_REASONING_TASKS + DEFAULT_DETERMINISTIC_TASKS
        } else {
            DEFAULT_CLOUD_ONLY_TASKS + DEFAULT_REASONING_TASKS + DEFAULT_DETERMINISTIC_TASKS
        }
    }

    fun addTask(task: String, isLocalModel: Boolean) {
        val trimmed = task.trim()
        if (trimmed.isEmpty()) return
        val current = getTasks(isLocalModel).toMutableList()
        if (!current.contains(trimmed)) {
            current.add(0, trimmed)
            saveTasks(current, isLocalModel)
        }
    }

    fun removeTask(task: String, isLocalModel: Boolean) {
        val current = getTasks(isLocalModel).toMutableList()
        if (current.remove(task)) {
            saveTasks(current, isLocalModel)
        }
    }

    fun resetToDefaults(isLocalModel: Boolean) {
        val key = if (isLocalModel) KEY_CUSTOM_LOCAL_TASKS else KEY_CUSTOM_CLOUD_TASKS
        KVUtils.putString(key, "")
        XLog.i(TAG, "Reset quick tasks to defaults for local=$isLocalModel")
    }

    private fun loadCustomTasks(isLocalModel: Boolean): List<String>? {
        val key = if (isLocalModel) KEY_CUSTOM_LOCAL_TASKS else KEY_CUSTOM_CLOUD_TASKS
        val raw = KVUtils.getString(key, "")
        if (raw.isBlank()) return null
        return try {
            val jsonArray = JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getString(i))
            }
            if (list.isNotEmpty()) list else null
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to parse custom quick tasks", e)
            null
        }
    }

    private fun saveTasks(tasks: List<String>, isLocalModel: Boolean) {
        val key = if (isLocalModel) KEY_CUSTOM_LOCAL_TASKS else KEY_CUSTOM_CLOUD_TASKS
        val jsonArray = JSONArray()
        tasks.forEach { jsonArray.put(it) }
        KVUtils.putString(key, jsonArray.toString())
        XLog.i(TAG, "Saved ${tasks.size} custom quick tasks for local=$isLocalModel")
    }
}
