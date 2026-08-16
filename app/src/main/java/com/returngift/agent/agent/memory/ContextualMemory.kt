// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.memory

import com.returngift.agent.agent.embedding.EmbeddingService
import com.returngift.agent.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Three-layer memory architecture for ReturnGift agent.
 * 
 * Layer 1: Short-term - Current conversation context (in-memory, per-session)
 * Layer 2: Structured facts - Entities, preferences, app state (persistent)
 * Layer 3: Historical context - Past tasks, outcomes (persistent with decay)
 * 
 * Integrates with KBManager for persistent storage and uses embedding
 * for semantic similarity search.
 */
object ContextualMemory {

    private const val TAG = "ContextualMemory"
    private const val MEMORY_FILE = "contextual_memory.json"
    
    // Layer 2: Structured facts (entities, preferences)
    private val structuredFacts = ConcurrentHashMap<String, Fact>()
    
    // Layer 3: Historical memories (past tasks)
    private val historicalMemories = ConcurrentHashMap<String, Memory>()
    
    // Layer 1: Short-term conversation buffer
    private val conversationBuffer = Collections.synchronizedList(mutableListOf<ConversationTurn>())
    
    // Embedding cache for memory retrieval
    private val embeddingCache = ConcurrentHashMap<String, FloatArray>()
    
    // Configuration
    private var maxShortTermTurns = 10
    private var recencyDecayDays = 7
    private var confidenceThreshold = 0.5f
    
    data class Fact(
        val id: String,
        val type: FactType,
        val value: String,
        val confidence: Float,
        val source: String,
        val lastUpdated: Long,
        val metadata: Map<String, String> = emptyMap()
    )
    
    enum class FactType {
        USER_PREFERENCE,
        APP_STATE,
        CONTACT_INFO,
        RECENT_ACTION,
        ENTITY
    }
    
    data class Memory(
        val id: String,
        val taskDescription: String,
        val outcome: Outcome,
        val entities: List<String>,
        val skillUsed: String?,
        val timestamp: Long,
        val confidence: Float,
        val embedding: FloatArray? = null
    )
    
    enum class Outcome {
        SUCCESS,
        FAILURE,
        PARTIAL,
        CANCELLED
    }
    
