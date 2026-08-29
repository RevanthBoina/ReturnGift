// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

interface AgentService {
    fun initialize(config: AgentConfig)
    fun updateConfig(config: AgentConfig)
    fun executeTask(userPrompt: String, callback: AgentCallback)
    /** P1.1a: Execute a task with an explicit, externally-provided task id. The id
     *  is shared by the orchestrator, ExecutionTracker, queue, and checkpoint stores. */
    fun executeTask(userPrompt: String, taskId: String, callback: AgentCallback)
    fun cancel()
    fun shutdown()
    fun isRunning(): Boolean
}
