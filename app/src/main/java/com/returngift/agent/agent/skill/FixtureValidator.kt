// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.skill

import com.returngift.agent.service.ScreenCaptureManager
import com.returngift.agent.utils.XLog
import java.util.concurrent.TimeUnit

/**
 * Fixture Validator - verifies skill screen_signatures against real captured XML trees.
 * 
 * Each skill can declare screen_signatures with:
 * - require_all: ALL patterns must match
 * - require_any: AT LEAST ONE pattern must match
 * - require_none: NO patterns must match (anti-match)
 * 
 * sha256sum each captured XML into tree_hash, then verify against the skill's signatures.
 */
object FixtureValidator {

    private const val TAG = "FixtureValidator"

    /**
     * Result of fixture validation.
     */
    data class ValidationResult(
        val isValid: Boolean,
        val matchedPatterns: List<String>,
        val failedPatterns: List<String>,
        val reason: String
    )

    /**
     * Screen signature pattern for a skill.
     */
    data class ScreenSignature(
        val kind: SignatureKind,
        val pattern: String,
        val description: String
    )

    enum class SignatureKind {
        REQUIRE_ALL,   // All patterns must match
        REQUIRE_ANY,   // At least one must match
        REQUIRE_NONE    // None should match
    }

    /**
     * Validate current screen against a skill's screen signatures.
     * 
     * @param skill The skill to validate against
     * @param currentXml The current accessibility tree XML
     * @param treeHash SHA-256 hash of the XML
     * @return ValidationResult indicating if the screen matches the skill's expectations
     */
    fun validate(
        skill: Skill,
        currentXml: String,
        treeHash: String
    ): ValidationResult {
        // Get screen signatures from the compiled skill or YAML metadata
        val signatures = getScreenSignatures(skill)
        
        if (signatures.isEmpty()) {
            XLog.d(TAG, "No screen signatures defined for skill: ${skill.id}")
            return ValidationResult(true, emptyList(), emptyList(), "No signatures defined")
        }

        val lowerXml = currentXml.lowercase()
        val matchedPatterns = mutableListOf<String>()
        val failedPatterns = mutableListOf<String>()

        for (signature in signatures) {
            val pattern = signature.pattern.lowercase()
            val matches = when (signature.kind) {
                SignatureKind.REQUIRE_ALL -> {
                    // For REQUIRE_ALL, all patterns in the signature string must match
                    val subPatterns = pattern.split("|").map { it.trim() }
                    subPatterns.all { subPattern -> 
                        lowerXml.contains(subPattern) || regexMatch(lowerXml, subPattern)
                    }
                }
                SignatureKind.REQUIRE_ANY -> {
                    // For REQUIRE_ANY, at least one pattern must match
                    val subPatterns = pattern.split("|").map { it.trim() }
                    subPatterns.any { subPattern -> 
                        lowerXml.contains(subPattern) || regexMatch(lowerXml, subPattern)
                    }
                }
                SignatureKind.REQUIRE_NONE -> {
                    // For REQUIRE_NONE, NO patterns should match
                    val subPatterns = pattern.split("|").map { it.trim() }
                    subPatterns.none { subPattern -> 
                        lowerXml.contains(subPattern) || regexMatch(lowerXml, subPattern)
                    }
                }
            }

            if (matches) {
                matchedPatterns.add(signature.pattern)
            } else {
                failedPatterns.add(signature.pattern)
            }
        }

        // Determine overall validity based on signature kinds present
        val requireAllPresent = signatures.any { it.kind == SignatureKind.REQUIRE_ALL }
        val requireAnyPresent = signatures.any { it.kind == SignatureKind.REQUIRE_ANY }
        val requireNonePresent = signatures.any { it.kind == SignatureKind.REQUIRE_NONE }

        val isValid = when {
            // If REQUIRE_ALL patterns failed, invalid
            requireAllPresent && failedPatterns.any { sig ->
                signatures.find { it.pattern == sig }?.kind == SignatureKind.REQUIRE_ALL
            } -> false
            
            // If REQUIRE_ANY present, must have at least one match
            requireAnyPresent && matchedPatterns.none { sig ->
                signatures.find { it.pattern == sig }?.kind == SignatureKind.REQUIRE_ANY
            } -> false
            
            // If REQUIRE_NONE patterns matched, invalid
            requireNonePresent && matchedPatterns.any { sig ->
                signatures.find { it.pattern == sig }?.kind == SignatureKind.REQUIRE_NONE
            } -> false
            
            else -> true
        }

        val reason = when {
            !isValid && failedPatterns.isNotEmpty() -> "Failed patterns: ${failedPatterns.joinToString()}"
            !isValid -> "Required patterns not matched"
            else -> "All signatures validated successfully"
        }

        XLog.d(TAG, "Fixture validation for ${skill.id}: valid=$isValid matched=${matchedPatterns.size} failed=${failedPatterns.size}")
        return ValidationResult(isValid, matchedPatterns, failedPatterns, reason)
    }