    data class ConversationTurn(
        val role: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    fun addTurn(role: String, content: String) {
        conversationBuffer.add(ConversationTurn(role, content))
        if (conversationBuffer.size > maxShortTermTurns) {
            conversationBuffer.removeAt(0)
        }
    }
    
    fun getShortTermContext(maxTurns: Int = maxShortTermTurns): String {
        val turns = conversationBuffer.takeLast(maxTurns)
        return turns.joinToString("\n") { "[${it.role}]: ${it.content}" }
    }
    
    fun extractAndStoreFacts(
        taskDescription: String,
        skillId: String?,
        outcome: Outcome,
        entities: Map<String, String>,
        confidence: Float
    ) {
        val timestamp = System.currentTimeMillis()
        
        for ((key, value) in entities) {
            val factType = inferFactType(key)
            val factId = "${factType.name.lowercase()}_${normalizeKey(key)}"
            
            val fact = Fact(
                id = factId,
                type = factType,
                value = value,
                confidence = confidence,
                source = skillId ?: "extraction",
                lastUpdated = timestamp,
                metadata = mapOf(
                    "original_task" to taskDescription,
                    "entity_key" to key
                )
            )
            
            structuredFacts[factId] = fact
            embeddingCache[factId] = EmbeddingService.embed("$factType: $value")

            // Bridge to cross-chat SharedKnowledgeStore
            val sharedCat = when (factType) {
                FactType.USER_PREFERENCE -> SharedKnowledgeStore.Category.USER_PREFERENCE
                FactType.APP_STATE -> SharedKnowledgeStore.Category.APP_STATE
                FactType.CONTACT_INFO -> SharedKnowledgeStore.Category.ENTITY
                FactType.RECENT_ACTION -> SharedKnowledgeStore.Category.TASK_FACT
                FactType.ENTITY -> SharedKnowledgeStore.Category.ENTITY
            }
            SharedKnowledgeStore.remember(sharedCat, key, value, sourceTask = taskDescription, confidence = confidence)
            
            XLog.d(TAG, "Stored fact: $factId = $value (confidence: $confidence)")
        }
        
        if (skillId != null) {
            val memoryId = "memory_${timestamp}_${skillId.hashCode()}"
            val memory = Memory(
                id = memoryId,
                taskDescription = taskDescription,
                outcome = outcome,
                entities = entities.values.toList(),
                skillUsed = skillId,
                timestamp = timestamp,
                confidence = confidence,
                embedding = EmbeddingService.embed(taskDescription)
            )
            historicalMemories[memoryId] = memory
        }
        
        applyRecencyDecay()
    }
    
    fun retrieveRelevantMemories(
        query: String,
        topK: Int = 5,
        recencyWeight: Float = 0.3f,
        confidenceWeight: Float = 0.4f,
        relevanceWeight: Float = 0.3f
    ): List<MemoryWithScore> {
        if (historicalMemories.isEmpty()) return emptyList()
        
        val queryEmbedding = EmbeddingService.embed(query)
        val now = System.currentTimeMillis()
        val decayMs = recencyDecayDays * 24 * 60 * 60 * 1000L
        
        return historicalMemories.values
            .map { memory ->
                val semanticScore = memory.embedding?.let {
                    EmbeddingService.cosineSimilarity(queryEmbedding, it)
                } ?: 0f
                
                val ageMs = now - memory.timestamp
                val recencyScore = (1f - (ageMs.toFloat() / decayMs)).coerceIn(0f, 1f)
                val confidenceScore = memory.confidence
                
                val combinedScore = 
                    semanticScore * relevanceWeight +
                    recencyScore * recencyWeight +
                    confidenceScore * confidenceWeight
                
                MemoryWithScore(memory, combinedScore, semanticScore, recencyScore, confidenceScore)
            }
            .filter { it.combinedScore >= confidenceThreshold }
            .sortedByDescending { it.combinedScore }
            .take(topK)
            .also { results ->
                XLog.d(TAG, "Retrieved ${results.size} relevant memories for: $query")
            }
    }
    
    fun retrieveRelevantFacts(query: String, topK: Int = 3): List<Fact> {
        if (structuredFacts.isEmpty()) return emptyList()
        
        val queryEmbedding = EmbeddingService.embed(query)
        val now = System.currentTimeMillis()
        val decayMs = recencyDecayDays * 24 * 60 * 60 * 1000L
        
        return structuredFacts.values
            .map { fact ->
                val embedding = embeddingCache[fact.id] ?: EmbeddingService.embed("${fact.type}: ${fact.value}").also {
                    embeddingCache[fact.id] = it
                }
                val semanticScore = EmbeddingService.cosineSimilarity(queryEmbedding, embedding)
                val recencyScore = (1f - ((now - fact.lastUpdated).toFloat() / decayMs)).coerceIn(0f, 1f)
                val combinedScore = semanticScore * 0.6f + fact.confidence * 0.2f + recencyScore * 0.2f
                
                fact to combinedScore
            }
            .filter { it.second >= confidenceThreshold }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }
    
    fun buildContextPrompt(query: String, maxTokens: Int = 500): String {
        val memories = retrieveRelevantMemories(query, topK = 3)
        val facts = retrieveRelevantFacts(query, topK = 3)
        
        val sb = StringBuilder()
        var tokens = 0
        
        if (facts.isNotEmpty()) {
            sb.append("\n## Relevant Facts\n")
            for (fact in facts) {
                val line = "- ${fact.type.name.lowercase()}: ${fact.value}"
                tokens += line.length / 4
                if (tokens > maxTokens) break
                sb.appendLine(line)
            }
        }
        
        if (memories.isNotEmpty()) {
            sb.append("\n## Relevant Past Tasks\n")
            for (memWithScore in memories) {
                val memory = memWithScore.memory
                val line = "- ${memory.taskDescription}: ${memory.outcome.name} (${(memWithScore.combinedScore * 100).toInt()}%)"
                tokens += line.length / 4
                if (tokens > maxTokens) break
                sb.appendLine(line)
            }
        }
        
        return sb.toString()
    }
    
    fun clearShortTerm() {
        conversationBuffer.clear()
        XLog.d(TAG, "Cleared short-term memory")
    }
    
    fun clearAll() {
        conversationBuffer.clear()
        structuredFacts.clear()
        historicalMemories.clear()
        embeddingCache.clear()
        XLog.d(TAG, "Cleared all memory layers")
    }
    
    fun getStats(): MemoryStats {
        return MemoryStats(
            shortTermTurns = conversationBuffer.size,
            structuredFacts = structuredFacts.size,
            historicalMemories = historicalMemories.size
        )
    }
    
    fun save() {
        try {
            // TODO: Deprecated API usage. Replace with context-aware path (e.g. context.filesDir).
            val file = File(android.os.Environment.getExternalStorageDirectory(), MEMORY_FILE)
            
            val json = JSONObject()
            val factsArray = JSONArray()
            for (fact in structuredFacts.values) {
                val factJson = JSONObject()
                factJson.put("id", fact.id)
                factJson.put("type", fact.type.name)
                factJson.put("value", fact.value)
                factJson.put("confidence", fact.confidence.toDouble())
                factJson.put("source", fact.source)
                factJson.put("lastUpdated", fact.lastUpdated)
                factsArray.put(factJson)
            }
            json.put("facts", factsArray)
            
            val memoriesArray = JSONArray()
            for (memory in historicalMemories.values) {
                val memJson = JSONObject()
                memJson.put("id", memory.id)
                memJson.put("taskDescription", memory.taskDescription)
                memJson.put("outcome", memory.outcome.name)
                memJson.put("entities", JSONArray(memory.entities))
                memJson.put("skillUsed", memory.skillUsed)
                memJson.put("timestamp", memory.timestamp)
                memJson.put("confidence", memory.confidence.toDouble())
                memoriesArray.put(memJson)
            }
            json.put("memories", memoriesArray)
            
            file.writeText(json.toString())
            XLog.d(TAG, "Saved memory state")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to save memory state", e)
        }
    }
    
    fun load() {
        try {
            // TODO: Deprecated API usage. Replace with context-aware path (e.g. context.filesDir).
            val file = File(android.os.Environment.getExternalStorageDirectory(), MEMORY_FILE)
            if (!file.exists()) return
            
            val json = JSONObject(file.readText())
            
            val factsArray = json.getJSONArray("facts")
            for (i in 0 until factsArray.length()) {
                val factJson = factsArray.getJSONObject(i)
                val fact = Fact(
                    id = factJson.getString("id"),
                    type = FactType.valueOf(factJson.getString("type")),
                    value = factJson.getString("value"),
                    confidence = factJson.getDouble("confidence").toFloat(),
                    source = factJson.getString("source"),
                    lastUpdated = factJson.getLong("lastUpdated")
                )
                structuredFacts[fact.id] = fact
            }
            
            val memoriesArray = json.getJSONArray("memories")
            for (i in 0 until memoriesArray.length()) {
                val memJson = memoriesArray.getJSONObject(i)
                val entitiesArray = memJson.getJSONArray("entities")
                val entities = (0 until entitiesArray.length()).map { entitiesArray.getString(it) }
                
                val memory = Memory(
                    id = memJson.getString("id"),
                    taskDescription = memJson.getString("taskDescription"),
                    outcome = Outcome.valueOf(memJson.getString("outcome")),
                    entities = entities,
                    skillUsed = memJson.optString("skillUsed", null),
                    timestamp = memJson.getLong("timestamp"),
                    confidence = memJson.getDouble("confidence").toFloat()
                )
                historicalMemories[memory.id] = memory
            }
            
            // TODO: Embeddings should be recomputed via EmbeddingService after load if they were not serialized.
            XLog.i(TAG, "Loaded ${structuredFacts.size} facts and ${historicalMemories.size} memories")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to load memory state", e)
        }
    }
    
    private fun applyRecencyDecay() {
        val cutoff = System.currentTimeMillis() - (recencyDecayDays * 24 * 60 * 60 * 1000L)
        
        val toRemove = historicalMemories.filter { it.value.timestamp < cutoff }
        for (key in toRemove.keys) {
            historicalMemories.remove(key)
        }
        
        for ((key, fact) in structuredFacts) {
            if (fact.lastUpdated < cutoff) {
                val decayedFact = fact.copy(confidence = fact.confidence * 0.8f)
                if (decayedFact.confidence < 0.1f) {
                    structuredFacts.remove(key)
                } else {
                    structuredFacts[key] = decayedFact
                }
            }
        }
        
        if (toRemove.isNotEmpty()) {
            XLog.d(TAG, "Applied recency decay: removed ${toRemove.size} old memories")
        }
    }
    
    private fun inferFactType(key: String): FactType {
        return when {
            key.contains("pref", ignoreCase = true) -> FactType.USER_PREFERENCE
            key.contains("app", ignoreCase = true) -> FactType.APP_STATE
            key.contains("contact", ignoreCase = true) || key.contains("recipient", ignoreCase = true) -> FactType.CONTACT_INFO
            key.contains("action", ignoreCase = true) || key.contains("did", ignoreCase = true) -> FactType.RECENT_ACTION
            else -> FactType.ENTITY
        }
    }
    
    private fun normalizeKey(key: String): String {
        return key.lowercase()
            .replace(Regex("[^a-z0-9]"), "_")
            .take(50)
    }
    
    data class MemoryWithScore(
        val memory: Memory,
        val combinedScore: Float,
        val semanticScore: Float,
        val recencyScore: Float,
        val confidenceScore: Float
    )
    
    data class MemoryStats(
        val shortTermTurns: Int,
        val structuredFacts: Int,
        val historicalMemories: Int
    )
}
