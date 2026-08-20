// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool.impl;

import com.returngift.agent.ClawApplication;
import com.returngift.agent.R;
import com.returngift.agent.agent.retrieve.WebFetcher;
import com.returngift.agent.tool.BaseTool;
import com.returngift.agent.tool.ToolParameter;
import com.returngift.agent.tool.ToolResult;
import com.returngift.agent.utils.XLog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Keyless web search (DuckDuckGo HTML endpoint) — returns title/URL/snippet
 * triples so the agent can FIND external content, then web_fetch the chosen
 * result. No API key, no account; honest failures on rate-limit (202/anomaly
 * challenge) or network errors — never fabricate results.
 */
public class WebSearchTool extends BaseTool {

    private static final String TAG = "WebSearchTool";
    private static final String ENDPOINT = "https://html.duckduckgo.com/html/?q=";
    private static final int TIMEOUT_S = 15;
    private static final int DEFAULT_MAX = 5;
    private static final int HARD_MAX = 10;
    private static final String UA =
            "Mozilla/5.0 (Linux; Android) ReturnGift/2.2 (+on-device agent)";

    private static volatile OkHttpClient client;

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_web_search);
    }

    @Override
    public String getDescriptionEN() {
        return "Search the web (DuckDuckGo) and return the top results as a numbered list of " +
                "title / URL / snippet. Use when the task asks to look something up and no URL is given. " +
                "Then call web_fetch on the most relevant URL to read the actual content. " +
                "If the search fails or is rate-limited, report that honestly — never invent results.";
    }

    @Override
    public String getDescriptionCN() {
        return "搜索网络（DuckDuckGo），返回标题/链接/摘要列表。" +
                "任务要求查资料但未给出链接时使用；随后用 web_fetch 读取最相关的结果。" +
                "搜索失败或被限流时请如实说明——不要编造结果。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        List<ToolParameter> params = new ArrayList<>();
        params.add(new ToolParameter("query", "string", "The search query", true));
        params.add(new ToolParameter("max_results", "string", "Optional: number of results, 1-10 (default 5)", false));
        return params;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String query = requireString(params, "query").trim();
        if (query.isEmpty()) return ToolResult.error("query must not be empty");
        int maxResults = DEFAULT_MAX;
        String maxStr = optionalString(params, "max_results", "").trim();
        if (!maxStr.isEmpty()) {
            try {
                maxResults = Math.max(1, Math.min(HARD_MAX, Integer.parseInt(maxStr)));
            } catch (NumberFormatException ignored) {
            }
        }

        String encoded;
        try {
            encoded = java.net.URLEncoder.encode(query, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return ToolResult.error("Cannot encode query");
        }
        String url = ENDPOINT + encoded;
        Request request = new Request.Builder().url(url).header("User-Agent", UA).build();
        long startedAt = System.currentTimeMillis();
        try (Response response = client().newCall(request).execute()) {
            int code = response.code();
            if (code == 202 || code == 429) {
                return ToolResult.error("Search is rate-limited right now (HTTP " + code +
                        "). Try again later or ask the user for a direct URL — do not fabricate results.");
            }
            if (!response.isSuccessful()) {
                return ToolResult.error("Search failed: HTTP " + code);
            }
            ResponseBody body = response.body();
            if (body == null) return ToolResult.error("Search returned an empty response");
            String html = body.string();
            if (WebFetcher.INSTANCE.isDuckDuckGoChallenge(html)) {
                return ToolResult.error("Search was blocked by a bot challenge. " +
                        "Report this honestly — do not fabricate results.");
            }
            List<WebFetcher.SearchResultItem> results =
                    WebFetcher.INSTANCE.parseDuckDuckGoResults(html, maxResults);
            if (results.isEmpty()) {
                return ToolResult.success("No results found for \"" + query + "\". " +
                        "Rephrase the query or tell the user nothing was found — do not invent content.");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Search results for \"").append(query).append("\":\n");
            int i = 1;
            for (WebFetcher.SearchResultItem r : results) {
                sb.append('\n').append(i++).append(". ").append(r.getTitle())
                        .append("\n   ").append(r.getUrl());
                if (!r.getSnippet().isEmpty()) sb.append("\n   ").append(r.getSnippet());
            }
            sb.append("\n\nNext: web_fetch the most relevant URL to read the full content.");
            XLog.i(TAG, "search '" + query + "' -> " + results.size() + " results in " +
                    (System.currentTimeMillis() - startedAt) + "ms");
            return ToolResult.success(sb.toString());
        } catch (IOException e) {
            XLog.e(TAG, "search failed: " + query, e);
            return ToolResult.error("Network error searching for \"" + query + "\": " + e.getMessage());
        }
    }

    private static OkHttpClient client() {
        OkHttpClient c = client;
        if (c == null) {
            synchronized (WebSearchTool.class) {
                c = client;
                if (c == null) {
                    c = new OkHttpClient.Builder()
                            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .build();
                    client = c;
                }
            }
        }
        return c;
    }
}
