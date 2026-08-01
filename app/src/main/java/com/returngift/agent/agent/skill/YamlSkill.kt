// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.skill

/** Mirrors the YAML skill schema (schema_version 2.1). Only fields used at runtime. */
data class YamlSkill(
    val skillId: String,
    val version: String = "",
    val status: String = "stable",
    val taxonomy: Taxonomy = Taxonomy(),
    val safety: Safety = Safety(),
    val slots: Map<String, Slot> = emptyMap(),
    val execution: Execution = Execution(),
    val recovery: List<RecoveryRule> = emptyList(),
    val routing: Routing = Routing(),
    val output: Output = Output(),
    val lastValidatedUtc: String = "",
    val stalenessSla: Int = 30,
)

data class Taxonomy(
    val domain: String = "",
    val riskTier: Int = 0,
    val reversible: Boolean = true,
    val estDurationMs: Int = 3000,
    val estSteps: Int = 3,
)

data class Safety(
    val requiresConfirmation: Boolean = false,
    val confirmationMode: String = "simple",
    val autoSend: Boolean = false,
    val neverRetryAfter: List<String> = emptyList(),
    val redactInLogs: List<String> = emptyList(),
    val blocklistPatterns: List<String> = emptyList(),
    val requiresPermissions: List<String> = emptyList(),
)

data class Slot(
    val type: String = "string",
    val required: Boolean = false,
    val defaultFrom: String = "",
    val promptIfMissing: String = "",
)

data class Execution(
    val strategy: String = "first_success",
    val routes: List<Route> = emptyList(),
)

data class Route(
    val id: String = "",
    val kind: String = "ui_automation",   // intent | ui_automation
    val reliability: Double = 0.5,
    val costMs: Int = 3000,
    val intent: IntentSpec? = null,
    val steps: List<StepSpec> = emptyList(),
    val then: List<StepSpec> = emptyList(),
    val verify: List<StepSpec> = emptyList(),
    val condition: String = "",           // "when" field
)

data class IntentSpec(
    val action: String = "",
    val data: String = "",
    val pkg: String = "",
    val extras: Map<String, String> = emptyMap(),
)

data class StepSpec(
    val id: String = "",
    val op: String = "",
    val target: Any? = null,             // string selector or map
    val text: String = "",
    val render: String = "",
    val value: String = "",
    val timeoutMs: Int = 5000,
)

data class RecoveryRule(
    val on: String = "",
    val doActions: List<String> = emptyList(),
)

data class Routing(
    val priority: Int = 50,
    val triggers: List<String> = emptyList(),
    val antiTriggers: List<AntiTrigger> = emptyList(),
    val requiredEntities: List<String> = emptyList(),
    val optionalEntities: List<String> = emptyList(),
    val disambiguateAgainst: List<String> = emptyList(),
)

data class AntiTrigger(
    val pattern: String = "",
    val redirectTo: String = "",
)

data class Output(
    val successTemplate: String = "Done.",
    val failedTemplate: String = "Failed.",
)
