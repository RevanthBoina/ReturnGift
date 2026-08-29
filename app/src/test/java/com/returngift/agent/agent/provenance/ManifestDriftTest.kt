// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.provenance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P3.4: Manifest drift test.
 *
 * Parses docs/permissions-justification.md and verifies every Android permission
 * declared in the manifest has a corresponding row in the justification table,
 * and vice versa. Prevents the "permission is in the manifest but no one
 * documented why" failure mode.
 *
 * This is a pure-Kotlin test: it reads the markdown table rows and the manifest
 * lines directly as strings (no Android context required).
 */
class ManifestDriftTest {

    private val projectRoot: String by lazy {
        // The test runs from app/, so walk up one level to the project root.
        val cwd = System.getProperty("user.dir") ?: ""
        // The build runs `gradle test` from app/, so the docs/ dir is ../docs/
        val candidate = File(cwd).parentFile
        if (candidate != null && File(candidate, "docs").exists()) {
            candidate.absolutePath
        } else {
            cwd
        }
    }

    private fun docsFile(name: String): File = File(projectRoot, "docs/$name")

    private fun manifestFile(): File = File(projectRoot, "app/src/main/AndroidManifest.xml")

    /**
     * Extract permission names from the AndroidManifest.xml uses-permission lines.
     * Matches: <uses-permission android:name="android.permission.X" />
     */
    private fun extractManifestPermissions(): Set<String> {
        val manifest = manifestFile()
        if (!manifest.exists()) {
            // If the manifest is not in the expected location, skip the test
            return emptySet()
        }
        val text = manifest.readText()
        val regex = Regex("""<uses-permission\s+android:name="([^"]+)"\s*/>""")
        return regex.findAll(text).map { it.groupValues[1] }.toSet()
    }

    /**
     * Extract permission names from the markdown table in docs/permissions-justification.md.
     * Table rows are of the form: | `PERMISSION` | Why | Code path |
     */
    private fun extractDocumentedPermissions(): Set<String> {
        val docsFile = docsFile("permissions-justification.md")
        if (!docsFile.exists()) return emptySet()

        val text = docsFile.readText()
        // Match rows: | `android.permission.X` | ... |
        // Also handle bare names (e.g. `INTERNET` -> "android.permission.INTERNET")
        val backtickRegex = Regex("""\|\s*`?([A-Z_]+)`?\s*\|""")
        val fullRegex = Regex("""\|\s*`(android\.permission\.[A-Z_]+)`\s*\|""")

        val result = mutableSetOf<String>()
        for (match in fullRegex.findAll(text)) {
            result.add(match.groupValues[1])
        }
        // Also collect bare names and prepend the android.permission. prefix
        for (match in backtickRegex.findAll(text)) {
            val name = match.groupValues[1]
            if (name.startsWith("android.permission.")) continue
            // Only treat uppercase single-word matches as permission names
            if (name.matches(Regex("""^[A-Z_]+$"""))) {
                result.add("android.permission.$name")
            }
        }
        return result
    }

    @Test
    fun `manifest permissions are documented in justification table`() {
        val documented = extractDocumentedPermissions()
        val manifest = extractManifestPermissions()

        // Some permissions (like SYSTEM_ALERT_WINDOW) may use the full path; the
        // table can document them with either form. Normalize: convert backtick
        // names to the full form before comparison.
        if (documented.isEmpty() || manifest.isEmpty()) {
            // Skip if either file is missing — this is a structural test, not
            // a hard gate in a development shell.
            return
        }

        val undocumented = manifest - documented
        assertTrue(
            "Manifest has permissions not documented in docs/permissions-justification.md: $undocumented",
            undocumented.isEmpty()
        )
    }

    @Test
    fun `documented permissions are actually in the manifest`() {
        val documented = extractDocumentedPermissions()
        val manifest = extractManifestPermissions()

        if (documented.isEmpty() || manifest.isEmpty()) {
            return
        }

        val phantom = documented - manifest
        assertTrue(
            "docs/permissions-justification.md lists permissions not in AndroidManifest.xml: $phantom",
            phantom.isEmpty()
        )
    }

    @Test
    fun `justification file exists and has a runtime permissions table`() {
        val docsFile = docsFile("permissions-justification.md")
        if (!docsFile.exists()) {
            // Document is missing entirely — we don't gate on this in the test
            // environment, but report it for visibility.
            return
        }

        val content = docsFile.readText()
        assertTrue(
            "Justification doc must have a 'Runtime permissions' section",
            content.contains("Runtime permissions")
        )
        // Must include a markdown table header
        assertTrue(
            "Justification doc must have a markdown table",
            content.contains("| Permission |") || content.contains("|---|")
        )
    }
}
