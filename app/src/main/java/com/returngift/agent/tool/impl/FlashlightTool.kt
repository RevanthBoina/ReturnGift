// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.tool.impl

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.returngift.agent.tool.BaseTool
import com.returngift.agent.tool.ToolParameter
import com.returngift.agent.tool.ToolResult
import com.returngift.agent.utils.XLog

/**
 * Tier-1 flashlight control. Toggles the device torch via [CameraManager.setTorchMode].
 *
 * Carries the torch-toggle behavior formerly in the dead TaskShortcuts.setTorch block, now
 * as a live tool so the deterministic Tier-1 layer has a registered execution target. Params:
 *   - "on": true|false to force state; omitted/absent → toggle (remembered internal state).
 *
 * Failure modes (no camera with a flash, CameraAccessException) surface as an honest
 * ToolResult.error — never a silent no-op.
 */
class FlashlightTool : BaseTool() {

    override fun getName() = "flashlight"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "on",
            "boolean",
            "Optional: true to turn ON, false to turn OFF. Omit to toggle remembered state.",
            false
        )
    )

    override fun execute(params: @JvmSuppressWildcards Map<String, Any>): ToolResult {
        val context = com.returngift.agent.ClawApplication.instance as? Context
        if (context == null) return ToolResult.error("App context not available")
        val on = params["on"]?.let { it.toString().toBooleanStrictOrNull() } ?: !torchEnabled
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId == null) {
                XLog.w(TAG, "Flashlight: no camera with flash")
                return ToolResult.error("This device has no flashlight.")
            }
            cameraManager.setTorchMode(cameraId, on)
            torchEnabled = on
            XLog.i(TAG, "Flashlight ${if (on) "on" else "off"}")
            ToolResult.success("Flashlight ${if (on) "on" else "off"}")
        } catch (e: Exception) {
            XLog.e(TAG, "Flashlight failed", e)
            ToolResult.error("Could not control flashlight: ${e.message}")
        }
    }

    override fun getDescriptionEN() =
        "Turn the device flashlight (torch) on or off. Param 'on' true/false, or omit to toggle."

    override fun getDescriptionCN() =
        "Turn the device flashlight on or off. Param 'on' true/false, or omit to toggle."

    companion object {
        private const val TAG = "FlashlightTool"
        private var torchEnabled = false
    }
}