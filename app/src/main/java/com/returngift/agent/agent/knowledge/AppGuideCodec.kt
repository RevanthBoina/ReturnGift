// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.knowledge

import com.returngift.agent.agent.exec.micro.AppSurface

/**
 * Wire format for [AppGuide] rows.
 *
 * Deliberately dependency-free (no Gson/JSON) so the same codec serialises:
 *  - the on-device store (`AppGuideStore`),
 *  - rows pushed by an MCP connector (`AppKnowledgeSource`), and
 *  - test fixtures,
 * and stays unit-testable on a plain JVM.
 *
 * Field separator is `\u001f`, list separator `\u001e` (both stripped from input),
 * so arbitrary user-facing text cannot corrupt the row.
 */
object AppGuideCodec {

    private const val FIELD = '\u001f'
    private const val LIST = '\u001e'
    private const val VERSION = "v1"

    fun encode(guide: AppGuide): String = buildString {
        append(VERSION).append(FIELD)
        append(clean(guide.packageName)).append(FIELD)
        append(clean(guide.label)).append(FIELD)
        append(guide.surface.name).append(FIELD)
        append(encodeList(guide.aliases)).append(FIELD)
        append(encodeList(guide.tips)).append(FIELD)
        append(guide.source.name).append(FIELD)
        append(guide.updatedAt).append(FIELD)
        append(guide.confidence).append(FIELD)
        append(encodeList(guide.searchLabels))
    }

    /** @return the decoded row, or null when the payload is malformed. */
    fun decode(payload: String?): AppGuide? {
        if (payload.isNullOrBlank()) return null
        val parts = payload.split(FIELD)
        if (parts.size < 10 || parts[0] != VERSION) return null
        val pkg = parts[1].trim()
        if (pkg.isEmpty()) return null
        val surface = runCatching { AppSurface.valueOf(parts[3]) }.getOrDefault(AppSurface.UNKNOWN)
        return AppGuide(
            packageName = pkg,
            label = parts[2].ifBlank { pkg },
            surface = surface,
            aliases = decodeList(parts[4]),
            tips = decodeList(parts[5]),
            source = GuideSource.fromStorage(parts[6]),
            updatedAt = parts[7].toLongOrNull() ?: 0L,
            confidence = parts[8].toDoubleOrNull() ?: 1.0,
            searchLabels = decodeList(parts[9]),
        )
    }

    /** Decode a newline-separated payload (what a connector would return). */
    fun decodeAll(payload: String?): List<AppGuide> =
        payload?.lineSequence()
            ?.mapNotNull { decode(it) }
            ?.toList()
            .orEmpty()

    fun encodeAll(guides: List<AppGuide>): String =
        guides.joinToString("\n") { encode(it) }

    private fun encodeList(values: List<String>): String =
        values.map { clean(it) }.filter { it.isNotEmpty() }.joinToString(LIST.toString())

    private fun decodeList(value: String): List<String> =
        if (value.isBlank()) emptyList() else value.split(LIST).filter { it.isNotBlank() }

    private fun clean(value: String): String =
        value.replace(FIELD, ' ').replace(LIST, ' ').replace('\n', ' ').trim()
}
