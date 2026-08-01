// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.anomaly

import android.content.Context
import com.returngift.agent.agent.planner.GraphState
import com.returngift.agent.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Behavioral Anomaly Detection - learns normal behavior, detects anomalies.
 */
class BehavioralAnomalyDetector(private val context: Context) {

    private val TAG = "BehavioralAnomalyDetector"
    private val PROFILE_FILE = "behavior_profile.json"
    
    var confidenceThreshold = 0.7f
    private val decayFactor = 0.95f
    private val baselineWindowDays = 7
    
    private val appUsageProfile = ConcurrentHashMap<String, AppUsageStats>()
    private val contactProfile = ConcurrentHashMap<String, ContactStats>()
    private val actionProfile = ConcurrentHashMap<String, ActionStats>()
    private val timingProfile = ConcurrentHashMap<Int, TimingStats>()
    
    private val sensitivePatterns = listOf(
        SensitivePattern("send_message", listOf("message", "send", "text"), RiskLevel.HIGH),
        SensitivePattern("make_call", listOf("call", "phone", "dial"), RiskLevel.HIGH),
        SensitivePattern("open_banking", listOf("bank", "pay", "transfer", "financial"), RiskLevel.CRITICAL),
        SensitivePattern("delete_data", listOf("delete", "remove", "clear"), RiskLevel.HIGH),
        SensitivePattern("share_location", listOf("location", "share", "gps"), RiskLevel.MEDIUM),
        SensitivePattern("modify_settings", listOf("settings", "permission"), RiskLevel.MEDIUM)
    )
    
