// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.service

import android.os.Bundle
import com.returngift.agent.AppCapabilityCoordinator
import com.returngift.agent.AppRequirement
import com.returngift.agent.base.BaseActivity
import com.returngift.agent.utils.XLog

/**
 * The door behind "tap to fix": lands straight on the Android settings page for the
 * capability that is missing, then gets out of the way. No UI of its own.
 */
class CapabilityFixActivity : BaseActivity() {

    companion object {
        const val EXTRA_REQUIREMENT = "extra_requirement"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requirement = intent?.getStringExtra(EXTRA_REQUIREMENT)
            ?.let { name -> runCatching { AppRequirement.valueOf(name) }.getOrNull() }
        if (requirement == null) {
            XLog.w("CapabilityFixActivity", "no requirement passed")
            finish()
            return
        }
        AppCapabilityCoordinator.openSystemSettings(this, requirement)
        finish()
    }
}
