// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.dev

import com.returngift.agent.utils.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Embedded code-modification engine.
 *
 * Uses the GitHub REST API directly (no JGit — the app already depends on OkHttp + org.json)
 * to read a source file, apply a change, validate the result with [KotlinSyntaxValidator],
 * commit it on a short-lived branch, and open a Pull Request.
 *
 * Flow (never pushes to `main` directly):
 *   1. GET /repos/{owner}/{repo}/contents/{path}?ref=main  → current file content + SHA
 *   2. decode base64, apply the requested edit
 *   3. [KotlinSyntaxValidator] — refuse to push uncompilable syntax
 *   4. create a branch from main's HEAD: POST /repos/.../git/refs
 *   5. PUT /repos/.../contents/{path} with the new base64 content + the file SHA on that branch
 *   6. POST /repos/.../pulls to open a PR from the branch → main
 *
 * The PAT ([DevConfig.getGithubToken]) must be a fine-grained token with:
 *   - Contents: Read and Write
 *   - Pull requests: Write
 *   - Metadata: Read
 *
 * CI (auto_build_and_test.yml) runs `./gradlew testDebugUnitTest` + `lintDebug` on the PR
 * and gates the merge — that is the authoritative compile check; the on-device validator
 * is a cheap pre-filter.
 */
object GitHubCodeEngine {

    private const val TAG = "GitHubCodeEngine"
    private const val API_BASE = "https://api.github.com"
    private const val PREVIEW = "application/vnd.github+json"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    sealed class CodeResult {
        data class PullRequestOpened(val prUrl: String, val prNumber: Int, val branch: String) : CodeResult()
        data class ValidationFailed(val errors: List<String>) : CodeResult()
        data class Error(val message: String) : CodeResult()
    }

    /**
     * Read a file from the repo (default branch).
     * Returns (decodedContent, fileSha) or null on failure.
     */
    suspend fun readFile(path: String, ref: String = "main"): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val slug = DevConfig.getRepoSlug()
            val url = "$API_BASE/repos/$slug/contents/${encodePath(path)}?ref=$ref"
            val resp = doRequest("GET", url, body = null) ?: return@withContext null
            if (resp.first != 200) {
                XLog.w(TAG, "readFile $path failed: HTTP ${resp.first} ${resp.second}")
                return@withContext null
            }
            try {
                val json = JSONObject(resp.second)
                val content = json.optString("content", "").replace("\n", "")
                val sha = json.optString("sha", "")
                val decoded = String(Base64.getMimeDecoder().decode(content))
                decoded to sha
            } catch (e: Exception) {
                XLog.e(TAG, "readFile parse failed", e)
                null
            }
        }

    /**
     * Open a PR that applies [newContent] to [path].
     *
     * Steps: validate syntax → create branch from main HEAD → PUT file on branch → open PR.
     * Never commits to main directly.
     */
    suspend fun submitChange(
        path: String,
        newContent: String,
        commitMessage: String,
        prTitle: String,
        prBody: String
    ): CodeResult = withContext(Dispatchers.IO) {
        if (!DevConfig.isConfigured()) {
            return@withContext CodeResult.Error("Developer config missing — set repo + GitHub token in Settings → Developer.")
        }

        // 1. Validate syntax before any network write.
        val validation = KotlinSyntaxValidator.validate(newContent)
        if (!validation.valid) {
            return@withContext CodeResult.ValidationFailed(validation.errors)
        }

        val slug = DevConfig.getRepoSlug()

        // 2. Resolve the current file SHA on main (needed for the Contents PUT).
        val current = readFile(path, "main")
        val fileSha = current?.second ?: ""

        // 3. Get main's commit SHA to branch from.
        val mainSha = getRefCommitSha(slug, "main")
            ?: return@withContext CodeResult.Error("Could not resolve main HEAD SHA (token may lack Metadata read).")

        // 4. Create a short-lived branch.
        val branch = "openhands-dev/${UUID.randomUUID().toString().take(8)}"
        val createBranch = createBranch(slug, branch, mainSha)
        if (!createBranch) {
            return@withContext CodeResult.Error("Could not create branch $branch (token may lack Contents/refs write).")
        }

        // 5. PUT the updated file on the branch.
        val putOk = putFile(slug, path, newContent, fileSha, branch, commitMessage)
        if (!putOk) {
            return@withContext CodeResult.Error("Could not commit $path on branch $branch.")
        }

        // 6. Open the PR.
        val pr = openPullRequest(slug, branch, "main", prTitle, prBody)
        if (pr == null) {
            return@withContext CodeResult.Error("Committed to $branch but failed to open a PR — open it manually.")
        }
        CodeResult.PullRequestOpened(pr.htmlUrl, pr.number, branch)
    }

    // ---- low-level GitHub API helpers ----

    private data class PrOpened(val number: Int, val htmlUrl: String)

    private fun getRefCommitSha(slug: String, branch: String): String? {
        val url = "$API_BASE/repos/$slug/git/refs/heads/$branch"
        val resp = doRequest("GET", url, null) ?: return null
        if (resp.first != 200) return null
        return try {
            JSONObject(resp.second).getJSONObject("object").getString("sha")
        } catch (_: Exception) { null }
    }

    private fun createBranch(slug: String, branch: String, fromSha: String): Boolean {
        val url = "$API_BASE/repos/$slug/git/refs"
        val body = JSONObject().apply {
            put("ref", "refs/heads/$branch")
            put("sha", fromSha)
        }.toString()
        val resp = doRequest("POST", url, body) ?: return false
        return resp.first == 201
    }

    private fun putFile(
        slug: String, path: String, content: String, fileSha: String,
        branch: String, message: String
    ): Boolean {
        val url = "$API_BASE/repos/$slug/contents/${encodePath(path)}"
        val body = JSONObject().apply {
            put("message", message)
            put("content", Base64.getEncoder().encodeToString(content.toByteArray()))
            put("branch", branch)
            if (fileSha.isNotEmpty()) put("sha", fileSha)
        }.toString()
        val resp = doRequest("PUT", url, body) ?: return false
        return resp.first in 200..299
    }

    private fun openPullRequest(
        slug: String, head: String, base: String, title: String, body: String
    ): PrOpened? {
        val url = "$API_BASE/repos/$slug/pulls"
        val payload = JSONObject().apply {
            put("title", title)
            put("head", head)
            put("base", base)
            put("body", body)
        }.toString()
        val resp = doRequest("POST", url, payload) ?: return null
        if (resp.first !in 200..299) return null
        return try {
            val json = JSONObject(resp.second)
            PrOpened(json.getInt("number"), json.optString("html_url"))
        } catch (_: Exception) { null }
    }

    /** Add the Bearer auth + media-type headers and execute; returns (code, body). */
    private fun doRequest(method: String, url: String, body: String?): Pair<Int, String>? {
        val token = DevConfig.getGithubToken()
        if (token.isBlank()) {
            XLog.w(TAG, "No GitHub token configured")
            return null
        }
        val rb = body?.toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", PREVIEW)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "ReturnGift-Dev-Engine")
            .method(method, rb)
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (resp.code !in 200..299) {
                    XLog.w(TAG, "$method $url -> HTTP ${resp.code}: ${respBody.take(300)}")
                }
                resp.code to respBody
            }
        } catch (e: Exception) {
            XLog.e(TAG, "$method $url request failed", e)
            null
        }
    }

    private fun encodePath(path: String): String {
        // URL-encode path segments but keep '/' separators.
        return path.split("/").joinToString("/") { seg ->
            java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
    }
}
