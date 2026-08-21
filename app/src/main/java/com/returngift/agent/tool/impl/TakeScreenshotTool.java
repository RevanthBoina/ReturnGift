// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool.impl;

import android.content.ContentValues;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import com.returngift.agent.ClawApplication;
import com.returngift.agent.R;
import com.returngift.agent.agent.knowledge.KBManager;
import com.returngift.agent.service.ClawAccessibilityService;
import com.returngift.agent.tool.BaseTool;
import com.returngift.agent.tool.ToolParameter;
import com.returngift.agent.tool.ToolResult;
import com.returngift.agent.utils.XLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class TakeScreenshotTool extends BaseTool {

    @Override
    public String getName() {
        return "take_screenshot";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_screenshot);
    }

    @Override
    public String getDescriptionEN() {
        return "Take a screenshot of the current screen. Returns the local file path of the saved PNG image. Requires Android 11+ (API 30). " +
               "Set save_to_vault=true to also persist the PNG into the vault (screenshots/<ts>.png) so the user can see and share it, " +
               "or to_gallery=true to also insert it into the system gallery (MediaStore).";
    }

    @Override
    public String getDescriptionCN() {
        return "Take a screenshot of the current screen, save it as a PNG file and return the local file path. Requires Android 11+ (API 30). " +
               "save_to_vault=true additionally saves into the vault; to_gallery=true additionally inserts into the system gallery.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
            new ToolParameter("save_to_vault", "boolean",
                "Also persist the screenshot into the vault at screenshots/<timestamp>.png (default false)", false),
            new ToolParameter("to_gallery", "boolean",
                "Also insert the screenshot into the system gallery via MediaStore (default false)", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }

        Bitmap bitmap = service.takeScreenshot(5000);
        if (bitmap == null) {
            return ToolResult.error("Failed to take screenshot. Requires Android 11+ (API 30).");
        }

        try {
            Bitmap softBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (softBitmap != null) {
                bitmap.recycle();
                bitmap = softBitmap;
            }

            File dir = new File(ClawApplication.Companion.getInstance().getCacheDir(), "screenshots");
            if (!dir.exists()) dir.mkdirs();

            String filename = System.currentTimeMillis() + ".png";
            File file = new File(dir, filename);

            java.io.ByteArrayOutputStream pngBytes = new java.io.ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, pngBytes);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(pngBytes.toByteArray());
            }
            bitmap.recycle();

            StringBuilder outcome = new StringBuilder(file.getAbsolutePath());

            if (optionalBoolean(params, "save_to_vault", false)) {
                String vaultPath = "screenshots/" + filename;
                // saveBytesFromJava: plain-signature wrapper (kotlin.Result cannot be called from Java).
                boolean saved = KBManager.INSTANCE.saveBytesFromJava(vaultPath, pngBytes.toByteArray());
                if (saved) {
                    outcome.append("\nSaved to vault: ").append(vaultPath);
                } else {
                    XLog.w("TakeScreenshotTool", "save_to_vault failed for " + vaultPath);
                    outcome.append("\nVault save failed for ").append(vaultPath);
                }
            }

            if (optionalBoolean(params, "to_gallery", false)) {
                String galleryResult = insertIntoGallery(filename, pngBytes.toByteArray());
                outcome.append("\n").append(galleryResult);
            }

            return ToolResult.success(outcome.toString());
        } catch (Exception e) {
            bitmap.recycle();
            return ToolResult.error("Failed to save screenshot: " + e.getMessage());
        }
    }

    /** Insert the PNG into the system gallery (MediaStore; no storage permission needed on API 29+). */
    private String insertIntoGallery(String filename, byte[] pngBytes) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return "Gallery insert requires Android 10+ (API 29)";
        }
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ReturnGift");
            Uri uri = ClawApplication.Companion.getInstance().getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return "Gallery insert failed (no content URI)";
            }
            try (OutputStream out = ClawApplication.Companion.getInstance().getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    return "Gallery insert failed (no output stream)";
                }
                out.write(pngBytes);
            }
            return "Saved to gallery: Pictures/ReturnGift/" + filename;
        } catch (Exception e) {
            XLog.e("TakeScreenshotTool", "gallery insert failed", e);
            return "Gallery insert failed: " + e.getMessage();
        }
    }
}
