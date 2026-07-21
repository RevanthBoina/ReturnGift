// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

interface AgentService {
    fun initialize(config: AgentConfig)
    fun updateConfig(config: AgentConfig)
    fun executeTask(userPrompt: String, callback: AgentCallback)
    fun cancel()
    fun shutdown()
    fun isRunning(): Boolean
}
