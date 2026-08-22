// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool

import com.returngift.agent.agent.knowledge.*
import com.returngift.agent.tool.impl.*
import com.returngift.agent.tool.impl.mobile.*
import com.returngift.agent.tool.impl.tv.*

object ToolRegistry {

    enum class DeviceType { TV, MOBILE }

    private val tools = LinkedHashMap<String, BaseTool>()
    var deviceType: DeviceType = DeviceType.TV
        private set

    @JvmStatic
    fun getInstance(): ToolRegistry = this

    fun registerAllTools(type: DeviceType = DeviceType.TV) {
        deviceType = type
        tools.clear()
        registerCommonTools()
        when (type) {
            DeviceType.TV -> registerTvTools()
            DeviceType.MOBILE -> registerMobileTools()
        }
    }

    private fun registerCommonTools() {
        register(GetScreenInfoTool())
        register(InputTextTool())
        register(SystemKeyTool())
        register(OpenAppTool())
        register(SwitchAppTool())
        register(GetInstalledAppsTool())
        register(GetForegroundAppTool())
        register(TakeScreenshotTool())
        register(WaitTool())
        register(ClipboardTool())
        register(SendFileTool())
        register(GetDeviceInfoTool())
        register(GetNotificationsTool())
        register(MakeCallTool())
        register(FinishTool())
        register(AskUserTool())
        register(WebFetchTool())
        register(WebSearchTool())
        register(ImportDownloadTool())
        // Knowledge Base tools — shared vault available in all modes
        register(KbWriteTool())
        register(KbReadTool())
        register(KbSearchTool())
        register(KbAppendTool())
        register(KbAddTodoTool())
        register(KbSaveFileTool())
        register(KbDeleteTool())
    }

    private fun registerTvTools() {
        register(DpadUpTool())
        register(DpadDownTool())
        register(DpadLeftTool())
        register(DpadRightTool())
        register(DpadCenterTool())
        register(VolumeUpTool())
        register(VolumeDownTool())
        register(PressMenuTool())
        register(PressPowerTool())
    }

    private fun registerMobileTools() {
        register(TapTool())
        register(TapNodeTool())
        register(FindAndTapTool())
        register(LongPressTool())
        register(SwipeTool())
        register(ScrollToFindTool())
        register(SendMessageTool())
        register(SearchAppInStoreTool())
    }

    fun register(tool: BaseTool) {
        tools[tool.getName()] = tool
    }

    fun getTool(name: String): BaseTool? = tools[name]

    fun getDisplayName(name: String): String = tools[name]?.getDisplayName() ?: name

    fun getAllTools(): List<BaseTool> = tools.values.toList()

    fun executeTool(name: String, params: Map<String, Any>): ToolResult {
        val tool = tools[name] ?: return ToolResult.error("Unknown tool: $name")

        // C3 fix: SafetyInterceptor existed but had zero call sites anywhere in the
        // codebase, so blocklist_patterns, risk_tier confirmation, and never_retry_after
        // checkpoints from the YAML skill specs were never enforced. This is the single
        // choke point every execution path (skills, agent loop, debug receivers, config
        // server) routes through, so wiring it in here covers all of them.
        //
        // Prefer the foreground Activity for the confirmation dialog when one exists;
        // fall back to the Application context otherwise (SafetyInterceptor already
        // fails closed — denies — if no dialog can be shown).
        val dialogContext = com.blankj.utilcode.util.ActivityUtils.getTopActivity()
            ?: com.returngift.agent.ClawApplication.instance
        val blockReason = com.returngift.agent.agent.SafetyInterceptor.check(name, params, dialogContext)
        if (blockReason != null) {
            com.returngift.agent.utils.XLog.w("ToolRegistry", "Blocked '$name': $blockReason")
            return ToolResult.error(blockReason)
        }

        return try {
            tool.executeWithWaitAfter(params)
        } catch (e: Exception) {
            com.returngift.agent.utils.XLog.e("ToolRegistry", "Tool '$name' execution failed with params=$params", e)
            ToolResult.error("Tool execution failed: ${e.message}")
        }
    }
}
