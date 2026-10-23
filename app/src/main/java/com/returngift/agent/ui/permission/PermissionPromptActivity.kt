// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.ui.permission

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.returngift.agent.AppCapabilityCoordinator
import com.returngift.agent.AppRequirement
import com.returngift.agent.R
import com.returngift.agent.agent.permission.PermissionPromptBroker
import com.returngift.agent.agent.permission.PermissionPromptLauncher
import com.returngift.agent.agent.permission.PermissionRequest
import com.returngift.agent.base.BaseActivity
import com.returngift.agent.utils.XLog

/**
 * The permission card of the APK — opened (in the foreground) whenever the agent
 * needs permission before acting:
 *
 *  - per-app access (per-app allow-list first encounter / re-enable), and
 *  - a ReturnGift system capability (accessibility, overlay, notification
 *    access, storage, battery).
 *
 * The agent thread is parked in [PermissionPromptBroker] while this screen is
 * up; every button answers that request and finishes the screen. Back/dismiss
 * answers "Cancel" so the safe default is always "do not act".
 */
class PermissionPromptActivity : BaseActivity() {

    private var request: PermissionRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parsed = PermissionPromptLauncher.requestFromIntent(intent)
            ?: PermissionPromptBroker.snapshot()
        request = parsed

        if (parsed == null) {
            XLog.w(TAG, "opened without a permission request — finishing")
            finish()
            return
        }
        XLog.i(TAG, "permission screen opened for ${parsed.kind} ${parsed.packageName.ifBlank { parsed.capabilityId }}")

        // Back = dismiss = Cancel (safe default), and it resolves the parked thread.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                answer(PermissionRequest.CHOICE_CANCEL)
            }
        })

        setContent {
            MaterialTheme {
                PermissionPromptScreen(
                    request = parsed,
                    onChoice = { choice -> answer(choice) },
                    onOpenAppInfo = { openAppInfo(parsed.packageName) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        PermissionPromptLauncher.requestFromIntent(intent)?.let { request = it }
    }

    /** Route a choice to the broker and close the screen. */
    private fun answer(choice: String) {
        val current = request
        XLog.i(TAG, "user chose \"$choice\" for ${current?.id}")
        val decision = current?.decisionFor(choice)
        if (decision == PermissionRequest.Decision.OPEN_SETTINGS) {
            val requirement = runCatching { AppRequirement.valueOf(current.capabilityId) }
                .getOrNull()
            if (requirement != null) {
                // Keep the request pending: the user grants in system settings and
                // returns to this card to confirm.
                AppCapabilityCoordinator.openSystemSettings(this, requirement)
                return
            }
        }
        PermissionPromptBroker.answer(choice)
        finish()
    }

    /** Deep-link to the target APK's own Android app-info page. */
    private fun openAppInfo(packageName: String) {
        if (packageName.isBlank()) return
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
        } catch (e: Throwable) {
            XLog.w(TAG, "could not open app info for $packageName", e)
        }
    }

    private companion object {
        const val TAG = "PermissionPromptActivity"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionPromptScreen(
    request: PermissionRequest,
    onChoice: (String) -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    // App icon: rendered when the request targets another APK.
    val icon: Bitmap? = remember(request.packageName) { loadAppIcon(request.packageName) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            if (icon != null) {
                Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = request.label,
                    modifier = Modifier.size(64.dp),
                )
            } else {
                Image(
                    painter = androidx.compose.ui.res.painterResource(id = R.mipmap.ic_launcher),
                    contentDescription = "ReturnGift",
                    modifier = Modifier.size(64.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = request.title(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (request.isAppAccess) "App permission" else "ReturnGift permission",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(20.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = request.body(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // How the agent will actually use this app (same recipes the model
                    // receives), so the user knows what they are approving.
                    val guide = remember(request.packageName) {
                        com.returngift.agent.agent.knowledge.AppUsageGuide.forPackage(request.packageName)
                    }
                    if (guide != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "How the agent will use ${guide.label}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        guide.tips.take(3).forEach { tip ->
                            Text(
                                text = "• $tip",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            if (request.kind == PermissionRequest.Kind.APP_ACCESS) {
                Button(
                    onClick = { onChoice(PermissionRequest.CHOICE_ALLOW_ONCE) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(PermissionRequest.CHOICE_ALLOW_ONCE) }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onChoice(PermissionRequest.CHOICE_ALWAYS_ALLOW) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) { Text(PermissionRequest.CHOICE_ALWAYS_ALLOW) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenAppInfo,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open app settings") }
            } else {
                Button(
                    onClick = { onChoice(PermissionRequest.CHOICE_OPEN_SETTINGS) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open settings") }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = { onChoice(PermissionRequest.CHOICE_CANCEL) }) {
                    Text(PermissionRequest.CHOICE_CANCEL)
                }
            }
        }
    }
}

private fun loadAppIcon(packageName: String): Bitmap? {
    if (packageName.isBlank()) return null
    return try {
        val app = com.returngift.agent.ClawApplication.instance
        val drawable: Drawable = app.packageManager.getApplicationIcon(packageName)
        when (drawable) {
            is BitmapDrawable -> drawable.bitmap
            else -> drawable.toBitmap(width = 128, height = 128)
        }
    } catch (e: Throwable) {
        null
    }
}
