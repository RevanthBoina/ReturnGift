// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool.impl.mobile;

import android.graphics.Rect;

import com.returngift.agent.agent.grounding.SemanticTargetResolver;
import com.returngift.agent.service.ClawAccessibilityService;
import com.returngift.agent.tool.BaseTool;
import com.returngift.agent.tool.ToolParameter;
import com.returngift.agent.tool.ToolResult;
import com.returngift.agent.utils.XLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tap a UI element resolved by stable semantic properties (preferred) or by a legacy
 * node ID (backwards-compatible fallback).
 *
 * Dynamic UI grounding: when semantic properties (text / content_desc / resource_id /
 * view_class) are provided, the tool re-queries the current accessibility hierarchy on
 * every call and resolves the target against the live tree — it does NOT trust a
 * previously observed volatile node ID like "n3" that may be stale after a UI transition.
 */
public class TapNodeTool extends BaseTool {

    private static final String TAG = "TapNodeTool";

    @Override
    public String getName() {
        return "tap_node";
    }

    @Override
    public String getDisplayName() {
        return "Tap Node";
    }

    @Override
    public String getDescriptionEN() {
        return "Tap a UI element. PREFER semantic properties (text, content_desc, resource_id, "
                + "view_class) — they are re-resolved against the current screen each call, so they "
                + "stay valid across UI transitions. node_id (e.g. 'n3') is a legacy fallback that "
                + "only works on the most recent get_screen_info snapshot; if it is stale the tool "
                + "re-grounds the target's coordinates against the current hierarchy.";
    }

    @Override
    public String getDescriptionCN() {
        return "Tap a UI element. Prefer semantic properties (text, content_desc, resource_id, "
                + "view_class) which are re-resolved live; node_id is a legacy fallback.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        List<ToolParameter> params = new ArrayList<>();
        params.add(new ToolParameter("node_id", "string",
                "Legacy: node ID from screen info (e.g. n3). Prefer semantic params below.", false));
        params.add(new ToolParameter("text", "string",
                "Exact text of the element to tap (e.g. \"Send\"). Re-resolved live.", false));
        params.add(new ToolParameter("content_desc", "string",
                "Exact content description of the element to tap. Re-resolved live.", false));
        params.add(new ToolParameter("resource_id", "string",
                "Fully-qualified resource id (e.g. \"com.example:id/send_button\"). Most stable.", false));
        params.add(new ToolParameter("view_class", "string",
                "View class name (e.g. \"ImageButton\") for icon buttons with no text.", false));
        return params;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }

        String text = optionalString(params, "text", "");
        String contentDesc = optionalString(params, "content_desc", "");
        String resourceId = optionalString(params, "resource_id", "");
        String viewClass = optionalString(params, "view_class", "");
        String nodeId = optionalString(params, "node_id", "");

        // Prefer semantic resolution (re-queries the live hierarchy).
        boolean hasSemantic = !text.isEmpty() || !contentDesc.isEmpty()
                || !resourceId.isEmpty() || !viewClass.isEmpty();
        if (hasSemantic) {
            SemanticTargetResolver.TargetDescription target = new SemanticTargetResolver.TargetDescription(
                    text.isEmpty() ? null : text,
                    contentDesc.isEmpty() ? null : contentDesc,
                    resourceId.isEmpty() ? null : resourceId,
                    viewClass.isEmpty() ? null : viewClass,
                    null, null, true, true);
            SemanticTargetResolver.ResolvedTarget resolved = SemanticTargetResolver.INSTANCE.resolve(target);
            if (resolved == null) {
                return ToolResult.error("No element matched semantic target "
                        + describeTarget(text, contentDesc, resourceId, viewClass)
                        + " on the current screen. Call get_screen_info to refresh.");
            }
            try {
                int x = resolved.getCenterX();
                int y = resolved.getCenterY();
                String boundsError = validateCoordinates(x, y);
                if (boundsError != null) {
                    return ToolResult.error(boundsError);
                }
                boolean success = service.performTap(x, y);
                XLog.i(TAG, "Semantic tap (" + resolved.getMethod() + ") at (" + x + "," + y + ")");
                return success
                        ? ToolResult.success("Tapped element (" + resolved.getMethod()
                                + ") at (" + x + ", " + y + ")")
                        : ToolResult.error("Failed to tap element at (" + x + ", " + y + ")");
            } finally {
                Rect b = resolved.getBounds();
                // node held by resolved is an obtained copy; recycle it.
                try { resolved.getNode().recycle(); } catch (Exception ignored) {}
            }
        }

        // Legacy: node_id fallback. Re-ground coordinates against the current hierarchy.
        if (nodeId.isEmpty()) {
            return ToolResult.error("Provide at least one of: text, content_desc, resource_id, "
                    + "view_class, or node_id.");
        }
        nodeId = nodeId.replace("[", "").replace("]", "").trim();
        SemanticTargetResolver.ResolvedTarget legacy =
                SemanticTargetResolver.INSTANCE.resolveByLegacyNodeId(nodeId);
        if (legacy == null) {
            return ToolResult.error("Node " + nodeId + " is stale or no longer on screen. "
                    + "Call get_screen_info to refresh, or use a semantic target (text/content_desc/resource_id).");
        }
        try {
            int x = legacy.getCenterX();
            int y = legacy.getCenterY();
            String boundsError = validateCoordinates(x, y);
            if (boundsError != null) return ToolResult.error(boundsError);
            boolean success = service.performTap(x, y);
            return success ? ToolResult.success("Tapped node " + nodeId + " (re-grounded) at (" + x + ", " + y + ")")
                    : ToolResult.error("Failed to tap node " + nodeId + " at (" + x + ", " + y + ")");
        } finally {
            try { legacy.getNode().recycle(); } catch (Exception ignored) {}
        }
    }

    private String describeTarget(String text, String contentDesc, String resourceId, String viewClass) {
        StringBuilder sb = new StringBuilder("{");
        if (!text.isEmpty()) sb.append("text=\"").append(text).append("\" ");
        if (!contentDesc.isEmpty()) sb.append("desc=\"").append(contentDesc).append("\" ");
        if (!resourceId.isEmpty()) sb.append("id=\"").append(resourceId).append("\" ");
        if (!viewClass.isEmpty()) sb.append("class=\"").append(viewClass).append("\" ");
        sb.append("}");
        return sb.toString();
    }
}
