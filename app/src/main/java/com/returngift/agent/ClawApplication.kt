// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent

import com.returngift.agent.agent.DefaultAgentService
import com.returngift.agent.agent.knowledge.AppCatalog
import com.returngift.agent.agent.llm.LocalBackendHealth
import com.returngift.agent.base.BaseApp
import com.returngift.agent.channel.ChannelManager
import com.returngift.agent.server.CloudDeepAgentManager
import com.returngift.agent.server.ConfigServerManager
import com.returngift.agent.tool.ToolRegistry
import com.returngift.agent.TaskOrchestrator
import com.returngift.agent.channel.Channel
import com.returngift.agent.service.ForegroundService
import com.returngift.agent.utils.AppLogStore
import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog
import com.returngift.agent.agent.llm.EngineHolder
import com.blankj.utilcode.util.NetworkUtils

/**
 * Application entry point
 */

val appViewModel: AppViewModel by lazy { ClawApplication.appViewModelInstance }
class ClawApplication : BaseApp() {

    companion object {
        private const val TAG = "ClawApplication"
        lateinit var instance: ClawApplication
            private set
        lateinit var appViewModelInstance: AppViewModel
    }

    override fun onCreate() {
        super.onCreate()
        AppCapabilityCoordinator.markProcessStart()
        instance = this
        AppLogStore.init(this)
        com.returngift.agent.utils.AppUiState.registerIn(this)
        XLog.setDEBUG(BuildConfig.DEBUG)
        registerNetworkCallback()
        appViewModelInstance = getAppViewModelProvider()[AppViewModel::class.java]
        KVUtils.init(this)
        // C4: process-death reconciliation — if a previous task was interrupted by app restart,
        // clear the state and notify the channel.
        val activeTask = KVUtils.getString(TaskOrchestrator.KEY_ACTIVE_TASK, "")
        if (activeTask.isNotEmpty()) {
            val parts = activeTask.split('|', limit = 2)
            val interruptedMessageId = parts[0]
            val interruptedChannel = if (parts.size > 1) parts[1] else Channel.LOCAL.name
            ForegroundService.resetToIdle(this)
            ChannelManager.sendMessage(Channel.valueOf(interruptedChannel), "task was interrupted by app restart — any queued (in-memory) task was dropped on restart", interruptedMessageId)
            KVUtils.remove(TaskOrchestrator.KEY_ACTIVE_TASK)
        }
        LocalBackendHealth.recoverPendingGpuCrashIfNeeded()
        ToolRegistry.getInstance().registerAllTools(ToolRegistry.DeviceType.MOBILE)
        com.returngift.agent.agent.skill.SkillRegistry.loadBuiltInSkills()
        // C2 fix: loadYamlSkills() was never called, so all 19 YAML-defined skills
        // (skill_library/skills/*.yaml) silently never entered the registry and the
        // router fell through to the LLM agent loop for everything they should have
        // handled. YAML skills with an id matching a built-in override the built-in.
        com.returngift.agent.agent.skill.SkillRegistry.loadYamlSkills(this)
        com.returngift.agent.agent.PlaybookManager.loadAll(this)
        XLog.e(TAG, "ClawApplication initialized, tools registered: ${ToolRegistry.getInstance().getAllTools().size}, skills registered: ${com.returngift.agent.agent.skill.SkillRegistry.getAll().size}")

        // W8: Register package broadcast receiver for app catalog invalidation
        registerPackageBroadcastReceiver()

        // Auto-start config server if enabled
        ConfigServerManager.autoStartIfNeeded(this)

        // Auto-start cloud deep agent service if enabled
        CloudDeepAgentManager.autoStartIfNeeded(this)

        // Write network logs to file (set to true when debugging)
        DefaultAgentService.FILE_LOGGING_ENABLED = BuildConfig.DEBUG
        DefaultAgentService.FILE_LOGGING_CACHE_DIR = cacheDir

        // Lightweight initialization (main thread)
        appViewModelInstance.initCommon()
        Thread({
            try {
                android.util.Log.e("RETURNGIFT_INIT", "app-async-init thread STARTED")
                val hasConfig = KVUtils.hasLlmConfig()
                android.util.Log.e("RETURNGIFT_INIT", "app-async-init: hasLlmConfig=$hasConfig, canDrawOverlays=${android.provider.Settings.canDrawOverlays(instance)}")
                if (hasConfig) {
                    appViewModelInstance.initAgent()
                    appViewModelInstance.afterInit()
                }
            } catch (e: Exception) {
                android.util.Log.e("RETURNGIFT_INIT", "app-async-init CRASHED: ${e.message}", e)
            }
        }, "app-async-init").start()
    }
    
    private fun registerPackageBroadcastReceiver() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                val action = intent.action
                val pkg = intent.getStringExtra("android.intent.extra.PACKAGE_NAME")
                if (pkg != null && (action == android.content.Intent.ACTION_PACKAGE_ADDED
                        || action == android.content.Intent.ACTION_PACKAGE_REMOVED
                        || action == android.content.Intent.ACTION_PACKAGE_REPLACED
                        || action == android.content.Intent.ACTION_PACKAGE_CHANGED)) {
                    XLog.d(TAG, "Package event: $action for $pkg — invalidating app catalog")
                    AppCatalog.getInstance(context).invalidatePackage(pkg)
                    // Rebuild catalog to include new/updated apps
                    if (action == android.content.Intent.ACTION_PACKAGE_ADDED
                            || action == android.content.Intent.ACTION_PACKAGE_REPLACED) {
                        AppCatalog.getInstance(context).maybeBuildOnFirstConnect(context)
                    }
                }
            }
        }
        val filter = android.content.IntentFilter()
        filter.addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
        filter.addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
        filter.addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
        filter.addAction(android.content.Intent.ACTION_PACKAGE_CHANGED)
        filter.addDataScheme("package")
        registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
    }

    private var networkListener: NetworkUtils.OnNetworkStatusChangedListener? = null

    /**
     * Listen for network recovery and automatically re-initialize channels.
     * Fixes channel initialization failures when booting with no network, and reconnects channels after network outages.
     */
    private fun registerNetworkCallback() {
        networkListener = object : NetworkUtils.OnNetworkStatusChangedListener {
            override fun onConnected(networkType: NetworkUtils.NetworkType?) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (KVUtils.hasLlmConfig()) {
                        XLog.i(TAG, "Network recovered (${networkType?.name}), checking and reconnecting dropped channels")
                        ChannelManager.reconnectIfNeeded()
                    }
                }, 2000)
            }

            override fun onDisconnected() {
                XLog.w(TAG, "Network disconnected")
            }
        }
        NetworkUtils.registerNetworkStatusChangedListener(networkListener)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        EngineHolder.onTrimMemory(level)
    }
}
