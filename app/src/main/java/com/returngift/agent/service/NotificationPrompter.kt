// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.returngift.agent.AppCapabilityCoordinator
import com.returngift.agent.AppRequirement
import com.returngift.agent.R
import com.returngift.agent.agent.clarify.ClarificationManager
import com.returngift.agent.agent.permission.PermissionRequest
import com.returngift.agent.ui.chat.ComposeChatActivity
import com.returngift.agent.utils.XLog

/**
 * The only place the agent talks to the owner.
 *
 * Everything it needs — a doubt, a permission, a fix it cannot perform itself — is one
 * notification with buttons or a reply box. The app is a mirror, not a control panel:
 * the owner answers from the shade or the lock screen and the parked task continues.
 *
 * Two channels only:
 *  - [CHANNEL_STATUS] low importance: progress and results, silenced without losing anything.
 *  - [CHANNEL_NEEDS] high importance: questions and permission asks, never silenced by accident.
 *
 * Every action finishes in a [android.content.BroadcastReceiver]; the only PendingIntent
 * that starts an activity is the tap on the notification body.
 */
object NotificationPrompter {

    private const val TAG = "NotificationPrompter"

    const val CHANNEL_STATUS = "returngift_agent_status"
    const val CHANNEL_NEEDS = "returngift_agent_needs_you"

    const val NOTIFICATION_ID_QUESTION = 2001
    const val NOTIFICATION_ID_PERMISSION = 2002
    const val NOTIFICATION_ID_CAPABILITY = 2003

    const val EXTRA_QUESTION_ID = "np_question_id"
    const val EXTRA_NONCE = "np_nonce"
    const val EXTRA_REQUEST_ID = "np_request_id"
    const val EXTRA_ANSWER = "np_answer"
    const val KEY_TEXT_REPLY = "np_text_reply"

    /** System-capability notices ("tap to fix") are allowed once per this window. */
    private const val CAPABILITY_REPEAT_MS = 10 * 60_000L

    @Volatile private var lastCapabilityAt = mutableMapOf<String, Long>()

