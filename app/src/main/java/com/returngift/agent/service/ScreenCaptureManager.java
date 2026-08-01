// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.returngift.agent.utils.XLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Screen Capture Manager - captures accessibility tree XML on screen transitions.
 * 
 * Hooks into ClawAccessibilityService window-change callbacks to dump accessibility-tree
 * XML, computes sha256 hash for fixture validation, and supports device profile tagging.
 */
public class ScreenCaptureManager {

    private static final String TAG = "ScreenCaptureManager";
    private static volatile ScreenCaptureManager instance;

    private final Context context;
    private final ExecutorService executor;
    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private final DeviceProfile deviceProfile;
    private final FixtureStore fixtureStore;
    private final ScreenChangeListener listener;

    public interface ScreenChangeListener {
        void onScreenCaptured(String xml, String treeHash, String packageName);
    }

    /**
     * Device profile for fixture tagging.
     * Tag each fixture with its capture profile (e.g., samsung_oneui_6_phone).
     */
    public static class DeviceProfile {
        public final String manufacturer;
        public final String model;
        public final String androidVersion;
        public final String screenDensity;
        public final String profileId;

        public DeviceProfile(Context context) {
            this.manufacturer = android.os.Build.MANUFACTURER.toLowerCase(Locale.US);
            this.model = android.os.Build.MODEL.toLowerCase(Locale.US);
            this.androidVersion = String.valueOf(android.os.Build.VERSION.SDK_INT);
            
            float density = context.getResources().getDisplayMetrics().density;
            this.screenDensity = String.format(Locale.US, "%.0fdpi", density * 160);
            
            // Generate profile ID: manufacturer_model_androidVersion_density
            this.profileId = String.format("%s_%s_android%s_%s",
                this.manufacturer.replaceAll("[^a-z0-9]", ""),
                this.model.replaceAll("[^a-z0-9]", ""),
                this.androidVersion,
                this.screenDensity.replaceAll("[^0-9]", ""));
        }

        public String toFileTag() {
            return profileId;
        }
    }

    /**
     * Fixture store for persisting captured XML trees.
     */
    public static class FixtureStore {
        private static final String FIXTURE_DIR = "screen_fixtures";
        private static final String FIXTURES_DB = "fixtures.db";
        
        private final Context context;
        private final File fixtureDir;

        public FixtureStore(Context context) {
            this.context = context.getApplicationContext();
            this.fixtureDir = new File(context.getCacheDir(), FIXTURE_DIR);
            this.fixtureDir.mkdirs();
            initDatabase();
        }

        private void initDatabase() {
            SQLiteDatabase db = openDatabase();
            db.execSQL("CREATE TABLE IF NOT EXISTS fixtures (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "tree_hash TEXT NOT NULL," +
                "package_name TEXT NOT NULL," +
                "device_profile TEXT NOT NULL," +
                "xml_content BLOB," +
                "captured_at INTEGER NOT NULL," +
                "validated INTEGER DEFAULT 0" +
            ")");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_fixtures_hash ON fixtures(tree_hash)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_fixtures_package ON fixtures(package_name)");
            db.close();
        }

        private SQLiteDatabase openDatabase() {
            return context.openOrCreateDatabase(FIXTURES_DB, Context.MODE_PRIVATE, null);
        }

        public void saveFixture(String treeHash, String packageName, String deviceProfile, byte[] xmlContent) {
            SQLiteDatabase db = openDatabase();
            db.execSQL("INSERT INTO fixtures (tree_hash, package_name, device_profile, xml_content, captured_at) VALUES (?, ?, ?, ?, ?)",
                new Object[]{treeHash, packageName, deviceProfile, xmlContent, System.currentTimeMillis()});
            db.close();
            XLog.i(TAG, "Saved fixture: hash=" + treeHash.substring(0, 12) + "... package=" + packageName);
        }

        public List<FixtureEntry> getFixturesForPackage(String packageName, String deviceProfile) {
            List<FixtureEntry> results = new ArrayList<>();
            SQLiteDatabase db = openDatabase();
            Cursor cursor = db.rawQuery(
                "SELECT id, tree_hash, package_name, device_profile, captured_at, validated FROM fixtures WHERE package_name = ? AND device_profile = ? ORDER BY captured_at DESC",
                new String[]{packageName, deviceProfile});
            while (cursor.moveToNext()) {
                results.add(new FixtureEntry(
                    cursor.getLong(0),
                    cursor.getString(1),
                    cursor.getString(2),
                    cursor.getString(3),
                    cursor.getLong(4),
                    cursor.getInt(5) == 1
                ));
            }
            cursor.close();
            db.close();
            return results;
        }

        public byte[] getXmlContent(long fixtureId) {
            SQLiteDatabase db = openDatabase();
            Cursor cursor = db.rawQuery("SELECT xml_content FROM fixtures WHERE id = ?", new String[]{String.valueOf(fixtureId)});
            byte[] content = null;
            if (cursor.moveToFirst()) {
                content = cursor.getBlob(0);
            }
            cursor.close();
            db.close();
            return content;
        }

