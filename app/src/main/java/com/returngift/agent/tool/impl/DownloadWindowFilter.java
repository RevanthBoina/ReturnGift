// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool.impl;

import java.util.List;

/**
 * Pure time-window selector for import_download (zero android.* imports →
 * JVM-unit-testable). Prevents grabbing a stale download from an earlier
 * failed task: only files added at/after the current task's start timestamp
 * are eligible.
 */
public final class DownloadWindowFilter {

    private DownloadWindowFilter() {}

    /**
     * @param addedEpochMs per-candidate creation time (epoch millis)
     * @param taskStartMs  task start (epoch millis); {@code <= 0} disables the window
     *                     (legacy behavior: newest overall)
     * @return index of the newest candidate inside the window, or -1 when none qualifies
     */
    public static int newestIndex(List<Long> addedEpochMs, long taskStartMs) {
        int best = -1;
        long bestTs = Long.MIN_VALUE;
        for (int i = 0; i < addedEpochMs.size(); i++) {
            Long boxed = addedEpochMs.get(i);
            if (boxed == null) continue;
            long ts = boxed;
            if (taskStartMs > 0 && ts < taskStartMs) continue;
            if (ts > bestTs) {
                bestTs = ts;
                best = i;
            }
        }
        return best;
    }
}
