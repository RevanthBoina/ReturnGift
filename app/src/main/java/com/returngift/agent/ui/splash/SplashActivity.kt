// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.ui.splash

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.returngift.agent.AppCapabilityCoordinator
import com.returngift.agent.AppRequirement
import com.returngift.agent.R
import com.returngift.agent.base.BaseActivity
import com.returngift.agent.service.ReturnGiftAccessibilityService
import com.returngift.agent.service.ReturnGiftNotificationListener
import com.returngift.agent.ui.chat.ComposeChatActivity
import com.returngift.agent.utils.KVUtils

/**
 * Splash screen - always navigates to the home screen; LLM does not need to be configured first, it can be set up in Settings
 */
class SplashActivity : BaseActivity() {

    companion object {
        /** Runs once per install: the services may have been switched off by the update. */
        private const val KEY_SERVICES_RECONCILED = "services_reconciled_v1"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        setContentView(R.layout.activity_splash)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* Back press disabled on splash screen */ }
        })

        // First run: one screen where everything is granted at once. It is never
        // shown again, and no later task asks for any of it individually.
        if (!KVUtils.isGuideShown()) {
            startActivity(Intent(this, com.returngift.agent.ui.guide.GuideActivity::class.java))
            finish()
            return
        }

        // Accessibility and notification access belong to the component that declared
        // them, not to the app, so an update can land with one or both switched off.
        // Check once after an update: one line, then the settings page that fixes it.
        if (!KVUtils.getBoolean(KEY_SERVICES_RECONCILED, false)) {
            KVUtils.putBoolean(KEY_SERVICES_RECONCILED, true)
            val needsAccessibility = !ReturnGiftAccessibilityService.isEnabledInSettings(this)
            val needsNotifications = !ReturnGiftNotificationListener.isEnabledInSettings(this)
            if (needsAccessibility || needsNotifications) {
                val message = when {
                    needsAccessibility && needsNotifications -> R.string.services_off_both
                    needsAccessibility -> R.string.services_off_accessibility
                    else -> R.string.services_off_notifications
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                AppCapabilityCoordinator.openSystemSettings(
                    this,
                    if (needsAccessibility) AppRequirement.ACCESSIBILITY
                    else AppRequirement.NOTIFICATION_ACCESS,
                )
                finish()
                return
            }
        }

        val intent = Intent(this, ComposeChatActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        // Forward debug task extra
        getIntent()?.getStringExtra("task")?.let { intent.putExtra("task", it) }
        startActivity(intent)
        finish()
    }
}