        public void markValidated(long fixtureId) {
            SQLiteDatabase db = openDatabase();
            db.execSQL("UPDATE fixtures SET validated = 1 WHERE id = ?", new Object[]{fixtureId});
            db.close();
        }
    }

    public static class FixtureEntry {
        public final long id;
        public final String treeHash;
        public final String packageName;
        public final String deviceProfile;
        public final long capturedAt;
        public final boolean validated;

        public FixtureEntry(long id, String treeHash, String packageName, String deviceProfile, long capturedAt, boolean validated) {
            this.id = id;
            this.treeHash = treeHash;
            this.packageName = packageName;
            this.deviceProfile = deviceProfile;
            this.capturedAt = capturedAt;
            this.validated = validated;
        }
    }

    private ScreenCaptureManager(Context context, ScreenChangeListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.executor = Executors.newSingleThreadExecutor();
        this.deviceProfile = new DeviceProfile(context);
        this.fixtureStore = new FixtureStore(context);
        XLog.i(TAG, "ScreenCaptureManager initialized with profile: " + deviceProfile.profileId);
    }

    public static void init(Context context, ScreenChangeListener listener) {
        if (instance == null) {
            synchronized (ScreenCaptureManager.class) {
                if (instance == null) {
                    instance = new ScreenCaptureManager(context, listener);
                }
            }
        }
    }

    public static ScreenCaptureManager getInstance() {
        return instance;
    }

    public DeviceProfile getDeviceProfile() {
        return deviceProfile;
    }

    public FixtureStore getFixtureStore() {
        return fixtureStore;
    }

    /**
     * Handle accessibility event - capture on window state changes.
     */
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        
        int eventType = event.getEventType();
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
            
            // Skip system packages that change frequently
            if (packageName.startsWith("com.android.") || 
                packageName.startsWith("com.google.android.input") ||
                packageName.isEmpty()) {
                return;
            }

            // Rate limit captures - don't capture more than once per second
            if (!capturing.compareAndSet(false, true)) {
                return;
            }

            final String finalPackageName = packageName;
            executor.execute(() -> {
                try {
                    captureCurrentScreen(finalPackageName);
                } finally {
                    capturing.set(false);
                }
            });
        }
    }

    /**
     * Capture the current screen and build XML tree.
     */
    private void captureCurrentScreen(String packageName) {
        ClawAccessibilityService service = ClawAccessibilityService.getInstance();
        if (service == null) {
            XLog.w(TAG, "Accessibility service not available for capture");
            return;
        }

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            XLog.d(TAG, "No root node available for capture");
            return;
        }

        try {
            // Build XML string
            StringBuilder xml = new StringBuilder();
            xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            xml.append("<accessibility-tree package=\"").append(escapeXml(packageName)).append("\" timestamp=\"").append(System.currentTimeMillis()).append("\">\n");
            buildNodeXml(root, xml, 1);
            xml.append("</accessibility-tree>");

            String xmlString = xml.toString();
            
            // Compute SHA-256 hash
            String treeHash = computeSha256(xmlString);
            
            // Save to fixture store
            byte[] xmlBytes = xmlString.getBytes(StandardCharsets.UTF_8);
            fixtureStore.saveFixture(treeHash, packageName, deviceProfile.profileId, xmlBytes);
            
            // Notify listener
            if (listener != null) {
                listener.onScreenCaptured(xmlString, treeHash, packageName);
            }

            XLog.d(TAG, "Captured screen: " + packageName + " hash=" + treeHash.substring(0, 16) + "...");

        } catch (Exception e) {
            XLog.e(TAG, "Error capturing screen", e);
        } finally {
            root.recycle();
        }
    }

    /**
     * Build XML representation of accessibility node.
     */
    private void buildNodeXml(AccessibilityNodeInfo node, StringBuilder xml, int depth) {
        if (node == null || depth > 20) return; // Limit depth

        String indent = "  ".repeat(depth);
        xml.append(indent).append("<node ");
        
        // Add node attributes
        if (node.getClassName() != null) {
            xml.append("class=\"").append(escapeXml(node.getClassName().toString())).append("\" ");
        }
        if (node.getText() != null) {
            xml.append("text=\"").append(escapeXml(node.getText().toString())).append("\" ");
        }
        if (node.getContentDescription() != null) {
            xml.append("content-desc=\"").append(escapeXml(node.getContentDescription().toString())).append("\" ");
        }
        xml.append("clickable=\"").append(node.isClickable()).append("\" ");
        xml.append("enabled=\"").append(node.isEnabled()).append("\" ");
        xml.append("focusable=\"").append(node.isFocusable()).append("\" ");
        xml.append("focused=\"").append(node.isFocused()).append("\" ");
        xml.append("scrollable=\"").append(node.isScrollable()).append("\" ");
        
        // Add resource ID if available
        String resourceId = node.getViewIdResourceName();
        if (resourceId != null) {
            xml.append("resource-id=\"").append(escapeXml(resourceId)).append("\" ");
        }
        
        xml.append("/>\n");

        // Process children
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                buildNodeXml(child, xml, depth + 1);
                child.recycle();
            }
        }
    }

    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }

    /**
     * Compute SHA-256 hash of string.
     */
    public static String computeSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public void shutdown() {
        executor.shutdown();
    }
}
