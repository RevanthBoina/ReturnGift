// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.utils;

import android.app.Activity;

/**
 * Checks GitHub Releases for a newer version of ReturnGift.
 * Delegates to AppUpdateManager for verified download, SHA-256 validation,
 * and package installer dispatch.
 */
public class UpdateChecker {

    public static void checkForUpdate(Activity activity) {
        AppUpdateManager.INSTANCE.checkForUpdates(false, state -> {
            if (state instanceof AppUpdateManager.UpdateState.UpdateAvailable) {
                AppUpdateManager.ReleaseInfo releaseInfo = 
                        ((AppUpdateManager.UpdateState.UpdateAvailable) state).getReleaseInfo();
                activity.runOnUiThread(() -> showUpdateDialog(activity, releaseInfo));
            }
            return null;
        });
    }

    /**
     * Show the update dialog for an already-resolved release (used by the dev-channel
     * self-update path, which fetches the rolling dev-latest prerelease directly).
     */
    public static void showUpdateForRelease(Activity activity, AppUpdateManager.ReleaseInfo info) {
        activity.runOnUiThread(() -> showUpdateDialog(activity, info));
    }

    private static void showUpdateDialog(Activity activity, AppUpdateManager.ReleaseInfo info) {
        try {
            android.app.AlertDialog progressDialog = new android.app.AlertDialog.Builder(activity)
                    .setTitle("Downloading Update...")
                    .setMessage("Starting download...")
                    .setCancelable(false)
                    .setNegativeButton("Cancel", (d, w) -> AppUpdateManager.INSTANCE.cancelDownload())
                    .create();

            new android.app.AlertDialog.Builder(activity)
                    .setTitle("Update Available: v" + info.getVersionName())
                    .setMessage("A new version of ReturnGift is available.\n\n" +
                            info.getReleaseNotes() + "\n\n" +
                            "Would you like to install it now?")
                    .setPositiveButton("Update Now", (d, w) -> {
                        progressDialog.show();
                        AppUpdateManager.INSTANCE.startDownload(info, downloadState -> {
                            if (downloadState instanceof AppUpdateManager.UpdateState.Downloading) {
                                AppUpdateManager.UpdateState.Downloading ds = 
                                        (AppUpdateManager.UpdateState.Downloading) downloadState;
                                long mbDownloaded = ds.getDownloadedBytes() / (1024 * 1024);
                                long mbTotal = ds.getTotalBytes() / (1024 * 1024);
                                progressDialog.setMessage("Downloading: " + ds.getProgressPercent() + "% (" + 
                                        mbDownloaded + " MB / " + (mbTotal > 0 ? mbTotal : "?") + " MB)");
                            } else if (downloadState instanceof AppUpdateManager.UpdateState.Verifying) {
                                progressDialog.setMessage("Verifying APK integrity & SHA256...");
                            } else if (downloadState instanceof AppUpdateManager.UpdateState.ReadyToInstall) {
                                progressDialog.dismiss();
                                AppUpdateManager.UpdateState.ReadyToInstall ready = 
                                        (AppUpdateManager.UpdateState.ReadyToInstall) downloadState;
                                AppUpdateManager.INSTANCE.installApk(activity, ready.getApkFile());
                            } else if (downloadState instanceof AppUpdateManager.UpdateState.Failed) {
                                progressDialog.dismiss();
                                AppUpdateManager.UpdateState.Failed f = 
                                        (AppUpdateManager.UpdateState.Failed) downloadState;
                                new android.app.AlertDialog.Builder(activity)
                                        .setTitle("Update Failed")
                                        .setMessage(f.getErrorMessage())
                                        .setPositiveButton("Retry", (rd, rw) -> showUpdateDialog(activity, info))
                                        .setNegativeButton("Cancel", null)
                                        .show();
                            }
                            return null;
                        });
                    })
                    .setNegativeButton("Later", null)
                    .show();
        } catch (Exception e) {
            XLog.w("UpdateChecker", "Failed to show update dialog", e);
        }
    }
}
