package com.returngift.agent.ml

import android.content.Context
import com.google.ai.edge.litert.Interpreter
import com.google.ai.edge.litert.TensorBuffer

/**
 * ModelRunner - Centralized model execution for ReturnGift.
 *
 * Handles both:
 * 1. Frozen base encoding (for Orchestrator routing) - NO adapter bound
 * 2. Skill adapter generation (for execution) - specific LoRA adapter bound
 */
class ModelRunner private constructor(
    private val context: Context,
    private val baseInterpreter: Interpreter,           // Frozen FunctionGemma base
    private val encoderInterpreter: Interpreter,        // Optional: distilled encoder for fast routing
) {

    companion object {
        private var INSTANCE: ModelRunner? = null

        @Synchronized
        fun initialize(context: Context): ModelRunner {
            if (INSTANCE == null) {
                INSTANCE = create(context)
            }
            return INSTANCE!!
        }

        fun getInstance(): ModelRunner? = INSTANCE

        private fun create(context: Context): ModelRunner {
            // Load frozen base model (.litertlm / .tflite)
            // This model MUST output hidden states from layer -2
            val baseFd = context.assets.openFd("models/functiongemma_base.tflite")
            val baseInterpreter = Interpreter(baseFd)
            baseFd.close()

            // Optional: Load distilled encoder for fast routing (recommended)
            val encoderInterpreter: Interpreter? = try {
                val encFd = context.assets.openFd("models/functiongemma_encoder.tflite")
                val interp = Interpreter(encFd)
                encFd.close()
                interp
            } catch (e: Exception) {
                null // Fall back to base model with hidden-state output
            }

            return ModelRunner(context, baseInterpreter, encoderInterpreter)
        }
    }

    /**
     * Encode instruction using FROZEN BASE (no adapter bound).
     * Used by OrchestratorRouter for routing.
     *
     * Returns mean-last-token embedding from layer -2.
     */
    fun encodeFrozenBase(inputText: String): FloatArray {
        val (inputIds, attentionMask) = tokenize(inputText)

        if (encoderInterpreter != null) {
            // Fast path: use distilled encoder
            return runEncoder(inputIds, attentionMask)
        } else {
            // Slow path: use base model with hidden-state output
            return runBaseWithHiddenStates(inputIds, attentionMask)
        }
    }

    /**
     * Generate with specific skill adapter bound.
     * Used for actual task execution after routing.
     */
    fun generateWithAdapter(skill: String, prompt: String): String {
        // Bind adapter for this skill
        // bindAdapter(skill)  // Your existing adapter binding logic

        // Run generation
        // val output = runGeneration(prompt)

        // unbindAdapter()  // Clean up
        // return output
        return "" // Placeholder - integrate with your existing generation code
    }

    // ============ Private: Tokenization ============

    private fun tokenize(text: String): Pair<IntArray, IntArray> {
        // Use your existing tokenizer (SentencePiece via LiteRT support lib)
        // Returns (inputIds, attentionMask) padded to 2048
        return Pair(intArrayOf(), intArrayOf())
    }

    // ============ Private: Inference ============

    private fun runEncoder(inputIds: IntArray, attentionMask: IntArray): FloatArray {
        // Encoder signature: (input_ids, attention_mask) -> embedding [1, hidden_dim]
        val inIds = TensorBuffer.createFixedSize(intArrayOf(1, inputIds.size), com.google.ai.edge.litert.DataType.INT32)
        inIds.loadArray(inputIds, intArrayOf(1, inputIds.size))

        val attn = TensorBuffer.createFixedSize(intArrayOf(1, attentionMask.size), com.google.ai.edge.litert.DataType.INT32)
        attn.loadArray(attentionMask, intArrayOf(1, attentionMask.size))

        val outEmb = TensorBuffer.createFixedSize(intArrayOf(1, 768), com.google.ai.edge.litert.DataType.FLOAT32) // adjust dim

        encoderInterpreter!!.runForMultipleInputsOutputs(
            arrayOf(inIds.buffer, attn.buffer),
            mapOf(0 to outEmb)
        )

        val embedding = outEmb.floatArray
        // Normalize
        normalizeInPlace(embedding)
        return embedding
    }

    private fun runBaseWithHiddenStates(inputIds: IntArray, attentionMask: IntArray): FloatArray {
        // Base model signature with hidden states output:
        // Inputs: (input_ids, attention_mask)
        // Outputs: (logits, hidden_states_layer_minus_2)
        // Requires model compiled with multiple outputs or FlexDelegate

        val inIds = TensorBuffer.createFixedSize(intArrayOf(1, inputIds.size), com.google.ai.edge.litert.DataType.INT32)
        inIds.loadArray(inputIds, intArrayOf(1, inputIds.size))

        val attn = TensorBuffer.createFixedSize(intArrayOf(1, attentionMask.size), com.google.ai.edge.litert.DataType.INT32)
        attn.loadArray(attentionMask, intArrayOf(1, attentionMask.size))

        val outHidden = TensorBuffer.createFixedSize(intArrayOf(1, inputIds.size, 768), com.google.ai.edge.litert.DataType.FLOAT32)

        baseInterpreter.runForMultipleInputsOutputs(
            arrayOf(inIds.buffer, attn.buffer),
            mapOf(1 to outHidden)  // Output index 1 = hidden states
        )

        val hidden = outHidden.floatArray  // [1, seq_len, hidden_dim]

        // Mean-last-token pooling: find last non-pad token
        var lastIdx = 0
        for (i in attentionMask.indices.reversed()) {
            if (attentionMask[i] == 1) {
                lastIdx = i
                break
            }
        }

        val hiddenDim = 768
        val embedding = FloatArray(hiddenDim)
        for (d in 0 until hiddenDim) {
            embedding[d] = hidden[lastIdx * hiddenDim + d]
        }

        normalizeInPlace(embedding)
        return embedding
    }

    private fun normalizeInPlace(arr: FloatArray) {
        var norm = 0f
        for (v in arr) norm += v * v
        norm = kotlin.math.sqrt(norm.toDouble()).toFloat()
        if (norm > 0f) {
            for (i in arr.indices) arr[i] /= norm
        }
    }
}