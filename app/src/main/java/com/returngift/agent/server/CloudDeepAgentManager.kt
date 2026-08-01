// Copyright 2026 ReturnGift Project. All rights reserved.
// Licensed under the Apache License, Version 2.0.

package com.returngift.agent.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.returngift.agent.utils.KVUtils
import com.returngift.agent.utils.XLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * CloudDeepAgentService lifecycle management singleton.
 */
object CloudDeepAgentManager {

    private const val TAG = "CloudDeepAgentManager"
    private const val MAX_PORT_RETRY = 10

    @Volatile
    private var server: CloudDeepAgentService? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var appContext: Context? = null

    /** Notification emitted when server state changes. */
    private val _stateChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val stateChanged: SharedFlow<Unit> = _stateChanged.asSharedFlow()

    fun notifyStateChanged() {
        _stateChanged.tryEmit(Unit)
    }

    /**
     * Start the Cloud Deep Agent server. Requires a WiFi connection.
     */
    fun start(context: Context): Boolean {
        val ctx = context.applicationContext
        appContext = ctx

        if (!isWifiConnected(ctx)) {
            XLog.e(TAG, "Cannot start CloudDeepAgentService: WiFi not connected")
            return false
        }

        if (isRunning()) return true

        for (port in CloudDeepAgentService.PORT until CloudDeepAgentService.PORT + MAX_PORT_RETRY) {
            try {
                val s = CloudDeepAgentService(ctx, port)
                s.start()
                server = s
                XLog.i(TAG, "CloudDeepAgentService started on port $port")
                registerNetworkCallback(ctx)
                notifyStateChanged()
                return true
            } catch (e: Exception) {
                XLog.e(TAG, "Port $port unavailable: ${e.message}")
            }
        }
        XLog.e(TAG, "Failed to start CloudDeepAgentService: all ports ${CloudDeepAgentService.PORT}-${CloudDeepAgentService.PORT + MAX_PORT_RETRY - 1} unavailable")
        return false
    }

    fun stop() {
        unregisterNetworkCallback()
        try {
            server?.stop()
        } catch (e: Exception) {
            XLog.e(TAG, "Error stopping CloudDeepAgentService: ${e.message}")
        }
        server = null
        XLog.i(TAG, "CloudDeepAgentService stopped")
        notifyStateChanged()
    }

    fun isRunning(): Boolean = server?.isAlive == true

    /**
     * Get the LAN access address, e.g. 192.168.1.100:9528
     */
    fun getAddress(): String? {
        val ip = getWifiIpAddress(appContext ?: return null) ?: return null
        val port = server?.listeningPort ?: return null
        return "$ip:$port"
    }

    /**
     * Call on app start: auto-starts if it was enabled last time.
     */
    fun autoStartIfNeeded(context: Context) {
        if (KVUtils.isCloudDeepAgentEnabled()) {
            start(context)
        }
    }

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun getWifiIpAddress(context: Context): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val ipInt = wifiInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                val ip = String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
                if (ip != "0.0.0.0") return ip
            }
        } catch (e: Exception) {
            XLog.e(TAG, "WifiManager IP failed: ${e.message}")
        }
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        } catch (e: Exception) {
            XLog.e(TAG, "NetworkInterface IP failed: ${e.message}")
            null
        }
    }

    private fun registerNetworkCallback(context: Context) {
        unregisterNetworkCallback()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                XLog.i(TAG, "WiFi lost, stopping CloudDeepAgentService")
                try { server?.stop() } catch (_: Exception) {}
                server = null
                notifyStateChanged()
            }

            override fun onAvailable(network: Network) {
                XLog.i(TAG, "WiFi available, restarting CloudDeepAgentService")
                if (KVUtils.isCloudDeepAgentEnabled() && !isRunning()) {
                    val ctx = appContext ?: return
                    for (port in CloudDeepAgentService.PORT until CloudDeepAgentService.PORT + MAX_PORT_RETRY) {
                        try {
                            val s = CloudDeepAgentService(ctx, port)
                            s.start()
                            server = s
                            XLog.i(TAG, "CloudDeepAgentService restarted on port $port")
                            break
                        } catch (e: Exception) {
                            XLog.e(TAG, "Port $port unavailable on restart: ${e.message}")
                        }
                    }
                    notifyStateChanged()
                }
            }
        }

        cm.registerNetworkCallback(request, callback)
        networkCallback = callback
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        try {
            val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to unregister network callback: ${e.message}")
        }
        networkCallback = null
    }
}