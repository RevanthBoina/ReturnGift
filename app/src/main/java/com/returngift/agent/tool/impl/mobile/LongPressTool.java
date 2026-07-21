// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool.impl.mobile;

import com.returngift.agent.ClawApplication;
import com.returngift.agent.R;
import com.returngift.agent.service.ClawAccessibilityService;
import com.returngift.agent.tool.BaseTool;
import com.returngift.agent.tool.ToolParameter;
import com.returngift.agent.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class LongPressTool extends BaseTool {

    @Override
    public String getName() {
        return "long_press";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_long_press);
    }

    @Override
    public String getDescriptionEN() {
        return "Perform a long press at the specified screen coordinates (x, y) for a given duration.";
    }

    @Override
    public String getDescriptionCN() {
        return "Long press at the specified screen coordinates (x, y) for the given duration.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("x", "integer", "X coordinate on screen", true),
                new ToolParameter("y", "integer", "Y coordinate on screen", true),
                new ToolParameter("duration_ms", "integer", "Duration of long press in milliseconds (default 1000)", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        int x = requireInt(params, "x");
        int y = requireInt(params, "y");
        String boundsError = validateCoordinates(x, y);
        if (boundsError != null) return ToolResult.error(boundsError);
        long duration = optionalLong(params, "duration_ms", 1000);
        boolean success = service.performLongPress(x, y, duration);
        return success ? ToolResult.success("Long pressed at (" + x + ", " + y + ") for " + duration + "ms")
                : ToolResult.error("Failed to long press at (" + x + ", " + y + ")");
    }
}
