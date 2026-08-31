package com.returngift.agent.agent.llm

/** User-tunable inference parameters. Null means preserve the provider SDK default. */
data class ModelGenerationConfig(
    val temperature: Double? = 0.1,
    val topP: Double? = null,
    val topK: Int? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val seed: Long? = null,
    val stopSequences: List<String>? = null,
    val outputTokenLimit: Int? = null,
    val minP: Double? = null,
    val repetitionPenalty: Double? = null,
    val contextWindowTokens: Int? = null,
    val reasoningEffort: ReasoningEffort? = null
) {
    companion object {
        val DEFAULTS = ModelGenerationConfig()
        private const val MAX_TOP_K = 4096
        private const val MAX_TOKEN_LIMIT = 32768
        private const val MAX_CONTEXT = 262144
        private const val MAX_STOPS = 16
        private const val MAX_STOP_LENGTH = 256

        fun validate(input: ModelGenerationConfig): ModelGenerationConfig = input.copy(
            temperature = finite(input.temperature)?.coerceIn(0.0, 2.0),
            topP = finite(input.topP)?.coerceIn(0.0, 1.0),
            topK = input.topK?.coerceIn(1, MAX_TOP_K),
            presencePenalty = finite(input.presencePenalty)?.coerceIn(-2.0, 2.0),
            frequencyPenalty = finite(input.frequencyPenalty)?.coerceIn(-2.0, 2.0),
            seed = input.seed,
            stopSequences = input.stopSequences
                ?.asSequence()
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.map { it.take(MAX_STOP_LENGTH) }
                ?.distinct()
                ?.take(MAX_STOPS)
                ?.toList()
                ?.ifEmpty { null },
            outputTokenLimit = input.outputTokenLimit?.coerceIn(1, MAX_TOKEN_LIMIT),
            minP = finite(input.minP)?.coerceIn(0.0, 1.0),
            repetitionPenalty = finite(input.repetitionPenalty)?.coerceIn(0.0, 2.0),
            contextWindowTokens = input.contextWindowTokens?.coerceIn(1, MAX_CONTEXT)
        )

        private fun finite(value: Double?): Double? = value?.takeIf { it.isFinite() }
    }
}

enum class ReasoningEffort { LOW, MEDIUM, HIGH }
