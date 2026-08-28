package com.returngift.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudContextHandoffFormatterTest {

    @Test
    fun `conversation handoff keeps user and assistant only`() {
        val lines = CloudContextHandoffFormatter.conversationLines(
            listOf(
                ChatMessage(ChatMessage.Role.SYSTEM, "Auto-reply active for Mom on Telegram."),
                ChatMessage(ChatMessage.Role.USER, "The codeword is zulu731."),
                ChatMessage(ChatMessage.Role.ASSISTANT, "ok", modelName = "gpt-4.1"),
                ChatMessage(ChatMessage.Role.TOOL_GROUP, "", toolSteps = listOf(ToolStep("search", "done"))),
                ChatMessage(ChatMessage.Role.SYSTEM, "Accessibility service connecting, please wait..."),
            )
        )

        assertEquals(
            listOf(
                "User: The codeword is zulu731.",
                "Assistant: ok",
            ),
            lines
        )
    }

    @Test
    fun `back-compat read-back defaults are USER for USER, MODEL for ASSISTANT`() {
        // Legacy rows with no explicit source must not be misread as model-authored when a
        // USER line is handed to the cloud: USER->USER (never MODEL), ASSISTANT->MODEL.
        assertEquals(ChatMessage.Source.USER, CloudContextHandoffFormatter.effectiveSource(ChatMessage(ChatMessage.Role.USER, "hi")))
        assertEquals(ChatMessage.Source.MODEL, CloudContextHandoffFormatter.effectiveSource(ChatMessage(ChatMessage.Role.ASSISTANT, "hi")))
        assertEquals(ChatMessage.Source.SYSTEM_NOTICE, CloudContextHandoffFormatter.effectiveSource(ChatMessage(ChatMessage.Role.SYSTEM, "notice")))
        assertEquals(ChatMessage.Source.TOOL_RESULT, CloudContextHandoffFormatter.effectiveSource(ChatMessage(ChatMessage.Role.TOOL_GROUP, "")))
    }

    @Test
    fun `web-fetch sourced content is handed off inside an untrusted delimiter`() {
        // Externally-sourced content (web-fetch / vault research) surfaced on the chat must
        // arrive at the cloud handoff visibly delimited as untrusted — never as a plain
        // User line that could be mistaken for an instruction.
        val lines = CloudContextHandoffFormatter.conversationLines(
            listOf(
                ChatMessage(ChatMessage.Role.USER, "check retmgift.dev", source = ChatMessage.Source.USER),
                ChatMessage(
                    ChatMessage.Role.ASSISTANT,
                    "Archived: retmgift.dev is a static site.",
                    source = ChatMessage.Source.UNTRUSTED,
                ),
                ChatMessage(ChatMessage.Role.SYSTEM, "Auto-reply active.", source = ChatMessage.Source.SYSTEM_NOTICE),
            )
        )

        assertEquals(
            listOf(
                "User: check retmgift.dev",
                "[untrusted] Archived: retmgift.dev is a static site.",
            ),
            lines
        )
    }
}
