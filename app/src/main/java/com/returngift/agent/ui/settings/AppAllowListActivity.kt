// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.ui.settings

import android.graphics.Bitmap
import com.returngift.agent.agent.permission.AppAccessMode
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.returngift.agent.ClawApplication
import com.returngift.agent.agent.AppAllowListStore
import com.returngift.agent.agent.knowledge.AppUsageGuide
import com.returngift.agent.agent.knowledge.InstalledApps
import com.returngift.agent.agent.prefs.AgentPreferences
import com.returngift.agent.base.BaseActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → App Permissions.
 *
 * Two things live here:
 *  1. **Toggles for the permission + micro-step machinery** — ask before a new
 *     app, remember choices permanently (or make every grant temporary), open
 *     the permission card in front, background notification, enforce mid-task
 *     micro-steps.
 *  2. **One row per app installed on the device** (Chrome, LinkedIn, YouTube, …)
 *     with a tri-state control:
 *        Always | Ask | Blocked
 *     and a one-line hint describing how the agent will use that app.
 *
 * Rows exist for every launchable app plus any app the agent already met, so the
 * list is useful before the first task instead of filling up only afterwards.
 */
class AppAllowListActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppAllowListScreen(onBack = { finish() }) }
    }
}

/** Combined row model for the list. */
private data class AppRow(
    val packageName: String,
    val label: String,
    val mode: AppAccessMode,
    val firstSeenAt: Long,
    val surfaceHint: String?,
)

private enum class RowFilter(val label: String) {
    ALL("All"),
    ALWAYS("Always"),
    ASK("Ask"),
    BLOCKED("Blocked"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppAllowListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AppAllowListStore.getInstance(context) }