    fun notificationsAvailable(context: Context): Boolean =
        ForegroundService.hasNotificationPermission(context)

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.notif_channel_status),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_status_desc)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEEDS,
                context.getString(R.string.notif_channel_needs),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_channel_needs_desc)
                setShowBadge(true)
            },
        )
    }

    // ── questions ────────────────────────────────────────────────────────────

    /**
     * Post the agent's doubt as a heads-up: the question as the title, the agent's best
     * guess and its runner-up as buttons, and an inline reply for anything else.
     * The notification stays until answered or expired, so "no answer" means "still
     * waiting", never "cancelled".
     */
    fun askQuestion(context: Context, question: ClarificationManager.PendingQuestion) {
        if (!notificationsAvailable(context)) {
            XLog.w(TAG, "notifications are blocked — the question stays in the app")
            return
        }
        try {
            ensureChannels(context)
            // A pending question must survive the OS reclaiming background work.
            if (!ForegroundService.isRunning()) {
                ForegroundService.start(
                    context,
                    context.getString(R.string.notif_waiting_title),
                    question.question.take(120),
                )
            }

            val oneLine = question.question.lineSequence().first().trim().take(90)
            val builder = baseNeeds(context, oneLine, context.getString(R.string.notif_question_body), question)
                .setStyle(NotificationCompat.BigTextStyle().bigText(question.question))
                .setSubText(context.getString(R.string.notif_question_subtext))

            // Buttons: best guess first, then the runner-up (never more than three actions).
            question.choices.take(2).forEachIndexed { index, choice ->
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        null,
                        choice.take(30),
                        answerPendingIntent(context, question, choice, index),
                    ).build(),
                )
            }
            // Anything else is typed straight into the notification.
            if (question.allowFreeText || question.choices.isEmpty()) {
                builder.addAction(replyAction(context, question, context.getString(R.string.notif_reply_action)))
            }

            notify(context, NOTIFICATION_ID_QUESTION, builder.build())
            XLog.i(TAG, "asked: \"${question.question.take(60)}\" (id=${question.id})")
        } catch (e: Throwable) {
            XLog.w(TAG, "could not post the question", e)
        }
    }

    /** The question never got answered in time: say so instead of pretending it is live. */
    fun markQuestionExpired(context: Context, question: ClarificationManager.PendingQuestion?) {
        if (!notificationsAvailable(context)) return
        try {
            val builder = NotificationCompat.Builder(context, CHANNEL_NEEDS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(context.getString(R.string.notif_expired_title))
                .setContentText(question?.question?.take(120) ?: "")
                .setContentIntent(openAppIntent(context, question?.id ?: "expired"))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
            notify(context, NOTIFICATION_ID_QUESTION, builder.build())
        } catch (e: Throwable) {
            XLog.w(TAG, "could not mark the question expired", e)
        }
    }

    /** A tap on an answer that no longer belongs to the live question. */
    fun markStaleAnswer(context: Context) {
        if (!notificationsAvailable(context)) return
        try {
            val builder = NotificationCompat.Builder(context, CHANNEL_NEEDS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(context.getString(R.string.notif_stale_title))
                .setContentText(context.getString(R.string.notif_stale_body))
                .setContentIntent(openAppIntent(context, "stale"))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            notify(context, NOTIFICATION_ID_QUESTION, builder.build())
        } catch (e: Throwable) {
            XLog.w(TAG, "could not mark the stale answer", e)
        }
    }

    fun dismissQuestion(context: Context) = cancel(context, NOTIFICATION_ID_QUESTION)

    // ── permissions ──────────────────────────────────────────────────────────

    /**
     * Ask about acting inside an app or using a capability: Allow once / Always allow /
     * Deny, all decided by a receiver. "Always" writes to the same store the in-app card
     * wrote to; Deny blocks that one step and nothing else.
     */
    fun askPermission(context: Context, request: PermissionRequest, nonce: String) {
        if (!notificationsAvailable(context)) {
            XLog.w(TAG, "notifications are blocked — falling back to the in-app card")
            return
        }
        try {
            ensureChannels(context)
            if (!ForegroundService.isRunning()) {
                ForegroundService.start(
                    context,
                    context.getString(R.string.notif_waiting_title),
                    request.title(),
                )
            }
            val builder = NotificationCompat.Builder(context, CHANNEL_NEEDS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(request.title().take(90))
                .setContentText(request.reason.ifBlank { request.body().lineSequence().first() }.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(request.reason.ifBlank { request.body() }))
                .setContentIntent(openAppIntent(context, request.id))
                .setOngoing(true)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion(context))

            request.choices().take(3).forEachIndexed { index, choice ->
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        null,
                        choice,
                        permissionPendingIntent(context, request, nonce, choice, index),
                    ).build(),
                )
            }
            notify(context, NOTIFICATION_ID_PERMISSION, builder.build())
            XLog.i(TAG, "permission asked: ${request.kind} ${request.packageName.ifBlank { request.capabilityId }}")
        } catch (e: Throwable) {
            XLog.w(TAG, "could not post the permission ask", e)
        }
    }

    fun dismissPermission(context: Context) = cancel(context, NOTIFICATION_ID_PERMISSION)

    // ── things only Android can grant ────────────────────────────────────────

    /**
     * "Accessibility is off — tap to fix", once per window. The owner cannot answer this
     * from the shade, so the notification is a door, not a question.
     */
    fun capabilityNeeded(context: Context, requirement: AppRequirement, reason: String = "") {
        if (!notificationsAvailable(context)) return
        val now = System.currentTimeMillis()
        val last = lastCapabilityAt[requirement.name] ?: 0L
        if (now - last < CAPABILITY_REPEAT_MS) {
            XLog.d(TAG, "capability notice for ${requirement.name} already shown recently")
            return
        }
        lastCapabilityAt[requirement.name] = now
        try {
            ensureChannels(context)
            val open = Intent(context, CapabilityFixActivity::class.java).apply {
                putExtra(CapabilityFixActivity.EXTRA_REQUIREMENT, requirement.name)
            }
            val pending = PendingIntent.getActivity(
                context,
                requirement.ordinal,
                open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_NEEDS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(context.getString(R.string.notif_capability_title, requirement.label))
                .setContentText(
                    reason.ifBlank { context.getString(R.string.notif_capability_body) }.take(140),
                )
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
            notify(context, NOTIFICATION_ID_CAPABILITY + requirement.ordinal, builder.build())
        } catch (e: Throwable) {
            XLog.w(TAG, "could not post the capability notice", e)
        }
    }

    // ── building blocks ──────────────────────────────────────────────────────

    private fun baseNeeds(
        context: Context,
        title: String,
        text: String,
        question: ClarificationManager.PendingQuestion,
    ): NotificationCompat.Builder = NotificationCompat.Builder(context, CHANNEL_NEEDS)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(openAppIntent(context, question.id))
        .setOngoing(true)
        .setAutoCancel(false)
        .setOnlyAlertOnce(false)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(publicVersion(context))

    /** Lock screen: the fact that an answer is wanted, never the question itself. */
    private fun publicVersion(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_NEEDS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_public_title))
            .setContentText(context.getString(R.string.notif_public_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

    private fun answerPendingIntent(
        context: Context,
        question: ClarificationManager.PendingQuestion,
        answer: String,
        index: Int,
    ): PendingIntent {
        val intent = Intent(context, ClarificationAnswerReceiver::class.java).apply {
            action = ClarificationAnswerReceiver.ACTION_ANSWER
            putExtra(EXTRA_ANSWER, answer)
            putExtra(EXTRA_QUESTION_ID, question.id)
            putExtra(EXTRA_NONCE, question.nonce)
        }
        return PendingIntent.getBroadcast(
            context,
            (question.id.hashCode() * 31) + index,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun replyAction(
        context: Context,
        question: ClarificationManager.PendingQuestion,
        label: String,
    ): NotificationCompat.Action {
        val intent = Intent(context, ClarificationAnswerReceiver::class.java).apply {
            action = ClarificationAnswerReceiver.ACTION_ANSWER
            putExtra(EXTRA_QUESTION_ID, question.id)
            putExtra(EXTRA_NONCE, question.nonce)
        }
        // RemoteInput only reads the answer from a mutable PendingIntent.
        val pending = PendingIntent.getBroadcast(
            context,
            (question.id.hashCode() * 31) + 7,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, label, pending)
            .addRemoteInput(
                RemoteInput.Builder(KEY_TEXT_REPLY)
                    .setLabel(context.getString(R.string.notif_reply_hint))
                    .build(),
            )
            .build()
    }

    private fun permissionPendingIntent(
        context: Context,
        request: PermissionRequest,
        nonce: String,
        choice: String,
        index: Int,
    ): PendingIntent {
        val intent = Intent(context, PermissionAnswerReceiver::class.java).apply {
            action = PermissionAnswerReceiver.ACTION_ANSWER
            putExtra(PermissionAnswerReceiver.EXTRA_REQUEST_ID, request.id)
            putExtra(PermissionAnswerReceiver.EXTRA_CHOICE, choice)
            putExtra(EXTRA_NONCE, nonce)
        }
        return PendingIntent.getBroadcast(
            context,
            (request.id.hashCode() * 31) + index,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** The tap on the body is the only thing that opens the app. */
    private fun openAppIntent(context: Context, tag: String): PendingIntent {
        val intent = Intent(context, ComposeChatActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            tag.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun notify(context: Context, id: Int, notification: Notification) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id, notification)
    }

    private fun cancel(context: Context, id: Int) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(id)
        } catch (e: Throwable) {
            XLog.w(TAG, "cancel($id) failed", e)
        }
    }
}
