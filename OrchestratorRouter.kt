package com.returngift.agent.orchestrator

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.interpreter.InterpreterApi
import com.google.ai.edge.litert.TensorBuffer
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OrchestratorRouter — On-device intent routing for ReturnGift.
 *
 * Runs frozen FunctionGemma-270M base forward pass (no adapter bound),
 * extracts mean-last-token embedding from layer -2, computes cosine
 * similarity against baked-in route centroids, returns matched skill
 * or "unresolved" if below threshold.
 *
 * This runs BEFORE any adapter is bound, so it doesn't block the
 * "agent preparing tools" animation (Section 1 Pre-route & Prefetch).
 *
 * Usage:
 *   val router = OrchestratorRouter(context)
 *   val result = router.route(screenState, userInstruction)
 *   when (result.route) {
 *       "messaging", "transactional", "analytical" -> bindAdapter(result.route)
 *       "unresolved" -> showFallbackUI()
 *   }
 *   // result.similarityScore feeds into Section 6 confidence gate
 */
class OrchestratorRouter private constructor(
    private val centroids: FloatArray,      // flat [numRoutes * hiddenDim]
    private val hiddenDim: Int,
    private val routes: List<Route>,
    private val model: InterpreterApi,      // frozen FunctionGemma .tflite
) {

    private companion object {
        private const val TAG = "OrchestratorRouter"
        private const val LAYER_INDEX = -2  // second-to-last layer
        private const val MAX_SEQ_LEN = 2048
    }

    data class Route(
        val name: String,
        val threshold: Float,
    )

    data class Result(
        val route: String,              // "messaging" | "transactional" | "analytical" | "unresolved"
        val similarityScore: Float,     // cosine similarity to matched centroid (0..1)
        val allScores: Map<String, Float>, // all route scores for debugging
    )

    /**
     * Factory: loads assets + initializes LiteRT interpreter.
     * Call once at app startup (e.g., in Application.onCreate()).
     */
    companion object {
        @Volatile private var INSTANCE: OrchestratorRouter? = null

        fun getInstance(context: Context): OrchestratorRouter {
            var instance = INSTANCE
            if (instance != null) return instance

            synchronized(this) {
                instance = INSTANCE
                if (instance == null) {
                    instance = create(context)
                    INSTANCE = instance
                }
                return instance!!
            }
        }

        private fun create(context: Context): OrchestratorRouter {
            // 1. Load centroids metadata
            val metaJson = context.assets.open("orchestrator/centroids_meta.json").bufferedReader().readText()
            val meta = com.google.gson.Gson().fromJson(metaJson, CentroidsMeta::class.java)

            // 2. Load centroids binary
            val binStream = context.assets.open("orchestrator/centroids.bin")
            val centroids = loadCentroids(binStream, meta.numRoutes, meta.hiddenDim)
            binStream.close()

            // 3. Build route list with thresholds
            val routes = meta.routes.map { Route(it.name, it.threshold) }

            // 4. Load frozen base model (.tflite / .litertlm)
            val modelPath = copyModelFromAssets(context, "orchestrator/functiongemma_base.tflite")
            val model = InterpreterApi.create(modelPath, InterpreterApi.Options())

            Log.i(TAG, "OrchestratorRouter initialized: ${routes.size} routes, dim=$hiddenDim")
            return OrchestratorRouter(centroids, meta.hiddenDim, routes, model)
        }
    }

    /**
     * Main routing entry point. Runs frozen base forward pass + centroid matching.
     *
     * @param screenState Accessibility tree / OCR text from current screen
     * @param instruction User's natural language instruction
     * @return Result with matched route (or "unresolved") + similarity score
     */
    fun route(screenState: String, instruction: String): Result {
        // Build input: "Screen: <state>\nUser: <instruction>"
        val inputText = "Screen: $screenState\nUser: $instruction"

        // Tokenize (same as centroid computation)
        val tokens = tokenize(inputText)
        if (tokens.isEmpty()) {
            return Result("unresolved", 0f, emptyMap())
        }

        // Run frozen base model forward pass
        val embedding = runForwardPass(tokens)

        // Cosine similarity against all centroids
        val scores = cosineSimilarities(embedding)

        // Find best match
        var bestIdx = -1
        var bestScore = -1f
        for (i in scores.indices) {
            if (scores[i] > bestScore) {
                bestScore = scores[i]
                bestIdx = i
            }
        }

        val matchedRoute = routes[bestIdx]
        val routeName = if (bestScore >= matchedRoute.threshold) {
            matchedRoute.name
        } else {
            "unresolved"
        }

        val allScores = routes.associate { it.name to scores[routes.indexOf(it)] }

        Log.d(TAG, "Route: $routeName (score=$bestScore, threshold=${matchedRoute.threshold})")

        return Result(routeName, bestScore, allScores)
    }

    /**
     * Run frozen FunctionGemma base model forward pass.
     * Extracts last-token hidden state from layer -2.
     */
    private fun runForwardPass(tokens: IntArray): FloatArray {
        // Prepare input tensors
        val inputIds = TensorBuffer.createFixedSize(intArrayOf(1, tokens.size), com.google.ai.edge.litert.DataType.INT32)
        inputIds.loadArray(tokens)

        val attentionMask = TensorBuffer.createFixedSize(intArrayOf(1, tokens.size), com.google.ai.edge.litert.DataType.INT32)
        attentionMask.loadArray(tokens.map { 1 }.toIntArray())

        // Output: we need hidden states from layer -2
        // The model must be exported with output_hidden_states=True
        // and return_dict=True to get all layer outputs
        val outputs = mutableMapOf<String, Any>()
        model.runForMultipleInputsOutputs(
            arrayOf(inputIds.buffer, attentionMask.buffer),
            outputs
        )

        // Extract hidden states from layer -2
        // Expected output key: "hidden_states" -> List of [1, seq, hidden] per layer
        // This depends on the exact TFLite export format
        val hiddenStates = outputs["hidden_states"] as? List<*>
            ?: throw IllegalStateException("Model must output hidden_states")

        val layerOutput = hiddenStates[hiddenStates.size + LAYER_INDEX] as FloatBuffer
            ?: throw IllegalStateException("Layer $LAYER_INDEX output not found")

        // Get last non-pad token
        val seqLen = tokens.size
        val embedding = FloatArray(hiddenDim)
        val baseOffset = seqLen * hiddenDim  // last token offset in flat buffer
        for (i in 0 until hiddenDim) {
            embedding[i] = layerOutput.get(baseOffset + i)
        }

        // L2 normalize
        var norm = 0f
        for (v in embedding) norm += v * v
        norm = kotlin.math.sqrt(norm) + 1e-8f
        for (i in embedding.indices) embedding[i] /= norm

        return embedding
    }

    /** Cosine similarity between query embedding and all centroids. */
    private fun cosineSimilarities(query: FloatArray): FloatArray {
        val scores = FloatArray(routes.size)
        for (r in routes.indices) {
            var dot = 0f
            val offset = r * hiddenDim
            for (i in 0 until hiddenDim) {
                dot += query[i] * centroids[offset + i]
            }
            scores[r] = dot  // centroids and query are already L2-normalized
        }
        return scores
    }

    /** Tokenize input text using same tokenizer as centroid computation. */
    private fun tokenize(text: String): IntArray {
        // In production, use the actual tokenizer (SentencePiece) via LiteRT
        // or bundle the tokenizer model as asset and use SentencePieceProcessor.
        // For now, placeholder - replace with real tokenization.
        return intArrayOf()  // TODO: implement tokenization
    }

    /** Load centroids from binary asset. */
    private fun loadCentroids(stream: InputStream, numRoutes: Int, hiddenDim: Int): FloatArray {
        val buffer = ByteBuffer.allocate(numRoutes * hiddenDim * 4).order(ByteOrder.LITTLE_ENDIAN)
        val bytes = stream.readBytes()
        buffer.put(bytes)
        buffer.rewind()
        return buffer.asFloatBuffer().array()
    }

    /** Copy model from assets to cache dir for LiteRT. */
    private fun copyModelFromAssets(context: Context, assetPath: String): String {
        val cacheFile = java.io.File(context.cacheDir, "functiongemma_base.tflite")
        if (!cacheFile.exists()) {
            context.assets.open(assetPath).use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return cacheFile.absolutePath
    }
}

/** Metadata from centroids_meta.json */
private data class CentroidsMeta(
    val version: String,
    val modelBase: String,
    val layerIndex: Int,
    val pooling: String,
    val numRoutes: Int,
    val hiddenDim: Int,
    val routes: List<RouteMeta>,
)

private data class RouteMeta(
    val name: String,
    val threshold: Float,
)