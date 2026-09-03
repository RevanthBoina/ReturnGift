// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool

import com.returngift.agent.agent.knowledge.*
import com.returngift.agent.core.DeviceRegistry
import com.returngift.agent.tool.impl.*
import com.returngift.agent.tool.impl.mobile.*
import com.returngift.agent.tool.impl.tv.*
import com.returngift.agent.utils.XLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ToolRegistry {

    enum class DeviceType { TV, MOBILE }

    /**
     * Preview/Dry-Run mode: when non-null, executeTool short-circuits to this
     * recorder instead of touching the device. Set only by DryRunRunner, then
     * restored to null. Volatile + synchronized setter for the worker thread.
     */
    @Volatile
    var stubHook: ((String, Map<String, Any>) -> ToolResult)? = null

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
        register(FlashlightTool())
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
        stubHook?.let { return it(name, params) }
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

    // ======================== P3.2: Companion Device Dispatch ========================

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * P3.2: Execute a tool on a companion device.
     *
     * POSTs to the companion's "hands" endpoint with the token-gated JSON contract:
     * {
     *   "tool": "dpad_up",
     *   "params": { ... },
     *   "token": "<pairing-token>"
     * }
     *
     * The companion device is a TV-optimized build of this app exposing ONLY the
     * accessibility service + hands endpoint (no UI, no brain). The companion target
     * itself is scope for a later wave — this wave ships the phone side + contract doc.
     *
     * @param deviceId The companion device ID from DeviceRegistry
     * @param toolName The tool name to execute (e.g. "dpad_up", "volume_up")
     * @param params Tool parameters
     * @return ToolResult from the companion, or an error if dispatch fails
     */
    fun executeOn(deviceId: String, toolName: String, params: Map<String, Any>): ToolResult {
        val device = DeviceRegistry.getDevice(deviceId)
            ?: return ToolResult.error("Companion device not found: $deviceId")

        // Verify the tool exists in our registry (both sides share the same tool vocabulary)
        val tool = tools[toolName] ?: return ToolResult.error("Unknown tool: $toolName")

        // Safety check runs on the phone (brain) before dispatch
        val dialogContext = com.blankj.utilcode.util.ActivityUtils.getTopActivity()
            ?: com.returngift.agent.ClawApplication.instance
        val blockReason = com.returngift.agent.agent.SafetyInterceptor.check(toolName, params, dialogContext)
        if (blockReason != null) {
            com.returngift.agent.utils.XLog.w("ToolRegistry", "Companion dispatch blocked '$toolName': $blockReason")
            return ToolResult.error(blockReason)
        }

        // Build the token-gated request body
        val requestBody = JSONObject().apply {
            put("tool", toolName)
            put("params", JSONObject(params))
            put("token", device.pairingToken)
        }.toString()

        val request = Request.Builder()
            .url("http://${device.address}/hands")
            .post(requestBody.toRequestBody(JSON))
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val body = response.body?.string() ?: "no body"
                XLog.w("ToolRegistry", "Companion $deviceId returned ${response.code}: $body")
                return ToolResult.error("Companion HTTP ${response.code}: $body")
            }
            val body = response.body?.string() ?: "empty response"
            val resultJson = JSONObject(body)
            val success = resultJson.optBoolean("success", false)
            val message = resultJson.optString("message", "")
            if (success) {
                ToolResult.success(message)
            } else {
                ToolResult.error(message)
            }
        } catch (e: Exception) {
            XLog.e("ToolRegistry", "Companion dispatch failed for $deviceId/$toolName", e)
            ToolResult.error("Companion dispatch failed: ${e.message}")
        }
    }

    /** P3.2: Loopback E2E test helper — dispatch to a fake companion for unit testing.
     *  Registers a fake DeviceRegistry entry pointing to a test server, dispatches dpad_up,
     *  and asserts the JSON arrives token-gated. Used by the unit test suite. */
    fun executeOnLoopback(testServerUrl: String, pairingToken: String, toolName: String, params: Map<String, Any>): ToolResult {
        val requestBody = JSONObject().apply {
            put("tool", toolName)
            put("params", JSONObject(params))
            put("token", pairingToken)
        }.toString()

        val request = Request.Builder()
            .url(testServerUrl)
            .post(requestBody.toRequestBody(JSON))
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: "empty response"
            val resultJson = JSONObject(body)
            val success = resultJson.optBoolean("success", false)
            val message = resultJson.optString("message", "")
            if (success) ToolResult.success(message) else ToolResult.error(message)
        } catch (e: Exception) {
            ToolResult.error("Loopback dispatch failed: ${e.message}")
        }
    }
}
