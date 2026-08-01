// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.embedding

import com.returngift.agent.ClawApplication
import com.returngift.agent.agent.skill.Skill
import com.returngift.agent.agent.skill.SkillRegistry
import com.returngift.agent.agent.skill.YamlSkill
import com.returngift.agent.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent versioned index for skill embeddings.
 * 
 * Automatically updated when YAML skills change.
 * Provides fast similarity search for semantic skill retrieval.
 */
class SkillEmbeddingIndex {

    data class SkillEmbedding(
        val skillId: String,
        val triggerEmbedding: FloatArray,
        val descriptionEmbedding: FloatArray,
        val metadataEmbedding: FloatArray,
        val combinedEmbedding: FloatArray,
        val version: Int,
        val lastUpdated: Long
    )
    
    private val embeddings = ConcurrentHashMap<String, SkillEmbedding>()
    private var indexVersion = 0
    private var lastIndexUpdate = 0L
    
    private val indexFile: File
        get() = File(ClawApplication.instance.getExternalFilesDir(null), INDEX_FILE)
    
    companion object {
        private const val TAG = "SkillEmbeddingIndex"
        private const val INDEX_FILE = "skill_embeddings_v2.json"
        private const val CURRENT_FORMAT_VERSION = 2
        
        @Volatile
        private var instance: SkillEmbeddingIndex? = null
        
        fun getInstance(): SkillEmbeddingIndex {
            return instance ?: synchronized(this) {
                instance ?: SkillEmbeddingIndex().also { instance = it }
            }
        }
    }
    
    init {
        load()
    }
    
    /**
     * Build embeddings for all registered skills.
     * Should be called when skills are loaded or updated.
     */
    fun rebuild() {
        XLog.i(TAG, "Rebuilding skill embedding index...")
        embeddings.clear()
        
        val allTexts = mutableListOf<String>()
        
        // Process built-in skills
        for (skill in SkillRegistry.getAll()) {
            val texts = collectSkillTexts(skill)
            allTexts.addAll(texts)
            
            val embedding = buildSkillEmbedding(skill, texts)
            embeddings[skill.id] = embedding
        }
        
        // Process YAML skill metadata
        for (skill in SkillRegistry.getAll()) {
            val yamlMeta = SkillRegistry.getYamlMeta(skill.id)
            if (yamlMeta != null) {
                val texts = collectYamlTexts(yamlMeta)
                allTexts.addAll(texts)
            }
        }
        
        // Learn vocabulary from all texts
        EmbeddingService.learnDocuments(allTexts)
        
        // Rebuild with learned vocabulary
        embeddings.clear()
        for (skill in SkillRegistry.getAll()) {
            val texts = collectSkillTexts(skill)
            val yamlMeta = SkillRegistry.getYamlMeta(skill.id)
            val yamlTexts = yamlMeta?.let { collectYamlTexts(it) } ?: emptyList()
            val allSkillTexts = texts + yamlTexts
            
            val embedding = buildSkillEmbedding(skill, allSkillTexts)
            embeddings[skill.id] = embedding
        }
        
        indexVersion++
        lastIndexUpdate = System.currentTimeMillis()
        save()
        
        XLog.i(TAG, "Built embeddings for ${embeddings.size} skills, vocab: ${EmbeddingService.hashCode()}")
    }
    
    /**
     * Search for skills using semantic similarity.
     * 
     * @param query The query text
     * @param topK Number of results to return
     * @param minScore Minimum similarity score (0-1)
     * @return List of (skillId, score) pairs
     */
    fun search(query: String, topK: Int = 5, minScore: Float = 0.1f): List<Pair<String, Float>> {
        if (embeddings.isEmpty()) {
            XLog.w(TAG, "Index empty, rebuilding...")
            rebuild()
        }
        
        val queryEmbedding = EmbeddingService.embed(query)
        
        return embeddings.entries
            .parallelStream()
            .map { (skillId, embedding) ->
                val score = EmbeddingService.cosineSimilarity(queryEmbedding, embedding.combinedEmbedding)
                skillId to score
            }
            .filter { it.second >= minScore }
            .sortedByDescending { it.second }
            .limit(topK.toLong())
            .toList()
    }
    
