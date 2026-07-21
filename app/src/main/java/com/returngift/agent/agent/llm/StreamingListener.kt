// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.llm

interface StreamingListener {
    fun onPartialText(token: String)
    fun onComplete(response: LlmResponse)
    fun onError(error: Throwable)
}
