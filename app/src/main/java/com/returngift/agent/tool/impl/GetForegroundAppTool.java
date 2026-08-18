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
 * System-state API: report the currently foregrounded app package without any UI navigation.
 *
 * Replaces the brittle pattern of opening Recents or navigating the launcher to discover
 * what is running. Uses the deterministic accessibility foreground detection centralized
 * in {@link ClawAccessibilityService#getForegroundPackage()}.
 */
public class GetForegroundAppTool extends BaseTool {

    private static final String TAG = "GetForegroundApp";

    @Override
    public String getName() {
        return "get_foreground_app";
    }

    @Override
    public String getDisplayName() {
        return "Foreground App";
    }

    @Override
    public String getDescriptionEN() {
        return "Report the package name of the app currently in the foreground, without any "
                + "UI navigation. Use this to verify which app is active before acting, or to "
                + "check whether an open_app / switch_app succeeded.";
    }

    @Override
    public String getDescriptionCN() {
        return "Report the foreground app package without UI navigation. Use to verify which "
                + "app is active.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.emptyList();
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        String pkg = service.getForegroundPackage();
        boolean overlay = service.isSystemOverlayLikely();
        if (pkg == null) {
            return ToolResult.error("Could not determine the foreground app (the screen may be off, locked, or the accessibility root is unavailable).");
        }
        XLog.i(TAG, "Foreground=" + pkg + " overlayLikely=" + overlay);
        String msg = "Foreground app: " + pkg + (overlay ? " (a system overlay is likely on top)" : "");
        return ToolResult.success(msg);
    }
}
