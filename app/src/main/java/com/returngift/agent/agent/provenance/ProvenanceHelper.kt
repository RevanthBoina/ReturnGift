// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.provenance

/**
 * Provenance tag for observations and artifacts.
 *
 * Kind indicates the source type and origin is a closed-shape string:
 * - SCREEN: screen:<package> (foreground package at observation time)
 * - WEB: web:<hostname> (host of fetched URL)
 * - EXTERNAL_AI: ai:<provider> (AI service like gemini, openai, anthropic)
 * - USER: user:<channel> (input channel: voice, typing, etc.)
 * - SYSTEM: system:<component> (internal component like TaskParser, SkillRegistry)
 *
 * Hostnames and packages are closed-vocab-ish machine ids, not raw content — privacy-safe.
 */
data class ProvenanceTag(
    val kind: Kind,
    val origin: String,
    val ts: Long = System.currentTimeMillis()
) {
    enum class Kind { SCREEN, WEB, EXTERNAL_AI, USER, SYSTEM }

    /** Serialize to a compact string for storage in metadata or frontmatter. */
    fun toStorageString(): String {
        return "${kind.name.toLowerCase()}:$origin"
    }

    /** Deserialize from storage string. */
    companion object {
        fun fromStorageString(s: String): ProvenanceTag? {
            val parts = s.split(":", limit = 2)
            if (parts.size != 2) return null
            val kindStr = parts[0].uppercase()
            val origin = parts[1]
            return try {
                val kind = Kind.valueOf(kindStr)
                ProvenanceTag(kind, origin)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/**
 * Utility for working with provenance in KBManager.
 *
 * When writing vault files with KBManager, provenance can be added to frontmatter.
 * Binary files (images/downloads) are tracked separately in ExecutionTracker.
 */
object ProvenanceHelper {
    /**
     * Add provenance information to a frontmatter map for vault files.
     * The provenance will be visible in the Vault UI (frontmatter field "provenance").
     */
    fun addToFrontmatter(frontmatter: MutableMap<String, Any>, tag: ProvenanceTag) {
        // Convert to storage string format; if multiple tags exist, consider a list
        // For now, we store as a single tag. Later extensions could support multiple.
        frontmatter["provenance"] = tag.toStorageString()
    }

    /**
     * Extract provenance from a frontmatter map.
     * Returns null if no provenance tag is present.
     */
    fun extractFromFrontmatter(frontmatter: Map<String, Any>): ProvenanceTag? {
        val raw = frontmatter["provenance"] as? String ?: return null
        return ProvenanceTag.fromStorageString(raw)
    }
}