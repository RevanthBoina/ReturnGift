// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.permission

import android.os.Handler
import android.os.Looper
import com.returngift.agent.agent.prefs.AgentPreferences
import com.returngift.agent.utils.XLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Blocking bridge between the agent thread and the permission UI.
 *
 * Why this exists: the per-app allow-list gate used to *assume* a UI would
 * surface the first-encounter prompt (it never did), so the agent silently
 * proceeded. [request] now:
 *
 *  1. opens ReturnGift's own permission card in the foreground
 *     ([PermissionPromptLauncher] → `PermissionPromptActivity`),
 *  2. parks the calling (agent) thread until the user picks a choice,
 *  3. posts a heads-up notification with the same choices when the app is in
 *     the background (so the request is never invisible),
 *  4. returns [PermissionRequest.safeDefault] (CANCEL) on timeout/cancel.
 *
 * Only ONE request can be pending at a time — a second request returns the safe
 * default immediately instead of queueing invisibly.
 */
object PermissionPromptBroker {

    private const val TAG = "PermissionPromptBroker"
    const val DEFAULT_TIMEOUT_MS = 45_000L
    private const val POLL_SLICE_MS = 250L

    /** Requests that must be answered by a human, not by a timer. */
    @Volatile
    private var pending: PermissionRequest? = null

    @Volatile
    private var latch: CountDownLatch? = null

    @Volatile
    private var answer: String? = null

    @Volatile
    private var cancelled: Boolean = false

