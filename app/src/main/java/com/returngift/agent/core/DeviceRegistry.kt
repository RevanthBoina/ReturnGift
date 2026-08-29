// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.core

import com.returngift.agent.utils.XLog
import java.util.concurrent.ConcurrentHashMap

/**
 * P3.2: In-memory registry of companion devices (second body).
 *
 * Devices are discovered via mDNS over the existing LAN infrastructure.  The companion
 * presents the SAME pairing token as the phone (P0.2 pairing token — no new auth surface).
 * This registry is observable: callers register a listener and are notified when devices
 * are added, removed, or updated.
 *
 * Thread-safe singleton.  Companion devices are:
 * - id: unique identifier (e.g. device MAC or mDNS service name)
 * - name: human-readable name (e.g. "Living Room TV")
 * - address: IP:port of the companion's "hands" endpoint
 * - deviceType: TV, WATCH, TABLET, etc.
 * - capabilities: fingerprint of what the companion can do (set by the companion on connect)
 */
object DeviceRegistry {

    private const val TAG = "DeviceRegistry"

    enum class DeviceType {
        TV, WATCH, TABLET, SPEAKER, UNKNOWN
    }

    data class CompanionDevice(
        val id: String,
        val name: String,
        val address: String,           // "192.168.1.100:8080"
        val deviceType: DeviceType = DeviceType.UNKNOWN,
        val capabilities: Set<String> = emptySet(), // e.g. setOf("dpad", "volume", "power")
        val pairingToken: String = "", // Same pairing token as the phone — token-gated handshake
        val lastSeenMs: Long = System.currentTimeMillis()
    )

    interface DeviceChangeListener {
        fun onDeviceAdded(device: CompanionDevice)
        fun onDeviceRemoved(deviceId: String)
        fun onDeviceUpdated(device: CompanionDevice)
    }

    private val devices = ConcurrentHashMap<String, CompanionDevice>()
    private val listeners = ConcurrentHashMap.newKeySet<DeviceChangeListener>()

    /**
     * Register a listener for device change events.
     * The listener is called on the caller's thread.
     */
    fun addListener(listener: DeviceChangeListener) {
        listeners.add(listener)
        XLog.d(TAG, "Listener registered (total: ${listeners.size})")
    }

    /**
     * Unregister a previously registered listener.
     */
    fun removeListener(listener: DeviceChangeListener) {
        listeners.remove(listener)
        XLog.d(TAG, "Listener unregistered (remaining: ${listeners.size})")
    }

    /**
     * Get a snapshot of all registered companion devices.
     */
    fun getAllDevices(): List<CompanionDevice> = devices.values.toList()

    /**
     * Get a specific device by its id.
     */
    fun getDevice(id: String): CompanionDevice? = devices[id]

    /**
     * Register (or update) a companion device.
     * If the device is new, all listeners are notified via onDeviceAdded.
     * If the device already exists, listeners are notified via onDeviceUpdated.
     */
    fun registerDevice(device: CompanionDevice) {
        val existing = devices.put(device.id, device)
        if (existing == null) {
            XLog.i(TAG, "Companion device registered: ${device.name} (${device.id}) at ${device.address}")
            notifyListeners { it.onDeviceAdded(device) }
        } else {
            XLog.i(TAG, "Companion device updated: ${device.name} (${device.id})")
            notifyListeners { it.onDeviceUpdated(device) }
        }
    }

    /**
     * Remove a companion device by its id.
     * All listeners are notified via onDeviceRemoved.
     */
    fun removeDevice(id: String) {
        val removed = devices.remove(id)
        if (removed != null) {
            XLog.i(TAG, "Companion device removed: ${removed.name} (${removed.id})")
            notifyListeners { it.onDeviceRemoved(id) }
        }
    }

    /**
     * Prune devices that have not been seen in [ttlMs] milliseconds.
     * Called periodically to keep the registry clean.
     */
    fun pruneStale(ttlMs: Long = 5 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - ttlMs
        val staleIds = devices.entries
            .filter { it.value.lastSeenMs < cutoff }
            .map { it.key }
        staleIds.forEach { removeDevice(it) }
        if (staleIds.isNotEmpty()) {
            XLog.d(TAG, "Pruned ${staleIds.size} stale companion devices")
        }
    }

    /**
     * Clear all devices and notify all listeners.
     * Used when the phone disconnects from the network or user requests a reset.
     */
    fun clearAll() {
        val ids = devices.keys().toList()
        devices.clear()
        ids.forEach { id ->
            notifyListeners { it.onDeviceRemoved(id) }
        }
        XLog.i(TAG, "Device registry cleared (removed ${ids.size} devices)")
    }

    private inline fun notifyListeners(action: (DeviceChangeListener) -> Unit) {
        listeners.forEach { listener ->
            runCatching { action(listener) }
                .onFailure { XLog.w(TAG, "Listener callback failed: ${it.message}") }
        }
    }
}
