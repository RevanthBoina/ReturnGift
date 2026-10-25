// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.flow

/**
 * Deep links that skip UI steps.
 *
 * "Open LinkedIn → search 'zip'" is expensive when driven through the UI: the
 * agent has to find a search affordance that moves between releases, and every
 * extra tap is a chance to land on an ad, a prompt or the wrong tab. Where the
 * app publishes a scheme, one intent replaces that whole ladder.
 *
 * Confidence matters, and it is recorded per entry:
 *  - [LinkConfidence.DOCUMENTED] — the scheme is documented by the app/vendor,
 *    so the agent may treat it as the primary route.
 *  - [LinkConfidence.BEST_EFFORT] — the scheme is plausibly supported but not
 *    guaranteed (LinkedIn accepts `linkedin://` for the feed, search URIs are
 *    version-dependent). The agent must open it, verify the screen changed, and
 *    fall back to the in-app search control when it did not.
 *
 * Pure Kotlin — unit tested in `AppDeepLinkTest`.
 */
enum class LinkConfidence {
    DOCUMENTED,
    BEST_EFFORT,
}

/**
 * @param homeUri   URI that opens the app's main surface, or null when unknown.
 * @param searchUri search URI template containing the literal `{query}` marker,
 *                  or null when the app has no (known) search entry point.
 */
data class AppDeepLink(
    val packageName: String,
    val label: String,
    val homeUri: String? = null,
    val searchUriTemplate: String? = null,
    val confidence: LinkConfidence = LinkConfidence.BEST_EFFORT,
) {
    val supportsSearch: Boolean get() = searchUriTemplate?.contains(QUERY_MARKER) == true

    /** Search URI for [query], or null when unsupported / the query is blank. */
    fun search(query: String): String? {
        val template = searchUriTemplate ?: return null
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null
        if (!template.contains(QUERY_MARKER)) return null
        return template.replace(QUERY_MARKER, AppDeepLinks.encode(trimmed))
    }

    /** One-line hint for the system prompt (empty when nothing is known). */
    fun promptHint(query: String? = null): String {
        val search = query?.let { search(it) }
        val parts = mutableListOf<String>()
        homeUri?.let { parts.add("open `$it`") }
        if (search != null) {
            parts.add("search `$search`")
        } else if (searchUriTemplate != null) {
            parts.add("search `$searchUriTemplate` (with {query} = the URL-encoded term)")
        }
        if (parts.isEmpty()) return ""
        val caveat = if (confidence == LinkConfidence.DOCUMENTED) {
            "Use it first — it skips the in-app search ladder."
        } else {
            "Best effort: open it, verify the screen changed, otherwise use the in-app search control."
        }
        return "$label: " + parts.joinToString(", ") + ". " + caveat
    }

    private companion object {
        const val QUERY_MARKER = "{query}"
    }
}

/** Registry of the deep links we are willing to fire. */
object AppDeepLinks {

    private const val QUERY_MARKER = "{query}"

    private val LINKS: List<AppDeepLink> = listOf(
        // ── Social / professional ───────────────────────────────────────────
        AppDeepLink(
            packageName = "com.linkedin.android",
            label = "LinkedIn",
            homeUri = "linkedin://",
            // LinkedIn's own docs never published a search URI; several builds
            // honour `linkedin://search?keywords=`. Treated as best effort so a
            // silent no-op falls back to the search icon.
            searchUriTemplate = "linkedin://search?keywords=$QUERY_MARKER",
            confidence = LinkConfidence.BEST_EFFORT,
        ),
        AppDeepLink(
            packageName = "com.instagram.android",
            label = "Instagram",
            homeUri = "instagram://app",
            confidence = LinkConfidence.BEST_EFFORT,
        ),
        AppDeepLink(
            packageName = "org.telegram.messenger",
            label = "Telegram",
            homeUri = "tg://",
            searchUriTemplate = "tg://search?q=$QUERY_MARKER",
            confidence = LinkConfidence.BEST_EFFORT,
        ),
        AppDeepLink(
            packageName = "com.twitter.android",
            label = "X",
            homeUri = "twitter://timeline",
            searchUriTemplate = "twitter://search?query=$QUERY_MARKER",
            confidence = LinkConfidence.BEST_EFFORT,
        ),

        // ── Media ───────────────────────────────────────────────────────────
        AppDeepLink(
            packageName = "com.google.android.youtube",
            label = "YouTube",
            homeUri = "vnd.youtube://",
            searchUriTemplate = "vnd.youtube://results?search_query=$QUERY_MARKER",
            confidence = LinkConfidence.DOCUMENTED,
        ),
        AppDeepLink(
            packageName = "com.spotify.music",
            label = "Spotify",
            homeUri = "spotify://",
            searchUriTemplate = "spotify://search/$QUERY_MARKER",
            confidence = LinkConfidence.BEST_EFFORT,
        ),

        // ── Shopping / maps / web ───────────────────────────────────────────
        AppDeepLink(
            packageName = "com.android.vending",
            label = "Play Store",
            homeUri = "market://",
            searchUriTemplate = "market://search?q=$QUERY_MARKER",
            confidence = LinkConfidence.DOCUMENTED,
        ),
        AppDeepLink(
            packageName = "com.google.android.apps.maps",
            label = "Maps",
            searchUriTemplate = "geo:0,0?q=$QUERY_MARKER",
            confidence = LinkConfidence.DOCUMENTED,
        ),
        AppDeepLink(
            packageName = "com.android.chrome",
            label = "Chrome",
            searchUriTemplate = "https://www.google.com/search?q=$QUERY_MARKER",
            confidence = LinkConfidence.DOCUMENTED,
        ),

        // ── Communication ───────────────────────────────────────────────────
        AppDeepLink(
            packageName = "com.google.android.apps.messaging",
            label = "Messages",
            homeUri = "sms:",
            confidence = LinkConfidence.DOCUMENTED,
        ),
        AppDeepLink(
            packageName = "com.google.android.dialer",
            label = "Phone",
            homeUri = "tel:",
            confidence = LinkConfidence.DOCUMENTED,
        ),
        AppDeepLink(
            packageName = "com.android.dialer",
            label = "Phone",
            homeUri = "tel:",
            confidence = LinkConfidence.DOCUMENTED,
        ),
    )

