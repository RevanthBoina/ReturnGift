// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.widget

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.blankj.utilcode.util.ThreadUtils
import com.lzf.easyfloat.EasyFloat
import com.lzf.easyfloat.enums.ShowPattern
import com.lzf.easyfloat.enums.SidePattern
import com.lzf.easyfloat.interfaces.OnFloatCallbacks
import com.returngift.agent.R
import com.returngift.agent.utils.XLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Confirmation dialog shown via system overlay (TYPE_APPLICATION_OVERLAY).
 * Used as a fallback when no foreground Activity is available but SYSTEM_ALERT_WINDOW
 * permission has been granted.
 *
 * This allows tier-2+ skill confirmations to work even for tasks triggered via:
 * - ExternalAutomationReceiver
 * - Scheduled tasks
 * - Cloud-initiated tasks while the app isn't open
 */
object OverlayConfirmDialog {

    private const val TAG = "OverlayConfirmDialog"
    private const val FLOAT_TAG = "confirm_overlay"
    private const val CONFIRM_TIMEOUT_SEC = 30L

    /**
     * Check if overlay permission is granted.
     * @param context Application context to check against
     * @return true if overlays can be displayed
     */
    fun hasOverlayPermission(context: Application): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Show a confirmation dialog using system overlay.
     * Falls back to denying if overlay permission is not granted.
     *
     * @param application Application instance (required for overlay)
     * @param title Dialog title
     * @param message Dialog message
     * @param actionTitle Confirm button text
     * @param cancelTitle Cancel button text
     * @param timeoutSeconds Maximum seconds to wait for user response
     * @return true if user confirmed, false if denied or timed out
     */
    fun showOverlay(
        application: Application,
        title: String,
        message: String,
        actionTitle: String = "Allow",
        cancelTitle: String = "Cancel",
        timeoutSeconds: Long = CONFIRM_TIMEOUT_SEC,
    ): Boolean {
        if (!hasOverlayPermission(application)) {
            XLog.w(TAG, "Overlay permission not granted — cannot show confirmation")
            return false
        }

        val latch = CountDownLatch(1)
        var allowed = false

        ThreadUtils.runOnUiThread {
            showOverlayInternal(
                application = application,
                title = title,
                message = message,
                actionTitle = actionTitle,
                cancelTitle = cancelTitle,
                onAllow = {
                    allowed = true
                    dismiss()
                    latch.countDown()
                },
                onDeny = {
                    allowed = false
                    dismiss()
                    latch.countDown()
                }
            )
        }

        val completed = latch.await(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            XLog.w(TAG, "Overlay confirmation timed out after ${timeoutSeconds}s — denying")
            dismiss()
        }
        return allowed
    }

    private fun showOverlayInternal(
        application: Application,
        title: String,
        message: String,
        actionTitle: String,
        cancelTitle: String,
        onAllow: () -> Unit,
        onDeny: () -> Unit,
    ) {
        // Dismiss any existing overlay
        dismiss()

        val container = LinearLayout(application).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_dialog_alert)
            setPadding(
                dpToPx(application, 24),
                dpToPx(application, 24),
                dpToPx(application, 24),
                dpToPx(application, 24)
            )
        }

        // Title
        val tvTitle = TextView(application).apply {
            text = title
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(application.getColor(R.color.colorTextPrimary))
        }
        container.addView(tvTitle)

        // Message
        if (message.isNotEmpty()) {
            val tvMessage = TextView(application).apply {
                text = message
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(application.getColor(R.color.colorTextTertiary))
                setPadding(0, dpToPx(application, 8), 0, 0)
            }
            container.addView(tvMessage)
        }

        // Button container
        val btnContainer = LinearLayout(application).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(application, 32)
            }
        }

        // Cancel button
        val btnCancel = com.returngift.agent.widget.KButton(application).apply {
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(application, 44), 1f)
            text = cancelTitle
            setBgColor(application.getColor(R.color.colorContainerBase))
            setTextColor(application.getColor(R.color.colorTextSecondary))
            setBorderColor(application.getColor(R.color.colorBorderBase))
            setOnClickListener { onDeny() }
        }
        btnContainer.addView(btnCancel)

        // Spacer
        btnContainer.addView(View(application).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(application, 8), 0)
        })

        // Allow button (red/warm for safety)
        val btnAllow = com.returngift.agent.widget.KButton(application).apply {
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(application, 44), 1f)
            text = actionTitle
            setBgColor(application.getColor(R.color.colorErrorPrimary))
            setBorderColor(application.getColor(R.color.colorErrorPrimary))
            setOnClickListener { onAllow() }
        }
        btnContainer.addView(btnAllow)

        container.addView(btnContainer)

        EasyFloat.with(application)
            .setLayout(container)
            .setShowPattern(ShowPattern.FOREGROUND)
            .setSidePattern(SidePattern.TOP)
            .setGravity(Gravity.CENTER_HORIZONTAL or Gravity.TOP, 0, dpToPx(application, 100))
            .setTag(FLOAT_TAG)
            .registerCallbacks(object : OnFloatCallbacks {
                override fun createdResult(isCreated: Boolean, msg: String?, view: View?) {
                    if (isCreated) {
                        XLog.i(TAG, "Overlay dialog created")
                    } else {
                        XLog.e(TAG, "Failed to create overlay dialog: $msg")
                    }
                }

                override fun dismiss() {
                    XLog.i(TAG, "Overlay dialog dismissed")
                }

                override fun drag(view: View, event: android.view.MotionEvent) {}
                override fun dragEnd(view: View) {}
                override fun hide(view: View) {}
                override fun show(view: View) {}
                override fun touchEvent(view: View, event: android.view.MotionEvent) {}
            })
            .show()
    }

    /**
     * Dismiss any showing overlay confirmation dialog.
     */
    fun dismiss() {
        try {
            EasyFloat.dismiss(FLOAT_TAG)
        } catch (e: Exception) {
            // Ignore if already dismissed
        }
    }

    private fun dpToPx(context: Application, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
