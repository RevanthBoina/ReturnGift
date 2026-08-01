// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool.impl.tv;

import android.view.KeyEvent;

import com.returngift.agent.ClawApplication;
import com.returngift.agent.R;

public class DpadUpTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "dpad_up";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_dpad_up);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the D-pad Up button on the remote. Moves focus to the element above the currently focused one.";
    }

    @Override
    public String getDescriptionCN() {
        return "Press the remote control up directional key. Moves focus to the element above the currently focused element.";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_DPAD_UP;
    }

    @Override
    protected String getKeyLabel() {
        return "D-pad Up";
    }
}
