// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.llm

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ChatMessage

interface LlmClient {
    /**
     * Blocking call. Returns the complete AI response.
     *
     * @param fast when true, the local client routes to the small (fast) engine slot
     *             for mechanical rounds (PROMPT 5: FastRoundRouter). Remote clients
     *             (OpenAI / Anthropic / OmniRoute) ignore the flag — the fast engine
     *             is local-only by construction.
     */
    fun chat(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        fast: Boolean = false,
    ): LlmResponse

    /**
     * Streaming call. Invokes listener callbacks as tokens arrive. Blocks until stream completes.
     * @param fast see [chat].
     */
    fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener,
        fast: Boolean = false,
    ): LlmResponse

    /**
     * Release any engine / native resources held by this client.
     * Called after task completes to free memory before reloading the chat engine.
     * Default is no-op for remote clients (OpenAI, Anthropic).
     */
    fun close() {}
}
