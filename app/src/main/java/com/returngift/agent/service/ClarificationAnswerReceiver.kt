// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.returngift.agent.agent.clarify.ClarificationManager
import com.returngift.agent.utils.XLog

/**
 * Every answer taps or types back here — never into an activity.
 *
 * The intent carries the question id and a random nonce, so an answer can only resolve
 * the question it was posted for. If the question has already gone (a newer question
 * replaced it, or the wait expired) the tap is ignored and the shade is corrected. If
 * the process died while the question was pending, the persisted question is used to
 * restart the parked task with the owner's answer folded in.
 */
class ClarificationAnswerReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ClarificationAnswerReceiver"
        const val ACTION_ANSWER = "com.returngift.agent.action.ANSWER_CLARIFICATION"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val answer = intent.getStringExtra(NotificationPrompter.EXTRA_ANSWER)
            ?: RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(NotificationPrompter.KEY_TEXT_REPLY)?.toString()
        if (answer.isNullOrBlank()) {
            XLog.w(TAG, "empty answer payload, dropping")
            return
        }
        val questionId = intent.getStringExtra(NotificationPrompter.EXTRA_QUESTION_ID)
        val nonce = intent.getStringExtra(NotificationPrompter.EXTRA_NONCE)

        val live = ClarificationManager.snapshot()
        if (live != null) {
            val belongs = (questionId == null || live.id == questionId) && (nonce == null || live.nonce == nonce)
            if (!belongs) {
                XLog.w(TAG, "stale answer for $questionId (live=${live.id}) — ignored")
                NotificationPrompter.markStaleAnswer(context)
                return
            }
            ClarificationManager.answer(answer)
            XLog.i(TAG, "answer delivered: \"${answer.take(60)}\"")
            return
        }

        // No parked thread: the process was killed while the question was open.
        val persisted = ClarificationManager.consumePersisted()
        if (persisted == null || (questionId != null && persisted.id != questionId) ||
            (nonce != null && persisted.nonce != nonce)
        ) {
            XLog.w(TAG, "no question to answer (stale or expired)")
            NotificationPrompter.markStaleAnswer(context)
            return
        }
        val ageMs = System.currentTimeMillis() - persisted.createdAtMs
        if (ageMs > ClarificationManager.DEFAULT_TIMEOUT_MS) {
            NotificationPrompter.markQuestionExpired(context, persisted)
            return
        }
        if (persisted.taskText.isBlank()) {
            XLog.w(TAG, "answer after process death has no task text to resume")
            NotificationPrompter.markQuestionExpired(context, persisted)
            return
        }
        val resumed = NotificationTaskIntake.submit(context, persisted.taskText, answerContext = answer)
        XLog.i(TAG, "answer after process death — task restarted: $resumed")
        if (resumed) NotificationPrompter.dismissQuestion(context)
    }
}
