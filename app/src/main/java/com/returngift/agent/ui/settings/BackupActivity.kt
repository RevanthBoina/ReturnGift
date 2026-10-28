// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.ui.settings

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.returngift.agent.base.BaseActivity
import com.returngift.agent.ui.chat.ChatHistoryManager
import com.returngift.agent.utils.KVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → Backup & restore.
 *
 * One file holds what the agent learned: every app guide, every chat, and the
 * settings that describe this phone (provider and model, the consent switch, channel
 * owners). Credentials are never written — API keys, bot tokens and passwords are
 * filtered out by name — so the file can sit in Downloads without guarding.
 */
class BackupActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BackupScreen() }
    }
}

@Composable
private fun BackupScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Nothing exported yet.") }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            status = withContext(Dispatchers.IO) { runCatching { Backup.restore(context, uri) }.getOrElse { "Restore failed: ${it.message}" } }
            busy = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Backup", style = MaterialTheme.typography.titleMedium)
        Text(
            "Settings, learned app guides and every chat, in one file in Downloads. API keys and tokens are not included.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                scope.launch {
                    status = withContext(Dispatchers.IO) { runCatching { Backup.export(context) }.getOrElse { "Export failed: ${it.message}" } }
                    busy = false
                }
            },
        ) { Text("Export to Downloads") }

        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            onClick = { picker.launch(arrayOf("application/json")) },
        ) { Text("Restore from a file") }

        Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
    }
    if (busy) Toast.makeText(context, "Working…", Toast.LENGTH_SHORT).show()
}

object Backup {

    private const val VERSION = 1
    private val PREFIXES = listOf("app_guide_v1_", "channel_owner_", "KEY_LLM_", "KEY_LOCAL_", "DEFAULT_")
    private val BOOL_KEYS = listOf("ask_before_risky_actions", "KEY_GUIDE_SHOWN", "agent_strict_plan_mode")

    /** Credentials never leave the device, even inside "settings". */
    private fun isSecret(key: String): Boolean =
        listOf("api_key", "token", "secret", "password").any { key.lowercase().contains(it) }

    fun export(context: Context): String {
        val settings = JSONObject()
        KVUtils.getAllKeys().forEach { key ->
            if (isSecret(key)) return@forEach
            if (key in BOOL_KEYS) settings.put(key, KVUtils.getBoolean(key, false))
            if (PREFIXES.any { key.startsWith(it) } && key !in BOOL_KEYS) {
                val value = KVUtils.getString(key, "")
                if (value.isNotEmpty()) settings.put(key, value)
            }
        }
        val chats = JSONObject()
        ChatHistoryManager.listConversations(context).forEach { summary ->
            runCatching { chats.put(summary.file.name, summary.file.readText()) }
        }
        val payload = JSONObject()
            .put("version", VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("settings", settings)
            .put("chats", chats)

        val name = "returngift-backup-" +
            SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
        val text = payload.toString(2)
        val location = writeDownloads(context, name, text)
        return "Exported ${settings.length()} settings and ${chats.length()} chats to $location"
    }

    private fun writeDownloads(context: Context, name: String, text: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("no Downloads entry")
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    ?: error("could not open Downloads entry")
                return "Downloads/$name"
            }
        }
        return runCatching {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(dir, name).writeText(text)
            "Downloads/$name"
        }.getOrElse {
            // No public Downloads access: the app's own folder still survives a reinstall of the data.
            val file = File(context.getExternalFilesDir(null), name)
            file.writeText(text)
            file.absolutePath
        }
    }

    fun restore(context: Context, uri: Uri): String {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return "Restore failed: the file could not be read."
        val payload = JSONObject(text)
        val settings = payload.optJSONObject("settings") ?: JSONObject()
        var restored = 0
        settings.keys().forEach { key ->
            val value = settings.get(key)
            when (value) {
                is Boolean -> KVUtils.putBoolean(key, value)
                is Int -> KVUtils.putInt(key, value)
                is Long -> KVUtils.putLong(key, value)
                else -> KVUtils.putString(key, value.toString())
            }
            restored++
        }
        KVUtils.sync()

        val chats = payload.optJSONObject("chats") ?: JSONObject()
        val dir = File(context.getExternalFilesDir(null), "chats").apply { mkdirs() }
        var chatCount = 0
        chats.keys().forEach { fileName ->
            runCatching {
                File(dir, fileName).writeText(chats.getString(fileName))
                chatCount++
            }
        }
        return "Restored $restored settings and $chatCount chats. Reopen the chat to see them."
    }
}
