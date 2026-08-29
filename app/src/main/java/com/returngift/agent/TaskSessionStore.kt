// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent

import com.returngift.agent.channel.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque

enum class TaskSessionPhase {
    IDLE,
    RUNNING,
    STOPPING,
    PENDING,
}

data class TaskSessionState(
    val phase: TaskSessionPhase = TaskSessionPhase.IDLE,
    val messageId: String = "",
    val channel: Channel? = null,
    val taskText: String = "",
    val startedAtMillis: Long = 0L,
    val stopRequested: Boolean = false,
    val autoReturnToChat: Boolean = false,
) {
    val isRunning: Boolean
        get() = phase != TaskSessionPhase.IDLE && messageId.isNotEmpty()
}

/**
 * A task held in the bounded FIFO pending queue. The orchestrator dequeues one whenever
 * the live session releases its lock (via [TaskSessionStore.tryDequeuePending]).
 *
 * `messageId` is the persisted "m<hex>" id; `taskText` is the user message exactly as
 * typed so re-routing (with `isFallback = false`) replays the same request.
 */
data class PendingTask(
    val messageId: String,
    val channel: Channel,
    val taskText: String,
    val enqueuedAtMillis: Long = System.currentTimeMillis(),
)

/**
 * Single authoritative state holder for the currently running task session.
 *
 * The orchestrator mutates this store; UI and service layers can observe it
 * without having to infer task truth from multiple ad-hoc fields.
 */
class TaskSessionStore {

    private val lock = Any()
    private val _state = MutableStateFlow(TaskSessionState())
    val state: StateFlow<TaskSessionState> = _state
    
    // D1: Pending task queue (bounded FIFO)
    private val pendingQueue = ArrayDeque<PendingTask>()
    private val _pendingFlow = MutableStateFlow(emptyList<PendingTask>())
    val pendingFlow: StateFlow<List<PendingTask>> = _pendingFlow

    fun snapshot(): TaskSessionState = _state.value

    fun isTaskRunning(): Boolean = _state.value.isRunning

    fun tryAcquire(
        messageId: String,
        channel: Channel,
        taskText: String = "",
        autoReturnToChat: Boolean = channel == Channel.LOCAL,
    ): Boolean {
        synchronized(lock) {
            if (_state.value.isRunning) return false
            _state.value = TaskSessionState(
                phase = TaskSessionPhase.RUNNING,
                messageId = messageId,
                channel = channel,
                taskText = taskText,
                startedAtMillis = System.currentTimeMillis(),
                stopRequested = false,
                autoReturnToChat = autoReturnToChat,
            )
            return true
        }
    }

    fun updateTaskText(taskText: String) {
        synchronized(lock) {
            val current = _state.value
            if (!current.isRunning || current.taskText == taskText) return
            _state.value = current.copy(taskText = taskText)
        }
    }

    fun markStopping(): Boolean {
        synchronized(lock) {
            val current = _state.value
            if (!current.isRunning) return false
            if (current.phase == TaskSessionPhase.STOPPING && current.stopRequested) return false
            _state.value = current.copy(
                phase = TaskSessionPhase.STOPPING,
                stopRequested = true,
            )
            return true
        }
    }

    fun release(): TaskSessionState {
        synchronized(lock) {
            val current = _state.value
            _state.value = TaskSessionState()
            return current
        }
    }

    /**
     * Compare-and-swap release (FIX 12): releases the session ONLY if the current session
     * still matches [messageId], returning the released state; returns null when the session
     * was already released by a different terminal path. Makes terminal cleanup idempotent —
     * exactly one handler performs channel confirmation / onTaskFinished / floating state.
     */
    fun releaseIfMatches(messageId: String): TaskSessionState? {
        synchronized(lock) {
            val current = _state.value
            if (current.messageId != messageId) {
                return null
            }
            _state.value = TaskSessionState()
            return current
        }
    }
    
    // D1: Pending queue operations
    fun enqueuePending(messageId: String, channel: Channel, taskText: String) {
        synchronized(lock) {
            pendingQueue.addLast(PendingTask(messageId, channel, taskText))
            _pendingFlow.value = pendingQueue.toList()
        }
    }
    
    fun tryDequeuePending(): PendingTask? {
        synchronized(lock) {
            val task = pendingQueue.pollFirst()
            _pendingFlow.value = pendingQueue.toList()
            return task
        }
    }
    
    val pendingCount: Int
        get() = synchronized(lock) { pendingQueue.size }
}
