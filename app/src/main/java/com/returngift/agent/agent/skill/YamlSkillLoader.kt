// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.skill

import android.content.Context
import com.returngift.agent.utils.XLog
import org.yaml.snakeyaml.Yaml

/**
 * Loads YamlSkill objects from assets/skill_library/skills/*.yaml at runtime.
 * Uses SnakeYAML to parse each file into a raw Map, then maps fields manually
 * to avoid reflection (keeps ProGuard-safe).
 */
object YamlSkillLoader {

    private const val TAG = "YamlSkillLoader"
    private const val SKILLS_DIR = "skill_library/skills"

    fun loadAll(context: Context): List<YamlSkill> {
        val yaml = Yaml()
        val results = mutableListOf<YamlSkill>()
        try {
            val files = context.assets.list(SKILLS_DIR) ?: return emptyList()
            for (file in files) {
                if (!file.endsWith(".yaml")) continue
                try {
                    context.assets.open("$SKILLS_DIR/$file").use { stream ->
                        @Suppress("UNCHECKED_CAST")
                        val map = yaml.load<Map<String, Any>>(stream) ?: return@use
                        val skill = parseSkill(map)
                        if (skill != null) results.add(skill)
                    }
                } catch (e: Exception) {
                    XLog.w(TAG, "Failed to parse $file: ${e.message}")
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "loadAll failed", e)
        }
        XLog.i(TAG, "Loaded ${results.size} YAML skills from assets")
        return results
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSkill(m: Map<String, Any>): YamlSkill? {
        val id = m["skill_id"]?.toString() ?: return null

        val tax = (m["taxonomy"] as? Map<String, Any>).orEmpty()
        val taxonomy = Taxonomy(
            domain = tax.str("domain"),
            riskTier = tax.int("risk_tier"),
            reversible = tax["reversible"] as? Boolean ?: true,
            estDurationMs = tax.int("est_duration_ms", 3000),
            estSteps = tax.int("est_steps", 3),
        )

        val saf = (m["safety"] as? Map<String, Any>).orEmpty()
        val safety = Safety(
            requiresConfirmation = saf["requires_confirmation"] as? Boolean ?: false,
            confirmationMode = saf.str("confirmation_mode", "simple"),
            autoSend = saf["auto_send"] as? Boolean ?: false,
            neverRetryAfter = saf.strList("never_retry_after"),
            redactInLogs = saf.strList("redact_in_logs"),
            blocklistPatterns = saf.strList("blocklist_patterns"),
            requiresPermissions = saf.strList("requires_permissions"),
        )

        val slotsRaw = (m["slots"] as? Map<String, Any>).orEmpty()
        val slots = slotsRaw.mapValues { (_, v) ->
            val sv = (v as? Map<String, Any>).orEmpty()
            Slot(
                type = sv.str("type", "string"),
                required = sv["required"] as? Boolean ?: false,
                defaultFrom = sv.str("default_from"),
                promptIfMissing = sv.str("prompt_if_missing"),
            )
        }

        val execRaw = (m["execution"] as? Map<String, Any>).orEmpty()
        val routesRaw = (execRaw["routes"] as? List<Map<String, Any>>).orEmpty()
        val routes = routesRaw.map { r ->
            val intentRaw = (r["intent"] as? Map<String, Any>)
            val intent = intentRaw?.let {
                IntentSpec(
                    action = it.str("action"),
                    data = it.str("data"),
                    pkg = it.str("package"),
                    extras = (it["extras"] as? Map<String, Any>)?.mapValues { e -> e.value.toString() }.orEmpty(),
                )
            }
            Route(
                id = r.str("id"),
                kind = r.str("kind", "ui_automation"),
                reliability = (r["reliability"] as? Number)?.toDouble() ?: 0.5,
                costMs = r.int("cost_ms", 3000),
                intent = intent,
                steps = parseSteps(r["steps"]),
                then = parseSteps(r["then"]),
                verify = parseSteps(r["verify"]),
                condition = r.str("when"),
            )
        }

        val recoveryRaw = (m["recovery"] as? List<Map<String, Any>>).orEmpty()
        val recovery = recoveryRaw.mapNotNull { r ->
            val on = r.str("on").ifEmpty { return@mapNotNull null }
            val doList = when (val d = r["do"]) {
                is List<*> -> d.map { it.toString() }
                is String -> listOf(d)
                else -> emptyList()
            }
            RecoveryRule(on = on, doActions = doList)
        }

        val routingRaw = (m["routing"] as? Map<String, Any>).orEmpty()
        val antiTriggersRaw = (routingRaw["anti_triggers"] as? List<Any>).orEmpty()
        val antiTriggers = antiTriggersRaw.mapNotNull { entry ->
            when (entry) {
                is Map<*, *> -> {
                    val pattern = entry["pattern"]?.toString() ?: return@mapNotNull null
                    AntiTrigger(pattern = pattern, redirectTo = entry["redirect_to"]?.toString() ?: "")
                }
                is String -> {
                    // "pattern -> skill_id" inline format
                    val parts = entry.split("->")
                    if (parts.size == 2) AntiTrigger(parts[0].trim().removeSurrounding("\""), parts[1].trim())
                    else null
                }
                else -> null
            }
        }
        val routing = Routing(
            priority = routingRaw.int("priority", 50),
            triggers = routingRaw.strList("triggers"),
            antiTriggers = antiTriggers,
            requiredEntities = routingRaw.strList("required_entities"),
            optionalEntities = routingRaw.strList("optional_entities"),
            disambiguateAgainst = routingRaw.strList("disambiguate_against"),
        )

        val outputRaw = (m["output"] as? Map<String, Any>).orEmpty()
        val templatesRaw = (outputRaw["templates"] as? Map<String, Any>).orEmpty()
        val output = Output(
            successTemplate = templatesRaw.str("success", "Done."),
            failedTemplate = templatesRaw.str("failed", "Failed."),
        )

        return YamlSkill(
            skillId = id,
            version = m.str("version"),
            status = m.str("status", "stable"),
            taxonomy = taxonomy,
            safety = safety,
            slots = slots,
            execution = Execution(strategy = execRaw.str("strategy", "first_success"), routes = routes),
            recovery = recovery,
            routing = routing,
            output = output,
            lastValidatedUtc = m.str("last_validated_utc"),
            stalenessSla = m.int("staleness_sla_days", 30),
            screenSignatures = m.strList("screen_signatures"),
            deviceProfiles = m.strList("device_profiles"),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSteps(raw: Any?): List<StepSpec> {
        val list = raw as? List<Map<String, Any>> ?: return emptyList()
        return list.map { s ->
            StepSpec(
                id = s.str("id"),
                op = s.str("op"),
                target = s["target"],
                text = s.str("text"),
                render = s.str("render"),
                value = s.str("value"),
                timeoutMs = s.int("timeout_ms", 5000),
            )
        }
    }

    // ── Map extension helpers ──────────────────────────────────────────────
    private fun Map<String, Any>.str(key: String, default: String = "") =
        this[key]?.toString() ?: default

    private fun Map<String, Any>.int(key: String, default: Int = 0) =
        (this[key] as? Number)?.toInt() ?: default

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.strList(key: String): List<String> =
        (this[key] as? List<*>)?.map { it.toString() } ?: emptyList()
}
