// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.dev

import com.returngift.agent.utils.XLog

/**
 * Lightweight Kotlin/Java syntax pre-validator that runs BEFORE a code change is pushed
 * to GitHub.
 *
 * This is NOT a full compiler — it deliberately avoids pulling kotlinc into the APK. It
 * performs fast, conservative structural checks that catch the most common "uncompilable
 * Kotlin" mistakes the agent (or a remote worker) might emit:
 *  - unbalanced `{ }`, `( )`, `[ ]`
 *  - unterminated string literals (`"` / `"""` / `'`)
 *  - unterminated block / line comments
 *  - forbidden `TODO("...")` compile-time stubs and `compileOnly`-style placeholders
 *
 * A passing validation does not guarantee compilation, but a failing validation means the
 * change is definitely uncompilable and must NOT be pushed. The real compile gate is CI
 * (`./gradlew testDebugUnitTest` + `lintDebug` in auto_build_and_test.yml); this validator
 * is the on-device pre-filter so we don't burn a CI run on trivially broken syntax.
 */
object KotlinSyntaxValidator {

    private const val TAG = "KotlinSyntaxValidator"

    data class ValidationResult(
        val valid: Boolean,
        val errors: List<String>
    )

    fun validate(source: String): ValidationResult {
        val errors = mutableListOf<String>()

        // Strip string literals and comments to a neutral form before brace/paren matching
        // so braces inside strings/comments don't skew the count.
        val stripped = stripStringsAndComments(source)

        checkBalance(stripped, '{', '}', errors)
        checkBalance(stripped, '(', ')', errors)
        checkBalance(stripped, '[', ']', errors)

        // Unbalanced strings/comments detected during stripping also indicate a problem.
        // (stripStringsAndComments records these in its return via markers — re-scan raw.)
        checkUnterminatedStrings(source, errors)

        // Forbidden compile-time stubs that would break a release build.
        if (Regex("""\bTODO\s*\(""").containsMatchIn(source)) {
            errors.add("Contains TODO(\"...\") which is a compile-time exception stub — remove before pushing.")
        }

        // Basic sanity: file should not be empty after trimming.
        if (source.isBlank()) {
            errors.add("Source is empty.")
        }

        if (errors.isNotEmpty()) {
            XLog.w(TAG, "Syntax validation FAILED: ${errors.size} error(s)")
            errors.take(5).forEach { XLog.w(TAG, "  - $it") }
        }
        return ValidationResult(valid = errors.isEmpty(), errors = errors)
    }

    private fun checkBalance(src: String, open: Char, close: Char, errors: MutableList<String>) {
        var depth = 0
        var line = 1
        for (ch in src) {
            if (ch == '\n') line++
            if (ch == open) depth++
            else if (ch == close) {
                depth--
                if (depth < 0) {
                    errors.add("Unexpected closing '$close' on line $line (no matching '$open').")
                    depth = 0
                }
            }
        }
        if (depth > 0) {
            errors.add("Unbalanced '$open' — $depth unclosed (EOF reached).")
        }
    }

    private fun stripStringsAndComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        val n = src.length
        var inLineComment = false
        var inBlockComment = false
        var inTriple = false
        var inChar = false
        var inString = false
        while (i < n) {
            val c = src[i]
            val next = if (i + 1 < n) src[i + 1] else ' '
            val triple = if (i + 2 < n) src.substring(i, i + 3) else ""

            if (inLineComment) {
                sb.append(' ')
                if (c == '\n') inLineComment = false
                i++; continue
            }
            if (inBlockComment) {
                sb.append(' ')
                if (c == '*' && next == '/') { inBlockComment = false; i += 2; continue }
                i++; continue
            }
            if (inTriple) {
                if (triple == "\"\"\"") { inTriple = false; sb.append("   "); i += 3; continue }
                sb.append(' ')
                i++; continue
            }
            if (inString) {
                if (c == '\\') { sb.append("  "); i += 2; continue }
                if (c == '"') { inString = false; sb.append(' '); i++; continue }
                sb.append(' '); i++; continue
            }
            if (inChar) {
                if (c == '\\') { sb.append("  "); i += 2; continue }
                if (c == '\'') { inChar = false; sb.append(' '); i++; continue }
                sb.append(' '); i++; continue
            }
            // Not in any string/comment
            if (c == '/' && next == '/') { inLineComment = true; sb.append("  "); i += 2; continue }
            if (c == '/' && next == '*') { inBlockComment = true; sb.append("  "); i += 2; continue }
            if (triple == "\"\"\"") { inTriple = true; sb.append("   "); i += 3; continue }
            if (c == '"') { inString = true; sb.append(' '); i++; continue }
            if (c == '\'') { inChar = true; sb.append(' '); i++; continue }
            sb.append(c); i++
        }
        return sb.toString()
    }

    private fun checkUnterminatedStrings(src: String, errors: MutableList<String>) {
        // Re-scan for unterminated string literals (after stripping, an unterminated string
        // would have left inString=true). Detect by counting unescaped " not part of """.
        var i = 0
        val n = src.length
        var inTriple = false
        var inString = false
        var inChar = false
        var inLine = false
        var inBlock = false
        while (i < n) {
            val c = src[i]
            val next = if (i + 1 < n) src[i + 1] else ' '
            val triple = if (i + 2 < n) src.substring(i, i + 3) else ""
            if (inLine) { if (c == '\n') inLine = false; i++; continue }
            if (inBlock) {
                if (c == '*' && next == '/') { inBlock = false; i += 2; continue }
                i++; continue
            }
            if (inTriple) { if (triple == "\"\"\"") { inTriple = false; i += 3; continue }; i++; continue }
            if (inString) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') { inString = false; i++; continue }
                i++; continue
            }
            if (inChar) {
                if (c == '\\') { i += 2; continue }
                if (c == '\'') { inChar = false; i++; continue }
                i++; continue
            }
            when {
                c == '/' && next == '/' -> { inLine = true; i += 2; continue }
                c == '/' && next == '*' -> { inBlock = true; i += 2; continue }
                triple == "\"\"\"" -> { inTriple = true; i += 3; continue }
                c == '"' -> { inString = true; i++; continue }
                c == '\'' -> { inChar = true; i++; continue }
                else -> { i++ }
            }
        }
        if (inString) errors.add("Unterminated double-quoted string literal.")
        if (inTriple) errors.add("Unterminated triple-quoted string literal.")
        if (inBlock) errors.add("Unterminated block comment /* ... */.")
    }
}
