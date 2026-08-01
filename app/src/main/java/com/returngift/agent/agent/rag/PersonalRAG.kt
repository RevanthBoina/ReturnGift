// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.rag

import android.content.Context
import com.returngift.agent.agent.embedding.EmbeddingService
import com.returngift.agent.agent.knowledge.KBManager
import com.returngift.agent.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Personal RAG system - offline semantic knowledge with hybrid retrieval.
 */
class PersonalRAG(private val context: Context) {

    private val TAG = "PersonalRAG"
    private val INDEX_FILE = "personal_rag_index.json"
    
    private val documentStore = ConcurrentHashMap<String, Document>()
    private val embeddingCache = ConcurrentHashMap<String, FloatArray>()
    
    private var semanticWeight = 0.6f
    private var keywordWeight = 0.2f
    private var recencyWeight = 0.15f
    private var documentCount = 0
    private var lastIndexUpdate = 0L
    
    data class Document(
        val id: String,
        val content: String,
        val metadata: DocumentMetadata,
        val embedding: FloatArray? = null,
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis()
    )
    
    data class DocumentMetadata(
        val source: SourceType,
        val tags: List<String> = emptyList(),
        val entities: List<String> = emptyList(),
        val author: String? = null,
        val title: String? = null,
        val relatedContact: String? = null
    )
    
    enum class SourceType {
        USER_NOTE, CLIPBOARD, REMINDER, EMAIL, MESSAGE, TASK_RESULT, EXTRACTION, KB_FILE, APP_CONTENT, OTHER
    }
    
    data class RetrievalResult(
        val document: Document,
        val semanticScore: Float,
        val keywordScore: Float,
        val recencyScore: Float,
        val combinedScore: Float,
        val rank: Int = 0
    )
    
    fun addDocument(document: Document) {
        val embedding = document.embedding ?: EmbeddingService.embed(document.content)
        val docWithEmbedding = document.copy(embedding = embedding, updatedAt = System.currentTimeMillis())
        documentStore[document.id] = docWithEmbedding
        embeddingCache[document.id] = embedding
        documentCount++
        lastIndexUpdate = System.currentTimeMillis()
    }
    
    fun addDocuments(documents: List<Document>) {
        documents.forEach { addDocument(it) }
        save()
    }
    
    fun updateDocument(id: String, content: String, metadata: DocumentMetadata? = null) {
        val existing = documentStore[id] ?: return
        val updatedMeta = metadata ?: existing.metadata
        val newEmbedding = EmbeddingService.embed(content)
        val updated = existing.copy(content = content, metadata = updatedMeta, embedding = newEmbedding, updatedAt = System.currentTimeMillis())
        documentStore[id] = updated
        embeddingCache[id] = newEmbedding
        lastIndexUpdate = System.currentTimeMillis()
    }
    
    fun deleteDocument(id: String) {
        documentStore.remove(id)
        embeddingCache.remove(id)
        documentCount--
    }
    
    fun retrieve(query: String, topK: Int = 10, filters: RetrievalFilters? = null): List<RetrievalResult> {
        if (documentStore.isEmpty()) return emptyList()
        
        val queryEmbedding = EmbeddingService.embed(query)
        val queryTokens = tokenize(query)
        val now = System.currentTimeMillis()
        val dayMs = 24 * 60 * 60 * 1000L
        
        val candidates = documentStore.values
            .filter { doc -> filters == null || matchesFilters(doc, filters) }
            .map { doc ->
                val semanticScore = doc.embedding?.let { EmbeddingService.cosineSimilarity(queryEmbedding, it) } ?: 0f
                val keywordScore = calculateKeywordScore(queryTokens, doc)
                val ageMs = now - doc.updatedAt
                val recencyScore = kotlin.math.exp(-ageMs.toFloat() / (7 * dayMs)).coerceIn(0f, 1f)
                val combined = semanticScore * semanticWeight + keywordScore * keywordWeight + recencyScore * recencyWeight
                RetrievalResult(doc, semanticScore, keywordScore, recencyScore, combined)
            }
            .filter { it.combinedScore > 0.1f }
            .sortedByDescending { it.combinedScore }
            .take(topK)
            .mapIndexed { index, result -> result.copy(rank = index + 1) }
        
        XLog.d(TAG, "Retrieved ${candidates.size} documents for: ${query.take(50)}")
        return candidates
    }
    