    var prefsVersion by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(RowFilter.ALL) }
    var refreshTick by remember { mutableStateOf(0) }
    var rows by remember { mutableStateOf<List<AppRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // PackageManager + SQLite lookups stay off the main thread.
    LaunchedEffect(refreshTick) {
        loading = true
        rows = withContext(Dispatchers.IO) { buildRows(store) }
        loading = false
    }
    val snapshot = remember(prefsVersion) { AgentPreferences.snapshot() }

    val filtered = remember(rows, query, filter) {
        rows.filter { row ->
            val matchesQuery = query.isBlank() ||
                row.label.contains(query, ignoreCase = true) ||
                row.packageName.contains(query, ignoreCase = true)
            val matchesFilter = when (filter) {
                RowFilter.ALL -> true
                RowFilter.ALWAYS -> row.mode == AppAccessMode.ALWAYS
                RowFilter.ASK -> row.mode == AppAccessMode.ASK
                RowFilter.BLOCKED -> row.mode == AppAccessMode.BLOCKED
            }
            matchesQuery && matchesFilter
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App permissions", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", fontSize = 20.sp) }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                Text(
                    text = "Control what the agent may do on this device. These switches apply " +
                        "immediately — no restart needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            // ── 1. Behaviour toggles ────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            text = "PERMISSIONS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                        ToggleRow(
                            title = "Ask before a new app",
                            subtitle = if (snapshot.askBeforeNewApp) {
                                "The permission card opens the first time the agent touches an app."
                            } else {
                                "Off — every app is allowed without asking (not recommended)."
                            },
                            checked = snapshot.askBeforeNewApp,
                            onCheckedChange = {
                                AgentPreferences.askBeforeNewApp = it
                                prefsVersion++
                            },
                        )
                        ToggleRow(
                            title = "Remember choices permanently",
                            subtitle = if (snapshot.rememberAppChoices) {
                                "\"Always allow\" is saved until you change it here."
                            } else {
                                "Off — every grant lasts one task only (temporary access)."
                            },
                            checked = snapshot.rememberAppChoices,
                            onCheckedChange = {
                                AgentPreferences.rememberAppChoices = it
                                prefsVersion++
                            },
                        )
                        ToggleRow(
                            title = "Open the permission card in front",
                            subtitle = "Brings the ReturnGift permission card to the foreground when the agent needs it.",
                            checked = snapshot.openPermissionCard,
                            onCheckedChange = {
                                AgentPreferences.openPermissionCard = it
                                prefsVersion++
                            },
                        )
                        ToggleRow(
                            title = "Notify in the background",
                            subtitle = "Posts a heads-up with the same choices while a task runs in the background.",
                            checked = snapshot.notifyPermission,
                            onCheckedChange = {
                                AgentPreferences.notifyPermission = it
                                prefsVersion++
                            },
                        )
                        ToggleRow(
                            title = "Enforce mid-task steps",
                            subtitle = "After typing, the agent must send (Enter/Send) before it can finish.",
                            checked = snapshot.enforceMicroSteps,
                            onCheckedChange = {
                                AgentPreferences.enforceMicroSteps = it
                                prefsVersion++
                            },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = {
                                AgentPreferences.resetToDefaults()
                                prefsVersion++
                            }) { Text("Reset to defaults") }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // ── 2. Search + filters ─────────────────────────────────────────
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RowFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(option.label, fontSize = 12.sp) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${filtered.size} app${if (filtered.size == 1) "" else "s"} · " +
                        "Always = permanent grant · Ask = card every task · Blocked = never act",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            if (loading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            }

            if (!loading && filtered.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (query.isBlank()) "No apps match this filter."
                            else "No app matches \"$query\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(filtered, key = { it.packageName }) { row ->
                AppRowItem(
                    row = row,
                    onModeChange = { newMode ->
                        store.setMode(row.packageName, newMode, row.label)
                        refreshTick++
                    },
                    onReset = {
                        // Forget the policy AND what we remembered about the session
                        // (consent accepted / seen signed in), so the next task starts
                        // from a clean first-encounter state.
                        store.forget(row.packageName)
                        com.returngift.agent.agent.flow.AppSessionMemory.forget(row.packageName)
                        refreshTick++
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AppRowItem(
    row: AppRow,
    onModeChange: (AppAccessMode) -> Unit,
    onReset: () -> Unit,
) {
    val icon: Bitmap? = remember(row.packageName) { loadIcon(row.packageName) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = row.label,
                    modifier = Modifier.size(36.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = row.label.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onReset) { Text("Reset", fontSize = 12.sp) }
        }

        if (!row.surfaceHint.isNullOrBlank()) {
            Text(
                text = row.surfaceHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, top = 2.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeChip(
                text = "Always allow",
                selected = row.mode == AppAccessMode.ALWAYS,
                onClick = { onModeChange(AppAccessMode.ALWAYS) },
            )
            ModeChip(
                text = "Ask",
                selected = row.mode == AppAccessMode.ASK,
                onClick = { onModeChange(AppAccessMode.ASK) },
            )
            ModeChip(
                text = "Blocked",
                selected = row.mode == AppAccessMode.BLOCKED,
                onClick = { onModeChange(AppAccessMode.BLOCKED) },
            )
        }
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, fontSize = 11.sp) },
    )
}

/** Union of installed apps, apps the agent already met, and guided apps. */
private fun buildRows(store: AppAllowListStore): List<AppRow> {
    val context = ClawApplication.instance
    val stored = runCatching { store.getAllEntries() }.getOrDefault(emptyList())
    val storedByPackage = stored.associateBy { it.packageName }
    val installed = runCatching { InstalledApps.launchable(context, forceRefresh = true) }
        .getOrDefault(emptyList())

    val rows = mutableListOf<AppRow>()
    val seen = mutableSetOf<String>()

    // Installed apps first (this is the list the user actually cares about).
    installed.forEach { app ->
        if (!seen.add(app.packageName)) return@forEach
        val entry = storedByPackage[app.packageName]
        rows.add(
            AppRow(
                packageName = app.packageName,
                label = app.label,
                mode = entry?.mode ?: AppAccessMode.ASK,
                firstSeenAt = entry?.firstSeenAt ?: 0L,
                surfaceHint = AppUsageGuide.forPackage(app.packageName)?.shortHint,
            )
        )
    }

    // Apps the agent met that are not launchable (widgets, system targets…).
    stored.filter { it.packageName !in seen }.forEach { entry ->
        if (!seen.add(entry.packageName)) return@forEach
        rows.add(
            AppRow(
                packageName = entry.packageName,
                label = entry.label.ifBlank { entry.packageName },
                mode = entry.mode,
                firstSeenAt = entry.firstSeenAt,
                surfaceHint = AppUsageGuide.forPackage(entry.packageName)?.shortHint,
            )
        )
    }

    // Guided apps that are installed but not launchable (rare) — keep them visible.
    AppUsageGuide.all().forEach { guide ->
        if (guide.packageName in seen) return@forEach
        if (!runCatching { InstalledApps.isInstalled(context, guide.packageName) }.getOrDefault(false)) {
            return@forEach
        }
        seen.add(guide.packageName)
        val entry = storedByPackage[guide.packageName]
        rows.add(
            AppRow(
                packageName = guide.packageName,
                label = guide.label,
                mode = entry?.mode ?: AppAccessMode.ASK,
                firstSeenAt = entry?.firstSeenAt ?: 0L,
                surfaceHint = guide.shortHint,
            )
        )
    }

    // Apps with a non-default mode float to the top so a temporary grant is visible.
    return rows.sortedWith(
        compareBy<AppRow> { it.mode == AppAccessMode.ASK }
            .thenBy { it.label.lowercase() }
    )
}

private fun loadIcon(packageName: String): Bitmap? = try {
    val drawable: Drawable = ClawApplication.instance.packageManager.getApplicationIcon(packageName)
    when (drawable) {
        is BitmapDrawable -> drawable.bitmap
        else -> drawable.toBitmap(width = 96, height = 96)
    }
} catch (_: Throwable) {
    null
}
