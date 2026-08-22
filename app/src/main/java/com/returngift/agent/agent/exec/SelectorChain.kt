// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec

/**
 * Ordered selector chain (pure Kotlin, JVM-unit-testable).
 *
 * Priority per the bounded-executor spec — most stable/semantic first:
 *   text → content description → resource ID → accessibility properties
 *   (clickable/editable + class) → coordinates (LAST resort).
 *
 * node_id values are ephemeral per snapshot and are NEVER part of a chain —
 * a stale node is recovered by one re-read + semantic rediscovery, never by
 * reusing the ID.
 */
data class SelectorChain(
    val text: String? = null,
    val contentDesc: String? = null,
    val resourceId: String? = null,
    val viewClass: String? = null,
    /** Accessibility-property hints, e.g. "clickable", "editable". */
    val a11yProps: Set<String> = emptySet(),
    val x: Int? = null,
    val y: Int? = null,
) {
    init {
        require(
            text != null || contentDesc != null || resourceId != null ||
                viewClass != null || (x != null && y != null)
        ) { "SelectorChain needs at least one selector candidate" }
    }

    /** Ordered candidates, most stable first. */
    fun orderedCandidates(): List<Candidate> {
        val out = mutableListOf<Candidate>()
        text?.takeIf { it.isNotBlank() }?.let { out.add(Candidate(Candidate.Kind.TEXT, it)) }
        contentDesc?.takeIf { it.isNotBlank() }?.let { out.add(Candidate(Candidate.Kind.CONTENT_DESC, it)) }
        resourceId?.takeIf { it.isNotBlank() }?.let { out.add(Candidate(Candidate.Kind.RESOURCE_ID, it)) }
        if (viewClass != null || a11yProps.isNotEmpty()) {
            out.add(Candidate(Candidate.Kind.A11Y_PROPS, viewClass ?: "", a11yProps))
        }
        if (x != null && y != null) out.add(Candidate(Candidate.Kind.COORDINATES, "$x,$y"))
        return out
    }

    data class Candidate(
        val kind: Kind,
        val value: String,
        val a11yProps: Set<String> = emptySet(),
    ) {
        enum class Kind { TEXT, CONTENT_DESC, RESOURCE_ID, A11Y_PROPS, COORDINATES }
    }

    companion object {
        /**
         * Parse an AI-escalation response of the form
         * {"text":"…","content_desc":"…","resource_id":"…","class":"…","x":1,"y":2}
         * Values are untrusted — everything is optional, blank strings dropped.
         */
        fun fromEscalationJson(json: Map<String, Any?>): SelectorChain? {
            fun str(k: String) = (json[k] as? String)?.takeIf { it.isNotBlank() }
            fun num(k: String) = (json[k] as? Number)?.toInt()
            return try {
                SelectorChain(
                    text = str("text"),
                    contentDesc = str("content_desc") ?: str("contentDesc"),
                    resourceId = str("resource_id") ?: str("resourceId"),
                    viewClass = str("class") ?: str("viewClass"),
                    x = num("x"),
                    y = num("y"),
                )
            } catch (e: IllegalArgumentException) {
                null // no usable selector candidate in the response
            }
        }
    }
}
