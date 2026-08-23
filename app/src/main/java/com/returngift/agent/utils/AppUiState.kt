// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.utils

import android.app.Activity
import android.app.Application

/**
 * Minimal "is our UI visible" tracker. Registered in ClawApplication; consulted
 * by heads-up notification logic (clarification questions get WhatsApp-style
 * notifications only when the app is not in front).
 */
object AppUiState {

    @Volatile
    private var foregroundActivity: Activity? = null

    val isForeground: Boolean
        get() = foregroundActivity != null

    fun registerIn(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                foregroundActivity = activity
            }

            override fun onActivityPaused(activity: Activity) {
                foregroundActivity = null
            }

            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
