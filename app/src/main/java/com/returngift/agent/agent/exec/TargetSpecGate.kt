// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.agent.exec

import com.returngift.agent.agent.knowledge.AppCatalog
import com.returngift.agent.ClawApplication

/**
 * Deterministic gate that detects under-specified enumeration tasks
 * (e.g., "open 5 apps", "message 3 contacts") and forces clarification
 * before the agent loop begins.
 */
object TargetSpecGate {

    data class MissingTargets(
        val requestedCount: Int,
        val explicitlyNamed: Int,
        val kind: String, // "apps", "contacts", "people", etc.
        val matchedApps: List<String>, // names of apps found in the task text
    )

    /**
     * Analyze a task for enumeration-without-names patterns.
     * Returns MissingTargets if the task asks for N items but names fewer than N.
     */
    fun missingTargets(task: String): MissingTargets? {
        val lower = task.lowercase().trim()
        
        // Patterns: "open/launch/close/screenshot/install N apps"
        val appPatterns = listOf(
            Regex("""\b(open|launch|close|screenshot|capture|install)\s+(\d+|a\s+couple\s+of|several|some|a\s+few|two|three|four|five|six|seven|eight|nine|ten)\s+apps?\b"""),
            Regex("""\b(\d+|a\s+couple\s+of|several|some|a\s+few|two|three|four|five|six|seven|eight|nine|ten)\s+apps?\s+(?:and|to|for)\s"""),
        )
        
        // Patterns: "message/call N contacts/people"
        val contactPatterns = listOf(
            Regex("""\b(message|text|sms|call|phone)\s+(\d+|a\s+couple\s+of|several|some|a\s+few|two|three|four|five|six|seven|eight|nine|ten)\s+(?:contacts?|people|persons?)\b"""),
        )
        
        for (pattern in appPatterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val countStr = match.groupValues[2]
                val requestedCount = parseCount(countStr)
                if (requestedCount > 0) {
                    val named = countNamedApps(lower)
                    if (named < requestedCount) {
                        return MissingTargets(requestedCount, named, "apps", findNamedApps(lower))
                    }
                }
            }
        }
        
        for (pattern in contactPatterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val countStr = match.groupValues[2]
                val requestedCount = parseCount(countStr)
                if (requestedCount > 0) {
                    // Contacts are harder to name in text - be conservative
                    val named = countNamedContacts(lower)
                    if (named < requestedCount) {
                        return MissingTargets(requestedCount, named, "contacts", emptyList())
                    }
                }
            }
        }
        
        return null
    }

    private fun parseCount(s: String): Int {
        val trimmed = s.trim().lowercase()
        return when (trimmed) {
            "a couple of", "a couple" -> 2
            "several" -> 3
            "some", "a few" -> 2
            "two" -> 2
            "three" -> 3
            "four" -> 4
            "five" -> 5
            "six" -> 6
            "seven" -> 7
            "eight" -> 8
            "nine" -> 9
            "ten" -> 10
            else -> trimmed.toIntOrNull() ?: 0
        }
    }

    private fun countNamedApps(task: String): Int {
        val catalog = AppCatalog.getInstance(ClawApplication.Companion.getInstance())
        var count = 0
        for (entry in catalog.getAllEntries()) {
            val labelLower = entry.label.lowercase()
            if (task.contains(labelLower)) count++
            // Also check common aliases
            for (alias in entry.aliases) {
                if (task.contains(alias)) { count++; break }
            }
        }
        return count
    }

    private fun findNamedApps(task: String): List<String> {
        val catalog = AppCatalog.getInstance(ClawApplication.Companion.getInstance())
        val found = mutableListOf<String>()
        for (entry in catalog.getAllEntries()) {
            val labelLower = entry.label.lowercase()
            if (task.contains(labelLower)) { found.add(entry.label); continue }
            for (alias in entry.aliases) {
                if (task.contains(alias)) { found.add(entry.label); break }
            }
        }
        return found.distinct()
    }

    private fun countNamedContacts(task: String): Int {
        // For now, return 0 - contacts are rarely explicitly named in task text
        // Could be enhanced to check for "Mom", "Dad", specific names, etc.
        return 0
    }
}