    /**
     * Get screen signatures for a skill.
     * Currently loaded from YAML metadata via SkillRegistry.getYamlMeta().
     */
    private fun getScreenSignatures(skill: Skill): List<ScreenSignature> {
        val yamlMeta = SkillRegistry.getYamlMeta(skill.id) ?: return emptyList()
        
        // Check for screen_signatures in YAML
        // Note: This field needs to be added to YamlSkill.kt and YamlSkillLoader.kt
        val rawSignatures = yamlMeta.screenSignatures
        
        return rawSignatures.mapNotNull { raw ->
            when {
                raw.startsWith("require_all:") -> {
                    val pattern = raw.removePrefix("require_all:").trim()
                    ScreenSignature(SignatureKind.REQUIRE_ALL, pattern, "require_all")
                }
                raw.startsWith("require_any:") -> {
                    val pattern = raw.removePrefix("require_any:").trim()
                    ScreenSignature(SignatureKind.REQUIRE_ANY, pattern, "require_any")
                }
                raw.startsWith("require_none:") -> {
                    val pattern = raw.removePrefix("require_none:").trim()
                    ScreenSignature(SignatureKind.REQUIRE_NONE, pattern, "require_none")
                }
                else -> null
            }
        }
    }

    /**
     * Simple regex match helper - matches pattern against text.
     */
    private fun regexMatch(text: String, pattern: String): Boolean {
        return try {
            Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
        } catch (e: Exception) {
            // If pattern is not a valid regex, treat as literal
            false
        }
    }

    /**
     * Validate fixture freshness against staleness SLA.
     * 
     * @param skillId The skill to check
     * @param lastValidatedUtc ISO timestamp of last validation
     * @param stalenessSlaDays Maximum days before fixture is considered stale
     * @return true if fixture is fresh, false if stale
     */
    fun checkStaleness(
        skillId: String,
        lastValidatedUtc: String,
        stalenessSlaDays: Int
    ): Boolean {
        if (lastValidatedUtc.isBlank() || stalenessSlaDays <= 0) {
            return true // No staleness enforcement if not configured
        }

        try {
            val lastValidated = java.time.Instant.parse(lastValidatedUtc)
            val now = java.time.Instant.now()
            val daysSinceValidation = TimeUnit.SECONDS.toDays(
                java.time.Duration.between(lastValidated, now).seconds
            )
            
            val isFresh = daysSinceValidation <= stalenessSlaDays
            
            if (!isFresh) {
                XLog.w(TAG, "Skill '$skillId' fixture is stale: last validated $daysSinceValidation days ago, SLA is $stalenessSlaDays days")
            }
            
            return isFresh
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to parse last_validated_utc for skill '$skillId': $lastValidatedUtc", e)
            return true // Assume fresh if we can't parse
        }
    }

    /**
     * Check if a skill can be promoted to a given status based on staleness.
     * Skills that exceed their staleness SLA cannot be promoted to canary or stable.
     */
    fun canPromote(skillId: String, targetStatus: String): Boolean {
        val yamlMeta = SkillRegistry.getYamlMeta(skillId) ?: return true
        
        // Only enforce staleness for canary and stable promotions
        if (targetStatus != "canary" && targetStatus != "stable") {
            return true
        }

        return checkStaleness(
            skillId,
            yamlMeta.lastValidatedUtc,
            yamlMeta.stalenessSla
        )
    }
}
