// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.ui.chat

/**
 * MarkdownLite — a deliberately small markdown renderer for chat bubbles.
 *
 * Zero dependencies, JVM-unit-testable. Supports the subset LLM answers actually use:
 * headings (#..###), fenced code blocks (```), bullet/numbered lists, blockquotes (>),
 * and inline **bold**, *italic*, `code`. Everything else degrades to plain text.
 *
 * Pure parsing only — Compose rendering lives in ChatScreen (MarkdownText).
 */
object MarkdownLite {

    sealed class Block {
        data class Heading(val level: Int, val text: String) : Block()
        data class CodeBlock(val code: String) : Block()
        data class ListItem(val ordered: Boolean, val index: Int, val text: String) : Block()
        data class Quote(val text: String) : Block()
        data class Paragraph(val text: String) : Block()
        object Divider : Block()
    }

    /** Inline styled segment: literal text + style flags. */
    data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

    fun parseBlocks(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = markdown.lines()
        var i = 0
        val paragraph = StringBuilder()

        fun flushParagraph() {
            val text = paragraph.toString().trim()
            if (text.isNotEmpty()) blocks.add(Block.Paragraph(text.replace("\n", " ")))
            paragraph.clear()
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.startsWith("```")) {
                flushParagraph()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.appendLine(lines[i])
                    i++
                }
                blocks.add(Block.CodeBlock(code.toString().trimEnd('\n')))
                i++ // skip closing fence (or EOF)
                continue
            }

            val heading = Regex("^(#{1,6})\\s+(.+)$").find(trimmed)
            if (heading != null) {
                flushParagraph()
                blocks.add(Block.Heading(heading.groupValues[1].length, heading.groupValues[2].trim()))
                i++
                continue
            }

            if (Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(trimmed)) {
                flushParagraph()
                blocks.add(Block.Divider)
                i++
                continue
            }

            val bullet = Regex("^[-*+]\\s+(.+)$").find(trimmed)
            if (bullet != null) {
                flushParagraph()
                blocks.add(Block.ListItem(ordered = false, index = 0, text = bullet.groupValues[1].trim()))
                i++
                continue
            }

            val numbered = Regex("^(\\d+)[.)]\\s+(.+)$").find(trimmed)
            if (numbered != null) {
                flushParagraph()
                blocks.add(Block.ListItem(ordered = true, index = numbered.groupValues[1].toInt(), text = numbered.groupValues[2].trim()))
                i++
                continue
            }

            if (trimmed.startsWith(">")) {
                flushParagraph()
                blocks.add(Block.Quote(trimmed.removePrefix(">").trim()))
                i++
                continue
            }

            if (trimmed.isEmpty()) {
                flushParagraph()
                i++
                continue
            }

            paragraph.appendLine(trimmed)
            i++
        }
        flushParagraph()
        return blocks
    }

    /** Parse inline **bold**, *italic*, `code` into styled spans. */
    fun parseInline(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        var i = 0
        val plain = StringBuilder()

        fun flush() {
            if (plain.isNotEmpty()) {
                spans.add(Span(plain.toString()))
                plain.clear()
            }
        }

        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        flush()
                        spans.add(Span(text.substring(i + 2, end), bold = true))
                        i = end + 2
                    } else { plain.append('*'); i++ }
                }
                text.startsWith("`", i) -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        flush()
                        spans.add(Span(text.substring(i + 1, end), code = true))
                        i = end + 1
                    } else { plain.append('`'); i++ }
                }
                text[i] == '*' && !text.startsWith("**", i) -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i + 1) {
                        flush()
                        spans.add(Span(text.substring(i + 1, end), italic = true))
                        i = end + 1
                    } else { plain.append('*'); i++ }
                }
                else -> { plain.append(text[i]); i++ }
            }
        }
        flush()
        return spans
    }
}
