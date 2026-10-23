// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

import com.returngift.agent.agent.permission.AppAccessMode
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.returngift.agent.utils.XLog

/**
 * Part B — Per-app allow-list store.
 *
 * Persists in the existing local SQLite fact store (separate DB helper that
 * shares the same app-private data directory as ChatDatabase).
 *
 * Schema (table: app_allow_list):
 *   package_name   TEXT  PRIMARY KEY
 *   label          TEXT  — human-readable app label cached at first encounter
 *   allowed        INT   — 1 = ON, 0 = OFF  (derived from mode, kept for callers)
 *   mode           TEXT  — "always" | "ask" | "blocked"  (v2)
 *   first_seen_at  INT   — Unix ms
 *   updated_at     INT   — Unix ms
 *
 * [AppAccessMode] is the user-facing tri-state:
 *   ALWAYS  — permanent grant, the agent never asks again for this app
 *   ASK     — temporary: every task asks once (Allow once / Always allow)
 *   BLOCKED — never act in this app (tool calls are blocked, no prompt)
 *
 * Default for a newly-encountered app is ASK, so the permission card — not a
 * silent default — decides whether the agent may act.
 */
class AppAllowListStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    // ── Schema ───────────────────────────────────────────────────────────────

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
                $COL_PKG        TEXT PRIMARY KEY,
                $COL_LABEL      TEXT NOT NULL DEFAULT '',
                $COL_ALLOWED    INTEGER NOT NULL DEFAULT 0,
                $COL_MODE       TEXT NOT NULL DEFAULT 'ask',
                $COL_FIRST_SEEN INTEGER NOT NULL,
                $COL_UPDATED    INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 → v2: introduce the tri-state mode. Existing ON rows become permanent
        // grants ("always"); existing OFF rows become "blocked" (the user had turned
        // the app off explicitly); anything brand new defaults to "ask".
        if (oldVersion < 2) {
            runCatching {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_MODE TEXT NOT NULL DEFAULT 'ask'")
                db.execSQL("UPDATE $TABLE SET $COL_MODE = CASE WHEN $COL_ALLOWED = 1 THEN 'always' ELSE 'blocked' END")
                XLog.i(TAG, "migrated allow-list to v2 (mode column)")
            }.onFailure { XLog.w(TAG, "allow-list v2 migration failed", it) }
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns true if the agent may act in [packageName] permanently (mode ALWAYS).
     * Never inserts a row: unknown apps are "ask", which the gate prompts for.
     */
    fun isAllowed(packageName: String): Boolean = modeFor(packageName) == AppAccessMode.ALWAYS

    /**
     * Current access mode, or null when the app has never been seen.
     * Callers use null to detect a first encounter.
     */
    fun modeFor(packageName: String): AppAccessMode? = getEntry(packageName)?.mode

    /** Mode with a safe default for unknown apps ([AppAccessMode.ASK]). */
    fun modeOrDefault(packageName: String): AppAccessMode = modeFor(packageName) ?: AppAccessMode.ASK

    /**
     * Returns true if this package appears in the store for the first time.
     */
    fun isFirstEncounter(packageName: String): Boolean = !hasRow(packageName)

    /** Record a package as seen (adds with mode ASK if new). */
    fun touchApp(packageName: String, label: String, allowed: Boolean = false) {
        touchApp(packageName, label, if (allowed) AppAccessMode.ALWAYS else AppAccessMode.ASK)
    }

    /** Record a package as seen with an explicit mode (adds the row when missing). */
    fun touchApp(packageName: String, label: String, mode: AppAccessMode) {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put(COL_PKG, packageName)
            put(COL_LABEL, label)
            put(COL_ALLOWED, if (mode == AppAccessMode.ALWAYS) 1 else 0)
            put(COL_MODE, mode.storageValue)
            put(COL_FIRST_SEEN, now)
            put(COL_UPDATED, now)
        }
        writableDatabase.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        XLog.d(TAG, "touchApp: pkg=$packageName mode=$mode")
    }

    /** Set the allowed state for a package (true → ALWAYS, false → BLOCKED). */
    fun setAllowed(packageName: String, allowed: Boolean, label: String = "") {
        setMode(packageName, if (allowed) AppAccessMode.ALWAYS else AppAccessMode.BLOCKED, label)
    }

    /** Set the tri-state policy for a package (creates the row if missing). */
    fun setMode(packageName: String, mode: AppAccessMode, label: String = "") {
        val now = System.currentTimeMillis()
        val existing = getEntry(packageName)
        if (existing == null) {
            touchApp(packageName, label, mode)
            // touchApp is INSERT-IGNORE; make sure the mode is applied even if the row
            // appeared concurrently.
            writableDatabase.execSQL(
                "UPDATE $TABLE SET $COL_MODE = ?, $COL_ALLOWED = ?, $COL_UPDATED = ? WHERE $COL_PKG = ?",
                arrayOf(mode.storageValue, if (mode == AppAccessMode.ALWAYS) 1 else 0, now, packageName)
            )
        } else {
            writableDatabase.execSQL(
                "UPDATE $TABLE SET $COL_MODE = ?, $COL_ALLOWED = ?, $COL_UPDATED = ?" +
                    (if (label.isNotBlank()) ", $COL_LABEL = ?" else "") + " WHERE $COL_PKG = ?",
                if (label.isNotBlank()) {
                    arrayOf<Any>(
                        mode.storageValue, if (mode == AppAccessMode.ALWAYS) 1 else 0, now, label, packageName
                    )
                } else {
                    arrayOf<Any>(
                        mode.storageValue, if (mode == AppAccessMode.ALWAYS) 1 else 0, now, packageName
                    )
                }
            )
        }
        XLog.i(TAG, "setMode: pkg=$packageName mode=$mode")
    }

    /** Forget a package entirely (it will be treated as a first encounter again). */
    fun forget(packageName: String) {
        writableDatabase.delete(TABLE, "$COL_PKG = ?", arrayOf(packageName))
        XLog.i(TAG, "forget: pkg=$packageName")
    }

    /** Retrieve all entries ordered by label for the Settings screen. */
    fun getAllEntries(): List<AppEntry> {
        val db = readableDatabase
        val results = mutableListOf<AppEntry>()
        db.rawQuery(
            "SELECT $COL_PKG, $COL_LABEL, $COL_ALLOWED, $COL_FIRST_SEEN, $COL_MODE " +
                "FROM $TABLE ORDER BY $COL_LABEL ASC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val mode = AppAccessMode.fromStorage(cursor.getString(4))
                results.add(
                    AppEntry(
                        packageName = cursor.getString(0),
                        label = cursor.getString(1),
                        allowed = mode == AppAccessMode.ALWAYS,
                        firstSeenAt = cursor.getLong(3),
                        mode = mode,
                    )
                )
            }
        }
        return results
    }

    private fun hasRow(packageName: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM $TABLE WHERE $COL_PKG = ? LIMIT 1",
            arrayOf(packageName)
        ).use { cursor -> return cursor.moveToFirst() }
    }

    private fun getEntry(packageName: String): AppEntry? {
        val db = readableDatabase
        db.rawQuery(
            "SELECT $COL_PKG, $COL_LABEL, $COL_ALLOWED, $COL_FIRST_SEEN, $COL_MODE " +
                "FROM $TABLE WHERE $COL_PKG = ?",
            arrayOf(packageName)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val mode = AppAccessMode.fromStorage(cursor.getString(4))
                return AppEntry(
                    packageName = cursor.getString(0),
                    label = cursor.getString(1),
                    allowed = mode == AppAccessMode.ALWAYS,
                    firstSeenAt = cursor.getLong(3),
                    mode = mode,
                )
            }
        }
        return null
    }

    // ── Data model ───────────────────────────────────────────────────────────

    data class AppEntry(
        val packageName: String,
        val label: String,
        val allowed: Boolean,
        val firstSeenAt: Long,
        val mode: AppAccessMode = if (allowed) AppAccessMode.ALWAYS else AppAccessMode.ASK,
    )

    // ── Singleton ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG       = "AppAllowListStore"
        private const val DB_NAME   = "returngift_allowlist.db"
        private const val DB_VERSION = 2
        private const val TABLE     = "app_allow_list"
        private const val COL_PKG   = "package_name"
        private const val COL_LABEL = "label"
        private const val COL_ALLOWED   = "allowed"
        private const val COL_MODE      = "mode"
        private const val COL_FIRST_SEEN = "first_seen_at"
        private const val COL_UPDATED   = "updated_at"

        @Volatile
        private var instance: AppAllowListStore? = null

        fun getInstance(context: Context): AppAllowListStore =
            instance ?: synchronized(this) {
                instance ?: AppAllowListStore(context).also { instance = it }
            }
    }
}
