// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool.impl;

import com.returngift.agent.service.ClawAccessibilityService;
import com.returngift.agent.tool.BaseTool;
import com.returngift.agent.tool.ToolParameter;
import com.returngift.agent.tool.ToolResult;
import com.returngift.agent.utils.XLog;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Deterministic application switching with foreground verification.
 *
 * Handles Recents overlays, stale activities, and task-stack conflicts that made naive
 * open_app-as-switch flaky. ReturnGift remains the background controller; the target app
 * is brought to the foreground and verified there.
 *
 * Strategy:
 *   1. If the target is already foreground, succeed immediately (no-op switch).
 *   2. Otherwise perform a verified foreground launch (resumes the existing task via
 *      REORDER_TO_FRONT rather than starting a new instance).
 *   3. If a system overlay (Recents, dialog) is detected and the target is not
 *      foreground, press Back once to dismiss it, then re-verify/re-launch.
 *   4. Always verify the foreground package after the switch and return a verified
 *      failure state if the target did not come to the front.
 */
public class SwitchAppTool extends BaseTool {

    private static final String TAG = "SwitchAppTool";

    @Override
    public String getName() {
        return "switch_app";
    }

    @Override
    public String getDisplayName() {
        return "Switch App";
    }

    @Override
    public String getDescriptionEN() {
        return "Switch to an app by package name, verifying it reaches the foreground. "
                + "Handles Recents overlays and stale task stacks. Use this instead of "
                + "open_app when the app is already running and you want to resume it. "
                + "Returns a verified failure if the target does not become foreground.";
    }

    @Override
    public String getDescriptionCN() {
        return "Switch to an app by package name, verifying foreground. Handles Recents "
                + "overlays and stale tasks. Returns a verified failure if not foregrounded.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("package_name", "string",
                        "The package name of the app to switch to (e.g. 'com.whatsapp')", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        String packageName = requireString(params, "package_name");
        if (packageName == null || packageName.isEmpty()) {
            return ToolResult.error("package_name is required");
        }

        // 1. Already foreground? No-op switch (avoid disturbing the task stack).
        if (service.isForeground(packageName)) {
            XLog.i(TAG, "switch_app: " + packageName + " already foreground");
            return ToolResult.success("Already in foreground: " + packageName);
        }

        // 2. Verified foreground launch (resumes the existing task).
        ClawAccessibilityService.LaunchResult launch = service.openAppForeground(packageName, 8000L);
        if (launch.success) {
            return ToolResult.success("Switched to and verified foreground: " + packageName
                    + " (foreground=" + launch.foregroundPackage + ")");
        }

        // 3. A system overlay (Recents, permission dialog) may be blocking. Dismiss and retry.
        if (service.isSystemOverlayLikely()) {
            XLog.w(TAG, "switch_app: system overlay likely blocking; pressing Back then retrying");
            try { service.pressBack(); } catch (Exception ignored) {}
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ClawAccessibilityService.LaunchResult retry = service.openAppForeground(packageName, 6000L);
            if (retry.success) {
                return ToolResult.success("Switched to " + packageName
                        + " after dismissing overlay (foreground=" + retry.foregroundPackage + ")");
            }
            return ToolResult.error("Could not switch to " + packageName + " — a system overlay "
                    + "blocked the switch and could not be dismissed. Foreground="
                    + retry.foregroundPackage + ". Try system_key(key=\"home\") then open_app.");
        }

        // 4. Verified failure state.
        return ToolResult.error(launch.error != null ? launch.error
                : ("Failed to switch to " + packageName + ". Foreground=" + launch.foregroundPackage
                        + ". The app may not be installed; call get_installed_apps to verify."));
    }
}
