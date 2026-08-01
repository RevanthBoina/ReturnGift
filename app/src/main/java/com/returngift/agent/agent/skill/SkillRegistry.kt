// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.skill

import android.content.Context
import com.returngift.agent.agent.embedding.SkillEmbeddingIndex
import com.returngift.agent.utils.XLog

/**
 * Registry of built-in and user-defined skills.
 * Skills are loaded at app startup and matched by trigger patterns.
 * 
 * Integration: Uses hybrid semantic retrieval combining:
 * - Embedding-based similarity search via SkillEmbeddingIndex
 * - Keyword-based matching with overlap scoring
 * - Confidence scoring with entity validation
 */
object SkillRegistry {

    private const val TAG = "SkillRegistry"
    private val skills = mutableMapOf<String, Skill>()
    private val yamlMeta = mutableMapOf<String, YamlSkill>()
    
    // Semantic embedding index for hybrid retrieval
    private val embeddingIndex = SkillEmbeddingIndex.getInstance()

    /**
     * Result of skill matching with metadata for routing decisions.
     */
    data class MatchResult(
        val skill: Skill,
        val matchedPattern: String,
        val confidence: Float,  // 0.0 to 1.0
        val extractedParams: Map<String, String>,
        val antiTriggerMatches: List<String>,  // Patterns that matched anti-triggers
        val missingRequiredEntities: List<String>,  // Required entities not found
        val redirectTo: String?  // If anti-trigger matched, redirect to this skill
    ) {
        val isHighConfidence: Boolean
            get() = confidence >= 0.8f && antiTriggerMatches.isEmpty() && missingRequiredEntities.isEmpty()
        
        val shouldRouteToSkill: Boolean
            get() = isHighConfidence && redirectTo == null
    }

    /**
     * Register a skill. Replaces existing skill with same ID.
     */
    fun register(skill: Skill) {
        skills[skill.id] = skill
        XLog.d(TAG, "Registered skill: ${skill.id} (${skill.name})")
    }

    fun findById(id: String): Skill? = skills[id]

    fun getAll(): List<Skill> = skills.values.toList()

    fun getByCategory(category: SkillCategory): List<Skill> =
        skills.values.filter { it.category == category }

    fun getUserFacing(): List<Skill> =
        skills.values.filter { it.userFacing }
    
    /**
     * Semantic skill retrieval using hybrid approach.
     * Combines embedding-based similarity with keyword boosting and confidence scoring.
     * 
     * @param query The user's task query
     * @param topK Maximum number of results to consider
     * @return MatchResult with full routing metadata, or null if no match above threshold
     */
    fun findBySemanticRetrieval(query: String, topK: Int = 5): MatchResult? {
        val lower = query.lowercase()
        
        // Compound tasks should go to agent loop
        if (lower.contains(" and ") || lower.contains(" then ") || lower.contains(" after ")) {
            XLog.d(TAG, "Compound task in semantic retrieval, skipping: $query")
            return null
        }
        
        // Use hybrid search with keyword boosting
        val candidates = embeddingIndex.searchWithBoosting(
            query = query,
            topK = topK,
            minScore = 0.15f,
            semanticWeight = 0.7f,
            keywordWeight = 0.3f
        )
        
        if (candidates.isEmpty()) {
            XLog.d(TAG, "No semantic matches found for: $query")
            return null
        }
        
        // Get best match and validate
        for ((skillId, baseScore) in candidates) {
            val skill = skills[skillId] ?: continue
            val yaml = yamlMeta[skillId]
            
            // Calculate detailed match metrics
            val antiTriggerMatches = mutableListOf<String>()
            var redirectTo: String? = null
            
            yaml?.routing?.antiTriggers?.forEach { anti ->
                val antiTokens = tokenize(anti.pattern)
                val queryTokens = tokenize(query)
                if (antiTokens.isNotEmpty() && antiTokens.all { queryTokens.contains(it) }) {
                    antiTriggerMatches.add(anti.pattern)
                    redirectTo = anti.redirectTo
                }
            }
            
            val missingEntities = mutableListOf<String>()
            yaml?.routing?.requiredEntities?.forEach { entity ->
                if (!canExtractEntity(query, "", entity)) {
                    missingEntities.add(entity)
                }
            }
            
            // Calculate final confidence
            val confidence = embeddingIndex.calculateConfidence(
                query = query,
                skillId = skillId,
                yamlMeta = yaml,
                antiTriggerMatches = antiTriggerMatches,
                missingEntities = missingEntities
            )
            
            if (confidence >= 0.4f) {
                XLog.d(TAG, "Semantic match: ${skill.id} score=$confidence")
                
                return MatchResult(
                    skill = skill,
                    matchedPattern = "",  // Semantic match doesn't have specific pattern
                    confidence = confidence,
                    extractedParams = extractParams(query, skill.triggerPatterns),
                    antiTriggerMatches = antiTriggerMatches,
                    missingRequiredEntities = missingEntities,
                    redirectTo = redirectTo
                )
            }
        }
        
        return null
    }

