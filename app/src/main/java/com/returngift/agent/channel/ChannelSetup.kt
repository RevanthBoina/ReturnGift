// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.channel

import com.returngift.agent.AppCapabilityCoordinator
import com.returngift.agent.ReturnGiftApplication
import com.returngift.agent.R
import com.returngift.agent.ServiceBindingState
import com.returngift.agent.TaskOrchestrator
import com.returngift.agent.service.ReturnGiftAccessibilityService
import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog

/**
 * Channel initialization and message routing.
 * Responsible for reading local config to initialize channels and dispatching received messages to [TaskOrchestrator].
 */
class ChannelSetup(
    private val taskOrchestrator: TaskOrchestrator
) {

    private companion object {
        const val TAG = "ChannelSetup"
    }

    fun setup() {
        ChannelManager.init(
            discordBotToken = KVUtils.getDiscordBotToken().ifEmpty { null },
            telegramBotToken = KVUtils.getTelegramBotToken().ifEmpty { null },
            wechatBotToken = KVUtils.getWechatBotToken().ifEmpty { null },
            wechatApiBaseUrl = KVUtils.getWechatApiBaseUrl().ifEmpty { null }
        )
        ChannelManager.setOnMessageReceivedListener(object : ChannelManager.OnMessageReceivedListener {
            override fun onMessageReceived(
                channel: Channel,
                message: String,
                messageID: String,
                senderId: String?,
            ) {
                val app = ReturnGiftApplication.instance
                // One owner per channel: the first sender claims it, and everyone else
                // gets a sentence back rather than control of this phone. Channels that
                // cannot report a sender id are left alone.
                val owner = KVUtils.getChannelOwner(channel.name)
                if (owner.isEmpty()) {
                    if (!senderId.isNullOrBlank()) KVUtils.setChannelOwner(channel.name, senderId)
                } else if (!senderId.isNullOrBlank() && senderId != owner) {
                    XLog.w(TAG, "Ignoring ${channel.displayName} message from a non-owner sender")
                    ChannelManager.sendMessage(channel, app.getString(R.string.channel_msg_not_owner), messageID)
                    ChannelManager.flushMessages(channel)
                    return
                }
                val capabilityState = AppCapabilityCoordinator.accessibilityState(app)
                if (capabilityState == ServiceBindingState.DISABLED) {
                    ChannelManager.sendMessage(channel, "Accessibility service is not enabled. Please enable ReturnGift in Settings > Accessibility.", messageID)
                    ChannelManager.flushMessages(channel)
                    return
                }
                val waitMs = if (capabilityState == ServiceBindingState.CONNECTING) 20_000L else 5_000L
                if (!ReturnGiftAccessibilityService.awaitRunning(waitMs)) {
                    val error = when (AppCapabilityCoordinator.accessibilityState(app)) {
                        ServiceBindingState.DISABLED ->
                            "Accessibility service is not enabled. Please enable ReturnGift in Settings > Accessibility."
                        ServiceBindingState.CONNECTING ->
                            "Accessibility is still reconnecting. Please try again in a few seconds."
                        ServiceBindingState.DEGRADED ->
                            "Accessibility disconnected. Please re-open ReturnGift or re-enable Accessibility."
                        ServiceBindingState.READY ->
                            app.getString(R.string.channel_msg_no_accessibility)
                    }
                    ChannelManager.sendMessage(channel, error, messageID)
                    ChannelManager.flushMessages(channel)
                    return
                }
                if (!taskOrchestrator.tryAcquireTask(messageID, channel, message)) {
                    ChannelManager.sendMessage(channel, app.getString(R.string.channel_msg_task_in_progress), messageID)
                    ChannelManager.flushMessages(channel)
                    return
                }
                taskOrchestrator.startNewTask(channel, message, messageID)
            }
        })
    }
}