    /**
     * Search with keyword boosting.
     * Combines semantic similarity with keyword overlap.
     */
    fun searchWithBoosting(
        query: String,
        topK: Int = 5,
        minScore: Float = 0.1f,
        semanticWeight: Float = 0.7f,
        keywordWeight: Float = 0.3f
    ): List<Pair<String, Float>> {
        val queryTokens = tokenize(query)
        
        // Get semantic scores
        val semanticScores = search(query, topK * 2, minScore * 0.5f).toMap()
        
        // Calculate keyword scores
        val keywordScores = mutableMapOf<String, Float>()
        for ((skillId, embedding) in embeddings) {
            val skill = SkillRegistry.findById(skillId) ?: continue
            val skillTokens = skill.triggerPatterns.flatMap { tokenize(it) }.toSet()
            
            val overlap = queryTokens.intersect(skillTokens).size
            val union = queryTokens.union(skillTokens).size
            val jaccard = if (union > 0) overlap.toFloat() / union else 0f
            
            keywordScores[skillId] = jaccard
        }
        
        // Combine scores
        val combinedScores = mutableMapOf<String, Float>()
        for (skillId in (semanticScores.keys + keywordScores.keys)) {
            val semantic = semanticScores[skillId] ?: 0f
            val keyword = keywordScores[skillId] ?: 0f
            
            // Normalize and combine
            val combined = (semantic * semanticWeight + keyword * keywordWeight)
            combinedScores[skillId] = combined
        }
        
        return combinedScores.entries
            .filter { it.value >= minScore }
            .sortedByDescending { it.value }
            .take(topK)
            .map { it.key to it.value }
    }
    
    /**
     * Calculate confidence score combining semantic match, keyword match,
     * and metadata relevance.
     */
    fun calculateConfidence(
        query: String,
        skillId: String,
        yamlMeta: YamlSkill?,
        antiTriggerMatches: List<String>,
        missingEntities: List<String>
    ): Float {
        val baseScores = searchWithBoosting(query, topK = 10, minScore = 0f)
        var confidence = baseScores.find { it.first == skillId }?.second ?: 0f
        
        // Boost if skill is in top results
        val rank = baseScores.indexOfFirst { it.first == skillId }
        if (rank >= 0) {
            confidence += (1f - rank * 0.1f) * 0.1f
        }
        
        // Penalize for anti-trigger matches
        if (antiTriggerMatches.isNotEmpty()) {
            confidence *= 0.5f
        }
        
        // Penalize for missing required entities
        confidence -= missingEntities.size * 0.15f
        
        return confidence.coerceIn(0f, 1f)
    }
    
    /**
     * Get embedding for a specific skill.
     */
    fun getEmbedding(skillId: String): SkillEmbedding? = embeddings[skillId]
    
    /**
     * Update embedding for a single skill.
     */
    fun updateSkill(skill: Skill, yamlMeta: YamlSkill?) {
        val texts = collectSkillTexts(skill)
        val yamlTexts = yamlMeta?.let { collectYamlTexts(it) } ?: emptyList()
        val allTexts = texts + yamlTexts
        
        val embedding = buildSkillEmbedding(skill, allTexts)
        embeddings[skill.id] = embedding
        indexVersion++
        lastIndexUpdate = System.currentTimeMillis()
        
        XLog.d(TAG, "Updated embedding for skill: ${skill.id}")
    }
    
    /**
     * Remove embedding for a skill.
     */
    fun removeSkill(skillId: String) {
        embeddings.remove(skillId)
        indexVersion++
        lastIndexUpdate = System.currentTimeMillis()
    }
    
    /**
     * Get index statistics.
     */
    fun getStats(): IndexStats {
        return IndexStats(
            skillCount = embeddings.size,
            version = indexVersion,
            lastUpdated = lastIndexUpdate,
            vocabularySize = EmbeddingService.hashCode()
        )
    }
    
