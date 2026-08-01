// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool.impl.tv;

import android.view.KeyEvent;

import com.returngift.agent.ClawApplication;
import com.returngift.agent.R;

public class VolumeUpTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "volume_up";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_volume_up);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the Volume Up button to increase the volume.";
    }

    @Override
    public String getDescriptionCN() {
        return "Press the volume up key to increase volume.";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_VOLUME_UP;
    }

    @Override
    protected String getKeyLabel() {
        return "Volume Up";
    }
}
