package com.returngift.agent.orchestrator

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.support.tensorbuffer.TensorBuffer
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * SentencePieceTokenizer — On-device tokenizer for FunctionGemma.
 *
 * Bundles the tokenizer model (tokenizer.model / spiece.model) as an asset
 * and uses the native SentencePieceProcessor via JNI or a pure-Kotlin port.
 *
 * For production, use the official sentencepiece-android library or
 * compile sentencepiece native library with JNI bindings.
 *
 * This is a minimal wrapper interface - implementation depends on your
 * chosen sentencepiece Android integration.
 */
interface SentencePieceTokenizer {
    fun encode(text: String): IntArray
    fun decode(ids: IntArray): String
    fun getPadId(): Int
    fun getEosId(): Int
    fun getVocabSize(): Int
}

/**
 * Factory for creating tokenizer instances.
 */
object TokenizerFactory {
    private const val TAG = "TokenizerFactory"

    /**
     * Create tokenizer from bundled assets.
     * Copies tokenizer.model to cache dir for native library access.
     */
    fun create(context: Context, assetName: String = "orchestrator/tokenizer.model"): SentencePieceTokenizer {
        val modelFile = copyToCache(context, assetName)
        return NativeSentencePieceTokenizer(modelFile.absolutePath)
    }

    private fun copyToCache(context: Context, assetPath: String): File {
        val cacheFile = File(context.cacheDir, "tokenizer.model")
        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return cacheFile
    }
}

/**
 * Native SentencePiece tokenizer using JNI bindings.
 *
 * Requires: sentencepiece-android AAR or custom JNI build.
 * See: https://github.com/google/sentencepiece/tree/master/java
 */
class NativeSentencePieceTokenizer(private val modelPath: String) : SentencePieceTokenizer {
    private val processor: Any // com.google.protobuf.GeneratedMessage or SPMProcessor
    private val spm: Any // sentencepiece.SentencePieceProcessor

    init {
        try {
            // Load native library
            System.loadLibrary("sentencepiece_jni")

            // Initialize processor via JNI
            // spm = SentencePieceProcessor()
            // spm.load(modelPath)
            // This is pseudo-code - actual API depends on the JNI binding
            throw UnsupportedOperationException(
                "Implement JNI binding for SentencePieceProcessor. " +
                "Add sentencepiece-android dependency or build custom JNI."
            )
        } catch (e: Exception) {
            Log.e("SPTokenizer", "Failed to load tokenizer", e)
            throw RuntimeException("Tokenizer init failed", e)
        }
    }

    override fun encode(text: String): IntArray {
        // return spm.encode(text)
        return intArrayOf()
    }

    override fun decode(ids: IntArray): String {
        // return spm.decode(ids)
        return ""
    }

    override fun getPadId(): Int {
        // return spm.get_pad_id()
        return 0
    }

    override fun getEosId(): Int {
        // return spm.get_eos_id()
        return 2
    }

    override fun getVocabSize(): Int {
        // return spm.get_piece_size()
        return 256000
    }
}

/**
 * Fallback pure-Kotlin tokenizer (for testing without JNI).
 * NOT for production - extremely slow, limited vocab.
 */
class FallbackTokenizer : SentencePieceTokenizer {
    override fun encode(text: String): IntArray {
        // Very naive: split on whitespace, map to hash codes
        return text.split("\\s+".toRegex()).map { it.hashCode() and 0x7FFFFFFF }.toIntArray()
    }

    override fun decode(ids: IntArray): String = ids.joinToString(" ") { it.toString() }

    override fun getPadId() = 0
    override fun getEosId() = 2
    override fun getVocabSize() = 32000
}

/**
 * Tokenizer wrapper that produces model-ready inputs.
 */
class OrchestratorTokenizer(
    private val spTokenizer: SentencePieceTokenizer,
) {
    companion object {
        const val MAX_SEQ_LEN = 2048
    }

    /**
     * Encode instruction for orchestrator forward pass.
     * Format: "Screen: <screen_state>\nUser: <instruction>"
     */
    fun encodeForRouting(screenState: String, instruction: String): TokenizedInput {
        val fullText = "Screen: $screenState\nUser: $instruction"
        val ids = spTokenizer.encode(fullText)

        // Truncate
        val truncated = if (ids.size > MAX_SEQ_LEN) ids.copyOf(MAX_SEQ_LEN) else ids

        // Pad
        val padId = spTokenizer.getPadId()
        val padded = truncated + Array(MAX_SEQ_LEN - truncated.size) { padId }

        // Attention mask
        val attentionMask = truncated.map { 1 } + Array(MAX_SEQ_LEN - truncated.size) { 0 }

        return TokenizedInput(
            inputIds = padded.toIntArray(),
            attentionMask = attentionMask.toIntArray(),
            originalLength = truncated.size,
        )
    }
}

data class TokenizedInput(
    val inputIds: IntArray,
    val attentionMask: IntArray,
    val originalLength: Int,
) {
    fun toTensorBuffers(): Pair<TensorBuffer, TensorBuffer> {
        val inputIdsBuf = TensorBuffer.createFixedSize(
            intArrayOf(1, inputIds.size), com.google.ai.edge.litert.DataType.INT32
        )
        inputIdsBuf.loadArray(inputIds)

        val maskBuf = TensorBuffer.createFixedSize(
            intArrayOf(1, attentionMask.size), com.google.ai.edge.litert.DataType.INT32
        )
        maskBuf.loadArray(attentionMask)

        return inputIdsBuf to maskBuf
    }
}