// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

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
 *   allowed        INT   — 1 = ON, 0 = OFF
 *   first_seen_at  INT   — Unix ms
 *   updated_at     INT   — Unix ms
 *
 * Default for any newly-encountered app is ALLOWED = 1.
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
                $COL_ALLOWED    INTEGER NOT NULL DEFAULT 1,
                $COL_FIRST_SEEN INTEGER NOT NULL,
                $COL_UPDATED    INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // reserved for future migrations
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns true if the agent is allowed to act in the given app package.
     *
     * If the package has never been seen before it is inserted with allowed=1
     * and this method returns true (caller must show first-time prompt separately).
     */
    fun isAllowed(packageName: String): Boolean {
        val db = readableDatabase
        db.rawQuery(
            "SELECT $COL_ALLOWED FROM $TABLE WHERE $COL_PKG = ?",
            arrayOf(packageName)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(0) == 1
            }
        }
        // Never seen — insert with default ON and tell caller
        touchApp(packageName, label = "", allowed = true)
        return true
    }

    /**
     * Returns true if this package appears in the store for the first time.
     * Call this BEFORE isAllowed() when you need to show the "allow once / add
     * to allow-list" prompt on first encounter.
     */
    fun isFirstEncounter(packageName: String): Boolean {
        val db = readableDatabase
        db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE $COL_PKG = ?",
            arrayOf(packageName)
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(0) == 0
        }
        return true
    }

    /** Record a package as seen (adds with allowed=1 if new). */
    fun touchApp(packageName: String, label: String, allowed: Boolean = true) {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put(COL_PKG, packageName)
            put(COL_LABEL, label)
            put(COL_ALLOWED, if (allowed) 1 else 0)
            put(COL_FIRST_SEEN, now)
            put(COL_UPDATED, now)
        }
        writableDatabase.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        XLog.d(TAG, "touchApp: pkg=$packageName allowed=$allowed")
    }

    /** Set the allowed state for a package (creates row if missing). */
    fun setAllowed(packageName: String, allowed: Boolean, label: String = "") {
        val now = System.currentTimeMillis()
        val existing = getEntry(packageName)
        if (existing == null) {
            touchApp(packageName, label, allowed)
        } else {
            writableDatabase.execSQL(
                "UPDATE $TABLE SET $COL_ALLOWED = ?, $COL_UPDATED = ? WHERE $COL_PKG = ?",
                arrayOf(if (allowed) 1 else 0, now, packageName)
            )
        }
        XLog.i(TAG, "setAllowed: pkg=$packageName allowed=$allowed")
    }

    /** Retrieve all entries ordered by label for the Settings screen. */
    fun getAllEntries(): List<AppEntry> {
        val db = readableDatabase
        val results = mutableListOf<AppEntry>()
        db.rawQuery(
            "SELECT $COL_PKG, $COL_LABEL, $COL_ALLOWED, $COL_FIRST_SEEN FROM $TABLE ORDER BY $COL_LABEL ASC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(
                    AppEntry(
                        packageName  = cursor.getString(0),
                        label        = cursor.getString(1),
                        allowed      = cursor.getInt(2) == 1,
                        firstSeenAt  = cursor.getLong(3)
                    )
                )
            }
        }
        return results
    }

    private fun getEntry(packageName: String): AppEntry? {
        val db = readableDatabase
        db.rawQuery(
            "SELECT $COL_PKG, $COL_LABEL, $COL_ALLOWED, $COL_FIRST_SEEN FROM $TABLE WHERE $COL_PKG = ?",
            arrayOf(packageName)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return AppEntry(
                    packageName = cursor.getString(0),
                    label       = cursor.getString(1),
                    allowed     = cursor.getInt(2) == 1,
                    firstSeenAt = cursor.getLong(3)
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
        val firstSeenAt: Long
    )

    // ── Singleton ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG       = "AppAllowListStore"
        private const val DB_NAME   = "returngift_allowlist.db"
        private const val DB_VERSION = 1
        private const val TABLE     = "app_allow_list"
        private const val COL_PKG   = "package_name"
        private const val COL_LABEL = "label"
        private const val COL_ALLOWED   = "allowed"
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
