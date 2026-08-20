// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool.impl;

import com.returngift.agent.ClawApplication;
import com.returngift.agent.R;
import com.returngift.agent.agent.clarify.ClarificationManager;
import com.returngift.agent.tool.BaseTool;
import com.returngift.agent.tool.ToolParameter;
import com.returngift.agent.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ask the user a clarifying question mid-task and WAIT for the answer.
 *
 * The call blocks the agent-loop thread (ClarificationManager parks it on a
 * latch) until the user answers via the chat clarification card, the task is
 * cancelled, or the timeout elapses — so the loop transparently resumes with
 * the answer as this tool's result. This is the clarification-first behavior:
 * resolve ambiguity BEFORE acting instead of guessing and completing.
 */
public class AskUserTool extends BaseTool {

    private static final int MAX_CHOICES = 6;

    @Override
    public String getName() {
        return "ask_user";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_ask_user);
    }

    @Override
    public String getDescriptionEN() {
        return "Ask the user a clarifying question and wait for their answer. Use BEFORE acting when the request is ambiguous, under-specified, or offers multiple valid targets/providers. Pass short 'choices' (semicolon-separated) when you can offer concrete options — the user taps one or replies freely. Never invent missing details yourself; ask instead. The tool result contains the user's answer.";
    }

    @Override
    public String getDescriptionCN() {
        return "向用户提出澄清问题并等待回答。当请求含糊、缺少细节或有多个合理选项时，在行动前先调用此工具。可用分号分隔的 choices 提供可点选项。不要自行编造缺失细节——先询问。工具返回值即为用户的回答。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        List<ToolParameter> params = new ArrayList<>();
        params.add(new ToolParameter("question", "string", "The clarifying question to show the user", true));
        params.add(new ToolParameter("choices", "string", "Optional: concrete answer options, separated by ';' (max " + MAX_CHOICES + "). The user can tap one.", false));
        params.add(new ToolParameter("allow_free_text", "string", "Optional: 'false' to require picking one of the choices. Default true (typed reply allowed).", false));
        return params;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String question = requireString(params, "question");
        if (question.trim().isEmpty()) {
            return ToolResult.error("ask_user requires a non-empty question");
        }

        List<String> choices = new ArrayList<>();
        String rawChoices = optionalString(params, "choices", "");
        if (!rawChoices.trim().isEmpty()) {
            for (String part : rawChoices.split("[;\\n]")) {
                String c = part.trim();
                if (!c.isEmpty() && choices.size() < MAX_CHOICES) choices.add(c);
            }
        }
        boolean allowFreeText = !"false".equalsIgnoreCase(optionalString(params, "allow_free_text", "true").trim());
        if (choices.isEmpty()) allowFreeText = true;

        String answer = ClarificationManager.INSTANCE.request(
                question.trim(), choices, allowFreeText, ClarificationManager.DEFAULT_TIMEOUT_MS);

        if (answer == null || answer.trim().isEmpty()) {
            return ToolResult.error("The user did not answer within " +
                    (ClarificationManager.DEFAULT_TIMEOUT_MS / 1000) + "s (or the task was stopped). " +
                    "Proceed with the safest reasonable default and say so explicitly, or call finish " +
                    "honestly explaining what was missing.");
        }
        return ToolResult.success("User answered: " + answer.trim());
    }
}
