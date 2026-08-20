// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.retrieve

import java.net.URI

/**
 * Pure-Kotlin core of the web_fetch tool — no android.* imports, so the whole
 * URL policy + HTML-to-text pipeline is JVM-unit-testable without stubs.
 *
 * Security policy (SSRF guard): http/https only, host must be a public DNS name
 * (no IPs, no localhost/private/link-local names, no embedded credentials).
 */
internal object WebFetcher {

    const val MAX_CHARS = 20_000
    const val MAX_BYTES = 2L * 1024 * 1024

    sealed class UrlVerdict {
        data class Ok(val normalized: String) : UrlVerdict()
        data class Rejected(val reason: String) : UrlVerdict()
    }

    fun validateUrl(raw: String): UrlVerdict {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return UrlVerdict.Rejected("empty URL")
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            return UrlVerdict.Rejected("malformed URL: ${e.message}")
        }
        val scheme = uri.scheme?.lowercase()
            ?: return UrlVerdict.Rejected("missing scheme — use http:// or https://")
        if (scheme != "http" && scheme != "https") {
            return UrlVerdict.Rejected("scheme '$scheme' not allowed — only http/https")
        }
        val host = uri.host?.lowercase()
            ?: return UrlVerdict.Rejected("URL has no host")
        if (uri.userInfo != null) return UrlVerdict.Rejected("URLs with embedded credentials are not allowed")
        if (isIpLiteral(host)) return UrlVerdict.Rejected("IP-literal hosts are not allowed (SSRF guard)")
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host.endsWith(".internal") || host.endsWith(".lan") || host == "metadata.google.internal"
        ) return UrlVerdict.Rejected("local/private host names are not allowed (SSRF guard)")
        if (!host.contains('.')) return UrlVerdict.Rejected("single-label host names are not allowed (SSRF guard)")
        return UrlVerdict.Ok(trimmed)
    }

    fun isIpLiteral(host: String): Boolean =
        host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || host.contains(':')

    fun isProbablyBinary(contentType: String?): Boolean {
        val ct = contentType?.lowercase() ?: return false
        return when {
            ct.startsWith("text/") -> false
            ct.contains("json") || ct.contains("xml") || ct.contains("html") -> false
            ct.contains("javascript") || ct.contains("x-www-form-urlencoded") -> false
            ct.contains("markdown") || ct.contains("csv") -> false
            ct.contains("application/octet-stream") -> true
            ct.startsWith("image/") || ct.startsWith("audio/") || ct.startsWith("video/") -> true
            ct.contains("pdf") || ct.contains("zip") || ct.contains("gzip") -> true
            else -> true // unknown application/* — treat as binary, don't garbage the context
        }
    }

    /** Best-effort HTML → readable text: strip scripts/styles/tags, decode entities, collapse whitespace. */
    fun extractText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?is)<!--.*?-->"), " ")
        s = s.replace(Regex("(?is)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?is)</(p|div|li|h[1-6]|tr|section|article|header|footer|blockquote)>"), "\n")
        s = s.replace(Regex("(?is)<li[^>]*>"), "\n- ")
        s = s.replace(Regex("(?is)<[^>]+>"), " ")
        s = decodeEntities(s)
        s = s.replace(Regex("[ \t\\x0B\\f\\r]+"), " ")
        s = s.replace(Regex(" *\\n *"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    internal fun decodeEntities(s: String): String {
        var out = s
        val named = mapOf(
            "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
            "&apos;" to "'", "&nbsp;" to " ", "&mdash;" to "—", "&ndash;" to "–",
            "&hellip;" to "…", "&lsquo;" to "'", "&rsquo;" to "'",
            "&ldquo;" to "\"", "&rdquo;" to "\"", "&copy;" to "©", "&reg;" to "®",
        )
        for ((k, v) in named) out = out.replace(k, v)
        out = out.replace(Regex("&#(\\d+);")) { m ->
            m.groupValues[1].toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value
        }
        out = out.replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value
        }
        return out
    }

    fun truncate(text: String, maxChars: Int = MAX_CHARS): Pair<String, Boolean> =
        if (text.length <= maxChars) text to false
        else text.substring(0, maxChars).trimEnd() to true

    fun hostOf(url: String): String = try {
        URI(url).host ?: "unknown-host"
    } catch (e: Exception) {
        "unknown-host"
    }

    // ── DuckDuckGo HTML search (keyless) ─────────────────────────────────────

    data class SearchResultItem(val title: String, val url: String, val snippet: String)

    private val DDG_TITLE_A = Regex(
        """(?is)<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
    )
    private val DDG_SNIPPET = Regex(
        """(?is)class="result__snippet"[^>]*>(.*?)</a>""",
    )

    /**
     * Parse the keyless html.duckduckgo.com/html/ result page into (title, url, snippet)
     * triples. DDG wraps outbound links as //duckduckgo.com/l/?uddg=<url-encoded target>;
     * results whose target cannot be decoded are skipped. Returns an empty list when the
     * page is a rate-limit/anomaly challenge or markup changed — callers must fail honestly.
     */
    fun parseDuckDuckGoResults(html: String, maxResults: Int = 10): List<SearchResultItem> {
        val out = mutableListOf<SearchResultItem>()
        for (m in DDG_TITLE_A.findAll(html)) {
            if (out.size >= maxResults) break
            val target = decodeDdgTarget(m.groupValues[1]) ?: continue
            val title = cleanInline(m.groupValues[2])
            if (title.isEmpty()) continue
            val window = html.substring(m.range.last + 1, minOf(html.length, m.range.last + 4001))
            val snippet = DDG_SNIPPET.find(window)?.let { cleanInline(it.groupValues[1]) } ?: ""
            out += SearchResultItem(title, target, snippet)
        }
        return out
    }

    /** True when the response looks like DDG's rate-limit / anomaly challenge, not results. */
    fun isDuckDuckGoChallenge(html: String): Boolean =
        html.contains("anomaly-modal") || html.contains("not a robot", ignoreCase = true)

    internal fun decodeDdgTarget(href: String): String? {
        val uddg = Regex("""[?&]uddg=([^&"]+)""").find(href)?.groupValues?.get(1)
        if (uddg != null) {
            val decoded = urlDecode(uddg)
            return decoded.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }
        return when {
            href.startsWith("//") -> "https:$href".takeIf { !it.contains("duckduckgo.com") }
            href.startsWith("http") -> href.takeIf { !it.contains("duckduckgo.com") }
            else -> null
        }
    }

    internal fun urlDecode(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    private fun cleanInline(html: String): String =
        decodeEntities(html.replace(Regex("(?is)<[^>]+>"), " "))
            .replace(Regex("""\s+"""), " ")
            .trim()
}
