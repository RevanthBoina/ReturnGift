package com.returngift.agent.agent.llm

import org.junit.Assert.*
import org.junit.Test

class ModelGenerationConfigTest {
    @Test fun clampsTemperature() = assertEquals(2.0, ModelGenerationConfig.validate(ModelGenerationConfig(temperature = 9.0)).temperature!!, 0.0)
    @Test fun clampsTopP() = assertEquals(1.0, ModelGenerationConfig.validate(ModelGenerationConfig(topP = -1.0)).topP!!, 0.0)
    @Test fun clampsPenalties() { val c = ModelGenerationConfig.validate(ModelGenerationConfig(presencePenalty = 9.0, frequencyPenalty = -9.0)); assertEquals(2.0, c.presencePenalty!!, 0.0); assertEquals(-2.0, c.frequencyPenalty!!, 0.0) }
    @Test fun dropsNonFinite() { val c = ModelGenerationConfig.validate(ModelGenerationConfig(temperature = Double.NaN, topP = Double.POSITIVE_INFINITY)); assertNull(c.temperature); assertNull(c.topP) }
    @Test fun clampsPositiveIntegersAtLeastOne() { val c = ModelGenerationConfig.validate(ModelGenerationConfig(topK = 0, outputTokenLimit = 0)); assertEquals(1, c.topK); assertEquals(1, c.outputTokenLimit) }
    @Test fun capsTokenLimits() { val c = ModelGenerationConfig.validate(ModelGenerationConfig(topK = Int.MAX_VALUE, outputTokenLimit = Int.MAX_VALUE)); assertEquals(4096, c.topK); assertEquals(32768, c.outputTokenLimit) }
    @Test fun normalizesStops() { val c = ModelGenerationConfig.validate(ModelGenerationConfig(stopSequences = listOf(" end ", "end", ""))); assertEquals(listOf("end"), c.stopSequences) }
    @Test fun capsAndTruncatesStops() { val c = ModelGenerationConfig.validate(ModelGenerationConfig(stopSequences = (1..20).map { "x".repeat(300) + it })); assertEquals(16, c.stopSequences!!.size); assertTrue(c.stopSequences!!.all { it.length == 256 }) }
    @Test fun blankStopsBecomeNull() { assertNull(ModelGenerationConfig.validate(ModelGenerationConfig(stopSequences = listOf(" ", "\n"))).stopSequences) }
    @Test fun defaultsPreserveLegacyTemperature() { assertEquals(0.1, ModelGenerationConfig.DEFAULTS.temperature!!, 0.0); assertNull(ModelGenerationConfig.DEFAULTS.topP) }
    @Test fun reasoningEffortSurvives() { assertEquals(ReasoningEffort.HIGH, ModelGenerationConfig.validate(ModelGenerationConfig(reasoningEffort = ReasoningEffort.HIGH)).reasoningEffort) }
    @Test fun validationIsIdempotent() { val c = ModelGenerationConfig.validate(ModelGenerationConfig(stopSequences = listOf(" a "), topK = 4)); assertEquals(c, ModelGenerationConfig.validate(c)) }
}
