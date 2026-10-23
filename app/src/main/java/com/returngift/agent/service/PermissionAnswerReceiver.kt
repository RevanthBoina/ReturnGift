// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.returngift.agent.AppCapabilityCoordinator
import com.returngift.agent.AppRequirement
import com.returngift.agent.agent.permission.PermissionPromptBroker
import com.returngift.agent.agent.permission.PermissionRequest
import com.returngift.agent.utils.XLog

/**
 * Delivers a permission choice tapped in the heads-up notification back to the
 * parked agent thread (same contract as [ClarificationAnswerReceiver]).
 */
class PermissionAnswerReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PermissionAnswerReceiver"
        const val ACTION_ANSWER = "com.returngift.agent.action.ANSWER_PERMISSION"
        const val EXTRA_REQUEST_ID = "permission_request_id"
        const val EXTRA_CHOICE = "permission_choice"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        val choice = intent.getStringExtra(EXTRA_CHOICE)
        val pending = PermissionPromptBroker.snapshot()

        if (choice.isNullOrBlank()) {
            XLog.w(TAG, "empty permission choice, dropping")
            return
        }
        if (pending != null && requestId != null && pending.id != requestId) {
            XLog.w(TAG, "stale permission answer for $requestId (pending=${pending.id})")
            return
        }

        // "Open settings" is a navigation answer: open the system page, then let
        // the user come back and choose again — do not resolve the request yet.
        if (pending != null && pending.decisionFor(choice) == PermissionRequest.Decision.OPEN_SETTINGS) {
            val requirement = runCatching { AppRequirement.valueOf(pending.capabilityId) }.getOrNull()
            if (requirement != null) {
                XLog.i(TAG, "notification answer opened system settings for $requirement")
                AppCapabilityCoordinator.openSystemSettings(context, requirement)
                return
            }
        }

        val consumed = PermissionPromptBroker.answer(choice)
        XLog.i(TAG, "permission answer ${if (consumed) "delivered" else "stale"}: \"$choice\"")
    }

}
