// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.permission

import android.content.Context
import android.content.Intent
import android.os.Build
import com.returngift.agent.ui.permission.PermissionPromptActivity
import com.returngift.agent.utils.AppUiState
import com.returngift.agent.utils.XLog

/**
 * Opens the permission card — ReturnGift's own interface — in front of whatever
 * app the agent is driving.
 *
 * Background activity starts are restricted on Android 10+. ReturnGift holds
 * SYSTEM_ALERT_WINDOW (an allowed exemption) and the caller is usually the
 * foreground-service agent, so the launch normally succeeds. When it does not,
 * the caller mirrors the request as a heads-up notification with the same
 * choices (see [com.returngift.agent.service.PermissionPromptNotifier]), so a
 * permission request is never silent.
 */
object PermissionPromptLauncher {

    private const val TAG = "PermissionPromptLauncher"

    const val EXTRA_ID = "permission_request_id"
    const val EXTRA_KIND = "permission_request_kind"
    const val EXTRA_PACKAGE = "permission_request_package"
    const val EXTRA_LABEL = "permission_request_label"
    const val EXTRA_REASON = "permission_request_reason"
    const val EXTRA_CAPABILITY = "permission_request_capability"

    /** @return true when the activity launch was accepted. */
    fun open(context: Context, request: PermissionRequest): Boolean {
        val intent = Intent(context, PermissionPromptActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(EXTRA_ID, request.id)
            putExtra(EXTRA_KIND, request.kind.name)
            putExtra(EXTRA_PACKAGE, request.packageName)
            putExtra(EXTRA_LABEL, request.label)
            putExtra(EXTRA_REASON, request.reason)
            putExtra(EXTRA_CAPABILITY, request.capabilityId)
        }
        return try {
            context.startActivity(intent)
            XLog.i(
                TAG,
                "Opened permission screen for ${request.kind} " +
                    "${request.packageName.ifBlank { request.capabilityId }} " +
                    "(appForeground=${AppUiState.isForeground}, sdk=${Build.VERSION.SDK_INT})"
            )
            true
        } catch (e: Throwable) {
            XLog.w(TAG, "Could not open the permission screen; notification fallback will be used", e)
            false
        }
    }

    /** Rebuild the request carried by an activity/receiver intent. */
    fun requestFromIntent(intent: Intent?): PermissionRequest? {
        intent ?: return null
        val id = intent.getStringExtra(EXTRA_ID) ?: return null
        val kind = runCatching {
            PermissionRequest.Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "")
        }.getOrNull() ?: return null
        return PermissionRequest(
            id = id,
            kind = kind,
            packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty(),
            label = intent.getStringExtra(EXTRA_LABEL).orEmpty(),
            reason = intent.getStringExtra(EXTRA_REASON).orEmpty(),
            capabilityId = intent.getStringExtra(EXTRA_CAPABILITY).orEmpty(),
        )
    }
}