    private fun buildSkillEmbedding(skill: Skill, texts: List<String>): SkillEmbedding {
        val triggerText = skill.triggerPatterns.joinToString(" ")
        val descriptionText = skill.description
        val metadataText = texts.joinToString(" ")
        
        val triggerEmbedding = EmbeddingService.embed(triggerText)
        val descriptionEmbedding = EmbeddingService.embed(descriptionText)
        val metadataEmbedding = EmbeddingService.embed(metadataText)
        
        // Combined embedding with weighted average
        val combined = FloatArray(triggerEmbedding.size) { i ->
            triggerEmbedding[i] * 0.5f + descriptionEmbedding[i] * 0.3f + metadataEmbedding[i] * 0.2f
        }
        
        return SkillEmbedding(
            skillId = skill.id,
            triggerEmbedding = triggerEmbedding,
            descriptionEmbedding = descriptionEmbedding,
            metadataEmbedding = metadataEmbedding,
            combinedEmbedding = combined,
            version = indexVersion,
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    private fun collectSkillTexts(skill: Skill): List<String> {
        return buildList {
            addAll(skill.triggerPatterns)
            add(skill.description)
            add(skill.name)
            addAll(skill.parameters.map { "${it.name} ${it.description}" })
        }
    }
    
    private fun collectYamlTexts(yaml: YamlSkill): List<String> {
        return buildList {
            addAll(yaml.routing.triggers)
            addAll(yaml.routing.triggers.map { extractPatternTerms(it) })
            add(yaml.taxonomy.domain)
            yaml.safety.blocklistPatterns.forEach { add(it) }
            addAll(yaml.slots.keys)
        }
    }
    
    private fun extractPatternTerms(pattern: String): String {
        // Extract meaningful terms from trigger patterns
        return pattern
            .replace(Regex("[{}|\\[\\]]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
    
    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[\\s\\-_.,!?;:'\"()\\[\\]{}]+"))
            .filter { it.length >= 2 }
            .toSet()
    }
    
    private fun save() {
        try {
            val json = JSONObject()
            json.put("version", CURRENT_FORMAT_VERSION)
            json.put("indexVersion", indexVersion)
            json.put("lastUpdated", lastIndexUpdate)
            
            val skillsArray = JSONArray()
            for ((_, embedding) in embeddings) {
                val skillJson = JSONObject()
                skillJson.put("skillId", embedding.skillId)
                skillJson.put("combinedEmbedding", JSONArray(embedding.combinedEmbedding.map { it.toDouble() }))
                skillJson.put("version", embedding.version)
                skillJson.put("lastUpdated", embedding.lastUpdated)
                skillsArray.put(skillJson)
            }
            json.put("skills", skillsArray)
            
            indexFile.writeText(json.toString(2))
            XLog.d(TAG, "Saved index to ${indexFile.absolutePath}")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to save embedding index", e)
        }
    }
    
    private fun load() {
        try {
            if (!indexFile.exists()) {
                XLog.i(TAG, "No existing index, will rebuild on first search")
                return
            }
            
            val json = JSONObject(indexFile.readText())
            val formatVersion = json.optInt("version", 1)
            
            if (formatVersion != CURRENT_FORMAT_VERSION) {
                XLog.w(TAG, "Index version mismatch ($formatVersion vs $CURRENT_FORMAT_VERSION), rebuilding")
                rebuild()
                return
            }
            
            indexVersion = json.optInt("indexVersion", 0)
            lastIndexUpdate = json.optLong("lastUpdated", 0L)
            
            val skillsArray = json.getJSONArray("skills")
            for (i in 0 until skillsArray.length()) {
                val skillJson = skillsArray.getJSONObject(i)
                val skillId = skillJson.getString("skillId")
                
                val embeddingArray = skillJson.getJSONArray("combinedEmbedding")
                val embedding = FloatArray(embeddingArray.length())
                for (j in 0 until embeddingArray.length()) {
                    embedding[j] = embeddingArray.getDouble(j).toFloat()
                }
                
                embeddings[skillId] = SkillEmbedding(
                    skillId = skillId,
                    triggerEmbedding = FloatArray(0),
                    descriptionEmbedding = FloatArray(0),
                    metadataEmbedding = FloatArray(0),
                    combinedEmbedding = embedding,
                    version = skillJson.optInt("version", 0),
                    lastUpdated = skillJson.optLong("lastUpdated", 0L)
                )
            }
            
            XLog.i(TAG, "Loaded ${embeddings.size} skill embeddings from index")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to load embedding index, will rebuild", e)
            rebuild()
        }
    }
    
    data class IndexStats(
        val skillCount: Int,
        val version: Int,
        val lastUpdated: Long,
        val vocabularySize: Int
    )
}
