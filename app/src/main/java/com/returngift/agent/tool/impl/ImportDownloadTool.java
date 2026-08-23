// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool.impl;

import android.Manifest;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.core.content.ContextCompat;

import com.returngift.agent.ClawApplication;
import com.returngift.agent.agent.knowledge.KBManager;
import com.returngift.agent.tool.BaseTool;
import com.returngift.agent.tool.ToolParameter;
import com.returngift.agent.tool.ToolResult;
import com.returngift.agent.utils.XLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * import_download — copies a file from the system Downloads folder into the vault.
 *
 * The intended flow for external-AI image deliverables: the agent drives the AI app
 * (e.g. Gemini), uses the app's OWN Download/Save control (never a screenshot of the
 * result), then imports the downloaded file here. The result carries the
 * "Saved to vault: <path>" marker so ArtifactContract counts it as a real artifact.
 *
 * Registered once in ToolRegistry.registerCommonTools() — tool registration is global
 * (LangChain4jToolBridge iterates getAllTools()), so both local and cloud loops see it.
 */
public class ImportDownloadTool extends BaseTool {

    private static final String TAG = "ImportDownloadTool";

    @Override
    public String getName() {
        return "import_download";
    }

    @Override
    public String getDisplayName() {
        return "Import Download";
    }

    @Override
    public String getDescriptionEN() {
        return "Import the newest file from the system Downloads folder into the vault. " +
                "Use this after an app's own Download/Save control (e.g. an AI-generated image " +
                "in Gemini) instead of taking a screenshot. Optional name_hint filters by file " +
                "name. Images land in images/, everything else in downloads/. Returns the vault path.";
    }

    @Override
    public String getDescriptionCN() {
        return "將系統「下載」資料夾中最新檔案匯入知識庫。請先透過目標 App 自帶的「下載/儲存」" +
                "按鈕下載（例如 Gemini 生成的圖片），再用此工具匯入；可用 name_hint 過濾檔名。" +
                "圖片存入 images/，其他存入 downloads/。回傳 vault 路徑。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        List<ToolParameter> params = new ArrayList<>();
        params.add(new ToolParameter(
                "name_hint", "string",
                "Optional case-insensitive substring filter on the downloaded file name, e.g. 'gemini' or 'flower'",
                false));
        return params;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String hint = optionalString(params, "name_hint", "").trim().toLowerCase(Locale.US);
        try {
            DownloadCandidate candidate = findNewestDownload(hint);
            if (candidate == null) {
                return ToolResult.error(
                        hint.isEmpty()
                                ? "import_download: no files found in the system Downloads folder"
                                : "import_download: no file in Downloads matches '" + hint + "'");
            }
            byte[] bytes = readAll(candidate);
            if (bytes == null || bytes.length == 0) {
                return ToolResult.error("import_download: could not read '" + candidate.displayName + "'");
            }
            String folder = KBManager.INSTANCE.isImage(candidate.displayName) ? "images" : "downloads";
            String vaultPath = folder + "/" + sanitize(candidate.displayName);
            // saveBytesFromJava: plain-signature wrapper (kotlin.Result cannot be called from Java).
            boolean saved = KBManager.INSTANCE.saveBytesFromJava(vaultPath, bytes);
            if (!saved) {
                XLog.w(TAG, "vault save failed for " + vaultPath);
                return ToolResult.error("import_download: vault save failed for " + vaultPath);
            }
            XLog.i(TAG, "imported " + candidate.displayName + " -> " + vaultPath
                    + " (" + bytes.length + " bytes)");
            return ToolResult.success("Saved to vault: " + vaultPath);
        } catch (Exception e) {
            XLog.e(TAG, "import_download failed", e);
            return ToolResult.error("import_download error: " + e.getMessage());
        }
    }

    // ── Discovery ────────────────────────────────────────────────────────────

    private DownloadCandidate findNewestDownload(String hintLower) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return queryMediaStoreDownloads(hintLower);
        }
        return scanLegacyDownloadsDir(hintLower);
    }

    /** MediaStore.Downloads is readable without storage permission (API 29+). */
    private DownloadCandidate queryMediaStoreDownloads(String hintLower) {
        ContentResolver resolver = ClawApplication.Companion.getInstance().getContentResolver();
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String[] projection = {
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads._ID,
        };
        try (Cursor cursor = resolver.query(
                collection, projection, null, null,
                MediaStore.Downloads.DATE_ADDED + " DESC")) {
            if (cursor == null) return null;
            while (cursor.moveToNext()) {
                String name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME));
                if (name == null) continue;
                if (!hintLower.isEmpty() && !name.toLowerCase(Locale.US).contains(hintLower)) continue;
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID));
                Uri uri = Uri.withAppendedPath(collection, String.valueOf(id));
                return new DownloadCandidate(uri, null, name);
            }
            return null;
        } catch (Exception e) {
            XLog.e(TAG, "MediaStore Downloads query failed", e);
            return null;
        }
    }

    /** Legacy (API 28): direct file scan; needs READ_EXTERNAL_STORAGE granted. */
    private DownloadCandidate scanLegacyDownloadsDir(String hintLower) {
        if (ContextCompat.checkSelfPermission(
                        ClawApplication.Companion.getInstance(),
                        Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            XLog.w(TAG, "READ_EXTERNAL_STORAGE not granted — cannot scan legacy Downloads");
            return null;
        }
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return null;
        File best = null;
        for (File f : files) {
            if (!f.isFile()) continue;
            if (!hintLower.isEmpty()
                    && !f.getName().toLowerCase(Locale.US).contains(hintLower)) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best == null ? null : new DownloadCandidate(null, best, best.getName());
    }

    private byte[] readAll(DownloadCandidate candidate) {
        ClawApplication app = ClawApplication.Companion.getInstance();
        try (InputStream in = candidate.uri != null
                ? app.getContentResolver().openInputStream(candidate.uri)
                : new FileInputStream(candidate.file)) {
            if (in == null) return null;
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            XLog.e(TAG, "read failed: " + candidate.displayName, e);
            return null;
        }
    }

    private String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static final class DownloadCandidate {
        final Uri uri;      // MediaStore path (API 29+)
        final File file;    // legacy path (API 28)
        final String displayName;

        DownloadCandidate(Uri uri, File file, String displayName) {
            this.uri = uri;
            this.file = file;
            this.displayName = displayName;
        }
    }
}
