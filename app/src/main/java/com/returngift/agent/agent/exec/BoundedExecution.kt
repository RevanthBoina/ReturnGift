// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec

import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Shared bounded-execution helper (C5 / FIX 5 / FIX 10).
 *
 * ONE implementation of "run this blocking body but give up after a wall-clock bound",
 * used by every non-LLM execution path that can hang:
 *  - Tier-1 DirectTool ([com.returngift.agent.agent.PipelineRouter.executeTool] caller in
 *    [com.returngift.agent.TaskOrchestrator]): a hung tool must not hold the session lock.
 *  - (Skill steps are bounded at step boundaries by [ExecutionBudget]-style checks in
 *    [com.returngift.agent.agent.skill.SkillExecutor] — never mid-step; the step itself may
 *    still block.)
 *
 * The body runs on a dedicated thread. On timeout the caller abandons the invocation as
 * cleanly as the API allows: the future is cancelled and the caller proceeds (the
 * abandoned thread keeps running to completion in the background — it can no longer
 * observe any result, but it also can no longer block the task pipeline).
 *
 * Pure JVM — unit-testable without Android.
 */
object BoundedExecution {

    companion object {
        private const val TAG = "BoundedExecution"
        const val DEFAULT_WALL_CLOCK_MS = 60_000L
        private val executor: java.util.concurrent.ExecutorService =
            // C2: single worker serializes abandoned work. A timed-out tool may finish its
            // own bounded work — every tool settle wait already caps at 500ms
            // (AdaptiveSettleController), so a stuck completion is bounded by ~500ms.
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r).apply { isDaemon = true }
            }
    }

    sealed class Outcome<out T> {
        /** Body returned [value] before the deadline. */
        data class Completed<T>(val value: T) : Outcome<T>()

        /** Body did not return within the bound. */
        object TimedOut : Outcome<Nothing>()

        /** Body threw; [error] is the cause's message (or the throwable itself). */
        data class Failed(val error: Throwable) : Outcome<Nothing>()
    }

    /**
     * Run [body] on a worker thread and wait up to [wallClockMs] for it.
     *
     * @param wallClockMs bound; when it elapses the future is cancelled and TimedOut returned
     * @param body        the blocking invocation to bound
     */
    fun <T> runBounded(
        wallClockMs: Long = DEFAULT_WALL_CLOCK_MS,
        body: () -> T,
    ): Outcome<T> {
        val future = executor.submit<T> { body() }
        return try {
            Outcome.Completed(future.get(wallClockMs, TimeUnit.MILLISECONDS))
        } catch (_: TimeoutException) {
            future.cancel(true)
            Outcome.TimedOut
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            Outcome.Failed(cause)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Outcome.Failed(e)
        }
    }
}