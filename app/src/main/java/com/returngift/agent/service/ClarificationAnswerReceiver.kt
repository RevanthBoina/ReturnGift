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
 * Receives answers tapped or typed into the clarification heads-up
 * notification and feeds them to [ClarificationManager.answer]. Non-exported;
 * only [ClarificationNotifier]'s explicit Intents can reach it.
 */
class ClarificationAnswerReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ANSWER = "com.returngift.agent.action.ANSWER_CLARIFICATION"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val answer = intent.getStringExtra(ClarificationNotifier.EXTRA_ANSWER)
            ?: RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(ClarificationNotifier.KEY_TEXT_REPLY)?.toString()
        if (answer.isNullOrBlank()) {
            XLog.w("ClarificationAnswerReceiver", "empty answer payload, dropping")
            return
        }
        val consumed = ClarificationManager.answer(answer)
        XLog.i("ClarificationAnswerReceiver", "notification answer ${if (consumed) "delivered" else "stale"}: \"${answer.take(60)}\"")
    }
}
