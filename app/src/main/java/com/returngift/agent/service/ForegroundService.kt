// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.returngift.agent.AppCapabilityCoordinator
import com.returngift.agent.R
import com.returngift.agent.ServiceBindingState
import com.returngift.agent.utils.XLog

/**
 * Foreground service for active task / monitor notifications only.
 */
class ForegroundService : Service() {

    companion object {
        private const val TAG = "ForegroundService"
        private const val MONITOR_HEALTH_POLL_MS = 5_000L
        const val CHANNEL_ID = "ReturnGift_foreground_channel"
        const val NOTIFICATION_ID = 1001
        /** Completion/alerts channel (IMPORTANCE_DEFAULT so a finished task actually alerts). */
        const val RESULT_CHANNEL_ID = "ReturnGift_results_channel"
        const val NOTIFICATION_DONE_ID = 1002
        /** Notification action: stop the currently running agent task. */
        const val ACTION_STOP_TASK = "com.returngift.agent.action.STOP_TASK"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_TEXT = "extra_text"
        private const val DEFAULT_TASK_TITLE = "ReturnGift · Task in progress"
        private const val DEFAULT_TASK_TEXT = "Running task..."
        private const val DEFAULT_MONITOR_TITLE = "ReturnGift · Monitoring"
        private const val DEGRADED_MONITOR_TITLE = "ReturnGift · Monitoring paused"

        private fun createResultChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    RESULT_CHANNEL_ID,
                    "Task results",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
            }
        }

        private enum class ForegroundMode {
            IDLE,
            TASK,
            MONITOR,
        }

        @Volatile
        private var _isRunning = false

        @Volatile
        private var _mode = ForegroundMode.IDLE

        /**
         * Check whether the foreground service is running
         */
        fun isRunning(): Boolean = _isRunning

        /**
         * Update the foreground notification with task progress text.
         * Safe to call from any thread — posts to NotificationManager directly.
         */
        fun updateTaskStatus(context: Context, statusText: String) {
            _mode = ForegroundMode.TASK
            showNotification(context, DEFAULT_TASK_TITLE, statusText, showStopAction = true)
        }

        /**
         * Kimi-Work style completion alert: posted when a task finishes while the chat
         * was backgrounded (minimize-on-verified flow). Tap reopens the conversation.
         */
        fun notifyTaskFinished(context: Context, success: Boolean, taskTitle: String, body: String) {
            if (!hasNotificationPermission(context)) return
            try {
                createResultChannel(context)
                val intent = Intent(context, com.returngift.agent.ui.chat.ComposeChatActivity::class.java)
                val pendingIntent = PendingIntent.getActivity(
                    context, 1, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val excerpt = body.trim().replace('\n', ' ').let { if (it.length > 160) it.take(157) + "…" else it }
                val notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
                    .setContentTitle(if (success) "✓ $taskTitle" else "✗ $taskTitle")
                    .setContentText(excerpt.ifEmpty { if (success) "Task finished" else "Task failed" })
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build()
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_DONE_ID, notification)
            } catch (e: Exception) {
                XLog.w(TAG, "notifyTaskFinished failed", e)
            }
        }

        /**
         * Show monitor state if auto-reply is active, otherwise stop the foreground service.
         */
        fun resetToIdle(context: Context) {
            syncToBackgroundState(context)
        }

        fun showMonitorStatus(context: Context): Boolean {
            val manager = AutoReplyManager.getInstance()
            if (!manager.isEnabled || manager.monitoredContacts.isEmpty()) {
                _mode = ForegroundMode.IDLE
                stop(context)
                return false
            }
            _mode = ForegroundMode.MONITOR
            val capabilities = AppCapabilityCoordinator.snapshot(context)
            if (capabilities.notificationAccessState != ServiceBindingState.READY) {
                return showNotification(
                    context,
                    DEGRADED_MONITOR_TITLE,
                    if (capabilities.notificationAccessState == ServiceBindingState.CONNECTING) {
                        "Notification Access reconnecting…"
                    } else {
                        "Notification Access disconnected"
                    }
                )
            }
            if (capabilities.accessibilityState != ServiceBindingState.READY) {
                return showNotification(
                    context,
                    DEGRADED_MONITOR_TITLE,
                    if (capabilities.accessibilityState == ServiceBindingState.CONNECTING) {
                        "Accessibility reconnecting…"
                    } else {
                        "Accessibility disconnected"
                    }
                )
            }
            val contacts = manager.monitoredContacts.toList()
            val text = when (contacts.size) {
                0 -> "Monitoring in background"
                1 -> "Monitoring ${contacts.first()}"
                else -> "Monitoring ${contacts.size} chats"
            }
            return showNotification(context, DEFAULT_MONITOR_TITLE, text)
        }

        fun syncToBackgroundState(context: Context): Boolean {
            val manager = AutoReplyManager.getInstance()
            return if (manager.isEnabled && manager.monitoredContacts.isNotEmpty()) {
                showMonitorStatus(context)
            } else {
                _mode = ForegroundMode.IDLE
                stop(context)
                false
            }
        }

        private fun showNotification(context: Context, title: String, text: String, showStopAction: Boolean = false): Boolean {
            if (!hasNotificationPermission(context)) {
                return false
            }

            try {
                if (_isRunning) {
                    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, buildNotification(context, title, text, showStopAction))
                    return true
                }
                start(context, title, text)
                return true
            } catch (e: Exception) {
                XLog.w(TAG, "Foreground notification update failed", e)
                return false
            }
        }

        /**
         * Start the foreground service
         * @param context Context
         * @return true if started successfully, false if notification permission is missing
         */
        fun start(
            context: Context,
            title: String = context.getString(R.string.notification_content_title),
            text: String = context.getString(R.string.notification_content_text)
        ): Boolean {
            // Android 13+ requires notification permission check
            if (!hasNotificationPermission(context)) {
                return false
            }

            return try {
                val intent = Intent(context, ForegroundService::class.java).apply {
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_TEXT, text)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                XLog.w(TAG, "Foreground service start blocked or failed", e)
                false
            }
        }

        private fun hasNotificationPermission(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }

        fun stop(context: Context) {
            _mode = ForegroundMode.IDLE
            val intent = Intent(context, ForegroundService::class.java)
            context.stopService(intent)
            runCatching {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(NOTIFICATION_ID)
            }
        }

        private fun buildNotification(context: Context, title: String, text: String, showStopAction: Boolean = false): Notification {
            val intent = Intent(context, com.returngift.agent.ui.chat.ComposeChatActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)

            if (showStopAction) {
                val stopIntent = Intent(context, ForegroundService::class.java).setAction(ACTION_STOP_TASK)
                val stopPending = PendingIntent.getService(
                    context, 2, stopIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                builder.addAction(0, "Stop", stopPending)
            }
            return builder.build()
        }
    }

    private val healthHandler = Handler(Looper.getMainLooper())
    private val healthRunnable = object : Runnable {
        override fun run() {
            if (!_isRunning) return
            if (_mode == ForegroundMode.MONITOR) {
                ForegroundService.syncToBackgroundState(applicationContext)
            }
            healthHandler.postDelayed(this, MONITOR_HEALTH_POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        _isRunning = true
        createNotificationChannel()
        if (hasNotificationPermission(this)) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(this, DEFAULT_TASK_TITLE, DEFAULT_TASK_TEXT)
            )
        } else {
            stopSelf()
            return
        }
        healthHandler.post(healthRunnable)
        createResultChannel(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning = false
        healthHandler.removeCallbacksAndMessages(null)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_TASK) {
            XLog.i(TAG, "Stop action tapped — cancelling current task")
            runCatching { com.returngift.agent.ClawApplication.appViewModelInstance.taskOrchestrator.cancelCurrentTask() }
                .onFailure { XLog.w(TAG, "Stop action: no running task to cancel", it) }
            return START_NOT_STICKY
        }
        val notification = createNotification(intent)
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(intent: Intent?): Notification {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: DEFAULT_TASK_TITLE
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: DEFAULT_TASK_TEXT
        return buildNotification(this, title, text)
    }
}