    fun retrieveForContext(query: String, maxTokens: Int = 500): String {
        val results = retrieve(query, topK = 3)
        if (results.isEmpty()) return ""
        
        val sb = StringBuilder()
        var tokens = 0
        sb.append("\n## Relevant Knowledge\n")
        
        for (result in results) {
            val doc = result.document
            val line = "- [${doc.metadata.source.name}] ${doc.content.take(200)}"
            val lineTokens = line.length / 4
            if (tokens + lineTokens > maxTokens) break
            tokens += lineTokens
            sb.appendLine(line)
        }
        return sb.toString()
    }
    
    fun indexKBContent() {
        val searchResult = KBManager.search("")
        if (searchResult.isFailure) return
        val results = searchResult.getOrNull() ?: return
        
        for (kbResult in results) {
            val readResult = KBManager.read(kbResult.path)
            if (readResult.isSuccess) {
                readResult.getOrNull()?.let { content ->
                    val doc = Document(
                        id = "kb_${kbResult.path.hashCode()}",
                        content = content,
                        metadata = DocumentMetadata(source = SourceType.KB_FILE, title = kbResult.path)
                    )
                    addDocument(doc)
                }
            }
        }
        save()
    }
    
    fun getStats() = IndexStats(documentCount, lastIndexUpdate, documentStore.values.groupBy { it.metadata.source }.mapValues { it.value.size })
    
    fun save() {
        try {
            val file = File(context.getExternalFilesDir(null), INDEX_FILE)
            val json = JSONObject()
            json.put("lastUpdate", lastIndexUpdate)
            json.put("documentCount", documentCount)
            
            val docsArray = JSONArray()
            for ((_, doc) in documentStore) {
                val docJson = JSONObject()
                docJson.put("id", doc.id)
                docJson.put("content", doc.content)
                docJson.put("createdAt", doc.createdAt)
                docJson.put("updatedAt", doc.updatedAt)
                
                val metaJson = JSONObject()
                metaJson.put("source", doc.metadata.source.name)
                metaJson.put("tags", JSONArray(doc.metadata.tags))
                docJson.put("metadata", metaJson)
                
                doc.embedding?.let { emb -> docJson.put("embedding", JSONArray(emb.map { it.toDouble() })) }
                docsArray.put(docJson)
            }
            json.put("documents", docsArray)
            file.writeText(json.toString(2))
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to save index", e)
        }
    }
    
    fun load() {
        try {
            val file = File(context.getExternalFilesDir(null), INDEX_FILE)
            if (!file.exists()) return
            
            val json = JSONObject(file.readText())
            lastIndexUpdate = json.optLong("lastUpdate", 0L)
            documentCount = json.optInt("documentCount", 0)
            
            val docsArray = json.getJSONArray("documents")
            for (i in 0 until docsArray.length()) {
                val docJson = docsArray.getJSONObject(i)
                val metaJson = docJson.getJSONObject("metadata")
                val meta = DocumentMetadata(
                    source = SourceType.valueOf(metaJson.optString("source", "OTHER")),
                    tags = metaJson.optJSONArray("tags")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
                )
                val embArray = docJson.optJSONArray("embedding")
                val embedding = embArray?.let { arr ->
                    FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
                }
                val doc = Document(docJson.getString("id"), docJson.getString("content"), meta, embedding,
                    docJson.optLong("createdAt", System.currentTimeMillis()), docJson.optLong("updatedAt", System.currentTimeMillis()))
                documentStore[doc.id] = doc
                embedding?.let { embeddingCache[doc.id] = it }
            }
            XLog.i(TAG, "Loaded $documentCount documents")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to load index", e)
        }
    }
    
    private fun tokenize(text: String) = text.lowercase().split(Regex("[\\s\\-_.,!?;:'\"()\\[\\]{}]+")).filter { it.length >= 2 }.toSet()
    
    private fun calculateKeywordScore(queryTokens: Set<String>, doc: Document): Float {
        val docTokens = tokenize(doc.content)
        val overlap = queryTokens.intersect(docTokens).size
        val union = queryTokens.union(docTokens).size
        return if (union > 0) overlap.toFloat() / union else 0f
    }
    
    private fun matchesFilters(doc: Document, filters: RetrievalFilters): Boolean {
        if (filters.sources.isNotEmpty() && doc.metadata.source !in filters.sources) return false
        if (filters.tags.isNotEmpty() && !doc.metadata.tags.any { it in filters.tags }) return false
        return true
    }
    
    data class RetrievalFilters(val sources: Set<SourceType> = emptySet(), val tags: Set<String> = emptySet())
    data class IndexStats(val totalDocuments: Int, val lastUpdate: Long, val distribution: Map<SourceType, Int>)
    
    companion object {
        @Volatile private var instance: PersonalRAG? = null
        fun getInstance(context: Context) = instance ?: synchronized(this) { instance ?: PersonalRAG(context).also { instance = it } }
    }
}
