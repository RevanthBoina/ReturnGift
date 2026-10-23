// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.returngift.agent.R
import com.returngift.agent.agent.permission.PermissionPromptLauncher
import com.returngift.agent.agent.permission.PermissionRequest
import com.returngift.agent.ui.permission.PermissionPromptActivity
import com.returngift.agent.utils.XLog

/**
 * Heads-up mirror of a pending [PermissionRequest].
 *
 * Tapping the notification opens the permission card (the APK interface); each
 * choice is also reachable directly from the notification. This is the fallback
 * (and the belt-and-braces signal) for when ReturnGift is running a task in the
 * background and the activity launch is deferred by the OS.
 */
object PermissionPromptNotifier {

    private const val TAG = "PermissionPromptNotifier"
    private const val CHANNEL_ID = "ReturnGift_permission_channel"
    private const val NOTIFICATION_ID = 1004

    fun show(context: Context, request: PermissionRequest) {
        if (!ForegroundService.hasNotificationPermission(context)) {
            XLog.w(TAG, "no notification permission — permission prompt stays in-app only")
            return
        }
        try {
            createChannel(context)

            val openScreen = Intent(context, PermissionPromptActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(PermissionPromptLauncher.EXTRA_ID, request.id)
                putExtra(PermissionPromptLauncher.EXTRA_KIND, request.kind.name)
                putExtra(PermissionPromptLauncher.EXTRA_PACKAGE, request.packageName)
                putExtra(PermissionPromptLauncher.EXTRA_LABEL, request.label)
                putExtra(PermissionPromptLauncher.EXTRA_REASON, request.reason)
                putExtra(PermissionPromptLauncher.EXTRA_CAPABILITY, request.capabilityId)
            }
            val contentIntent = PendingIntent.getActivity(
                context,
                request.id.hashCode(),
                openScreen,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(request.title())
                .setContentText(request.body().lineSequence().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(request.body()))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)

            var requestCode = request.id.hashCode()
            request.choices().take(3).forEach { choice ->
                requestCode++
                val answer = Intent(context, PermissionAnswerReceiver::class.java).apply {
                    action = PermissionAnswerReceiver.ACTION_ANSWER
                    putExtra(PermissionAnswerReceiver.EXTRA_REQUEST_ID, request.id)
                    putExtra(PermissionAnswerReceiver.EXTRA_CHOICE, choice)
                }
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        null,
                        choice,
                        PendingIntent.getBroadcast(
                            context,
                            requestCode,
                            answer,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    ).build(),
                )
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, builder.build())
            XLog.i(TAG, "permission heads-up posted for ${request.kind} ${request.label}")
        } catch (e: Throwable) {
            XLog.w(TAG, "permission heads-up failed", e)
        }
    }

    fun dismiss(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (e: Throwable) {
            XLog.w(TAG, "permission notification dismiss failed", e)
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App permissions",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Prompts ReturnGift shows before acting inside another app"
            }
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
