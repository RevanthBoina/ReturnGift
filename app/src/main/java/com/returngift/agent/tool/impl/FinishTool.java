// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool.impl;

import com.returngift.agent.ClawApplication;
import com.returngift.agent.R;
import com.returngift.agent.tool.BaseTool;
import com.returngift.agent.tool.ToolParameter;
import com.returngift.agent.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class FinishTool extends BaseTool {

    @Override
    public String getName() {
        return "finish";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_finish);
    }

    @Override
    public String getDescriptionEN() {
        return "Signal that the current task is complete. Call this when you have successfully accomplished the user's request. Provide a summary of what was done.";
    }

    @Override
    public String getDescriptionCN() {
        return "Mark the current task as complete. Call this tool when the user's request has been successfully fulfilled. Provide a summary of what was accomplished.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("summary", "string", "A brief summary of what was accomplished", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String summary = requireString(params, "summary");
        return ToolResult.success("Task completed: " + summary);
    }
}
