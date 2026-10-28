// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.ui.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.returngift.agent.agent.tracker.ExecutionTracker
import com.returngift.agent.agent.tracker.UsageCounters
import com.returngift.agent.base.BaseActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → Usage.
 *
 * What the phone actually did, counted locally: which channel carried the message,
 * which engine took the task, which routine or skill ran, how many model calls that
 * cost. The task list underneath is the last ten runs with their outcome and size, so
 * a repeated task that still reaches the model is visible rather than guessed at.
 */
class UsageActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { UsageScreen(onBack = { finish() }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(onBack: () -> Unit) {
    var counters by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var tasks by remember { mutableStateOf<List<ExecutionTracker.Trajectory>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            counters = UsageCounters.all()
            tasks = runCatching { ExecutionTracker.getRecentTrajectories(10) }.getOrDefault(emptyList())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usage") },
                navigationIcon = { IconButton(onClick = onBack) { Text("\u2190") } },
                actions = {
                    TextButton(onClick = {
                        UsageCounters.clear()
                        tick++
                    }) { Text("Reset") }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            item {
                Text(
                    "Counters",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            if (counters.isEmpty()) {
                item { Text("Nothing counted yet.", style = MaterialTheme.typography.bodySmall) }
            }
            items(counters) { (name, count) ->
                Text("$name  ·  $count", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
            }

            item {
                Text(
                    "Last 10 tasks",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
            }
            if (tasks.isEmpty()) {
                item { Text("No tasks recorded.", style = MaterialTheme.typography.bodySmall) }
            }
            items(tasks) { task ->
                val seconds = if (task.completedAt > task.startedAt) (task.completedAt - task.startedAt) / 1000 else 0
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(task.taskText.take(90), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${task.outcome} · ${task.channel} · ${task.totalSteps} steps · " +
                                "${task.totalTokens} tokens · ${seconds}s",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
