// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.returngift.agent.ui.chat.ComposeChatActivity

/**
 * Quick Settings tile: one tap from anywhere to the agent's chat, without hunting for
 * the app icon. It opens the chat ready for typing — the tile has no text to run, so
 * "start a task" starts with the owner's next sentence.
 */
class QuickLaunchTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ComposeChatActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
