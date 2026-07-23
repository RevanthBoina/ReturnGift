// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.ui.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.returngift.agent.agent.AppAllowListStore
import com.returngift.agent.base.BaseActivity

/**
 * Part B — Settings screen listing all apps the agent has encountered,
 * each with an ON/OFF toggle.
 *
 * Launched from SettingsActivity via "App Permissions" menu item.
 */
class AppAllowListActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppAllowListScreen(onBack = { finish() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppAllowListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AppAllowListStore.getInstance(context) }
    var entries by remember { mutableStateOf(store.getAllEntries()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Permissions", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", fontSize = 20.sp)
                    }
                }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No apps encountered yet.\nApps appear here after the agent first acts in them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                item {
                    Text(
                        text = "Control which apps the agent is allowed to act in. " +
                               "Turning an app OFF will block all agent actions in it until you re-enable it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
                items(entries, key = { it.packageName }) { entry ->
                    AllowListRow(
                        entry = entry,
                        onToggle = { newAllowed ->
                            store.setAllowed(entry.packageName, newAllowed, entry.label)
                            entries = store.getAllEntries()
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun AllowListRow(
    entry: AppAllowListStore.AppEntry,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label.ifBlank { entry.packageName },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = entry.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = entry.allowed,
            onCheckedChange = onToggle,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
