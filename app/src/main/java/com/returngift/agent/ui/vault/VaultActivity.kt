// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.ui.vault

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import com.returngift.agent.agent.knowledge.KBManager
import com.returngift.agent.ui.chat.ReturnGiftColors
import com.returngift.agent.ui.chat.ThemeManager
import com.returngift.agent.utils.XLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Vault viewer — makes agent-saved work (notes, plans, todos written via kb_write
 * / kb_append / kb_add_todo) visible to the user. Without this screen the vault is
 * invisible app-private storage, which caused "the AI said it prepared a plan but
 * never showed it".
 */
class VaultActivity : ComponentActivity() {

    companion object {
        private const val TAG = "VaultActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themeColors = ThemeManager.getColors()
        window.statusBarColor = themeColors.toolbarBg
        val colors = with(ThemeManager) { themeColors.toComposeColors() }

        setContent {
            VaultScreen(
                colors = colors,
                onOpenFile = { openExternally(it) },
                onShareFile = { shareExternally(it) },
                onDeleteFile = { deleteFile(it) },
                onBack = { finish() },
            )
        }
    }

    /** True on success; toasts either way — delete failures must be user-visible. */
    private fun deleteFile(file: KBManager.VaultFile): Boolean {
        return KBManager.delete(file.path).fold(
            onSuccess = {
                Toast.makeText(this, "Deleted ${file.name}", Toast.LENGTH_SHORT).show()
                true
            },
            onFailure = {
                XLog.e(TAG, "delete failed: ${file.path}", it)
                Toast.makeText(this, "Couldn't delete: ${it.message}", Toast.LENGTH_LONG).show()
                false
            }
        )
    }

    private fun openExternally(file: KBManager.VaultFile) {
        try {
            val target = KBManager.absoluteFile(file.path)
            if (!target.exists()) {
                Toast.makeText(this, "File no longer exists", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, KBManager.mimeOf(file.path))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            XLog.e(TAG, "openExternally failed: ${file.path}", e)
            Toast.makeText(this, "Couldn't open: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareExternally(file: KBManager.VaultFile) {
        try {
            val target = KBManager.absoluteFile(file.path)
            if (!target.exists()) {
                Toast.makeText(this, "File no longer exists", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = KBManager.mimeOf(file.path)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share via"))
        } catch (e: Exception) {
            XLog.e(TAG, "shareExternally failed: ${file.path}", e)
            Toast.makeText(this, "Couldn't share: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultScreen(
    colors: ReturnGiftColors,
    onOpenFile: (KBManager.VaultFile) -> Unit,
    onShareFile: (KBManager.VaultFile) -> Unit,
    onDeleteFile: (KBManager.VaultFile) -> Boolean,
    onBack: () -> Unit,
) {
    var refreshTick by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<KBManager.VaultFile?>(null) }
    var pendingDelete by remember { mutableStateOf<KBManager.VaultFile?>(null) }

    val files by produceState<List<KBManager.VaultFile>>(initialValue = emptyList(), refreshTick) {
        value = withContext(Dispatchers.IO) { KBManager.listAllFiles() }
    }
    // Text preview only — binary files render via Glide (images) or the actions row.
    val content by produceState<String?>(initialValue = null, selected, refreshTick) {
        val file = selected
        value = if (file == null || !KBManager.isTextLike(file.path)) null else withContext(Dispatchers.IO) {
            KBManager.read(file.path).getOrElse { "Couldn't read ${file.path}: ${it.message}" }
        }
    }

    BackHandler(enabled = selected != null) { selected = null }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        selected?.name ?: "Vault",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = colors.textPrimary,
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (selected != null) selected = null else onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.textPrimary,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = colors.textSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
            )
        },
    ) { padding ->
        val current = selected
        if (current == null) {
            VaultFileList(
                files = files,
                colors = colors,
                onSelect = { selected = it },
                modifier = Modifier.padding(padding),
            )
        } else {
            VaultFileDetail(
                file = current,
                content = content,
                colors = colors,
                onOpenFile = onOpenFile,
                onShareFile = onShareFile,
                onDeleteFile = { pendingDelete = current },
                modifier = Modifier.padding(padding),
            )
        }
    }

    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete file?", color = colors.textPrimary) },
            text = {
                Text(
                    "${file.path} will be permanently deleted from the vault.",
                    color = colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    if (onDeleteFile(file)) {
                        selected = null
                        refreshTick++
                    }
                }) {
                    Text("Delete", color = Color(0xFFF44336), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            },
            containerColor = colors.surface,
        )
    }
}

@Composable
private fun VaultFileList(
    files: List<KBManager.VaultFile>,
    colors: ReturnGiftColors,
    onSelect: (KBManager.VaultFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Nothing saved yet",
                fontSize = 16.sp,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Notes, plans and summaries the assistant saves with kb_write will appear here.",
                fontSize = 13.sp,
                color = colors.textTertiary,
            )
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(files, key = { it.path }) { file ->
            Surface(
                color = colors.surface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clickable { onSelect(file) },
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(file.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary)
                    if (file.path != file.name) {
                        Text(file.path, fontSize = 11.sp, color = colors.textTertiary)
                    }
                    Text(
                        "${formatSize(file.sizeBytes)} · ${formatTimestamp(file.modified)}",
                        fontSize = 11.sp,
                        color = colors.textTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun VaultFileDetail(
    file: KBManager.VaultFile,
    content: String?,
    colors: ReturnGiftColors,
    onOpenFile: (KBManager.VaultFile) -> Unit,
    onShareFile: (KBManager.VaultFile) -> Unit,
    onDeleteFile: (KBManager.VaultFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { onShareFile(file) }) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Share", color = colors.accent)
            }
            TextButton(onClick = { onOpenFile(file) }) {
                Text("Open with…", color = colors.accent)
            }
            TextButton(onClick = { onDeleteFile(file) }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = Color(0xFFF44336),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Delete", color = Color(0xFFF44336))
            }
        }
        when {
            KBManager.isImage(file.path) -> {
                AndroidView(
                    factory = { ctx ->
                        ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                    },
                    update = { view ->
                        Glide.with(view).load(KBManager.absoluteFile(file.path)).fitCenter().into(view)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .padding(bottom = 24.dp),
                )
            }
            KBManager.isTextLike(file.path) -> {
                SelectionContainer(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 24.dp),
                ) {
                    Text(
                        text = content ?: "Loading…",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = colors.textPrimary,
                    )
                }
            }
            else -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Binary file (${KBManager.mimeOf(file.path)})",
                        fontSize = 14.sp,
                        color = colors.textSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No inline preview — use Open with or Share above.",
                        fontSize = 12.sp,
                        color = colors.textTertiary,
                    )
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(millis))
