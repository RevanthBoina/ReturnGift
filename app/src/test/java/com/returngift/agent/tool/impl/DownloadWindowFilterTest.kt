// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool.impl

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the import_download time-window selector. Two mock MediaStore
 * entries — one added before the task started, one after — and only the
 * post-taskStart entry may be selected.
 */
class DownloadWindowFilterTest {

    private val taskStart = 1_800_000_000_000L

    @Test
    fun `only the post-taskStart entry is selected`() {
        val entries = listOf(
            taskStart - 60_000L,   // stale download from an earlier task
            taskStart + 5_000L,    // the current task's actual output
        )
        assertEquals(1, DownloadWindowFilter.newestIndex(entries, taskStart))
    }

    @Test
    fun `entry exactly at taskStart is inside the window`() {
        val entries = listOf(taskStart - 1L, taskStart)
        assertEquals(1, DownloadWindowFilter.newestIndex(entries, taskStart))
    }

    @Test
    fun `no candidate inside the window returns -1 (honest failure, no stale fallback)`() {
        val entries = listOf(taskStart - 120_000L, taskStart - 60_000L)
        assertEquals(-1, DownloadWindowFilter.newestIndex(entries, taskStart))
    }

    @Test
    fun `newest of several in-window candidates wins`() {
        val entries = listOf(
            taskStart - 60_000L,   // stale
            taskStart + 1_000L,
            taskStart + 9_000L,    // newest in window
            taskStart + 4_000L,
        )
        assertEquals(2, DownloadWindowFilter.newestIndex(entries, taskStart))
    }

    @Test
    fun `window disabled when taskStart unknown (legacy newest-overall)`() {
        val entries = listOf(taskStart - 60_000L, taskStart - 30_000L)
        assertEquals(1, DownloadWindowFilter.newestIndex(entries, 0L))
    }
}
