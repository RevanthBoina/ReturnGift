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
import androidx.core.app.RemoteInput
import com.returngift.agent.R
import com.returngift.agent.agent.clarify.ClarificationManager
import com.returngift.agent.utils.XLog

/**
 * WhatsApp-style heads-up posted when an ask_user question parks while the
 * chat is in the background: up to 3 tappable choice actions plus a RemoteInput
 * free-text reply. Answering from the notification feeds
 * [ClarificationManager.answer] via [ClarificationAnswerReceiver] — the task
 * resumes exactly as if the user had answered in the chat card.
 */
object ClarificationNotifier {

    private const val TAG = "ClarificationNotifier"
    private const val CLARIFY_CHANNEL_ID = "ReturnGift_clarify_channel"
    private const val CLARIFY_NOTIFICATION_ID = 1003

    const val KEY_TEXT_REPLY = "key_text_reply"
    const val EXTRA_ANSWER = "extra_answer"

    // Android surfaces at most 3 actions — more choices fall back to opening
    // the chat card, where every choice chip is rendered.
    private const val MAX_NOTIFICATION_CHOICES = 3

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CLARIFY_CHANNEL_ID,
                "Agent questions",
                NotificationManager.IMPORTANCE_HIGH, // heads-up, like a message
            )
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    fun show(context: Context, question: ClarificationManager.PendingQuestion) {
        if (!ForegroundService.hasNotificationPermission(context)) return
        try {
            createChannel(context)
            val openChat = Intent(context, com.returngift.agent.ui.chat.ComposeChatActivity::class.java)
            val openPending = PendingIntent.getActivity(
                context, question.id.hashCode(), openChat,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            var requestCode = question.id.hashCode()

            val builder = NotificationCompat.Builder(context, CLARIFY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("ReturnGift is asking…")
                .setContentText(question.question)
                .setContentIntent(openPending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)

            val choices = question.choices.take(MAX_NOTIFICATION_CHOICES)
            fun addChoiceAction(choice: String) {
                requestCode++
                val intent = answerIntent(context, choice)
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        null, choice,
                        PendingIntent.getBroadcast(
                            context, requestCode, intent,
                            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    ).build(),
                )
            }

            if (choices.isNotEmpty() && !question.allowFreeText) {
                choices.forEach(::addChoiceAction)
            } else {
                // Free-text reply box in the notification itself (choice-only
                // questions reach here when choices is empty).
                requestCode++
                val intent = answerTypedIntent(context)
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        null, "Reply",
                        PendingIntent.getBroadcast(
                            context, requestCode, intent,
                            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    ).addRemoteInput(RemoteInput.Builder(KEY_TEXT_REPLY).setLabel("Your answer").build())
                        .build(),
                )
                // Choices as extra tappable actions when free text is also allowed.
                choices.forEach(::addChoiceAction)
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(CLARIFY_NOTIFICATION_ID, builder.build())
            XLog.i(TAG, "heads-up posted for \"${question.question.take(60)}\"")
        } catch (e: Exception) {
            XLog.w(TAG, "clarification heads-up failed", e)
        }
    }

    private fun answerIntent(context: Context, choice: String): Intent =
        Intent(context, ClarificationAnswerReceiver::class.java).apply {
            action = ClarificationAnswerReceiver.ACTION_ANSWER
            putExtra(EXTRA_ANSWER, choice)
        }

    private fun answerTypedIntent(context: Context): Intent =
        Intent(context, ClarificationAnswerReceiver::class.java).apply {
            action = ClarificationAnswerReceiver.ACTION_ANSWER
        }

    fun dismiss(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(CLARIFY_NOTIFICATION_ID)
        } catch (e: Exception) {
            XLog.w(TAG, "dismiss failed", e)
        }
    }
}
