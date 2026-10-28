// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
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
    private const val CHANNEL_ID = "ReturnGift_accessibility_channel"
    private const val NOTIFICATION_ID = 1005

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
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (e: Throwable) {
            XLog.w(TAG, "could not clear the accessibility notice", e)
        }
    }

    private fun post(context: Context) {
        // Notifications are a privilege, not a guarantee: the tool error still stands alone.
        if (!ForegroundService.hasNotificationPermission(context)) {
            XLog.w(TAG, "accessibility is off and notifications are not permitted")
            return
        }
        try {
            createChannel(context)
            val openSettings = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                openSettings,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(context.getString(R.string.accessibility_off_title))
                .setContentText(context.getString(R.string.accessibility_off_body))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
            XLog.i(TAG, "accessibility notice posted")
        } catch (e: Throwable) {
            XLog.w(TAG, "accessibility notice failed", e)
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.accessibility_off_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.accessibility_off_channel_desc)
            }
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
