// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.service

import android.content.Context
import com.returngift.agent.R
import com.returngift.agent.utils.XLog

/**
 * The one thing the app says when it cannot act.
 *
 * Accessibility off (or killed by the system mid-task) used to surface as a tool error
 * that the model retried, at a wait of up to twenty seconds per call. Here it surfaces as
 * one notification that goes straight to the settings page, and a short circuit breaker
 * so the rest of the run fails in milliseconds instead of stalling.
 */
object AccessibilityNotice {

    private const val TAG = "AccessibilityNotice"
    /** How long every a11y call fails fast after the first miss. */
    private const val BACKOFF_MS = 15_000L

    /** The notification is re-posted at most this often while the outage continues. */
    private const val RENOTIFY_MS = 10 * 60_000L

    @Volatile private var unavailableUntil = 0L

    @Volatile private var lastPostedAt = 0L

    /** True right after a miss: callers return "not available" without waiting again. */
    fun isBackingOff(): Boolean = System.currentTimeMillis() < unavailableUntil

    /**
     * Record that the service is not usable and, unless it was said recently, tell the
     * owner once — tapping the notification opens the accessibility settings page.
     */
    fun markUnavailable(context: Context) {
        unavailableUntil = System.currentTimeMillis() + BACKOFF_MS
        val now = System.currentTimeMillis()
        if (now - lastPostedAt < RENOTIFY_MS) return
        lastPostedAt = now
        post(context)
    }

    /** The service is back: drop the notice and the breaker. */
    fun clear(context: Context) {
        unavailableUntil = 0L
        lastPostedAt = 0L
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(
                NotificationPrompter.NOTIFICATION_ID_CAPABILITY +
                    com.returngift.agent.AppRequirement.ACCESSIBILITY.ordinal,
            )
        } catch (e: Throwable) {
            XLog.w(TAG, "could not clear the accessibility notice", e)
        }
    }

    private fun post(context: Context) {
        // Same layer and the same two channels as every other ask; this one is a door,
        // because accessibility can only be granted on the system page.
        NotificationPrompter.capabilityNeeded(
            context,
            com.returngift.agent.AppRequirement.ACCESSIBILITY,
            context.getString(R.string.accessibility_off_body),
        )
    }
}