    /** Link for [packageName] (case-insensitive), or null when there is none. */
    fun forPackage(packageName: String?): AppDeepLink? {
        val pkg = packageName?.trim()?.lowercase().orEmpty()
        if (pkg.isEmpty()) return null
        return LINKS.firstOrNull { it.packageName.lowercase() == pkg }
    }

    fun homeUri(packageName: String?): String? = forPackage(packageName)?.homeUri

    /** Search URI for the app, or null when unsupported / unknown package. */
    fun searchUri(packageName: String?, query: String?): String? =
        forPackage(packageName)?.search(query.orEmpty())

    fun supportsSearch(packageName: String?): Boolean = forPackage(packageName)?.supportsSearch == true

    fun all(): List<AppDeepLink> = LINKS

    /** Prompt hint for one package (empty when unknown). */
    fun promptHintFor(packageName: String?, query: String? = null): String =
        forPackage(packageName)?.promptHint(query).orEmpty()

    /** Prompt lines for the apps a task touches (empty when none of them has a link). */
    fun promptSection(packages: List<String>, query: String? = null): String {
        val hints = packages.distinct()
            .mapNotNull { forPackage(it) }
            .map { it.promptHint(query) }
            .filter { it.isNotBlank() }
        if (hints.isEmpty()) return ""
        return buildString {
            append("\n## Direct entry points (prefer these)\n")
            hints.forEach { append("- ").append(it).append('\n') }
        }
    }

    /**
     * Best-effort extraction of the search term from a user request, so the prompt
     * can show a ready-to-use deep link ("search 'zip' on LinkedIn" → `zip`).
     *
     * Returns null when the request has no recognisable search term, so nothing
     * misleading is injected into the prompt.
     */
    fun queryHint(task: String): String? {
        val text = task.trim()
        if (text.isEmpty()) return null
        val patterns = listOf(
            Regex("""search\s+(?:for\s+)?"([^"]{1,60})"""", RegexOption.IGNORE_CASE),
            Regex("""search\s+(?:for\s+)?'([^']{1,60})'""", RegexOption.IGNORE_CASE),
            Regex("""search\s+(?:for\s+)?(.{1,60}?)\s+(?:on|in|at)\s+""", RegexOption.IGNORE_CASE),
            Regex("""search\s+(?:for\s+)?(.{1,60})$""", RegexOption.IGNORE_CASE),
            Regex("""look\s+up\s+(.{1,60}?)\s+(?:on|in)\s+""", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val raw = match.groupValues.getOrNull(1) ?: continue
            val term = raw
                // "zip on LinkedIn" → "zip"; the app name is not part of the query.
                // NOTE: no missingDelimiterValue override — chaining with the original
                // string as the fallback would undo the first cut when the next
                // delimiter is absent.
                .substringBefore(" on ")
                .substringBefore(" in ")
                .substringBefore(" at ")
                .trim()
                .trimEnd('.', ',', '!', '?', ';', ':')
            if (term.length >= 2 &&
                !term.equals("it", ignoreCase = true) &&
                !term.equals("something", ignoreCase = true) &&
                !term.equals("this", ignoreCase = true)
            ) {
                return term
            }
        }
        return null
    }

    /**
     * Percent-encode a query for a URI. Encodes spaces as `%20` (not `+`) so the
     * result is valid in both `http(s)` and custom schemes.
     */
    fun encode(query: String): String =
        java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
}
