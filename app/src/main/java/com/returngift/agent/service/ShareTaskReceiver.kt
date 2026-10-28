// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.returngift.agent.utils.XLog

/**
 * "Send to ReturnGift" in the share sheet: the shared text becomes a task, silently.
 *
 * No activity is launched — the task starts, its progress appears in the notification the
 * owner already has, and the result replaces it. Sharing a link or a paragraph is enough
 * to hand the agent work.
 */
class ShareTaskReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ShareTaskReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            XLog.w(TAG, "shared intent carried no text")
            return
        }
        if (!NotificationTaskIntake.submit(context, text)) {
            XLog.w(TAG, "shared task was not started")
        }
    }
}
