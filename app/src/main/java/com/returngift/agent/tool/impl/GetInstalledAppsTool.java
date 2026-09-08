// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool.impl;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.returngift.agent.ClawApplication;
import com.returngift.agent.R;
import com.returngift.agent.agent.knowledge.AppCatalog;
import com.returngift.agent.tool.BaseTool;
import com.returngift.agent.tool.ToolParameter;
import com.returngift.agent.tool.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Get the list of installed launchable apps on the device (app name + package name).
 * When the target app's package name is unknown, call this tool first to get the list, then use open_app to open it.
 */
public class GetInstalledAppsTool extends BaseTool {

    @Override
    public String getName() {
        return "get_installed_apps";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_get_installed_apps);
    }

    @Override
    public String getDescriptionEN() {
        return "Get a list of all installed launchable apps on the device, including app name and package name. Use this when you don't know the exact package name of an app, then use open_app to launch it.";
    }

    @Override
    public String getDescriptionCN() {
        return "Get a list of all installed launchable apps on the device, including app name and package name. Use this tool when the target app's package name is unknown, then use open_app to open it.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("keyword", "string",
                        "Optional keyword to filter apps by name (case-insensitive). If empty, returns all apps.", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String keyword = optionalString(params, "keyword", "");

        try {
            // W8: Use AppCatalog for instant results
            AppCatalog catalog = AppCatalog.getInstance(ClawApplication.Companion.getInstance());
            List<AppCatalog.AppEntry> entries = catalog.getAllEntries();

            List<String> appList = new ArrayList<>();
            for (AppCatalog.AppEntry entry : entries) {
                if (!keyword.isEmpty()) {
                    String lower = keyword.toLowerCase();
                    if (!entry.getLabel().toLowerCase().contains(lower
                            && !entry.getPackageName().toLowerCase().contains(lower)) {
                        continue;
                    }
                }

                appList.add(entry.getLabel() + " | " + entry.getPackageName());
            }

            if (appList.isEmpty()) {
                return ToolResult.success("No apps found matching keyword: " + keyword);
            }

            Collections.sort(appList);
            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(appList.size()).append(" apps:\n");
            for (String app : appList) {
                sb.append(app).append("\n");
            }
            return ToolResult.success(sb.toString());
        } catch (Exception e) {
            return ToolResult.error("Failed to get installed apps: " + e.getMessage());
        }
    }
}
