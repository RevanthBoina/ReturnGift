// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.retrieve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [WebFetcher] — pure Kotlin, no mocks. Covers the SSRF URL
 * policy, binary content-type detection, and HTML→text extraction. Network I/O
 * itself is covered by the WF1–WF5 device QA section.
 */
class WebFetcherTest {

    // ── URL policy ───────────────────────────────────────────────────────────

    @Test
    fun `valid https URL accepted`() {
        val v = WebFetcher.validateUrl("https://example.com/article")
        assertTrue(v is WebFetcher.UrlVerdict.Ok)
        assertEquals("https://example.com/article", (v as WebFetcher.UrlVerdict.Ok).normalized)
    }

    @Test
    fun `valid http URL accepted`() {
        assertTrue(WebFetcher.validateUrl("http://example.com/x?y=1") is WebFetcher.UrlVerdict.Ok)
    }

    @Test
    fun `empty URL rejected`() {
        assertTrue(WebFetcher.validateUrl("   ") is WebFetcher.UrlVerdict.Rejected)
    }

    @Test
    fun `missing scheme rejected`() {
        assertTrue(WebFetcher.validateUrl("example.com/page") is WebFetcher.UrlVerdict.Rejected)
    }

    @Test
    fun `ftp scheme rejected`() {
        assertTrue(WebFetcher.validateUrl("ftp://example.com/f") is WebFetcher.UrlVerdict.Rejected)
    }

    @Test
    fun `file scheme rejected`() {
        assertTrue(WebFetcher.validateUrl("file:///etc/passwd") is WebFetcher.UrlVerdict.Rejected)
    }

    @Test
    fun `localhost rejected`() {
        assertTrue(WebFetcher.validateUrl("http://localhost:8080/admin") is WebFetcher.UrlVerdict.Rejected)
        assertTrue(WebFetcher.validateUrl("http://foo.localhost/") is WebFetcher.UrlVerdict.Rejected)
    }

    @Test
    fun `private domain suffixes rejected`() {
        assertTrue(WebFetcher.validateUrl("http://router.lan/") is WebFetcher.UrlVerdict.Rejected)
        assertTrue(WebFetcher.validateUrl("http://nas.local/") is WebFetcher.UrlVerdict.Rejected)
        assertTrue(WebFetcher.validateUrl("http://server.internal/") is WebFetcher.UrlVerdict.Rejected)
        assertTrue(WebFetcher.validateUrl("http://metadata.google.internal/") is WebFetcher.UrlVerdict.Rejected)
    }

    @Test
    fun `IP literal hosts rejected`() {
        assertTrue(WebFetcher.validateUrl("http://192.168.1.1/admin") is WebFetcher.UrlVerdict.Rejected)
        assertTrue(WebFetcher.validateUrl("http://10.0.0.5/") is WebFetcher.UrlVerdict.Rejected)
        assertTrue(WebFetcher.validateUrl("http://169.254.169.254/latest/meta-data") is WebFetcher.UrlVerdict.Rejected)
        assertTrue(WebFetcher.validateUrl("http://[::1]:8080/") is WebFetcher.UrlVerdict.Rejected)
    }

    @Test
    fun `single label host rejected`() {
        assertTrue(WebFetcher.validateUrl("http://intranet/page") is WebFetcher.UrlVerdict.Rejected)
    }

    @Test
    fun `embedded credentials rejected`() {
        assertTrue(WebFetcher.validateUrl("https://user:pass@example.com/x") is WebFetcher.UrlVerdict.Rejected)
    }

    // ── content-type policy ──────────────────────────────────────────────────

    @Test
    fun `text content types are not binary`() {
        assertFalse(WebFetcher.isProbablyBinary("text/html; charset=utf-8"))
        assertFalse(WebFetcher.isProbablyBinary("text/plain"))
        assertFalse(WebFetcher.isProbablyBinary("application/json"))
        assertFalse(WebFetcher.isProbablyBinary("application/xhtml+xml"))
        assertFalse(WebFetcher.isProbablyBinary("text/markdown"))
        assertFalse(WebFetcher.isProbablyBinary("text/csv"))
    }

    @Test
    fun `binary content types detected`() {
        assertTrue(WebFetcher.isProbablyBinary("application/pdf"))
        assertTrue(WebFetcher.isProbablyBinary("image/png"))
        assertTrue(WebFetcher.isProbablyBinary("application/zip"))
        assertTrue(WebFetcher.isProbablyBinary("application/octet-stream"))
        assertTrue(WebFetcher.isProbablyBinary("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
    }

    @Test
    fun `missing content type is not assumed binary`() {
        assertFalse(WebFetcher.isProbablyBinary(null))
    }

    // ── HTML → text extraction ───────────────────────────────────────────────

    @Test
    fun `extractText strips scripts styles and tags`() {
        val html = """
            <html><head><style>body{color:red}</style><title>t</title></head>
            <body><h1>Hello</h1><script>alert(1)</script><p>World <b>wide</b> web</p>
            <ul><li>one</li><li>two</li></ul></body></html>
        """.trimIndent()
        val text = WebFetcher.extractText(html)
        assertFalse(text.contains("alert"))
        assertFalse(text.contains("color:red"))
        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("World wide web"))
        assertTrue(text.contains("- one"))
        assertTrue(text.contains("- two"))
    }

    @Test
    fun `extractText decodes entities`() {
        val text = WebFetcher.extractText("<p>Fish &amp; Chips &lt;3 &#8212; &#x2026;</p>")
        assertEquals("Fish & Chips <3 — …", text)
    }

    @Test
    fun `extractText collapses blank line runs`() {
        val text = WebFetcher.extractText("<p>a</p><br><br><br><br><p>b</p>")
        assertFalse(text.contains("\n\n\n"))
        assertTrue(text.contains("a"))
        assertTrue(text.contains("b"))
    }

    @Test
    fun `extractText handles plain text input unchanged`() {
        val text = WebFetcher.extractText("just some plain text, no html")
        assertEquals("just some plain text, no html", text)
    }

    // ── truncation / host ────────────────────────────────────────────────────

    @Test
    fun `truncate caps long text and flags it`() {
        val long = "x".repeat(WebFetcher.MAX_CHARS + 500)
        val (out, truncated) = WebFetcher.truncate(long)
        assertTrue(truncated)
        assertTrue(out.length <= WebFetcher.MAX_CHARS)
        val (short, notTruncated) = WebFetcher.truncate("short")
        assertFalse(notTruncated)
        assertEquals("short", short)
    }

    @Test
    fun `hostOf extracts host and survives garbage`() {
        assertEquals("example.com", WebFetcher.hostOf("https://example.com/a?b=c"))
        assertEquals("unknown-host", WebFetcher.hostOf("not a url at all:::"))
    }
}
