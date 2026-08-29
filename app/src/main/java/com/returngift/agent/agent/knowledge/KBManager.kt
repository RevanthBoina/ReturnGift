// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.knowledge

import com.returngift.agent.ClawApplication
import com.returngift.agent.agent.provenance.ProvenanceTag
import com.returngift.agent.utils.XLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Knowledge Base Manager — reads and writes the local MD vault.
 *
 * Vault root: /storage/emulated/0/Android/data/com.returngift.agent/files/vault/
 *
 * All paths passed to public methods are relative to the vault root.
 * Path traversal (../) is stripped before resolving.
 */
object KBManager {

    private const val TAG = "KBManager"
    private const val VAULT_SUBDIR = "vault"

    data class SearchResult(val path: String, val snippet: String, val modified: Long)

    /** A user-visible artifact stored in the vault (path is relative to the vault root). */
    data class VaultFile(val path: String, val name: String, val sizeBytes: Long, val modified: Long)

    // ── Vault root ────────────────────────────────────────────────────────────

    private fun vaultDir(): File {
        val dir = File(ClawApplication.instance.getExternalFilesDir(null), VAULT_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Resolve a caller-supplied relative path safely (no traversal). */
    private fun resolve(path: String): File {
        val clean = path.trimStart('/').replace("..", "")
        val file = File(vaultDir(), clean)
        val canonicalVault = vaultDir().canonicalFile
        val canonicalFile = file.canonicalFile
        if (!canonicalFile.path.startsWith(canonicalVault.path)) {
            throw SecurityException("Path traversal attempt: $path")
        }
        return canonicalFile
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Write (create or overwrite) a markdown file.
     * Automatically prepends frontmatter built from [frontmatter] map.
     */
    fun write(path: String, frontmatter: Map<String, Any>, content: String): Result<String> {
        return try {
            val file = resolve(path)
            file.parentFile?.mkdirs()
            val text = buildFrontmatter(frontmatter) + "\n\n" + content
            file.writeText(text)
            XLog.i(TAG, "kb_write: $path (${text.length} chars)")
            Result.success("Written: $path")
        } catch (e: Exception) {
            XLog.e(TAG, "kb_write failed: $path", e)
            Result.failure(e)
        }
    }

    /**
     * Java-callable variant of [write]. `kotlin.Result` is a value class, so JVM
     * functions whose signature contains it get name-mangled (`write-xxxxx`) and
     * cannot be invoked from Java — `WebFetchTool.java` (Java tool) uses this.
     */
    fun writeFromJava(path: String, frontmatter: Map<String, Any>, content: String): Boolean =
        write(path, frontmatter, content).isSuccess

    /**
     * Write (create or overwrite) a binary file — screenshots, images, any non-text
     * artifact. Returns "Written: <path>" on success so ArtifactContract's existing
     * path extraction picks it up unchanged.
     */
    fun saveBytes(path: String, bytes: ByteArray): Result<String> {
        return try {
            val file = resolve(path)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            XLog.i(TAG, "kb_save_bytes: $path (${bytes.size} bytes)")
            Result.success("Written: $path")
        } catch (e: Exception) {
            XLog.e(TAG, "kb_save_bytes failed: $path", e)
            Result.failure(e)
        }
    }

    /** Java-callable variant of [saveBytes] — see [writeFromJava] for why this exists. */
    fun saveBytesFromJava(path: String, bytes: ByteArray): Boolean =
        saveBytes(path, bytes).isSuccess

    /**
     * MIME type for a vault path, derived from its extension. Used for FileProvider
     * open/share intents and to decide whether a preview (image) is possible.
     * Pure — safe to call from JVM unit tests.
     */
    fun mimeOf(path: String): String = when (File(path).extension.lowercase(Locale.US)) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "pdf" -> "application/pdf"
        "md", "markdown" -> "text/markdown"
        "txt", "log" -> "text/plain"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    /** True when the vault path points at an image that can be previewed inline. */
    fun isImage(path: String): Boolean = mimeOf(path).startsWith("image/")

    /** True when the vault path holds text that [read] can render. */
    fun isTextLike(path: String): Boolean =
        mimeOf(path).let { it.startsWith("text/") || it == "application/json" }

    /** Read the full content of a file. */
    fun read(path: String): Result<String> {
        return try {
            val file = resolve(path)
            if (!file.exists()) return Result.failure(Exception("File not found: $path"))
            val text = file.readText()
            XLog.i(TAG, "kb_read: $path (${text.length} chars)")
            Result.success(text)
        } catch (e: Exception) {
            XLog.e(TAG, "kb_read failed: $path", e)
            Result.failure(e)
        }
    }

    /**
     * Full-text search across all .md files in the vault.
     * Returns up to 10 results, newest-modified first.
     */
    fun search(query: String): Result<List<SearchResult>> {
        return try {
            val vault = vaultDir()
            val results = vault.walkTopDown()
                .filter { it.isFile && it.extension == "md" }
                .mapNotNull { file ->
                    val content = file.readText()
                    if (content.contains(query, ignoreCase = true)) {
                        val rel = file.relativeTo(vault).path
                        SearchResult(rel, extractSnippet(content, query), file.lastModified())
                    } else null
                }
                .sortedByDescending { it.modified }
                .take(10)
                .toList()
            XLog.i(TAG, "kb_search: \"$query\" → ${results.size} results")
            Result.success(results)
        } catch (e: Exception) {
            XLog.e(TAG, "kb_search failed: $query", e)
            Result.failure(e)
        }
    }

    /** Append content to an existing file (does not overwrite). */
    fun append(path: String, content: String): Result<String> {
        return try {
            val file = resolve(path)
            if (!file.exists()) {
                return Result.failure(Exception("File not found: $path — use kb_write to create it first."))
            }
            file.appendText(content)
            XLog.i(TAG, "kb_append: $path (+${content.length} chars)")
            Result.success("Appended to: $path")
        } catch (e: Exception) {
            XLog.e(TAG, "kb_append failed: $path", e)
            Result.failure(e)
        }
    }

    /** List files and sub-folders inside a vault folder. */
    fun list(folder: String): Result<List<String>> {
        return try {
            val dir = resolve(folder)
            if (!dir.exists() || !dir.isDirectory) {
                return Result.failure(Exception("Folder not found: $folder"))
            }
            val entries = dir.listFiles()
                ?.map { if (it.isDirectory) it.name + "/" else it.name }
                ?.sorted()
                ?: emptyList()
            Result.success(entries)
        } catch (e: Exception) {
            XLog.e(TAG, "kb_list failed: $folder", e)
            Result.failure(e)
        }
    }

    /** List every file in the vault (recursively), newest-modified first. Used by the Vault viewer UI. */
    fun listAllFiles(): List<VaultFile> {
        val vault = vaultDir()
        if (!vault.exists()) return emptyList()
        return vault.walkTopDown()
            .filter { it.isFile }
            .map { VaultFile(it.relativeTo(vault).path, it.name, it.length(), it.lastModified()) }
            .sortedByDescending { it.modified }
            .toList()
    }

    /**
     * Read the frontmatter of a vault file (YAML between `---` markers).
     * Returns an empty map if the file has no frontmatter or is not a .md file.
     * Pure read — does not throw.
     */
    fun readFrontmatter(path: String): Map<String, String> {
        return try {
            val file = resolve(path)
            if (!file.exists() || !file.name.endsWith(".md", ignoreCase = true)) return emptyMap()
            val text = file.readText()
            val out = mutableMapOf<String, String>()
            if (!text.startsWith("---")) return out
            val end = text.indexOf("\n---", startIndex = 3)
            if (end < 0) return out
            text.substring(3, end)
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.contains(":") }
                .forEach { line ->
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val key = line.substring(0, idx).trim()
                        val value = line.substring(idx + 1).trim()
                        out[key] = value
                    }
                }
            out
        } catch (e: Exception) {
            XLog.w(TAG, "readFrontmatter failed: $path (${e.message})")
            emptyMap()
        }
    }

    /**
     * P3.3: Find vault files whose frontmatter `provenance` field matches the given origin.
     * Used by `forgetApp` to find and remove vault artifacts tagged with a specific package.
     */
    fun vaultFilesForProvenance(origin: String): List<VaultFile> {
        return try {
            val vault = vaultDir()
            if (!vault.exists()) return emptyList()
            vault.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".md", ignoreCase = true) }
                .mapNotNull { file ->
                    val rel = file.relativeTo(vault).path
                    val fm = readFrontmatter(rel)
                    val prov = fm["provenance"] ?: ""
                    if (prov.endsWith(":$origin") || prov.contains(origin)) rel else null
                }
                .map { rel -> VaultFile(rel, File(rel).name, 0L, 0L) }
                .toList()
        } catch (e: Exception) {
            XLog.w(TAG, "vaultFilesForProvenance failed: $origin (${e.message})")
            emptyList()
        }
    }

    /** Absolute file for a vault-relative path — for FileProvider sharing/opening from the UI. */
    fun absoluteFile(relativePath: String): File = resolve(relativePath)

    /**
     * Delete a file from the vault. Traversal-safe via [resolve] — only files under the
     * vault root are touched. Deleting a parked task checkpoint draft is fine:
     * TaskCheckpointStore.peek() drops the pointer when the file is gone.
     */
    fun delete(path: String): Result<String> {
        return try {
            val file = resolve(path)
            if (!file.exists() || !file.isFile) {
                return Result.failure(Exception("File not found: $path"))
            }
            if (!file.delete()) {
                return Result.failure(Exception("Couldn't delete: $path"))
            }
            XLog.i(TAG, "kb_delete: $path")
            Result.success("Deleted: $path")
        } catch (e: Exception) {
            XLog.e(TAG, "kb_delete failed: $path", e)
            Result.failure(e)
        }
    }

    /**
     * Add a todo item to today's todo file.
     * File: todos/YYYY-MM-DD.md — created automatically if absent.
     */
    fun addTodo(text: String, due: String?, priority: String?): Result<String> {
        return try {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val todoPath = "todos/$today.md"
            val file = resolve(todoPath)
            file.parentFile?.mkdirs()

            if (!file.exists()) {
                val fm = buildFrontmatter(mapOf(
                    "type" to "todo",
                    "date" to today,
                    "tags" to "[todo]"
                ))
                file.writeText("$fm\n\n# Todos — $today\n\n")
            }

            val duePart = if (!due.isNullOrBlank()) " <!-- due: $due -->" else ""
            val priorityPart = when (priority?.lowercase()) {
                "high" -> " [HIGH]"
                "medium" -> " [MED]"
                "low" -> " [LOW]"
                else -> ""
            }
            file.appendText("- [ ]$priorityPart $text$duePart\n")
            XLog.i(TAG, "kb_add_todo: \"$text\" → $todoPath")
            Result.success("Added todo to $todoPath")
        } catch (e: Exception) {
            XLog.e(TAG, "kb_add_todo failed: $text", e)
            Result.failure(e)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildFrontmatter(data: Map<String, Any>): String {
        val sb = StringBuilder("---\n")
        data.forEach { (k, v) -> sb.appendLine("$k: $v") }
        sb.append("---")
        return sb.toString()
    }

    private fun extractSnippet(content: String, query: String, contextChars: Int = 120): String {
        val idx = content.indexOf(query, ignoreCase = true)
        if (idx < 0) return content.take(contextChars)
        val start = maxOf(0, idx - contextChars / 2)
        val end = minOf(content.length, idx + query.length + contextChars / 2)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < content.length) "…" else ""
        return prefix + content.substring(start, end) + suffix
    }
}