    /**
     * Find a skill that matches a task by trigger patterns.
     * Returns null if no skill matches.
     * 
     * This method uses simple regex matching. For embedding-based retrieval,
     * use findByTriggerWithEmbedding() when available.
     */
    fun findByTrigger(task: String): Skill? {
        val matchResult = findByTriggerDetailed(task)
        return matchResult?.skill
    }

    /**
     * Find a skill with detailed match information including anti-trigger
     * and entity validation.
     * 
     * @param task The user's task utterance
     * @return MatchResult with full routing metadata, or null if no match
     */
    fun findByTriggerDetailed(task: String): MatchResult? {
        val lower = task.lowercase()
        
        // Compound tasks with conjunctions should go to agent loop, not skills.
        // Skills are for simple, single-action commands only.
        if (lower.contains(" and ") || lower.contains(" then ") || lower.contains(" after ")) {
            XLog.d(TAG, "Compound task detected, skipping skill matching: $task")
            return null
        }

        val taskTokens = tokenize(task)
        
        // Find best matching skill
        var bestMatch: MatchResult? = null
        var bestScore = 0f

        for (skill in skills.values) {
            val yamlMeta = yamlMeta[skill.id]
            
            // Calculate trigger match score
            var matchedPattern = ""
            var maxOverlap = 0f
            
            for (pattern in skill.triggerPatterns) {
                val patternTokens = tokenize(removePlaceholders(pattern))
                if (patternTokens.isEmpty()) continue
                
                val overlap = patternTokens.intersect(taskTokens).size.toFloat() / patternTokens.size
                if (overlap > maxOverlap) {
                    maxOverlap = overlap
                    matchedPattern = pattern
                }
            }

            if (maxOverlap == 0f) continue

            // Check anti-triggers - if any match, skip this skill
            val antiTriggerMatches = mutableListOf<String>()
            var redirectTo: String? = null
            
            yamlMeta?.routing?.antiTriggers?.forEach { antiTrigger ->
                val antiTokens = tokenize(antiTrigger.pattern)
                if (antiTokens.isNotEmpty() && antiTokens.all { taskTokens.contains(it) }) {
                    antiTriggerMatches.add(antiTrigger.pattern)
                    redirectTo = antiTrigger.redirectTo
                    XLog.d(TAG, "Skill ${skill.id} vetoed by anti-trigger: ${antiTrigger.pattern} -> ${antiTrigger.redirectTo}")
                }
            }

            // Check required entities
            val missingEntities = mutableListOf<String>()
            yamlMeta?.routing?.requiredEntities?.forEach { entity ->
                // Check if the entity is extractable from the task
                if (!canExtractEntity(task, matchedPattern, entity)) {
                    missingEntities.add(entity)
                }
            }

            // Calculate confidence score
            val antiTriggerPenalty = if (antiTriggerMatches.isNotEmpty()) 0.5f else 0f
            val missingEntityPenalty = missingEntities.size * 0.15f
            val confidence = (maxOverlap - antiTriggerPenalty - missingEntityPenalty).coerceIn(0f, 1f)

            if (confidence > bestScore) {
                bestScore = confidence
                bestMatch = MatchResult(
                    skill = skill,
                    matchedPattern = matchedPattern,
                    confidence = confidence,
                    extractedParams = extractParams(task, skill.triggerPatterns),
                    antiTriggerMatches = antiTriggerMatches,
                    missingRequiredEntities = missingEntities,
                    redirectTo = redirectTo
                )
            }
        }

        if (bestMatch != null) {
            XLog.d(TAG, "Best match: ${bestMatch.skill.id} confidence=${bestMatch.confidence} " +
                    "antiTriggers=${bestMatch.antiTriggerMatches.size} " +
                    "missingEntities=${bestMatch.missingRequiredEntities.size}")
        }

        return bestMatch
    }

