package com.homepod.airplay.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.homepod.airplay.data.model.AirPlayDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AirPlayDiscovery(private val context: Context) {
    companion object {
        private const val TAG = "AirPlayDiscovery"
        private const val SERVICE_TYPE_RAOP = "_raop._tcp."
        private const val SERVICE_TYPE_AIRPLAY = "_airplay._tcp."
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _discoveredDevices = MutableStateFlow<List<AirPlayDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<AirPlayDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var discoveryListenerRaop: NsdManager.DiscoveryListener? = null
    private var discoveryListenerAirplay: NsdManager.DiscoveryListener? = null

    fun startDiscovery() {
        if (_isScanning.value) return

        try {
            // Acquire Multicast lock to allow mDNS packets on Android Wi-Fi
            multicastLock = wifiManager.createMulticastLock("HomePodAirPlayMulticastLock").apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire multicast lock: ${e.message}")
        }

        _isScanning.value = true
        _discoveredDevices.value = emptyList()

        discoveryListenerRaop = createDiscoveryListener()
        discoveryListenerAirplay = createDiscoveryListener()

        try {
            nsdManager.discoverServices(SERVICE_TYPE_RAOP, NsdManager.PROTOCOL_DNS_SD, discoveryListenerRaop)
            nsdManager.discoverServices(SERVICE_TYPE_AIRPLAY, NsdManager.PROTOCOL_DNS_SD, discoveryListenerAirplay)
            Log.d(TAG, "mDNS discovery started for RAOP and AirPlay")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service discovery: ${e.message}", e)
            _isScanning.value = false
        }
    }

    fun stopDiscovery() {
        if (!_isScanning.value) return

        try {
            discoveryListenerRaop?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping RAOP discovery: ${e.message}")
        }

        try {
            discoveryListenerAirplay?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AirPlay discovery: ${e.message}")
        }

        discoveryListenerRaop = null
        discoveryListenerAirplay = null
        _isScanning.value = false

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        multicastLock = null
    }

    fun addManualDevice(name: String, ip: String, port: Int = 5000) {
        val device = AirPlayDevice(
            id = "manual_$ip:$port",
            name = name.ifBlank { "HomePod ($ip)" },
            ip = ip,
            port = port,
            model = "HomePod mini",
            isHomePod = true
        )
        _discoveredDevices.update { current ->
            if (current.any { it.ip == ip }) current else current + device
        }
    }

    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
                resolveServiceWithRetry(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                _discoveredDevices.update { list ->
                    list.filterNot { it.id == serviceInfo.serviceName }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }
        }
    }

    private fun resolveServiceWithRetry(serviceInfo: NsdServiceInfo) {
        try {
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: code $errorCode")
                }

                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    val hostAddress = resolvedInfo.host?.hostAddress ?: return
                    val port = resolvedInfo.port
                    val rawName = resolvedInfo.serviceName

                    // Format: MAC@Speaker Name for RAOP, or Speaker Name for AirPlay
                    val displayName = if (rawName.contains("@")) {
                        rawName.substringAfter("@")
                    } else {
                        rawName
                    }

                    val attributes = resolvedInfo.attributes ?: emptyMap()
                    val model = attributes["model"]?.let { String(it) } ?: "HomePod"
                    val isHomePod = model.contains("AudioAccessory", ignoreCase = true) ||
                            displayName.contains("HomePod", ignoreCase = true)

                    val isRaop = rawName.contains("@")
                    val effectivePort = if (isRaop && port > 0) {
                        port
                    } else if (port == 7000 || port <= 0) {
                        5000
                    } else {
                        port
                    }

                    val device = AirPlayDevice(
                        id = rawName,
                        name = displayName,
                        ip = hostAddress,
                        port = effectivePort,
                        model = model,
                        isHomePod = isHomePod
                    )

                    Log.d(TAG, "Resolved AirPlay Device: $device")

                    _discoveredDevices.update { current ->
                        val existingIndex = current.indexOfFirst { it.ip == device.ip }
                        if (existingIndex >= 0) {
                            val existing = current[existingIndex]
                            // If existing device already has RAOP id (with @) or port 5000, preserve it
                            val updated = if (!isRaop && existing.port == 5000) {
                                existing.copy(model = if (model != "HomePod") model else existing.model)
                            } else {
                                device
                            }
                            current.toMutableList().apply { set(existingIndex, updated) }
                        } else {
                            current + device
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Exception resolving service: ${e.message}")
        }
    }
}
