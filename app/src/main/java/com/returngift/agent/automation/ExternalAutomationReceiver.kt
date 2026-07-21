// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Production entrypoint for external automation apps.
 *
 * Example:
 * adb shell am broadcast \
 *   -a com.returngift.agent.RUN_TASK \
 *   -p com.returngift.agent \
 *   --es task "Summarize my notifications"
 */
class ExternalAutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        ExternalAutomationEntrypoint.handle(
            context = context,
            intent = intent,
            launchFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
    }
}
