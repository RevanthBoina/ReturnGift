// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool.impl.tv;

import android.view.KeyEvent;

import com.returngift.agent.ClawApplication;
import com.returngift.agent.R;

public class DpadDownTool extends BaseKeyTool {

    @Override
    public String getName() {
        return "dpad_down";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_dpad_down);
    }

    @Override
    public String getDescriptionEN() {
        return "Press the D-pad Down button on the remote. Moves focus to the element below the currently focused one.";
    }

    @Override
    public String getDescriptionCN() {
        return "Press the remote control down directional key. Moves focus to the element below the currently focused element.";
    }

    @Override
    protected int getKeyCode() {
        return KeyEvent.KEYCODE_DPAD_DOWN;
    }

    @Override
    protected String getKeyLabel() {
        return "D-pad Down";
    }
}
