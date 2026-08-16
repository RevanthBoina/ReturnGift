// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool.impl.tv;

import android.view.KeyEvent;

import com.returngift.agent.ClawApplication;
import com.returngift.agent.R;

public class VolumeDownTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "volume_down";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_volume_down);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the Volume Down button to decrease the volume.";
    }

    @Override
    public String getDescriptionCN() {
        return "Press the volume down key to decrease volume.";
    }

    @Override
    public ToolResult execute(java.util.Map<String, Object> params) {
        try {
            android.media.AudioManager audio = (android.media.AudioManager) ClawApplication.Companion.getInstance()
                    .getSystemService(android.content.Context.AUDIO_SERVICE);
            if (audio != null) {
                audio.adjustVolume(android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI);
                return ToolResult.success("Decreased volume");
            }
        } catch (Exception ignored) {}
        return super.execute(params);
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_VOLUME_DOWN;
    }

    @Override
    protected String getKeyLabel() {
        return "Volume Down";
    }
}
