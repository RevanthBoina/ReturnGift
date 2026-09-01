// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.core.input

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.returngift.agent.service.ClawAccessibilityService
import com.returngift.agent.utils.XLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * DynamicIMEInjector handles robust text injection across rich-text fields,
 * search bars, WebViews, and terminal emulators.
 */
object DynamicIMEInjector {

    private const val TAG = "DynamicIMEInjector"

    /** Typed failure method returned when a sensitive field rejects ACTION_SET_TEXT. */
    const val METHOD_SENSITIVE_FIELD_INPUT_FAILED = "SENSITIVE_FIELD_INPUT_FAILED"

    /**
     * Keywords (lowercased) in a field's placeholder/hint text that mark it as
     * credential/OTP input. AccessibilityNodeInfo does NOT expose View autofill
     * hints; hintText (API 26) is the available accessibility-side signal.
     */
    private val SENSITIVE_HINT_KEYWORDS = listOf("password", "passcode", "one-time", "otp", "cvv")

    /**
     * Test hook for the clipboard write in [pasteFromClipboard]: posts to the main
     * thread in production; unit tests replace it with immediate execution (the
     * production latch-await would otherwise deadlock Robolectric's paused main looper).
     */
    internal var mainThreadPoster: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) }

    /**
     * True when [node] is a password field, is marked accessibility-data-sensitive
     * (API 34+, e.g. banking/OTP screens), or its hint text names a credential/OTP.
     * Such fields must never receive text via the shared clipboard (readable by other
     * apps / clipboard managers).
     */
    @JvmStatic
    fun isSensitiveField(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isPassword) return true
        if (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) return true
        val hint = node.hintText?.toString()?.lowercase(java.util.Locale.US) ?: return false
        return SENSITIVE_HINT_KEYWORDS.any { hint.contains(it) }
    }

    data class InjectionResult(
        val success: Boolean,
        val method: String,
        val message: String
    )

    /**
     * Injects text into a target node or focused field with multi-strategy fallback and verification.
     */
    @JvmStatic
    @JvmOverloads
    fun injectText(
        service: ClawAccessibilityService,
        text: String,
        targetNode: AccessibilityNodeInfo?,
        clearFirst: Boolean = true
    ): InjectionResult {
        var node = targetNode ?: findFocusedOrFirstEditable(service)

        if (node == null) {
            return InjectionResult(false, "none", "No editable text field found to inject text")
        }

        // 1. Focus verification & tap
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        // 2. Clear if requested
        if (clearFirst) {
            clearField(node)
        }

        // 3. Strategy 1: ACTION_SET_TEXT
        if (trySetText(node, text, clearFirst)) {
            XLog.i(TAG, "Injected text via ACTION_SET_TEXT")
            return InjectionResult(true, "ACTION_SET_TEXT", "Successfully entered text")
        }

        // Sensitive fields (password / username / OTP): the clipboard is readable by
        // other apps and clipboard managers, so pasting credentials there is a privacy
        // leak. Retry ACTION_SET_TEXT one final time; never fall back to clipboard.
        if (isSensitiveField(node)) {
            if (trySetText(node, text, clearFirst)) {
                XLog.i(TAG, "Injected text via ACTION_SET_TEXT (sensitive retry)")
                return InjectionResult(true, "ACTION_SET_TEXT", "Successfully entered text")
            }
            XLog.w(TAG, "Sensitive field rejected all input strategies; clipboard fallback refused")
            return InjectionResult(
                false,
                METHOD_SENSITIVE_FIELD_INPUT_FAILED,
                "SENSITIVE_FIELD_INPUT_FAILED: field is a password/credential/OTP input — " +
                    "clipboard paste is disabled for sensitive fields; ask the user to type it manually"
            )
        }

        // 4. Strategy 2: Clipboard Paste
        val clipboardSuccess = pasteFromClipboard(service, text, node, clearFirst)
        if (clipboardSuccess) {
            XLog.i(TAG, "Injected text via Clipboard Paste")
            return InjectionResult(true, "CLIPBOARD_PASTE", "Successfully pasted text")
        }

        // 5. Strategy 3: Key event fallback if enter or tab
        if (text == "\n" || text.equals("enter", ignoreCase = true)) {
            try {
                Runtime.getRuntime().exec(arrayOf("input", "keyevent", "66")).waitFor()
                return InjectionResult(true, "KEY_EVENT_ENTER", "Dispatched enter key")
            } catch (_: Exception) {}
        } else if (text == "\t" || text.equals("tab", ignoreCase = true)) {
            try {
                Runtime.getRuntime().exec(arrayOf("input", "keyevent", "61")).waitFor()
                return InjectionResult(true, "KEY_EVENT_TAB", "Dispatched tab key")
            } catch (_: Exception) {}
        }

        return InjectionResult(false, "all_failed", "Failed to enter text into field")
    }

    private fun trySetText(node: AccessibilityNodeInfo, text: String, clearFirst: Boolean): Boolean {
        for (attempt in 0 until 2) {
            val existing = node.text?.toString().orEmpty()
            val finalText = if (clearFirst) text else (existing + text)

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return true
            }
            try { Thread.sleep(150) } catch (_: InterruptedException) {}
        }
        return false
    }

    private fun pasteFromClipboard(
        context: Context,
        text: String,
        node: AccessibilityNodeInfo,
        clearFirst: Boolean
    ): Boolean {
        val latch = CountDownLatch(1)
        var clipSet = false

        mainThreadPoster(Runnable {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("ime_inject", text))
                    clipSet = true
                }
            } catch (_: Exception) {}
            latch.countDown()
        })

        try {
            latch.await(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {}

        if (!clipSet) return false

        if (clearFirst) {
            clearField(node)
        } else {
            val existing = node.text?.toString().orEmpty()
            val end = existing.length
            val cursorArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, end)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)
        }

        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun clearField(node: AccessibilityNodeInfo) {
        val selectAll = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Integer.MAX_VALUE)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAll)

        val clearArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
    }

    private fun findFocusedOrFirstEditable(service: ClawAccessibilityService): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && isGenuineEditable(focused)) {
            return focused
        }
        return findFirstEditableRecursive(root)
    }

    fun isGenuineEditable(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isEditable) return true
        val cn = node.className?.toString().orEmpty()
        return cn.contains("EditText") || cn.contains("TextInput") ||
               cn.contains("SearchAutoComplete") || cn.contains("TextField")
    }

    private fun findFirstEditableRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isGenuineEditable(node)) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditableRecursive(child)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }
}
