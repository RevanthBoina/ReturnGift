// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.returngift.agent.ClawApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Robust In-Built Update Engine for ReturnGift.
 *
 * Lifecycle / State Flow:
 * Idle -> Checking -> UpdateAvailable / UpToDate
 * UpdateAvailable -> Downloading -> Verifying -> ReadyToInstall -> Installing
 * Any state on error -> Failed (with retry support)
 */
object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val GITHUB_API_LATEST = "https://api.github.com/repos/RevanthBoina/ReturnGift/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 15000
    private const val DEV_UPDATE_THROTTLE_MS = 24L * 60 * 60 * 1000  // 24h
    private const val KV_LAST_DEV_CHECK = "last_dev_update_check"
    private const val DEV_TAG = "dev-latest"
    private const val DEV_ASSET_NAME = "ReturnGift-dev.apk"

    sealed class UpdateState {
        object Idle : UpdateState()
        object Checking : UpdateState()
        data class UpdateAvailable(val releaseInfo: ReleaseInfo) : UpdateState()
        data class UpToDate(val currentVersion: String) : UpdateState()
        data class Downloading(
            val progressPercent: Int,
            val downloadedBytes: Long,
            val totalBytes: Long
        ) : UpdateState()
        data class Verifying(val message: String) : UpdateState()
        data class ReadyToInstall(val apkFile: File, val releaseInfo: ReleaseInfo) : UpdateState()
        object Installing : UpdateState()
        data class Failed(
            val errorMessage: String,
            val canRetry: Boolean,
            val failedPhase: String
        ) : UpdateState()
    }

    data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val versionCode: Int,
        val releaseNotes: String,
        val apkDownloadUrl: String,
        val apkFileName: String,
        val apkSizeBytes: Long,
        val sha256Url: String?,
        val htmlUrl: String
    )

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var downloadJob: Job? = null
    private var lastReleaseInfo: ReleaseInfo? = null

    /**
     * Check GitHub for the latest release.
     */
    fun checkForUpdates(
        force: Boolean = true,
        onResult: ((UpdateState) -> Unit)? = null
    ) {
        scope.launch {
            _updateState.value = UpdateState.Checking
            onResult?.invoke(UpdateState.Checking)

            val result = withContext(Dispatchers.IO) {
                try {
                    val context = ClawApplication.instance
                    val currentVersion = try {
                        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                        pInfo.versionName ?: "1.0.0"
                    } catch (e: Exception) {
                        "1.0.0"
                    }

                    XLog.i(TAG, "Checking for update... Current version: $currentVersion")

                    val conn = (URL(GITHUB_API_LATEST).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/vnd.github.v3+json")
                        setRequestProperty("User-Agent", "ReturnGift-App-Updater")
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                    }

                    val code = conn.responseCode
                    if (code != 200) {
                        val err = "GitHub API returned HTTP $code"
                        XLog.w(TAG, err)
                        return@withContext UpdateState.Failed(
                            errorMessage = err,
                            canRetry = true,
                            failedPhase = "CHECKING"
                        )
                    }

                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseText)

                    val releaseInfo = parseReleaseJson(json)
                    lastReleaseInfo = releaseInfo

                    XLog.i(TAG, "Latest release on GitHub: ${releaseInfo.tagName} (${releaseInfo.versionName})")

                    if (isNewerVersion(releaseInfo.versionName, currentVersion)) {
                        KVUtils.putLong("last_update_check", System.currentTimeMillis())
                        UpdateState.UpdateAvailable(releaseInfo)
                    } else {
                        KVUtils.putLong("last_update_check", System.currentTimeMillis())
                        UpdateState.UpToDate(currentVersion)
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Update check failed", e)
                    UpdateState.Failed(
                        errorMessage = e.message ?: "Failed to connect to update server",
                        canRetry = true,
                        failedPhase = "CHECKING"
                    )
                }
            }

            _updateState.value = result
            onResult?.invoke(result)
        }
    }

    /**
     * Dev-channel OTA: check the rolling `dev-latest` prerelease published by
     * auto_build_and_test.yml on every push to main, and offer to self-update.
     *
     * Uses the user's GitHub PAT ([com.returngift.agent.dev.DevConfig.getGithubToken]) so it
     * works for private repos and to authenticate the asset download. Throttled to once per
     * 24h (the stable-channel throttle was previously written but never read — this path
     * implements the guard explicitly). Set [force] to bypass the throttle (used by the
     * Settings → Developer → Check now action).
     */
    fun checkForDevUpdate(
        force: Boolean = false,
        onResult: ((UpdateState) -> Unit)? = null
    ) {
        if (!com.returngift.agent.dev.DevConfig.isDevChannelEnabled()) {
            onResult?.invoke(UpdateState.UpToDate("dev channel disabled"))
            return
        }
        if (!force) {
            val last = KVUtils.getLong(KV_LAST_DEV_CHECK, 0L)
            if (System.currentTimeMillis() - last < DEV_UPDATE_THROTTLE_MS) {
                XLog.i(TAG, "Dev update check throttled; skipping")
                onResult?.invoke(UpdateState.UpToDate("throttled"))
                return
            }
        }
        scope.launch {
            _updateState.value = UpdateState.Checking
            onResult?.invoke(UpdateState.Checking)
            val result = withContext(Dispatchers.IO) {
                try {
                    val token = com.returngift.agent.dev.DevConfig.getGithubToken()
                    val slug = com.returngift.agent.dev.DevConfig.getRepoSlug()
                    if (token.isBlank()) {
                        return@withContext UpdateState.Failed("No GitHub token configured", true, "CHECKING")
                    }
                    val urlStr = "https://api.github.com/repos/$slug/releases/tags/$DEV_TAG"
                    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/vnd.github.v3+json")
                        setRequestProperty("User-Agent", "ReturnGift-App-Updater")
                        setRequestProperty("Authorization", "Bearer $token")
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                    }
                    if (conn.responseCode != 200) {
                        return@withContext UpdateState.Failed(
                            "Dev release not found (HTTP ${conn.responseCode})", true, "CHECKING")
                    }
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val releaseInfo = parseDevReleaseJson(json)
                    lastReleaseInfo = releaseInfo
                    KVUtils.putLong(KV_LAST_DEV_CHECK, System.currentTimeMillis())
                    // For the dev channel we offer the update whenever a build exists whose
                    // commit differs from the installed one (BuildConfig includes the origin
                    // fingerprint). A versionName/Code check is not reliable for rolling
                    // dev builds, so we always offer and let the user decide.
                    UpdateState.UpdateAvailable(releaseInfo)
                } catch (e: Exception) {
                    XLog.e(TAG, "Dev update check failed", e)
                    UpdateState.Failed(e.message ?: "dev check failed", true, "CHECKING")
                }
            }
            _updateState.value = result
            onResult?.invoke(result)
        }
    }

    private fun parseDevReleaseJson(release: JSONObject): ReleaseInfo {
        val tagName = release.optString("tag_name", DEV_TAG)
        val body = release.optString("body", "ReturnGift dev build (rolling).")
        val htmlUrl = release.optString("html_url", "")
        var apkUrl = ""
        var apkName = DEV_ASSET_NAME
        var apkSize: Long = 0
        var sha256Url: String? = null
        if (release.has("assets")) {
            val assets = release.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                if (name.equals(DEV_ASSET_NAME, ignoreCase = true) || name.endsWith(".apk")) {
                    apkUrl = url
                    apkName = name
                    apkSize = asset.optLong("size", 0)
                }
            }
        }
        // Derive a pseudo versionCode from the release body's commit SHA is not possible
        // without parsing; use a stable high code so the installer treats it as newer than
        // debug builds (debug is code 1). Real gating is CI + signing key.
        val versionCode = 99990000
        return ReleaseInfo(
            tagName = tagName,
            versionName = "dev",
            versionCode = versionCode,
            releaseNotes = body,
            apkDownloadUrl = apkUrl,
            apkFileName = apkName,
            apkSizeBytes = apkSize,
            sha256Url = sha256Url,
            htmlUrl = htmlUrl
        )
    }

    /**
     * Start downloading the release APK with progress tracking.
     */
    fun startDownload(
        releaseInfo: ReleaseInfo? = lastReleaseInfo,
        onProgress: ((UpdateState) -> Unit)? = null
    ) {
        val resolved = releaseInfo ?: lastReleaseInfo ?: run {
            XLog.w(TAG, "startDownload: no release info available")
            _updateState.value = UpdateState.Failed("No release to download", false, "DOWNLOADING")
            return
        }
        downloadJob?.cancel()
        downloadJob = scope.launch {
            _updateState.value = UpdateState.Downloading(0, 0, resolved.apkSizeBytes)
            onProgress?.invoke(_updateState.value)

            val downloadResult = withContext(Dispatchers.IO) {
                try {
                    val context = ClawApplication.instance
                    val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
                    val targetApk = File(updatesDir, "ReturnGift-v${resolved.versionName}.apk")

                    // Clean previous partial download
                    if (targetApk.exists()) {
                        targetApk.delete()
                    }

                    XLog.i(TAG, "Downloading APK from: ${resolved.apkDownloadUrl} -> ${targetApk.absolutePath}")

                    val downloadedFile = downloadFileWithRedirects(
                        urlStr = resolved.apkDownloadUrl,
                        destFile = targetApk,
                        bearerToken = if (resolved.versionName == "dev")
                            com.returngift.agent.dev.DevConfig.getGithubToken() else null
                    ) { progress, current, total ->
                        scope.launch(Dispatchers.Main) {
                            val state = UpdateState.Downloading(progress, current, total)
                            _updateState.value = state
                            onProgress?.invoke(state)
                        }
                    }

                    // Verification phase
                    scope.launch(Dispatchers.Main) {
                        val vState = UpdateState.Verifying("Verifying APK package integrity...")
                        _updateState.value = vState
                        onProgress?.invoke(vState)
                    }

                    val isValid = verifyApk(context, downloadedFile, resolved)
                    if (!isValid) {
                        targetApk.delete()
                        return@withContext UpdateState.Failed(
                            errorMessage = "APK integrity or package verification failed.",
                            canRetry = true,
                            failedPhase = "VERIFYING"
                        )
                    }

                    UpdateState.ReadyToInstall(downloadedFile, resolved)
                } catch (ce: CancellationException) {
                    XLog.i(TAG, "Download cancelled by user")
                    UpdateState.Idle
                } catch (e: Exception) {
                    XLog.e(TAG, "Download or verification failed", e)
                    UpdateState.Failed(
                        errorMessage = e.message ?: "Download failed",
                        canRetry = true,
                        failedPhase = "DOWNLOADING"
                    )
                }
            }

            _updateState.value = downloadResult
            onProgress?.invoke(downloadResult)
        }
    }

    /**
     * Launch the Android package installer for the downloaded APK.
     */
    fun installApk(activity: Activity, apkFile: File) {
        try {
            if (!apkFile.exists()) {
                XLog.e(TAG, "Cannot install: APK file does not exist: ${apkFile.absolutePath}")
                _updateState.value = UpdateState.Failed("APK file missing", true, "INSTALLING")
                return
            }

            // Android 8.0+ Unknown sources check
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    XLog.w(TAG, "Install permission not granted, opening Settings")
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                    activity.startActivity(intent)
                    return
                }
            }

            _updateState.value = UpdateState.Installing

            val contentUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            XLog.i(TAG, "Launching PackageInstaller for contentUri: $contentUri")
            activity.startActivity(installIntent)

        } catch (e: Exception) {
            XLog.e(TAG, "Failed to start installation", e)
            _updateState.value = UpdateState.Failed(
                errorMessage = "Failed to launch installer: ${e.message}",
                canRetry = true,
                failedPhase = "INSTALLING"
            )
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _updateState.value = UpdateState.Idle
    }

    /**
     * Download file following HTTP 301/302 redirects (e.g. GitHub S3 release assets).
     * For private-repo dev assets, pass a [bearerToken] so the redirect target is fetched
     * with authorization.
     */
    private fun downloadFileWithRedirects(
        urlStr: String,
        destFile: File,
        bearerToken: String? = null,
        onProgress: (progress: Int, currentBytes: Long, totalBytes: Long) -> Unit
    ): File {
        var currentUrl = urlStr
        var conn: HttpURLConnection
        var redirects = 0
        val maxRedirects = 5

        while (true) {
            conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "ReturnGift-App-Updater")
                if (!bearerToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $bearerToken")
                }
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }

            val status = conn.responseCode
            if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == HttpURLConnection.HTTP_SEE_OTHER ||
                status == 307 || status == 308
            ) {
                val newUrl = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                currentUrl = newUrl
                redirects++
                if (redirects > maxRedirects) {
                    throw IllegalStateException("Too many HTTP redirects ($redirects)")
                }
                continue
            }
            break
        }

        val totalLength = conn.contentLength.toLong()
        var downloaded: Long = 0

        conn.inputStream.use { input ->
            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var lastProgress = 0

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead

                    if (totalLength > 0) {
                        val progress = ((downloaded * 100) / totalLength).toInt()
                        if (progress != lastProgress) {
                            lastProgress = progress
                            onProgress(progress, downloaded, totalLength)
                        }
                    }
                }
                output.flush()
            }
        }

        return destFile
    }

    /**
     * Verifies downloaded APK integrity: package name matches and archive is valid.
     */
    private fun verifyApk(context: Context, file: File, releaseInfo: ReleaseInfo): Boolean {
        if (!file.exists() || file.length() <= 0) return false

        // 1. Android PackageArchive validation
        val pm = context.packageManager
        val pkgInfo = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_ACTIVITIES)
        if (pkgInfo == null) {
            XLog.e(TAG, "Archive is corrupt: getPackageArchiveInfo returned null")
            return false
        }

        if (pkgInfo.packageName != context.packageName) {
            XLog.e(TAG, "Package name mismatch! Expected: ${context.packageName}, got: ${pkgInfo.packageName}")
            return false
        }

        // 2. Optional SHA256 checksum verification if SHA256SUMS.txt was published
        if (!releaseInfo.sha256Url.isNullOrEmpty()) {
            try {
                val expectedSha = fetchSha256Checksum(releaseInfo.sha256Url, releaseInfo.apkFileName)
                if (!expectedSha.isNullOrEmpty()) {
                    val computedSha = calculateFileSha256(file)
                    if (!computedSha.equals(expectedSha, ignoreCase = true)) {
                        XLog.e(TAG, "SHA256 mismatch! Expected: $expectedSha, calculated: $computedSha")
                        return false
                    }
                    XLog.i(TAG, "SHA-256 verification passed: $computedSha")
                }
            } catch (e: Exception) {
                XLog.w(TAG, "Could not fetch/verify SHA256 checksum, proceeding with package info verification", e)
            }
        }

        XLog.i(TAG, "APK package verified successfully: ${pkgInfo.packageName} v${pkgInfo.versionName}")
        return true
    }

    private fun fetchSha256Checksum(shaUrl: String, apkFileName: String): String? {
        val conn = (URL(shaUrl).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "ReturnGift-App-Updater")
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        if (conn.responseCode != 200) return null
        val lines = conn.inputStream.bufferedReader().use { it.readLines() }
        for (line in lines) {
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size >= 2) {
                val hash = parts[0]
                val name = parts[1]
                if (name == apkFileName || name.endsWith(".apk")) {
                    return hash
                }
            }
        }
        return null
    }

    private fun calculateFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun parseReleaseJson(release: JSONObject): ReleaseInfo {
        val tagName = release.optString("tag_name", "v1.0.0")
        val cleanTag = tagName.replaceFirst("^v", "").replaceFirst("-.*", "")
        val body = release.optString("body", "New release available.")
        val htmlUrl = release.optString("html_url", "")

        var bestApkUrl = htmlUrl
        var bestApkName = "ReturnGift-release.apk"
        var bestApkSize: Long = 0
        var sha256Url: String? = null

        if (release.has("assets")) {
            val assets = release.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                val downloadUrl = asset.optString("browser_download_url", "")
                val size = asset.optLong("size", 0)

                if (name.equals("SHA256SUMS.txt", ignoreCase = true)) {
                    sha256Url = downloadUrl
                }

                if (name == "ReturnGift-release.apk" || name == "ReturnGift.apk") {
                    bestApkUrl = downloadUrl
                    bestApkName = name
                    bestApkSize = size
                } else if (bestApkUrl == htmlUrl && name.endsWith(".apk")) {
                    bestApkUrl = downloadUrl
                    bestApkName = name
                    bestApkSize = size
                }
            }
        }

        // Derive versionCode: major*10000 + minor*100 + patch
        val versionCode = parseVersionCode(cleanTag)

        return ReleaseInfo(
            tagName = tagName,
            versionName = cleanTag,
            versionCode = versionCode,
            releaseNotes = body,
            apkDownloadUrl = bestApkUrl,
            apkFileName = bestApkName,
            apkSizeBytes = bestApkSize,
            sha256Url = sha256Url,
            htmlUrl = htmlUrl
        )
    }

    fun parseVersionCode(versionName: String): Int {
        return try {
            val parts = versionName.split(".")
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            major * 10000 + minor * 100 + patch
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Compare semantic versions. Returns true if remote > local.
     */
    fun isNewerVersion(remote: String, local: String): Boolean {
        try {
            val r = remote.split(".")
            val l = local.split(".")
            val maxLen = maxOf(r.size, l.size)
            for (i in 0 until maxLen) {
                val rv = r.getOrNull(i)?.toIntOrNull() ?: 0
                val lv = l.getOrNull(i)?.toIntOrNull() ?: 0
                if (rv > lv) return true
                if (rv < lv) return false
            }
        } catch (e: Exception) {
            XLog.w(TAG, "Version parse error: remote=$remote local=$local", e)
        }
        return false
    }
}
