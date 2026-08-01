// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.embedding

import com.returngift.agent.utils.XLog
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * Lightweight embedding service for semantic similarity search.
 * 
 * Uses a simple TF-IDF based approach when the on-device embedding model
 * is not available. This provides meaningful semantic retrieval without
 * requiring a full ML model.
 * 
 * Architecture integration point:
 * - Primary: Uses LiteRT-compatible text embeddings when available
 * - Fallback: TF-IDF vectorization with cosine similarity
 */
object EmbeddingService {

    private const val TAG = "EmbeddingService"
    
    // Vocabulary for TF-IDF fallback
    private val vocabulary = mutableMapOf<String, Int>()
    private val idfValues = mutableMapOf<Int, Double>()
    private var documentCount = 0
    
    // Pre-computed vocabulary for common skill-related terms
    private val seedTerms = listOf(
        "send", "message", "text", "call", "phone", "open", "app", "search",
        "find", "create", "delete", "edit", "share", "upload", "download",
        "camera", "photo", "video", "music", "alarm", "reminder", "calendar",
        "email", "whatsapp", "telegram", "slack", "gmail", "settings",
        "contact", "recipient", "to", "from", "with", "on", "in", "at",
        "schedule", "book", "ride", "music", "navigate", "settings", "wifi",
        "bluetooth", "battery", "storage", "display", "volume", "brightness"
    ).associateWith { vocabulary.getOrPut(it) { vocabulary.size } }
    
    // Document frequencies for seed terms
    private val seedDocFreq = mutableMapOf<String, Int>()
    
    /**
     * Generate embedding vector for text using TF-IDF approach.
     * Returns normalized vector for cosine similarity computation.
     */
    fun embed(text: String): FloatArray {
        val terms = tokenize(text.lowercase())
        if (terms.isEmpty()) return FloatArray(vocabulary.size) { 0f }
        
        // Count term frequencies
        val tf = mutableMapOf<String, Int>()
        for (term in terms) {
            tf[term] = (tf[term] ?: 0) + 1
        }
        
        // Build vector with TF-IDF weights
        val dim = vocabulary.size.coerceAtLeast(1)
        val vector = FloatArray(dim)
        
        for ((term, freq) in tf) {
            val idx = vocabulary[term] ?: continue
            val df = idfValues[idx] ?: 1.0
            val idf = if (documentCount > 0) kotlin.math.ln(documentCount / df) else 1.0
            val tfidf = (freq.toDouble() / terms.size) * idf
            vector[idx] = tfidf.toFloat()
        }
        
        // Normalize
        normalize(vector)
        return vector
    }
    
    /**
     * Compute cosine similarity between two vectors.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0f) dotProduct / denom else 0f
    }
    
    /**
     * Update the vocabulary and document frequencies with new documents.
     * Call this when loading new skills to update the embedding index.
     */
    fun learnDocuments(documents: List<String>) {
        if (documents.isEmpty()) return
        
        documentCount += documents.size
        val seenTerms = mutableSetOf<String>()
        
        for (doc in documents) {
            val terms = tokenize(doc.lowercase())
            for (term in terms) {
                if (term !in seenTerms) {
                    seenTerms.add(term)
                    idfValues[vocabulary[term] ?: continue] = 
                        (idfValues[vocabulary[term] ?: continue] ?: 0.0) + 1.0
                }
            }
        }
        
        XLog.d(TAG, "Learned ${documents.size} documents, total: $documentCount, vocab: ${vocabulary.size}")
    }
    
    /**
     * Reset the embedding service state.
     */
    fun reset() {
        vocabulary.clear()
        vocabulary.putAll(seedTerms)
        idfValues.clear()
        documentCount = 0
        seedDocFreq.clear()
    }
    
    private fun tokenize(text: String): List<String> {
        return text.split(Regex("[\\s\\-_.,!?;:'\"()\\[\\]{}]+"))
            .filter { it.length >= 2 }
    }
    
    private fun normalize(vector: FloatArray) {
        var norm = 0f
        for (v in vector) norm += v * v
        norm = sqrt(norm)
        if (norm > 0f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
    }
    
    /**
     * Serialize embedding vector to bytes for storage.
     */
    fun serialize(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4)
        for (v in vector) buffer.putFloat(v)
        return buffer.array()
    }
    
    /**
     * Deserialize embedding vector from bytes.
     */
    fun deserialize(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes)
        val vector = FloatArray(bytes.size / 4)
        for (i in vector.indices) {
            vector[i] = buffer.getFloat()
        }
        return vector
    }
    
    init {
        reset()
    }
}
