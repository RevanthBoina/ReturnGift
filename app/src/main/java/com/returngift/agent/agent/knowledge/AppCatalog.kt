// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.knowledge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Handler
import android.os.Looper
import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog
import com.returngift.agent.tool.impl.OpenAppTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * App address registry — built at setup time, persisted as JSON.
 * Provides instant resolution of app names to exact launch addresses.
 */
class AppCatalog(private val context: Context) {

    companion object {
        private const val TAG = "AppCatalog"
        private const val STORE_KEY = "app_catalog_json"
        private const val BUILT_FLAG = "app_catalog_built"
        private const val REBUILD_AFTER_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
        private const val SEED_ALIASES_KEY = "seed_aliases"
        
        @Volatile private var instance: AppCatalog? = null
        
        @JvmStatic
        fun getInstance(context: Context): AppCatalog {
            return instance ?: synchronized(this) {
                instance ?: AppCatalog(context.applicationContext).also { instance = it }
            }
        }
        
        @JvmStatic
        fun invalidate() {
            instance = null
        }
    }

    data class AppEntry(
        val label: String,
        val packageName: String,
        val componentName: String,
        val aliases: List<String>,
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("label", label)
                put("packageName", packageName)
                put("componentName", componentName)
                put("aliases", JSONArray(aliases))
            }
        }
        
        companion object {
            fun fromJson(json: JSONObject): AppEntry {
                val aliases = mutableListOf<String>()
                val aliasesArray = json.getJSONArray("aliases")
                for (i in 0 until aliasesArray.length()) {
                    aliases.add(aliasesArray.getString(i))
                }
                return AppEntry(
                    label = json.getString("label"),
                    packageName = json.getString("packageName"),
                    componentName = json.getString("componentName"),
                    aliases = aliases,
                )
            }
        }
    }

    private val entries = mutableMapOf<String, AppEntry>()
    private var lastBuiltMs: Long = 0

    init {
        loadFromStore()
        // Seed with hardcoded common names from OpenAppTool
        seedCommonAliases()
    }

    private fun seedCommonAliases() {
        // Add well-known app aliases that might not be installed
        val seedMap = mapOf(
            "linkedin" to "com.linkedin.android",
            "wa" to "com.whatsapp",
            "whatsapp" to "com.whatsapp",
            "ig" to "com.instagram.android",
            "instagram" to "com.instagram.android",
            "yt" to "com.google.android.youtube",
            "youtube" to "com.google.android.youtube",
            "maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "chrome" to "com.android.chrome",
            "photos" to "com.google.android.apps.photos",
            "drive" to "com.google.android.apps.docs",
            "calendar" to "com.google.android.calendar",
        )
        
        for ((alias, pkg) in seedMap) {
            // We don't have component names for uninstalled apps, but the alias helps resolution
            // when the app IS installed and we build the catalog
        }
    }

    /**
     * Build the full catalog by querying PackageManager for all launcher activities.
     * Runs on IO dispatcher. Updates KV store and writes vault markdown.
     */
    fun build(onProgress: ((Int, Int) -> Unit)? = null): Int {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = pm.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS)
        
        val newEntries = mutableMapOf<String, AppEntry>()
        var count = 0
        
        for (info in activities) {
            if (shouldAbort()) return count
            
            val label = info.loadLabel(pm).toString()
            val pkg = info.activityInfo.packageName
            val cls = info.activityInfo.name
            val component = "$pkg/$cls"
            
            // Generate aliases
            val aliases = mutableSetOf<String>()
            aliases.add(label.lowercase())
            aliases.add(label.lowercase().replace(" ", ""))
            label.split(" ").firstOrNull()?.let { aliases.add(it.lowercase()) }
            
            // Add seed aliases for this package
            when (pkg) {
                "com.linkedin.android" -> aliases.addAll(listOf("linkedin", "linked in"))
                "com.whatsapp" -> aliases.addAll(listOf("wa", "whatsapp"))
                "com.instagram.android" -> aliases.addAll(listOf("ig", "instagram"))
                "com.google.android.youtube" -> aliases.addAll(listOf("yt", "youtube"))
                "com.google.android.apps.maps" -> aliases.add("maps")
                "com.google.android.gm" -> aliases.add("gmail")
                "com.android.chrome" -> aliases.add("chrome")
                "com.google.android.apps.photos" -> aliases.add("photos")
                "com.google.android.apps.docs" -> aliases.add("drive")
                "com.google.android.calendar" -> aliases.add("calendar")
            }
            
            val entry = AppEntry(label, pkg, component, aliases.toList())
            newEntries[pkg] = entry
            newEntries[label.lowercase()] = entry
            for (alias in aliases) {
                newEntries[alias] = entry
            }
            count++
            
            onProgress?.invoke(count, activities.size)
        }
        
        entries.clear()
        entries.putAll(newEntries)
        lastBuiltMs = System.currentTimeMillis()
        saveToStore()
        writeVaultMarkdown()
        KVUtils.putBoolean(BUILT_FLAG, true)
        KVUtils.putLong("app_catalog_last_built", lastBuiltMs)
        
        XLog.i(TAG, "Built app catalog with $count entries")
        return count
    }

    private fun shouldAbort(): Boolean = false // placeholder for future cancellation

    /**
     * Resolve an app name to its package name.
     * @return package name if found, null otherwise
     */
    fun resolve(name: String): String? {
        val trimmed = name.trim().lowercase()
        if (trimmed.isEmpty()) return null
        
        // Exact alias hit
        entries[trimmed]?.let { return it.packageName }
        
        // Fuzzy scoring (port from OpenAppTool)
        var bestMatch: AppEntry? = null
        var bestScore = 0
        
        for (entry in entries.values.distinct()) {
            val score = scoreMatch(trimmed, entry)
            if (score > bestScore) {
                bestScore = score
                bestMatch = entry
            }
        }
        
        if (bestScore >= 2) { // threshold for fuzzy match
            XLog.d(TAG, "Fuzzy resolved '$name' -> ${bestMatch?.packageName} (score=$bestScore)")
            return bestMatch?.packageName
        }
        
        return null
    }

    private fun scoreMatch(query: String, entry: AppEntry): Int {
        var score = 0
        val labelLower = entry.label.lowercase()
        
        // Exact match on label
        if (query == labelLower) return 100
        if (query == entry.packageName) return 100
        
        // Contains
        if (labelLower.contains(query)) score += 10
        if (entry.packageName.contains(query)) score += 10
        
        // Prefix
        if (labelLower.startsWith(query)) score += 5
        if (entry.packageName.startsWith(query)) score += 5
        
        // Alias prefix
        for (alias in entry.aliases) {
            if (alias == query) return 50
            if (alias.startsWith(query)) score += 3
            if (alias.contains(query)) score += 2
        }
        
        return score
    }

    fun getAllEntries(): List<AppEntry> = entries.values.distinct().toList()

    /** Invalidate a single package (e.g., after install/uninstall). */
    fun invalidatePackage(packageName: String) {
        entries.values
            .filter { it.packageName == packageName }
            .forEach { entries.remove(it.label.lowercase()) }
        entries.remove(packageName)
        saveToStore()
    }

    /** Rebuild if older than 7 days. */
    fun maybeRebuildIfStale() {
        if (System.currentTimeMillis() - lastBuiltMs > REBUILD_AFTER_MS) {
            CoroutineScope(Dispatchers.IO).launch { build() }
        }
    }

    private fun saveToStore() {
        try {
            val json = JSONObject()
            val array = JSONArray()
            for (entry in entries.values.distinct()) {
                array.put(entry.toJson())
            }
            json.put("entries", array)
            json.put("lastBuilt", lastBuiltMs)
            KVUtils.putString(STORE_KEY, json.toString())
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to save catalog", e)
        }
    }

    private fun loadFromStore() {
        val jsonStr = KVUtils.getString(STORE_KEY, "")
        if (jsonStr.isBlank()) return
        try {
            val json = JSONObject(jsonStr)
            lastBuiltMs = json.optLong("lastBuilt", 0)
            val array = json.getJSONArray("entries")
            for (i in 0 until array.length()) {
                val entry = AppEntry.fromJson(array.getJSONObject(i))
                entries[entry.packageName] = entry
                entries[entry.label.lowercase()] = entry
                for (alias in entry.aliases) {
                    entries[alias] = entry
                }
            }
            XLog.d(TAG, "Loaded catalog with ${entries.values.distinct().size} entries from store")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to load catalog", e)
        }
    }

    /**
     * Build catalog on first accessibility connection if not already built.
     * Shows a progress toast and runs on IO dispatcher.
     */
    fun maybeBuildOnFirstConnect(context: Context) {
        val alreadyBuilt = KVUtils.getBoolean(BUILT_FLAG, false)
        if (alreadyBuilt) {
            maybeRebuildIfStale()
            return
        }
        
        // Show progress toast
        android.widget.Toast.makeText(
            context,
            "Indexing your apps so tasks can find them — please wait…",
            android.widget.Toast.LENGTH_LONG
        ).show()
        
        CoroutineScope(Dispatchers.IO).launch {
            val count = build { current, total ->
                // Could update a progress dialog here if needed
                XLog.d(TAG, "App catalog build: $current/$total")
            }
            
            // Update UI on main thread
            Handler(Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    context,
                    "Indexed $count apps. Ready!",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            
            XLog.i(TAG, "App catalog built on first connect: $count apps")
        }
    }

    /** Write apps/app-registry.md to vault for kb_search awareness. */
    private fun writeVaultMarkdown() {
        val lines = mutableListOf<String>()
        lines.add("# App Registry")
        lines.add("")
        lines.add("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
        lines.add("")
        
        for (entry in entries.values.distinct().sortedBy { it.label }) {
            val aliasStr = entry.aliases.joinToString(", ")
            lines.add("- ${entry.label} — ${entry.packageName} — ${entry.componentName} (alias: $aliasStr)")
        }
        
        val content = lines.joinToString("\n")
        com.returngift.agent.agent.KBManager.write("apps/app-registry.md", content)
        XLog.d(TAG, "Wrote vault markdown for app registry")
    }
}