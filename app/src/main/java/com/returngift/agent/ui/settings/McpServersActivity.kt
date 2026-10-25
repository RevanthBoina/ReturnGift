// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.ui.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.sp
import com.returngift.agent.agent.mcp.McpRuntime
import com.returngift.agent.agent.mcp.McpServerConfig
import com.returngift.agent.agent.mcp.McpServerStore
import com.returngift.agent.base.BaseActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → MCP connections.
 *
 * Connects the agent to Model Context Protocol servers so a task can use tools that
 * do not ship inside the APK (issue trackers, calendars, internal APIs, knowledge
 * feeds). Rows are data — adding a connection never needs a new build.
 *
 * The screen is honest about state: a server shows "N tools" only after a real
 * `initialize` + `tools/list` succeeded in this process.
 */
class McpServersActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { McpServersScreen(onBack = { finish() }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServersScreen(onBack: () -> Unit) {
    var servers by remember { mutableStateOf(McpServerStore.all()) }
    var toolCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var refreshTick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // Network work always off the main thread; a failing server leaves the row but
    // contributes no tools.
    LaunchedEffect(refreshTick) {
        busy = true
        toolCounts = withContext(Dispatchers.IO) {
            runCatching { McpRuntime.refresh() }
            McpRuntime.allTools().groupingBy { it.serverId }.eachCount()
        }
        servers = McpServerStore.all()
        busy = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP connections") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("\u2190") }
                },
                actions = {
                    TextButton(
                        enabled = !busy,
                        onClick = { refreshTick++ },
                    ) { Text(if (busy) "Checking\u2026" else "Refresh") }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Connect MCP servers to give the agent extra tools. " +
                        "Tools are discovered at startup and only the most relevant ones " +
                        "are shown to the model for each task.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (servers.isEmpty()) {
                item {
                    Text(
                        "No servers yet. Add one below — for example a local or hosted " +
                            "MCP endpoint that ends in /mcp.",
                        fontSize = 13.sp,
                    )
                }
            }

            items(servers, key = { it.id }) { server ->
                ServerCard(
                    server = server,
                    toolCount = toolCounts[server.id] ?: 0,
                    onToggle = { enabled ->
                        McpServerStore.setEnabled(server.id, enabled)
                        servers = McpServerStore.all()
                        refreshTick++
                    },
                    onDelete = {
                        McpRuntime.removeServer(server.id)
                        servers = McpServerStore.all()
                        toolCounts = toolCounts - server.id
                    },
                )
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Add a server", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text("URL (https://\u2026/mcp)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Bearer token (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val config = McpServerConfig(
                            id = slugOf(name, url),
                            name = name.trim().ifBlank { url.trim() },
                            url = url.trim(),
                            authToken = token.trim().takeIf { it.isNotBlank() },
                        )
                        val problem = config.validationError()
                        if (problem != null) {
                            error = problem
                        } else {
                            error = null
                            McpRuntime.addServer(config)
                            name = ""; url = ""; token = ""
                            servers = McpServerStore.all()
                            refreshTick++
                        }
                    }) { Text("Add") }
                    TextButton(onClick = { name = ""; url = ""; token = ""; error = null }) {
                        Text("Clear")
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ServerCard(
    server: McpServerConfig,
    toolCount: Int,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(server.name, fontWeight = FontWeight.SemiBold)
                    Text(server.url, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        when {
                            !server.enabled -> "Disabled"
                            toolCount > 0 -> "$toolCount tool(s) available"
                            else -> "Not connected (no tools discovered)"
                        },
                        fontSize = 12.sp,
                    )
                }
                Switch(checked = server.enabled, onCheckedChange = onToggle)
                TextButton(onClick = onDelete) { Text("Remove") }
            }
        }
    }
}

/** Stable id from the name, falling back to the host. Never user-visible. */
internal fun slugOf(name: String, url: String): String {
    val base = name.trim().ifBlank { url.trim() }
    return base.lowercase()
        .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
        .trim()
        .replace(Regex("\\s+"), "-")
        .take(40)
        .ifBlank { "mcp-" + System.currentTimeMillis() }
}
