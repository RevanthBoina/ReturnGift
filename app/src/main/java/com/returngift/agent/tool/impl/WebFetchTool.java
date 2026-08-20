// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool.impl;

import com.returngift.agent.ClawApplication;
import com.returngift.agent.R;
import com.returngift.agent.agent.knowledge.KBManager;
import com.returngift.agent.agent.retrieve.WebFetcher;
import com.returngift.agent.tool.BaseTool;
import com.returngift.agent.tool.ToolParameter;
import com.returngift.agent.tool.ToolResult;
import com.returngift.agent.utils.XLog;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetch an external artifact (web page or text resource) over HTTP(S) and bring
 * it ONTO the device: text is returned to the model (truncated at 20k chars) and
 * optionally persisted into the vault (research/<host>-<ts>.md).
 *
 * This closes the "external artifact" gap: the agent can retrieve what the user
 * pointed at instead of hallucinating content, and the artifact contract sees
 * the resulting vault file.
 *
 * No robots-bypass, no CAPTCHA solving, no logins — failures fail honestly.
 */
public class WebFetchTool extends BaseTool {

    private static final String TAG = "WebFetchTool";
    private static final int CONNECT_TIMEOUT_S = 10;
    private static final int READ_TIMEOUT_S = 15;
    private static final String UA =
            "Mozilla/5.0 (Linux; Android) ReturnGift/2.2 (+on-device agent)";

    private static volatile OkHttpClient client;

    @Override
    public String getName() {
        return "web_fetch";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_web_fetch);
    }

    @Override
    public String getDescriptionEN() {
        return "Fetch a web page or text resource by URL and return its readable text (max 20000 chars). " +
                "Use when the task references a URL or external content (article, docs, readme, public file). " +
                "Set save_to_vault=true to persist the content as a Markdown note (path is returned). " +
                "Only http/https public URLs; binary downloads and logins are not supported — report those honestly.";
    }

    @Override
    public String getDescriptionCN() {
        return "通过 URL 抓取网页或文本资源并返回可读文本（最多 20000 字）。" +
                "当任务涉及链接或外部内容时使用。save_to_vault=true 可将内容保存为知识库 Markdown 笔记。" +
                "仅支持公开 http/https 地址；不支持二进制下载和登录——失败请如实报告。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        List<ToolParameter> params = new ArrayList<>();
        params.add(new ToolParameter("url", "string", "The http(s) URL to fetch", true));
        params.add(new ToolParameter("save_to_vault", "string", "Optional: 'true' to persist the fetched text as a vault note (research/<host>.md). Default false.", false));
        return params;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String rawUrl = requireString(params, "url");
        WebFetcher.UrlVerdict verdict = WebFetcher.INSTANCE.validateUrl(rawUrl);
        if (verdict instanceof WebFetcher.UrlVerdict.Rejected) {
            XLog.w(TAG, "URL rejected: " + ((WebFetcher.UrlVerdict.Rejected) verdict).getReason());
            return ToolResult.error("URL rejected: " + ((WebFetcher.UrlVerdict.Rejected) verdict).getReason());
        }
        String url = ((WebFetcher.UrlVerdict.Ok) verdict).getNormalized();
        boolean saveToVault = "true".equalsIgnoreCase(
                optionalString(params, "save_to_vault", "false").trim());

        Request request = new Request.Builder().url(url).header("User-Agent", UA).build();
        long startedAt = System.currentTimeMillis();
        try (Response response = client().newCall(request).execute()) {
            int code = response.code();
            String contentType = response.header("Content-Type");
            if (code == 401 || code == 403) {
                return ToolResult.error("HTTP " + code + " — the resource requires login or blocks access. " +
                        "Do not try to bypass; report to the user that the content is not publicly accessible.");
            }
            if (code == 429) {
                return ToolResult.error("HTTP 429 — rate limited. Try a different source or report the failure honestly.");
            }
            if (!response.isSuccessful()) {
                return ToolResult.error("HTTP " + code + " fetching " + url);
            }
            if (WebFetcher.INSTANCE.isProbablyBinary(contentType)) {
                return ToolResult.error("The URL serves binary content (" + contentType +
                        ") — I can only retrieve text/web pages. Report this honestly instead of claiming the file.");
            }
            ResponseBody body = response.body();
            if (body == null) return ToolResult.error("Empty response body");
            byte[] bytes = readCapped(body, WebFetcher.MAX_BYTES);
            String raw = new String(bytes, charsetFor(contentType));
            String text = WebFetcher.INSTANCE.extractText(raw);
            if (text.isEmpty()) return ToolResult.error("Fetched page contains no readable text");

            kotlin.Pair<String, Boolean> trunc = WebFetcher.INSTANCE.truncate(text, WebFetcher.MAX_CHARS);
            String outText = trunc.getFirst();
            boolean truncated = trunc.getSecond();

            StringBuilder result = new StringBuilder();
            result.append("Fetched ").append(url).append(" (").append(text.length()).append(" chars");
            if (truncated) result.append(", truncated to ").append(WebFetcher.MAX_CHARS);
            result.append("):\n\n").append(outText);

            if (saveToVault) {
                String saved = saveToVault(url, text);
                if (saved != null) {
                    result.append("\n\nSaved to vault: ").append(saved);
                } else {
                    result.append("\n\n(Vault save failed — content is included above.)");
                }
            }
            XLog.i(TAG, "Fetched " + url + " -> " + text.length() + " chars in " +
                    (System.currentTimeMillis() - startedAt) + "ms, saved=" + saveToVault);
            return ToolResult.success(result.toString());
        } catch (IOException e) {
            XLog.e(TAG, "fetch failed: " + url, e);
            return ToolResult.error("Network error fetching " + url + ": " + e.getMessage());
        }
    }

    private static byte[] readCapped(ResponseBody body, long maxBytes) throws IOException {
        java.io.InputStream in = body.byteStream();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                out.write(buf, 0, n - (int) (total - maxBytes));
                break;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static java.nio.charset.Charset charsetFor(String contentType) {
        if (contentType != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("charset=([\\w.-]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(contentType);
            if (m.find()) {
                try {
                    return java.nio.charset.Charset.forName(m.group(1));
                } catch (Exception ignored) {
                }
            }
        }
        return java.nio.charset.StandardCharsets.UTF_8;
    }

    private static String saveToVault(String url, String text) {
        try {
            String host = WebFetcher.INSTANCE.hostOf(url).replaceAll("[^A-Za-z0-9.-]", "_");
            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            String path = "research/" + host + "-" + ts + ".md";
            Map<String, Object> frontmatter = new HashMap<>();
            frontmatter.put("type", "research");
            frontmatter.put("source", url);
            frontmatter.put("date", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));
            // NB: KBManager.write returns kotlin.Result (value class → JVM name
            // mangling, uncallable from Java) — use the Boolean wrapper instead.
            boolean ok = KBManager.INSTANCE.writeFromJava(path, frontmatter,
                    "> Source: " + url + "\n\n" + text);
            return ok ? path : null;
        } catch (Exception e) {
            XLog.e(TAG, "vault save failed", e);
            return null;
        }
    }

    private static OkHttpClient client() {
        OkHttpClient c = client;
        if (c == null) {
            synchronized (WebFetchTool.class) {
                c = client;
                if (c == null) {
                    c = new OkHttpClient.Builder()
                            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
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