    /**
     * Extract parameters from task using trigger patterns.
     */
    private fun extractParams(task: String, patterns: List<String>): Map<String, String> {
        val lower = task.lowercase()
        for (pattern in patterns) {
            val paramNames = Regex("\\{(\\w+)\\}").findAll(pattern).map { it.groupValues[1] }.toList()
            val regexStr = pattern.lowercase().replace(Regex("\\{\\w+\\}"), "(.+)")
            val match = Regex(regexStr).find(lower)
            if (match != null && match.groupValues.size > 1) {
                return paramNames.zip(match.groupValues.drop(1)).toMap()
            }
        }
        return emptyMap()
    }

    /**
     * Check if a required entity can be extracted from the task.
     */
    private fun canExtractEntity(task: String, matchedPattern: String, entity: String): Boolean {
        // Simple check: if entity name appears in task, consider it extractable
        val lower = task.lowercase()
        val entityLower = entity.lowercase()
        
        // Check if entity is a placeholder name that was filled
        val patternLower = matchedPattern.lowercase()
        if (patternLower.contains("{$entity}") || patternLower.contains("{$entity|")) {
            // Entity was in the pattern, check if a value was extracted
            val regexStr = patternLower.replace(Regex("\\{\\w+[^}]*}"), "(.+)")
            val match = Regex(regexStr).find(lower)
            return match != null && match.groupValues.size > 1
        }
        
        // Entity wasn't in pattern, check if it's a common entity type
        return when (entity) {
            "recipient", "contact", "person" -> 
                lower.contains("to ") || lower.contains(" for ") || lower.contains("@")
            "app" ->
                lower.contains(" on ") || lower.contains(" via ")
            "message", "body", "text" ->
                lower.contains(" saying ") || lower.contains(" with message ") || lower.contains(" that ")
            "query", "search" ->
                lower.contains("search ") || lower.contains(" for ") || lower.contains(" look up ")
            else -> true  // Unknown entities assume extractable
        }
    }

    private fun tokenize(text: String): Set<String> {
        return Regex("[a-z0-9]+").findAll(text.lowercase()).map { it.value }.toSet()
    }

    private fun removePlaceholders(pattern: String): String {
        return pattern.replace(Regex("\\{[^}]+\\}"), "").lowercase()
    }

    /**
     * Load built-in skills. Called once at app startup.
     *
     * Only simple, context-free, app-agnostic skills belong here.
     * Complex app-specific tasks (messaging, camera, etc.) go through the agent loop.
     */
    fun loadBuiltInSkills() {
        register(BuiltInSkills.searchInApp())
        register(BuiltInSkills.submitForm())
        register(BuiltInSkills.dismissPopup())
        register(BuiltInSkills.scrollAndRead())
        register(BuiltInSkills.copyScreenText())
        register(BuiltInSkills.acceptPermission())
        register(BuiltInSkills.swipeGesture())
        register(BuiltInSkills.goBack())
        register(BuiltInSkills.waitForContent())
        XLog.i(TAG, "Loaded ${skills.size} built-in skills")
    }

    /**
     * Load YAML-defined skills from assets. Call after loadBuiltInSkills().
     * YAML skills with the same id as a built-in will override the built-in.
     */
    fun loadYamlSkills(context: Context) {
        val yamlSkills: List<YamlSkill> = YamlSkillLoader.loadAll(context)
        var compiled = 0
        for (yaml in yamlSkills) {
            val skill = YamlSkillCompiler.compile(yaml) ?: continue
            register(skill)
            // Keep the raw YamlSkill for safety/routing metadata lookups
            yamlMeta[yaml.skillId] = yaml
            compiled++
        }
        XLog.i(TAG, "Compiled $compiled YAML skills (${yamlSkills.size} parsed)")
        
        // Rebuild semantic embedding index with all loaded skills
        embeddingIndex.rebuild()
    }

    /** Returns the raw YamlSkill metadata for a skill id, if loaded from YAML. */
    fun getYamlMeta(skillId: String): YamlSkill? = yamlMeta[skillId]

    fun clear() { skills.clear(); yamlMeta.clear() }
}
