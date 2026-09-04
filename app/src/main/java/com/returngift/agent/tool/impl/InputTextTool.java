// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool.impl;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;

import com.returngift.agent.ClawApplication;
import com.returngift.agent.R;
import com.returngift.agent.core.telemetry.AdaptiveSettleController;
import com.returngift.agent.service.ClawAccessibilityService;
import com.returngift.agent.tool.BaseTool;
import com.returngift.agent.tool.ToolParameter;
import com.returngift.agent.tool.ToolResult;
import com.returngift.agent.utils.XLog;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class InputTextTool extends BaseTool {

    private static final String TAG = "InputTextTool";

    @Override
    public String getName() {
        return "input_text";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_input_text);
    }

    @Override
    public String getDescriptionEN() {
        return "Input text into a text field. If node_id is provided, taps that node first to focus it, "
                + "then types the text — use this to target a specific field (e.g. To, Subject, Body). "
                + "If node_id is omitted, types into the currently focused field. "
                + "By default clears existing content before inputting (clear_first=true). "
                + "Set clear_first=false to append text without clearing.";
    }

    @Override
    public String getDescriptionCN() {
        return "Input text into a text field. If node_id is provided, taps that node first to focus it, "
                + "then types the text. If node_id is omitted, types into the currently focused field. "
                + "By default clears existing content (clear_first=true). Set clear_first=false to append.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("text", "string", "The text to input", true),
                new ToolParameter("node_id", "string", "Optional: node ID from get_screen_info (e.g. 'n5') to target a specific text field", false),
                new ToolParameter("clear_first", "boolean", "Whether to clear existing text before input (default true)", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }

        String text = requireString(params, "text");
        String nodeId = optionalString(params, "node_id", "");
        boolean clearFirst = optionalBoolean(params, "clear_first", true);
        int[] targetCoords = null;

        // If node_id provided, tap that node first to focus it.
        // Re-ground via the live hierarchy (the node ID may be stale after a transition).
        if (!nodeId.isEmpty()) {
            nodeId = nodeId.replace("[", "").replace("]", "").trim();
            targetCoords = service.getNodeCoordinates(nodeId);
            if (targetCoords == null) {
                return ToolResult.error("Node " + nodeId + " not found. Call get_screen_info first to refresh node IDs.");
            }
            service.performTap(targetCoords[0], targetCoords[1]);
            // State-based settle: wait until an editable node holds focus, not a fixed sleep.
            if (!service.waitForEditableFocus(2000L)) {
                XLog.w(TAG, "Editable focus not acquired after tapping node " + nodeId + "; retrying focus");
            }
        }

        AccessibilityNodeInfo targetNode = waitForTargetEditable(service, targetCoords);

        if (targetNode == null) {
            // Recovery: request keyboard for whatever currently has focus and retry once.
            service.requestKeyboardForFocused();
            targetNode = waitForTargetEditable(service, targetCoords);
        }
        if (targetNode == null) {
            return ToolResult.error("No target text field found"
                    + (nodeId.isEmpty() ? " — no editable field has focus; tap the field first"
                                          : " after tapping node " + nodeId));
        }

        // Use DynamicIMEInjector for resilient text injection
        com.returngift.agent.core.input.DynamicIMEInjector.InjectionResult injection =
                com.returngift.agent.core.input.DynamicIMEInjector.INSTANCE.injectText(service, text, targetNode, clearFirst);

        if (injection.getSuccess()) {
            return ToolResult.success((clearFirst ? "Input text: " : "Appended text: ") + text + " (via " + injection.getMethod() + ")");
        }

        return ToolResult.error("Failed to input text: " + injection.getMessage());
    }

    /**
     * Verifies the expected text was actually entered into the focused field.
     * State-based: re-queries the live focused node rather than trusting the action return value.
     */
    private boolean verifyEnteredText(ClawAccessibilityService service, String expected, boolean clearFirst) {
        // Give the field a brief, bounded moment to commit the text. The settle
        // controller does not declare checked exceptions, so no catch is needed here.
        AdaptiveSettleController.INSTANCE.waitForSettle();
        long deadline = System.currentTimeMillis() + 1500L;
        while (System.currentTimeMillis() < deadline) {
            String current = service.getFocusedEditableText();
            if (current != null) {
                if (clearFirst) {
                    if (current.equals(expected)) return true;
                } else {
                    if (current.contains(expected)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Clear input field: select all → delete
     */
    private void clearNodeText(AccessibilityNodeInfo node) {
        // Select all
        Bundle selectAllArgs = new Bundle();
        selectAllArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0);
        selectAllArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Integer.MAX_VALUE);
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAllArgs);

        // Overwrite selection with empty string
        Bundle clearArgs = new Bundle();
        clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs);
    }

    private boolean trySetTextWithRetries(AccessibilityNodeInfo node, String text, boolean clearFirst) {
        for (int attempt = 0; attempt < 3; attempt++) {
            CharSequence existing = node.getText();
            String candidateText = clearFirst ? text : ((existing != null ? existing.toString() : "") + text);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, candidateText);
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return true;
            }
            // IME composition settle (replaces Thread.sleep(200))
            AdaptiveSettleController.INSTANCE.waitForSettle();
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        return false;
    }

    private AccessibilityNodeInfo waitForTargetEditable(ClawAccessibilityService service, int[] targetCoords) {
        for (int attempt = 0; attempt < 5; attempt++) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) {
                sleepShort();
                continue;
            }

            AccessibilityNodeInfo focused = findFocusedEditText(root);
            if (focused != null) {
                return focused;
            }

            if (targetCoords != null) {
                AccessibilityNodeInfo nearTarget = findEditableNearPoint(root, targetCoords[0], targetCoords[1]);
                if (nearTarget != null) {
                    return nearTarget;
                }
            }

            sleepShort();
        }
        return null;
    }

    private boolean setClipboardText(Context context, String text) {
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] result = {false};

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("input_text", text));
                    result[0] = true;
                }
            } catch (Exception ignored) {
            }
            latch.countDown();
        });

        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return result[0];
    }

    private AccessibilityNodeInfo findFocusedEditText(AccessibilityNodeInfo root) {
        if (root == null) return null;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null && isGenuineEditable(focused)) {
            return focused;
        }
        // Fallback: find first editable node
        return findFirstEditable(root);
    }

    private boolean isGenuineEditable(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isEditable()) return true;
        CharSequence cn = node.getClassName();
        if (cn == null) return false;
        String name = cn.toString();
        return name.contains("EditText") || name.contains("TextInput") ||
               name.contains("SearchAutoComplete") || name.contains("TextField");
    }

    private AccessibilityNodeInfo findEditableNearPoint(AccessibilityNodeInfo node, int x, int y) {
        if (node == null) return null;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        boolean editable = node.isEditable();
        CharSequence className = node.getClassName();
        boolean isEditText = className != null && className.toString().contains("EditText");
        if ((editable || isEditText) && bounds.contains(x, y)) {
            return node;
        }

        AccessibilityNodeInfo best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo candidate = findEditableNearPoint(child, x, y);
            if (candidate != null) {
                Rect candidateBounds = new Rect();
                candidate.getBoundsInScreen(candidateBounds);
                int dx = candidateBounds.centerX() - x;
                int dy = candidateBounds.centerY() - y;
                int distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private AccessibilityNodeInfo findFirstEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo result = findFirstEditable(child);
            if (result != null) {
                // Don't recycle child if it's the result itself
                if (result != child) {
                    child.recycle();
                }
                return result;
            }
            child.recycle();
        }
        return null;
    }

    private void sleepShort() {
        // UI settle (replaces Thread.sleep(200))
        AdaptiveSettleController.INSTANCE.waitForSettle();
    }
}