    data class AppUsageStats(val packageName: String, var launchCount: Int = 0, var lastUsed: Long = 0, var typicalTimeOfDay: Set<Int> = emptySet())
    data class ContactStats(val contactId: String, var name: String, var interactionCount: Int = 0, var lastInteraction: Long = 0)
    data class ActionStats(val actionType: String, var totalExecutions: Int = 0, var successCount: Int = 0, var lastExecuted: Long = 0)
    data class TimingStats(val hour: Int, var actionCount: Int = 0, var successRate: Float = 1f)
    data class SensitivePattern(val type: String, val keywords: List<String>, val riskLevel: RiskLevel)
    enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }
    
    fun calculateAnomalyScore(action: String, riskLevel: GraphState.RiskLevel = GraphState.RiskLevel.MEDIUM, context: Map<String, String> = emptyMap()): Float {
        val lower = action.lowercase()
        var baseScore = when (riskLevel) {
            GraphState.RiskLevel.LOW -> 0.2f
            GraphState.RiskLevel.MEDIUM -> 0.4f
            GraphState.RiskLevel.HIGH -> 0.6f
            GraphState.RiskLevel.CRITICAL -> 0.8f
        }
        
        val sensitiveMatch = sensitivePatterns.find { it.keywords.any { kw -> lower.contains(kw) } }
        if (sensitiveMatch != null) {
            baseScore += when (sensitiveMatch.riskLevel) {
                RiskLevel.HIGH -> 0.2f
                RiskLevel.CRITICAL -> 0.3f
                else -> 0.1f
            }
        }
        
        val contactId = context["contact_id"]
        if (contactId != null) {
            val contact = contactProfile[contactId]
            if (contact != null) {
                baseScore -= calculateContactFamiliarity(contact) * 0.3f
            } else {
                baseScore += 0.2f
            }
        }
        
        val packageName = context["package"]
        if (packageName != null) {
            val app = appUsageProfile[packageName]
            if (app != null) {
                baseScore -= calculateAppFamiliarity(app) * 0.2f
            } else {
                baseScore += 0.15f
            }
        }
        
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timingStats = timingProfile[currentHour]
        if (timingStats != null && timingStats.actionCount > 10) {
            baseScore -= timingStats.successRate * 0.1f
        }
        
        return baseScore.coerceIn(0f, 1f)
    }
    
    fun recordExecution(actionType: String, success: Boolean, durationMs: Long, context: Map<String, String> = emptyMap()) {
        val now = System.currentTimeMillis()
        val stats = actionProfile.getOrPut(actionType) { ActionStats(actionType) }
        actionProfile[actionType] = stats.copy(
            totalExecutions = stats.totalExecutions + 1,
            successCount = stats.successCount + if (success) 1 else 0,
            lastExecuted = now
        )
        
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timingStats = timingProfile.getOrPut(currentHour) { TimingStats(currentHour) }
        val newCount = timingStats.actionCount + 1
        timingProfile[currentHour] = timingStats.copy(
            actionCount = newCount,
            successRate = ((timingStats.successRate * timingStats.actionCount) + if (success) 1f else 0f) / newCount
        )
        
        context["contact_id"]?.let { contactId ->
            val contact = contactProfile.getOrPut(contactId) { ContactStats(contactId, context["contact_name"] ?: "Unknown") }
            contactProfile[contactId] = contact.copy(interactionCount = contact.interactionCount + 1, lastInteraction = now)
        }
        
        context["package"]?.let { pkg ->
            val app = appUsageProfile.getOrPut(pkg) { AppUsageStats(pkg) }
            appUsageProfile[pkg] = app.copy(launchCount = app.launchCount + 1, lastUsed = now, typicalTimeOfDay = app.typicalTimeOfDay + currentHour)
        }
        
        applyDecay()
    }
    
    fun getConfirmationMessage(action: String, anomalyScore: Float, context: Map<String, String> = emptyMap()): String {
        return when {
            anomalyScore < 0.3f -> ""
            anomalyScore < 0.5f -> "This action seems unusual. Continue?"
            anomalyScore < 0.7f -> context["contact_name"]?.let { "You're about to contact $it. Continue?" } ?: "This action is different from your usual behavior. Continue?"
            else -> "This action may be risky. Please confirm."
        }
    }
    
    fun requiresConfirmation(action: String, riskLevel: GraphState.RiskLevel = GraphState.RiskLevel.MEDIUM, context: Map<String, String> = emptyMap()): Boolean {
        return calculateAnomalyScore(action, riskLevel, context) > confidenceThreshold
    }
    
    fun getFrequentApps(limit: Int = 10) = appUsageProfile.values.sortedByDescending { it.launchCount }.take(limit)
    fun getFrequentContacts(limit: Int = 10) = contactProfile.values.sortedByDescending { it.interactionCount }.take(limit)
    fun getActionStats() = actionProfile.toMap()
    
    fun save() {
        try {
            val file = File(context.getExternalFilesDir(null), PROFILE_FILE)
            val json = JSONObject()
            
            val appsArray = JSONArray()
            appUsageProfile.forEach { (_, app) ->
                appsArray.put(JSONObject().apply {
                    put("packageName", app.packageName)
                    put("launchCount", app.launchCount)
                    put("lastUsed", app.lastUsed)
                })
            }
            json.put("apps", appsArray)
            
            val contactsArray = JSONArray()
            contactProfile.forEach { (_, contact) ->
                contactsArray.put(JSONObject().apply {
                    put("contactId", contact.contactId)
                    put("name", contact.name)
                    put("interactionCount", contact.interactionCount)
                })
            }
            json.put("contacts", contactsArray)
            
            val actionsArray = JSONArray()
            actionProfile.forEach { (_, action) ->
                actionsArray.put(JSONObject().apply {
                    put("actionType", action.actionType)
                    put("totalExecutions", action.totalExecutions)
                    put("successCount", action.successCount)
                })
            }
            json.put("actions", actionsArray)
            
            file.writeText(json.toString(2))
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to save profile", e)
        }
    }
    
    fun load() {
        try {
            val file = File(context.getExternalFilesDir(null), PROFILE_FILE)
            if (!file.exists()) return
            
            val json = JSONObject(file.readText())
            
            json.optJSONArray("apps")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val appJson = arr.getJSONObject(i)
                    appUsageProfile[appJson.getString("packageName")] = AppUsageStats(
                        appJson.getString("packageName"), appJson.optInt("launchCount", 0), appJson.optLong("lastUsed", 0)
                    )
                }
            }
            
            json.optJSONArray("contacts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    contactProfile[c.getString("contactId")] = ContactStats(c.getString("contactId"), c.optString("name", "Unknown"), c.optInt("interactionCount", 0), c.optLong("lastInteraction", 0))
                }
            }
            
            json.optJSONArray("actions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    actionProfile[a.getString("actionType")] = ActionStats(a.getString("actionType"), a.optInt("totalExecutions", 0), a.optInt("successCount", 0), a.optLong("lastExecuted", 0))
                }
            }
            
            XLog.i(TAG, "Loaded: ${appUsageProfile.size} apps, ${contactProfile.size} contacts, ${actionProfile.size} actions")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to load profile", e)
        }
    }
    
    fun clearProfile() {
        appUsageProfile.clear()
        contactProfile.clear()
        actionProfile.clear()
        timingProfile.clear()
    }
    
    private fun calculateContactFamiliarity(contact: ContactStats): Float {
        val recencyDays = (System.currentTimeMillis() - contact.lastInteraction) / (24 * 60 * 60 * 1000)
        return kotlin.math.exp(-recencyDays / 7f).coerceIn(0f, 1f)
    }
    
    private fun calculateAppFamiliarity(app: AppUsageStats): Float {
        val recencyDays = (System.currentTimeMillis() - app.lastUsed) / (24 * 60 * 60 * 1000)
        return kotlin.math.exp(-recencyDays / 7f).coerceIn(0f, 1f)
    }
    
    private fun applyDecay() {
        val cutoff = System.currentTimeMillis() - (baselineWindowDays * 24 * 60 * 60 * 1000L)
        actionProfile.entries.removeIf { it.value.lastExecuted < cutoff && it.value.totalExecutions < 3 }
    }
    
    companion object {
        @Volatile private var instance: BehavioralAnomalyDetector? = null
        fun getInstance(context: Context) = instance ?: synchronized(this) { instance ?: BehavioralAnomalyDetector(context).also { instance = it } }
    }
}
