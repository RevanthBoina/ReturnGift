// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.llm

import com.returngift.agent.agent.AgentConfig
import com.returngift.agent.agent.CloudProvider
import com.returngift.agent.agent.LlmProvider
import com.returngift.agent.utils.KVUtils
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

enum class ActiveModelMode { LOCAL, CLOUD }

data class LocalModelConfig(
    val modelPath: String,
    val modelId: String,
    val displayName: String,
    val backendPreference: String
) {
    val isConfigured: Boolean get() = modelPath.isNotBlank()
}

data class CloudModelConfig(
    val providerName: String,
    val modelName: String,
    val baseUrl: String,
    val apiKey: String
) {
    val provider: CloudProvider get() = CloudProvider.fromName(providerName)
    val resolvedBaseUrl: String
        get() = baseUrl.ifBlank {
            if (provider == CloudProvider.CUSTOM) "" else provider.defaultBaseUrl
        }
    val isConfigured: Boolean get() = modelName.isNotBlank() && apiKey.isNotBlank()
    val agentProvider: LlmProvider
        get() = when (provider) {
            CloudProvider.ANTHROPIC -> LlmProvider.ANTHROPIC
            CloudProvider.OMNIROUTE -> LlmProvider.OMNIROUTE
            else -> LlmProvider.OPENAI
        }
}

data class ResolvedModelConfig(
    val activeMode: ActiveModelMode,
    val local: LocalModelConfig,
    val activeCloud: CloudModelConfig,
    val defaultCloud: CloudModelConfig
) {
    fun isLocalActive(): Boolean = activeMode == ActiveModelMode.LOCAL

    fun toAgentConfig(
        temperature: Double,
        maxIterations: Int,
        streaming: Boolean = false,
        generation: ModelGenerationConfig = ModelConfigRepository.getGenerationConfig()
    ): AgentConfig {
        // Inject persistent global instructions (#45) into systemPrompt.
        // This is the runtime construction path used by AppViewModel/AgentService,
        // bypassing AgentConfig.Builder.build(), so we must apply the helper here too.
        val finalSystemPrompt = com.returngift.agent.agent.PromptUtils
            .applyGlobalPrompt(AgentConfig.DEFAULT_SYSTEM_PROMPT)
        return if (activeMode == ActiveModelMode.LOCAL) {
            AgentConfig(
                apiKey = "",
                baseUrl = local.modelPath,
                modelName = local.modelId,
                systemPrompt = finalSystemPrompt,
                maxIterations = maxIterations,
                temperature = temperature,
                provider = LlmProvider.LOCAL,
                streaming = streaming,
                generation = generation
            )
        } else {
            AgentConfig(
                apiKey = activeCloud.apiKey,
                baseUrl = activeCloud.resolvedBaseUrl,
                modelName = activeCloud.modelName,
                systemPrompt = finalSystemPrompt,
                maxIterations = maxIterations,
                temperature = temperature,
                provider = activeCloud.agentProvider,
                streaming = streaming,
                generation = generation
            )
        }
    }
}

/**
 * Resolves active/default local and cloud model config from KVUtils without changing
 * the persisted key format. This is the single source of truth for model selection.
 */
object ModelConfigRepository {

