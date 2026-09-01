// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.core

import android.view.accessibility.AccessibilityNodeInfo
import com.returngift.agent.service.ClawAccessibilityService
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TerminalBufferExtractor reconstructs terminal/shell output streams and active prompts
 * from terminal apps (e.g. Termux) where conventional UI nodes are not individually exposed.
 */
object TerminalBufferExtractor {

    private val TERMINAL_PACKAGES = setOf(
        "com.termux",
        "jackpal.androidterm",
        "org.connectbot",
        "com.server.auditor.ssh.client",
        "com.sonelli.juicessh"
    )

    private val recentEventLines = CopyOnWriteArrayList<String>()
    private const val MAX_BUFFER_LINES = 100

    data class TerminalState(
        val isTerminalActive: Boolean,
        val packageName: String,
        val bufferText: String,
        val activePromptLine: String?
    )

    /**
     * Records text from accessibility events emitted by terminal emulators.
     */
    @JvmStatic
    fun onAccessibilityEventText(packageName: String, textList: List<CharSequence>) {
        if (!TERMINAL_PACKAGES.contains(packageName)) return

        for (cs in textList) {
            val text = cs.toString().trim()
            if (text.isNotEmpty()) {
                recentEventLines.add(text)
                if (recentEventLines.size > MAX_BUFFER_LINES) {
                    recentEventLines.removeAt(0)
                }
            }
        }
    }

    /**
     * Reconstructs terminal screen buffer from active accessibility root.
     */
    @JvmStatic
    fun extractTerminalBuffer(service: ClawAccessibilityService): TerminalState {
        val root = service.rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: ""
        val isTerminal = TERMINAL_PACKAGES.contains(pkg)

        if (!isTerminal || root == null) {
            return TerminalState(
                isTerminalActive = false,
                packageName = pkg,
                bufferText = "",
                activePromptLine = null
            )
        }

        val collectedText = mutableListOf<String>()
        collectAllText(root, collectedText)

        // Merge event stream if available
        if (collectedText.isEmpty() && recentEventLines.isNotEmpty()) {
            collectedText.addAll(recentEventLines)
        }

        val fullBuffer = collectedText.joinToString("\n")
        val lastLine = collectedText.lastOrNull { it.isNotBlank() }

        return TerminalState(
            isTerminalActive = true,
            packageName = pkg,
            bufferText = fullBuffer,
            activePromptLine = lastLine
        )
    }

    private fun collectAllText(node: AccessibilityNodeInfo, outList: MutableList<String>) {
        val t = node.text?.toString()?.trim()
        val cd = node.contentDescription?.toString()?.trim()
        val line = if (!t.isNullOrEmpty()) t else cd
        if (!line.isNullOrEmpty()) {
            outList.add(line)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllText(child, outList)
            child.recycle()
        }
    }
}