    private val listeners = CopyOnWriteArrayList<(PermissionRequest?) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Overridable in JVM unit tests (Looper is stubbed there). */
    internal var isMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() }

    /**
     * Overridable so tests can assert the UI/notification seam without Android.
     * Production implementations resolve the app context themselves.
     */
    /**
     * The in-app card. Only reached when notifications are blocked, or when the owner
     * deliberately turns "Open the permission card in front" back on.
     */
    internal var openUiHook: (PermissionRequest) -> Unit = { request ->
        PermissionPromptLauncher.open(com.returngift.agent.ReturnGiftApplication.instance, request)
    }

    /** The one prompt surface: a notification answered in place. */
    internal var notifyHook: (PermissionRequest) -> Unit = { request ->
        com.returngift.agent.service.NotificationPrompter.askPermission(
            com.returngift.agent.ReturnGiftApplication.instance, request, currentNonce()
        )
    }

    /** False when Android will not post anything, in which case the card is the fallback. */
    internal var notificationsAvailableHook: () -> Boolean = {
        runCatching {
            com.returngift.agent.service.NotificationPrompter.notificationsAvailable(
                com.returngift.agent.ReturnGiftApplication.instance
            )
        }.getOrDefault(false)
    }

    /** Overridable so tests never touch NotificationManager. */
    internal var dismissHook: () -> Unit = {
        try {
            com.returngift.agent.service.NotificationPrompter.dismissPermission(
                com.returngift.agent.ReturnGiftApplication.instance
            )
        } catch (e: Throwable) {
            XLog.w(TAG, "notification dismiss failed", e)
        }
    }

    /** Random tag for the posted buttons; an answer only counts for the live request. */
    @Volatile
    private var nonce: String = ""

    fun currentNonce(): String = nonce

    fun snapshot(): PermissionRequest? = pending

    fun addListener(listener: (PermissionRequest?) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (PermissionRequest?) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Ask the user and wait for the decision.
     *
     * MUST NOT be called on the main thread (parking the UI thread would deadlock
     * the very screen that has to answer).
     */
    fun request(
        request: PermissionRequest,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): PermissionRequest.Decision {
        if (isMainThread()) {
            XLog.e(TAG, "request() called on the main thread — refusing to block; denying")
            return request.safeDefault()
        }
        synchronized(this) {
            if (pending != null) {
                XLog.w(TAG, "request: another permission prompt is already pending, denying this one")
                return request.safeDefault()
            }
            pending = request
            latch = CountDownLatch(1)
            answer = null
            cancelled = false
            nonce = java.util.UUID.randomUUID().toString().take(8)
        }

        XLog.i(TAG, "Permission pending: kind=${request.kind} target=${request.packageName.ifBlank { request.capabilityId }}")
        notifyListeners(request)

        // The notification is the surface. The card opens only when Android will not
        // post one, or when the owner asked for it in Settings.
        var opened = false
        var notified = false
        val canNotify = notificationsAvailableHook() && AgentPreferences.notifyPermission

        if (canNotify) {
            try {
                notifyHook(request)
                notified = true
            } catch (e: Throwable) {
                XLog.w(TAG, "could not post the permission notification", e)
            }
        }
        if (!notified || AgentPreferences.openPermissionCard) {
            if (!notified) {
                XLog.w(TAG, "notifications are blocked — the permission card is the only way to ask")
                toastOnce(request)
            }
            try {
                openUiHook(request)
                opened = true
            } catch (e: Throwable) {
                XLog.w(TAG, "could not open the permission screen", e)
            }
        }
        XLog.i(TAG, "permission request surfaced (card=$opened, notification=$notified)")

        val activeLatch = latch
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                finishRequest()
                XLog.w(TAG, "Permission prompt timed out after ${timeoutMs}ms → cancelling the tool call")
                return request.safeDefault()
            }
            try {
                if (activeLatch?.await(minOf(POLL_SLICE_MS, remaining), TimeUnit.MILLISECONDS) == true) break
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                finishRequest()
                XLog.w(TAG, "Permission prompt interrupted → cancelling the tool call")
                return request.safeDefault()
            }
            if (cancelled) {
                finishRequest()
                XLog.i(TAG, "Permission prompt cancelled with the task")
                return request.safeDefault()
            }
        }

        val choice = answer
        finishRequest()
        val decision = request.decisionFor(choice) ?: request.safeDefault()
        XLog.i(TAG, "Permission decided: kind=${request.kind} choice=\"$choice\" decision=$decision")
        return decision
    }

    /**
     * Resolve the pending request. Returns true when a pending request consumed
     * the answer.
     */
    fun answer(choice: String): Boolean {
        val current = pending ?: return false
        if (current.decisionFor(choice) == null) {
            XLog.w(TAG, "answer: unrecognised choice \"$choice\" for ${current.id}")
            return true
        }
        answer = choice
        latch?.countDown()
        return true
    }

    /** Unblock a pending prompt (task cancelled / app shut down). Idempotent. */
    fun cancelPending() {
        if (pending == null) return
        cancelled = true
        latch?.countDown()
    }

    fun isPending(): Boolean = pending != null

    /**
     * One line in the app saying why the question is on screen instead of in the shade.
     * Not a dialog, not a setting: a sentence, once per request.
     */
    private fun toastOnce(request: PermissionRequest) {
        try {
            val app = com.returngift.agent.ReturnGiftApplication.instance
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    app,
                    "Notifications are off, so the permission card has to open. Allow notifications to answer from the shade.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        } catch (e: Throwable) {
            XLog.w(TAG, "could not show the fallback notice", e)
        }
    }

    private fun finishRequest() {
        synchronized(this) {
            pending = null
            latch = null
            answer = null
            cancelled = false
        }
        notifyListeners(null)
        try {
            dismissHook()
        } catch (e: Throwable) {
            XLog.w(TAG, "dismiss hook failed", e)
        }
    }

    private fun notifyListeners(request: PermissionRequest?) {
        val dispatch = Runnable {
            for (listener in listeners) {
                try {
                    listener(request)
                } catch (e: Exception) {
                    XLog.w(TAG, "permission listener error", e)
                }
            }
        }
        // Handler.post returns false in JVM unit tests → fall back to a direct call.
        if (!mainHandler.post(dispatch)) dispatch.run()
    }
}