    fun snapshot(): ResolvedModelConfig {
        val activeProviderRaw = KVUtils.getLlmProvider().ifBlank { "OPENAI" }.uppercase()
        val activeMode = if (activeProviderRaw == "LOCAL") ActiveModelMode.LOCAL else ActiveModelMode.CLOUD

        val localModelPath = KVUtils.getLocalModelPath()
        val matchedLocalModel = LocalModelManager.AVAILABLE_MODELS.find { localModelPath.endsWith(it.fileName) }
        val localModelId = matchedLocalModel?.id
            ?: if (activeMode == ActiveModelMode.LOCAL) KVUtils.getLlmModelName() else ""
        val localDisplayName = matchedLocalModel?.displayName
            ?: localModelPath.takeIf { it.isNotBlank() }?.let { File(it).nameWithoutExtension }
            ?: localModelId

        val local = LocalModelConfig(
            modelPath = localModelPath,
            modelId = localModelId,
            displayName = localDisplayName,
            backendPreference = KVUtils.getLocalBackendPreference()
        )

        val defaultProvider = normalizeCloudProvider(
            KVUtils.getDefaultCloudProvider().ifBlank {
                if (activeMode == ActiveModelMode.CLOUD) activeProviderRaw else "OPENAI"
            }
        )
        val defaultModel = KVUtils.getDefaultCloudModel().ifBlank {
            if (activeMode == ActiveModelMode.CLOUD && activeProviderRaw == defaultProvider) {
                KVUtils.getLlmModelName()
            } else {
                ""
            }
        }
        val defaultBaseUrl = KVUtils.getDefaultCloudBaseUrl().ifBlank {
            if (activeMode == ActiveModelMode.CLOUD && activeProviderRaw == defaultProvider) {
                KVUtils.getLlmBaseUrl()
            } else {
                ""
            }
        }
        val defaultCloud = buildCloudConfig(defaultProvider, defaultModel, defaultBaseUrl)

        val activeCloudProvider = if (activeMode == ActiveModelMode.CLOUD) {
            normalizeCloudProvider(activeProviderRaw)
        } else {
            defaultCloud.providerName
        }
        val sameProviderFallback = activeCloudProvider == defaultCloud.providerName
        val activeCloudModel = if (activeMode == ActiveModelMode.CLOUD) {
            KVUtils.getLlmModelName().ifBlank { if (sameProviderFallback) defaultCloud.modelName else "" }
        } else {
            defaultCloud.modelName
        }
        val activeCloudBaseUrl = if (activeMode == ActiveModelMode.CLOUD) {
            KVUtils.getLlmBaseUrl().ifBlank { if (sameProviderFallback) defaultCloud.baseUrl else "" }
        } else {
            defaultCloud.baseUrl
        }
        val activeCloud = buildCloudConfig(activeCloudProvider, activeCloudModel, activeCloudBaseUrl)

        return ResolvedModelConfig(
            activeMode = activeMode,
            local = local,
            activeCloud = activeCloud,
            defaultCloud = defaultCloud
        )
    }

    fun isLocalActive(): Boolean = snapshot().isLocalActive()

    fun getGenerationConfig(): ModelGenerationConfig {
        val raw = KVUtils.getGenerationProfileJson()
        if (raw.isBlank()) return ModelGenerationConfig.DEFAULTS
        return runCatching {
            val json = JSONObject(raw)
            ModelGenerationConfig.validate(ModelGenerationConfig(
                temperature = json.optNullableDouble("temperature"),
                topP = json.optNullableDouble("topP"),
                topK = json.optNullableInt("topK"),
                presencePenalty = json.optNullableDouble("presencePenalty"),
                frequencyPenalty = json.optNullableDouble("frequencyPenalty"),
                seed = json.optNullableLong("seed"),
                stopSequences = json.optJSONArray("stopSequences")?.let { array ->
                    buildList { for (i in 0 until array.length()) add(array.optString(i)) }
                },
                outputTokenLimit = json.optNullableInt("outputTokenLimit"),
                minP = json.optNullableDouble("minP"),
                repetitionPenalty = json.optNullableDouble("repetitionPenalty"),
                contextWindowTokens = json.optNullableInt("contextWindowTokens"),
                reasoningEffort = json.optString("reasoningEffort", "").takeIf { it.isNotBlank() }
                    ?.let { runCatching { ReasoningEffort.valueOf(it) }.getOrNull() }
            ))
        }.getOrDefault(ModelGenerationConfig.DEFAULTS)
    }

    fun saveGenerationConfig(config: ModelGenerationConfig) {
        val value = ModelGenerationConfig.validate(config)
        val json = JSONObject()
        value.temperature?.let { json.put("temperature", it) }
        value.topP?.let { json.put("topP", it) }
        value.topK?.let { json.put("topK", it) }
        value.presencePenalty?.let { json.put("presencePenalty", it) }
        value.frequencyPenalty?.let { json.put("frequencyPenalty", it) }
        value.seed?.let { json.put("seed", it) }
        value.stopSequences?.let { json.put("stopSequences", JSONArray(it)) }
        value.outputTokenLimit?.let { json.put("outputTokenLimit", it) }
        value.minP?.let { json.put("minP", it) }
        value.repetitionPenalty?.let { json.put("repetitionPenalty", it) }
        value.contextWindowTokens?.let { json.put("contextWindowTokens", it) }
        value.reasoningEffort?.let { json.put("reasoningEffort", it.name) }
        KVUtils.setGenerationProfileJson(json.toString())
    }

    fun resetGenerationConfig() = KVUtils.clearGenerationProfile()

