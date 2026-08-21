package com.returngift.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLiteTest {

    @Test
    fun `headings become Heading blocks`() {
        val blocks = MarkdownLite.parseBlocks("# Title\n\nText\n\n## Sub")
        assertEquals(MarkdownLite.Block.Heading(1, "Title"), blocks[0])
        assertTrue(blocks[1] is MarkdownLite.Block.Paragraph)
        assertEquals(MarkdownLite.Block.Heading(2, "Sub"), blocks[2])
    }

    @Test
    fun `fenced code block survives inline markers`() {
        val md = "Before\n```kotlin\nval x = \"**not bold**\"\n```\nAfter"
        val blocks = MarkdownLite.parseBlocks(md)
        val code = blocks.filterIsInstance<MarkdownLite.Block.CodeBlock>().single()
        assertEquals("val x = \"**not bold**\"", code.code)
        assertEquals("Before", (blocks[0] as MarkdownLite.Block.Paragraph).text)
        assertEquals("After", (blocks[2] as MarkdownLite.Block.Paragraph).text)
    }

    @Test
    fun `unclosed fence consumes to end`() {
        val blocks = MarkdownLite.parseBlocks("```\ncode\nno close")
        assertEquals(listOf(MarkdownLite.Block.CodeBlock("code\nno close")), blocks)
    }

    @Test
    fun `bullets and numbered items become ListItem blocks`() {
        val md = "- one\n- two\n\n3. three"
        val blocks = MarkdownLite.parseBlocks(md)
        assertEquals(MarkdownLite.Block.ListItem(false, 0, "one"), blocks[0])
        assertEquals(MarkdownLite.Block.ListItem(false, 0, "two"), blocks[1])
        assertEquals(MarkdownLite.Block.ListItem(true, 3, "three"), blocks[2])
    }

    @Test
    fun `quote and divider parse`() {
        val blocks = MarkdownLite.parseBlocks("> quoted\n\n---")
        assertEquals(MarkdownLite.Block.Quote("quoted"), blocks[0])
        assertEquals(MarkdownLite.Block.Divider, blocks[1])
    }

    @Test
    fun `multiline paragraph joins into single block`() {
        val blocks = MarkdownLite.parseBlocks("line one\nline two")
        assertEquals(listOf(MarkdownLite.Block.Paragraph("line one line two")), blocks)
    }

    @Test
    fun `inline bold italic and code spans`() {
        val spans = MarkdownLite.parseInline("a **bold** b *italic* c `code`")
        assertEquals(MarkdownLite.Span("a "), spans[0])
        assertEquals(MarkdownLite.Span("bold", bold = true), spans[1])
        assertEquals(MarkdownLite.Span(" b "), spans[2])
        assertEquals(MarkdownLite.Span("italic", italic = true), spans[3])
        assertEquals(MarkdownLite.Span("code", code = true), spans[4])
    }

    @Test
    fun `unterminated markers stay literal`() {
        val spans = MarkdownLite.parseInline("a ** broken")
        assertEquals(1, spans.size)
        assertEquals("a ** broken", spans[0].text)
    }

    @Test
    fun `plain text returns single span`() {
        assertEquals(listOf(MarkdownLite.Span("hello world")), MarkdownLite.parseInline("hello world"))
    }
}
