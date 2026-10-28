// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.ui.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.returngift.agent.AppCapabilityCoordinator
import com.returngift.agent.AppRequirement
import com.returngift.agent.ServiceBindingState
import com.returngift.agent.agent.llm.ModelConfigRepository
import com.returngift.agent.base.BaseActivity
import com.returngift.agent.service.ReturnGiftAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Settings → Health check.
 *
 * One tap answers "does this install actually work right now": the services Android
 * grants to a component, the provider it talks to, and one harmless screen read that
 * proves the agent can see. A red row offers the single action that fixes it.
 */
class HealthCheckActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HealthCheckScreen(onBack = { finish() }, onFix = { requirement -> AppCapabilityCoordinator.openSystemSettings(this, requirement) }) }
    }
}

private data class HealthRow(
    val label: String,
    val detail: String,
    val ok: Boolean,
    val requirement: AppRequirement? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthCheckScreen(onBack: () -> Unit, onFix: (AppRequirement) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var rows by remember { mutableStateOf<List<HealthRow>?>(null) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tick) {
        rows = null
        rows = withContext(Dispatchers.IO) { runChecks(context) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Health check") },
                navigationIcon = { IconButton(onClick = onBack) { Text("\u2190") } },
                actions = { TextButton(onClick = { tick++ }) { Text("Run again") } },
            )
        },
    ) { padding ->
        val current = rows
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
        ) {
            if (current == null) {
                item { Text("Checking…", modifier = Modifier.padding(16.dp)) }
            } else {
                val failures = current.count { !it.ok }
                item {
                    Text(
                        text = if (failures == 0) "Everything works." else "$failures thing(s) need attention.",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                items(current) { row ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = (if (row.ok) "✓  " else "✕  ") + row.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (row.ok) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                                )
                                Text(row.detail, style = MaterialTheme.typography.bodySmall)
                            }
                            if (!row.ok && row.requirement != null) {
                                TextButton(onClick = { onFix(row.requirement) }) { Text("Fix") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Blocking work — always called from [Dispatchers.IO]. */
private fun runChecks(context: android.content.Context): List<HealthRow> {
    val snapshot = AppCapabilityCoordinator.snapshot(context)
    val config = ModelConfigRepository.snapshot()
    val localActive = config.activeMode == com.returngift.agent.agent.llm.ActiveModelMode.LOCAL

    val rows = mutableListOf<HealthRow>()
    rows += HealthRow(
        label = "Accessibility service",
        detail = when (snapshot.accessibilityState) {
            ServiceBindingState.READY -> "Connected"
            ServiceBindingState.CONNECTING -> "Starting…"
            ServiceBindingState.DEGRADED -> "Disconnected"
            ServiceBindingState.DISABLED -> "Off — the agent cannot see or tap anything"
        },
        ok = snapshot.accessibilityState == ServiceBindingState.READY,
        requirement = AppRequirement.ACCESSIBILITY,
    )
    rows += HealthRow(
        label = "Notification access",
        detail = when (snapshot.notificationAccessState) {
            ServiceBindingState.READY -> "Connected"
            ServiceBindingState.DISABLED -> "Off — the agent cannot read or reply to notifications"
            else -> "Not connected"
        },
        ok = snapshot.notificationAccessState == ServiceBindingState.READY,
        requirement = AppRequirement.NOTIFICATION_ACCESS,
    )
    rows += HealthRow(
        label = "Display over other apps",
        detail = if (snapshot.overlayGranted) "Granted" else "Off — the stop bubble cannot show",
        ok = snapshot.overlayGranted,
        requirement = AppRequirement.OVERLAY,
    )
    rows += HealthRow(
        label = "Battery optimisation",
        detail = if (snapshot.batteryOptimizationIgnored) "Ignored — the agent keeps running"
        else "On — Android may kill a long task",
        ok = snapshot.batteryOptimizationIgnored,
        requirement = AppRequirement.BATTERY_OPTIMIZATION,
    )

    if (localActive) {
        val path = config.local.modelPath
        val present = path.isNotBlank() && File(path).exists()
        rows += HealthRow(
            label = "Local model",
            detail = when {
                path.isBlank() -> "No model selected"
                present -> File(path).name
                else -> "File missing: $path"
            },
            ok = present,
            requirement = null,
        )
    } else {
        val key = config.activeCloud.apiKey
        rows += HealthRow(
            label = "Provider key",
            detail = if (key.isBlank()) "No key for ${config.activeCloud.providerName}" else "Saved for ${config.activeCloud.providerName}",
            ok = key.isNotBlank(),
            requirement = null,
        )
        val base = config.activeCloud.resolvedBaseUrl
        val reachable = when {
            base.isBlank() -> false
            key.isBlank() -> false
            else -> reachable(base)
        }
        rows += HealthRow(
            label = "Provider reachable",
            detail = when {
                base.isBlank() -> "No base URL configured"
                key.isBlank() -> "Skipped — no key to authenticate with"
                reachable -> base
                else -> "No answer from $base"
            },
            ok = reachable,
            requirement = null,
        )
    }

    val tree = runCatching {
        ReturnGiftAccessibilityService.getConnectedInstance(1500L)?.getScreenTree()
    }.getOrNull()
    rows += HealthRow(
        label = "Dry run (read the screen)",
        detail = if (tree.isNullOrBlank()) "Nothing readable — check accessibility above" else "Read ${tree.length} characters",
        ok = !tree.isNullOrBlank(),
        requirement = null,
    )
    return rows
}

/** Any HTTP answer below 500 means the endpoint exists; 401/404 still prove reachability. */
private fun reachable(baseUrl: String): Boolean {
    val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()
    return runCatching {
        client.newCall(Request.Builder().url(baseUrl.trimEnd('/') + "/models").get().build()).execute().use { it.code < 500 }
    }.getOrDefault(false)
}
