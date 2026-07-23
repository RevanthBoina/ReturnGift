package com.returngift.agent.orchestrator

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ProcessLifecycleOwner
import com.returngift.agent.ml.ModelRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OrchestratorManager — High-level integration for ReturnGift orchestrator.
 *
 * Lifecycle:
 *  1. Call OrchestratorManager.initialize(context) at app startup
 *  2. On each user task: call routeAndExecute(screenState, instruction)
 *  3. Result routes to appropriate adapter + confidence gate
 */
class OrchestratorManager private constructor(
    private val context: Context,
) : LifecycleObserver {

    companion object {
        private var INSTANCE: OrchestratorManager? = null
        private const val TAG = "OrchestratorManager"

        fun initialize(context: Context): OrchestratorManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OrchestratorManager(context.applicationContext).also {
                    INSTANCE = it
                    // Register for process lifecycle (optional)
                    ProcessLifecycleOwner.get().lifecycle.addObserver(it)
                }
            }
        }

        fun getInstance(): OrchestratorManager? = INSTANCE
    }

    private val router = OrchestratorRouter(context)
    private val modelRunner = ModelRunner.getInstance() ?: ModelRunner.initialize(context)

    // Replace with your actual adapter binding
    // private val adapterBinder = AdapterBinder(context)

    /**
     * Main entry point: route instruction -> bind adapter -> execute.
     *
     * @param screenStateJson Accessibility/OCR dump of current screen
     * @param instruction User's natural language instruction
     * @param callback Result callback with routed skill and confidence
     */
    fun routeAndExecute(
        screenStateJson: String,
        instruction: String,
        callback: (RoutingOutcome) -> Unit,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            // 1. Run routing (frozen base, no adapter bound)
            val routingResult = router.route(screenStateJson, instruction)

            if (routingResult.route == "unresolved") {
                // Below threshold - show fallback UI
                withContext(Dispatchers.Main) {
                    callback(RoutingOutcome.unresolved(routingResult.confidence, routingResult.allScores))
                }
                return@launch
            }

            // 2. Bind matching adapter (async, can run during "preparing tools" animation)
            val skill = routingResult.route
            val adapterLoaded = bindAdapter(skill)

            if (!adapterLoaded) {
                withContext(Dispatchers.Main) {
                    callback(RoutingOutcome.error("Failed to bind $skill adapter"))
                }
                return@launch
            }

            // 3. Execute with bound adapter
            val prompt = buildPrompt(skill, screenStateJson, instruction)
            val generation = modelRunner.generateWithAdapter(skill, prompt)

            // 4. Return outcome with routing confidence for Section 6 gate
            val outcome = RoutingOutcome(
                route = skill,
                confidence = routingResult.confidence,
                allScores = routingResult.allScores,
                generation = generation,
            )
            withContext(Dispatchers.Main) {
                callback(outcome)
            }
        }
    }

    /**
     * Bind LoRA adapter for the routed skill.
     * This is where you integrate with your existing adapter hot-swap logic.
     */
    private fun bindAdapter(skill: String): Boolean {
        // adapterBinder.bind(skill)
        Log.d(TAG, "Binding adapter for $skill")
        return true // Replace with actual binding result
    }

    private fun buildPrompt(skill: String, screenState: String, instruction: String): String {
        // Match the exact system prompt from configs/<skill>.yaml
        val systemPrompt = when (skill) {
            "messaging" ->
                "You are ReturnGift's Messaging Action Skill. Given a screen state and instruction, output exactly one tool call to compose, edit, or send a message. Preserve exact contact names and message content the user specified. Never invent recipients or content not given. Output format: {tool, params} only."
            "transactional" ->
                "You are ReturnGift's Transactional Action Skill. Given a screen state and instruction involving bookings, payments, or confirmations, output exactly one tool call that advances the transaction by one verified step. Never submit a final confirmation or payment action without an explicit CONFIRM flag in the instruction. Output format: {tool, params} only, no commentary."
            "analytical" ->
                "You are ReturnGift's Analytical Skill. Given a screen state and instruction, output exactly one tool call to perform data analysis or extraction. Preserve exact data points the user specified. Never invent data not given. Output format: {tool, params} only."
            else -> ""
        }
        return "$systemPrompt\nScreen: $screenState\nUser: $instruction"
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroy() {
        // Cleanup if needed
    }
}

/**
 * Result of routing + execution.
 */
sealed class RoutingOutcome {
    data class Success(
        val route: String,
        val confidence: Float,
        val allScores: Map<String, Float>,
        val generation: String,
    ) : RoutingOutcome()

    data class Unresolved(
        val confidence: Float,
        val allScores: Map<String, Float>,
    ) : RoutingOutcome()

    data class Error(val message: String) : RoutingOutcome()

    companion object {
        fun unresolved(confidence: Float, scores: Map<String, Float>) = Unresolved(confidence, scores)
        fun error(msg: String) = Error(msg)
    }
}

/**
 * Usage in your Activity/Fragment:
 *
 * class MainActivity : AppCompatActivity() {
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         OrchestratorManager.initialize(this)
 *     }
 *
 *     fun onUserInstruction(screenState: String, instruction: String) {
 *         OrchestratorManager.getInstance()?.routeAndExecute(screenState, instruction) { outcome ->
 *             when (outcome) {
 *                 is RoutingOutcome.Success -> {
 *                     // Pass outcome.confidence to Section 6 confidence gate
 *                     confidenceGate.check(outcome.confidence) { proceed ->
 *                         if (proceed) executeToolCall(outcome.generation)
 *                     }
 *                 }
 *                 is RoutingOutcome.Unresolved -> {
 *                     showFallbackUI("I'm not sure how to help with that")
 *                 }
 *                 is RoutingOutcome.Error -> {
 *                     showError(outcome.message)
 *                 }
 *             }
 *         }
 *     }
 * }
 */