// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent

object AgentServiceFactory {

    @JvmStatic
    fun create(): AgentService = DefaultAgentService()
}
