// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.clarify

import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.returngift.agent.ClawApplication
import com.returngift.agent.utils.AppUiState
import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Suspend/resume bridge for the ask_user tool — the keystone of the
 * clarification-first agent behavior (ask before acting on ambiguity instead of
 * guessing and completing).
 *
 * The agent loop executes tools synchronously on its worker thread, so
 * [request] simply parks that thread on a latch until the UI (chat card / typed
 * reply) calls [answer], the task is cancelled ([cancelPending]), or the
 * timeout elapses. No coroutine/plumbing surgery in the loop is needed.
 *
 * UI observers register via [addListener]; notifications are always posted on
 * the main thread.
 */
object ClarificationManager {

    private const val TAG = "ClarificationManager"
    const val DEFAULT_TIMEOUT_MS = 120_000L
    private const val POLL_SLICE_MS = 250L
    private const val KEY_PERSISTED_QUESTION = "clarification_pending_question"

    data class PendingQuestion(
        val id: String,
        val question: String,
        val choices: List<String>,
        val allowFreeText: Boolean,
        val createdAtMs: Long,
    )

    /** True when a question just resolved within [windowMs] — used by the chat
     *  funnels to detect a stale-answer race (UI saw the question but the loop
     *  already timed out / was cancelled) and acknowledge it instead of
     *  silently routing the text as a new message. */
    fun resolvedRecently(windowMs: Long = 30_000L): Boolean =
        System.currentTimeMillis() - lastResolvedAtMs <= windowMs

    @Volatile
    private var lastResolvedAtMs: Long = 0L

    @Volatile
    private var pending: PendingQuestion? = null

    @Volatile
    private var latch: CountDownLatch? = null

    @Volatile
    private var answer: String? = null

    @Volatile
    private var cancelled: Boolean = false

    private val listeners = CopyOnWriteArrayList<(PendingQuestion?) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Main-thread check, overridable in JVM unit tests where Looper is stubbed. */
    internal var isMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() }

    private val gson = Gson()

    /**
     * Persists the parked question so a process death does not silently lose what the
     * agent was asking. Overridable in JVM unit tests (MMKV is uninitialized there).
     * Called with null when the question resolves (answered/cancelled/timeout).
     */
    internal var persistHook: (PendingQuestion?) -> Unit = { q ->
        try {
            KVUtils.putString(KEY_PERSISTED_QUESTION, q?.let { gson.toJson(it) })
        } catch (e: Exception) {
            XLog.w(TAG, "clarification persistence failed", e)
        }
    }

    /**
     * Heads-up notification hook — posts/cancels the WhatsApp-style notification
     * when the chat UI is not in front. Overridable in JVM unit tests.
     */
    internal var headsUpHook: (PendingQuestion) -> Unit = { q ->
        try {
            val ctx = ClawApplication.instance
            if (!AppUiState.isForeground) {
                com.returngift.agent.service.ClarificationNotifier.show(ctx, q)
            }
        } catch (e: Exception) {
            XLog.w(TAG, "heads-up hook failed", e)
        }
    }

    internal var headsUpDismissHook: () -> Unit = {
        try {
            com.returngift.agent.service.ClarificationNotifier.dismiss(ClawApplication.instance)
        } catch (e: Exception) {
            XLog.w(TAG, "heads-up dismiss failed", e)
        }
    }

    /**
     * Read and clear a persisted parked question (from a previous process). The restored
     * question is informational only — no loop thread is parked on it anymore.
     */
    fun consumePersisted(): PendingQuestion? {
        return try {
            val json = KVUtils.getString(KEY_PERSISTED_QUESTION, "")
            if (json.isEmpty()) {
                null
            } else {
                KVUtils.putString(KEY_PERSISTED_QUESTION, null)
                gson.fromJson(json, PendingQuestion::class.java)
            }
        } catch (e: Exception) {
            XLog.w(TAG, "consumePersisted failed", e)
            null
        }
    }

    /** Current pending question, or null. Safe to call from any thread. */
    fun snapshot(): PendingQuestion? = pending

    fun addListener(listener: (PendingQuestion?) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (PendingQuestion?) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Park the calling (agent-loop) thread until the user answers, the task is
     * cancelled, or [timeoutMs] elapses. Returns the user's answer text, or null
     * on timeout/cancellation. Only one question can be pending at a time — a
     * second request while one is pending returns null immediately.
     */
    fun request(
        question: String,
        choices: List<String>,
        allowFreeText: Boolean,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): String? {
        if (isMainThread()) {
            // Parking the UI thread would deadlock: the answer can only arrive via the UI.
            XLog.e(TAG, "request() called on the main thread — refusing to block")
            return null
        }
        synchronized(this) {
            if (pending != null) {
                XLog.w(TAG, "request: another clarification already pending, refusing")
                return null
            }
            pending = PendingQuestion(
                id = UUID.randomUUID().toString(),
                question = question,
                choices = choices,
                allowFreeText = allowFreeText,
                createdAtMs = System.currentTimeMillis(),
            )
            latch = CountDownLatch(1)
            answer = null
            cancelled = false
        }
        XLog.i(TAG, "Clarification pending: \"$question\" choices=${choices.size} freeText=$allowFreeText")
        persistHook(pending)
        notifyListeners(pending)
        pending?.let { headsUpHook(it) }

        val activeLatch = latch
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                finishRequest()
                XLog.w(TAG, "Clarification timed out after ${timeoutMs}ms: \"$question\"")
                return null
            }
            try {
                if (activeLatch?.await(minOf(POLL_SLICE_MS, remaining), TimeUnit.MILLISECONDS) == true) break
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                finishRequest()
                XLog.w(TAG, "Clarification interrupted: \"$question\"")
                return null
            }
            if (cancelled) {
                finishRequest()
                XLog.i(TAG, "Clarification cancelled: \"$question\"")
                return null
            }
        }
        val result = answer
        finishRequest()
        XLog.i(TAG, "Clarification answered: \"$question\" -> \"${result?.take(80)}\"")
        return result
    }

    /**
     * Resolve the pending question with the user's answer (choice tap or typed
     * reply). Returns true when a pending question consumed the answer, false
     * when nothing was pending (caller should route the text as a normal
     * chat/task message).
     */
    fun answer(text: String): Boolean {
        val current = pending ?: return false
        if (!current.allowFreeText && current.choices.isNotEmpty() && text !in current.choices) {
            XLog.w(TAG, "answer: rejecting free-text reply for choice-only question")
            return true // consumed, but ignored — the card constrains input
        }
        this.answer = text
        latch?.countDown()
        return true
    }

    /** Unblock any pending question (task cancelled / service shutdown). Idempotent. */
    fun cancelPending() {
        if (pending == null) return
        cancelled = true
        latch?.countDown()
    }

    private fun finishRequest() {
        synchronized(this) {
            pending = null
            latch = null
            answer = null
            cancelled = false
            lastResolvedAtMs = System.currentTimeMillis()
        }
        persistHook(null)
        notifyListeners(null)
        headsUpDismissHook()
    }

    private fun notifyListeners(q: PendingQuestion?) {
        val dispatch = Runnable {
            for (listener in listeners) {
                try {
                    listener(q)
                } catch (e: Exception) {
                    XLog.w(TAG, "listener error", e)
                }
            }
        }
        // Handler.post returns false in JVM unit tests (stubbed) — fall back to a
        // direct call so listener logic stays testable without Robolectric.
        if (!mainHandler.post(dispatch)) dispatch.run()
    }
}