    private fun JSONObject.optNullableDouble(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key) else null
    private fun JSONObject.optNullableInt(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
    private fun JSONObject.optNullableLong(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null

    fun saveLocalDefault(modelPath: String, modelId: String, activateNow: Boolean) {
        KVUtils.setLocalModelPath(modelPath)
        if (activateNow) {
            activateLocal(modelPath, modelId)
        }
    }

    fun activateLocal(modelPath: String, modelId: String) {
        KVUtils.setLocalModelPath(modelPath)
        KVUtils.setLlmProvider("LOCAL")
        KVUtils.setLlmModelName(modelId)
    }

    fun saveCloudDefault(
        providerName: String,
        modelId: String,
        baseUrl: String,
        apiKey: String,
        activateNow: Boolean
    ) {
        val normalizedProvider = normalizeCloudProvider(providerName)
        val resolvedBaseUrl = resolveCloudBaseUrl(normalizedProvider, baseUrl)
        KVUtils.setDefaultCloudModel(modelId)
        KVUtils.setDefaultCloudProvider(normalizedProvider)
        KVUtils.setDefaultCloudBaseUrl(resolvedBaseUrl)
        KVUtils.setLlmApiKey(apiKey)
        KVUtils.setApiKeyForProvider(normalizedProvider, apiKey)
        if (activateNow) {
            activateCloudSelection(
                modelId = modelId,
                explicitProviderName = normalizedProvider,
                explicitBaseUrl = resolvedBaseUrl
            )
        }
    }

    /**
     * Get the persisted fast model configuration (separate from the primary local model).
     * Returns null if no fast model is configured (routing disabled by default).
     */
    fun getFastModelConfig(): LocalModelConfig? {
        val fastModelPath = KVUtils.getFastLocalModelPath()
        if (fastModelPath.isBlank()) return null
        val matchedLocalModel = LocalModelManager.AVAILABLE_MODELS.find { fastModelPath.endsWith(it.fileName) }
        val fastModelId = matchedLocalModel?.id
            ?: fastModelPath.let { File(it).nameWithoutExtension }
        val fastDisplayName = matchedLocalModel?.displayName
            ?: fastModelPath.let { File(it).nameWithoutExtension }
        return LocalModelConfig(
            modelPath = fastModelPath,
            modelId = fastModelId,
            displayName = fastDisplayName,
            backendPreference = "CPU"  // Fast engine uses CPU
        )
    }

    /**
     * Save the fast model configuration. Pass empty modelPath to disable routing.
     */
    fun saveFastModelConfig(modelPath: String) {
        KVUtils.setFastLocalModelPath(modelPath)
    }

    fun activateCloudSelection(
        modelId: String,
        explicitProviderName: String? = null,
        explicitBaseUrl: String? = null
    ) {
        val snapshot = snapshot()
        val inferredProvider = explicitProviderName
            ?.takeIf { it.isNotBlank() }
            ?: CloudProvider.findProviderForModel(modelId)?.name
            ?: snapshot.defaultCloud.providerName
        val normalizedProvider = normalizeCloudProvider(inferredProvider)
        val resolvedBaseUrl = resolveCloudBaseUrl(
            normalizedProvider,
            explicitBaseUrl
                ?: if (snapshot.defaultCloud.providerName == normalizedProvider) snapshot.defaultCloud.baseUrl else ""
        )

        KVUtils.setDefaultCloudModel(modelId)
        KVUtils.setDefaultCloudProvider(normalizedProvider)
        KVUtils.setDefaultCloudBaseUrl(resolvedBaseUrl)
        KVUtils.setLlmProvider(normalizedProvider)
        KVUtils.setLlmModelName(modelId)
        KVUtils.setLlmBaseUrl(resolvedBaseUrl)
    }

    private fun buildCloudConfig(
        providerName: String,
        modelName: String,
        baseUrl: String
    ): CloudModelConfig {
        val normalizedProvider = normalizeCloudProvider(providerName)
        val apiKey = KVUtils.getApiKeyForProvider(normalizedProvider)
            .ifEmpty { KVUtils.getLlmApiKey() }
        return CloudModelConfig(
            providerName = normalizedProvider,
            modelName = coerceOmniRouteModel(normalizedProvider, modelName),
            baseUrl = resolveCloudBaseUrl(normalizedProvider, baseUrl),
            apiKey = apiKey
        )
    }

    /**
     * OmniRoute exposes only "Auto" to the user; older installs may have a per-provider
     * model persisted — coerce it back to "auto" while routing stays internal to OmniRoute.
     */
    private fun coerceOmniRouteModel(providerName: String, modelName: String): String {
        return if (providerName == CloudProvider.OMNIROUTE.name && modelName.isNotBlank() && modelName != "auto") {
            "auto"
        } else modelName
    }

    private fun normalizeCloudProvider(providerName: String): String {
        val normalized = providerName.ifBlank { "OPENAI" }.uppercase()
        return if (normalized == "LOCAL") "OPENAI" else normalized
    }

    private fun resolveCloudBaseUrl(providerName: String, baseUrl: String): String {
        if (baseUrl.isNotBlank()) return baseUrl.trim()
        val provider = CloudProvider.fromName(providerName)
        return if (provider == CloudProvider.CUSTOM) "" else provider.defaultBaseUrl
    }
}
