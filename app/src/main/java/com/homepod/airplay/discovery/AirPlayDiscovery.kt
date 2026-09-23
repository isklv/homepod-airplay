package com.homepod.airplay.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.homepod.airplay.data.model.AirPlayDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class AirPlayDiscovery(private val context: Context) {
    companion object {
        private const val TAG = "AirPlayDiscovery"
        private const val SERVICE_TYPE_RAOP = "_raop._tcp."
        private const val SERVICE_TYPE_AIRPLAY = "_airplay._tcp."
        private const val PREFS_NAME = "homepod_discovery_cache"
        private const val PREF_KEY_DEVICES = "cached_devices"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var countdownJob: Job? = null

    private val _discoveredDevices = MutableStateFlow<List<AirPlayDevice>>(loadDevicesFromCache())
    val discoveredDevices: StateFlow<List<AirPlayDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanRemainingSeconds = MutableStateFlow(0)
    val scanRemainingSeconds: StateFlow<Int> = _scanRemainingSeconds.asStateFlow()

    private var discoveryListenerRaop: NsdManager.DiscoveryListener? = null
    private var discoveryListenerAirplay: NsdManager.DiscoveryListener? = null

    fun startDiscovery(timeoutSeconds: Int = 10) {
        if (_isScanning.value) {
            restartCountdown(timeoutSeconds)
            return
        }

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

        discoveryListenerRaop = createDiscoveryListener()
        discoveryListenerAirplay = createDiscoveryListener()

        try {
            nsdManager.discoverServices(SERVICE_TYPE_RAOP, NsdManager.PROTOCOL_DNS_SD, discoveryListenerRaop)
            nsdManager.discoverServices(SERVICE_TYPE_AIRPLAY, NsdManager.PROTOCOL_DNS_SD, discoveryListenerAirplay)
            Log.d(TAG, "mDNS discovery started for RAOP and AirPlay (${timeoutSeconds}s timer)")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service discovery: ${e.message}", e)
            stopDiscovery()
            return
        }

        restartCountdown(timeoutSeconds)
    }

    private fun restartCountdown(timeoutSeconds: Int) {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            for (sec in timeoutSeconds downTo 1) {
                _scanRemainingSeconds.value = sec
                delay(1000L)
            }
            _scanRemainingSeconds.value = 0
            Log.d(TAG, "Scan timeout (${timeoutSeconds}s) reached, automatically stopping discovery")
            stopDiscovery()
        }
    }

    fun stopDiscovery() {
        countdownJob?.cancel()
        countdownJob = null
        _scanRemainingSeconds.value = 0

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
        Log.d(TAG, "mDNS discovery stopped and multicast lock released")
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
            val updated = if (current.any { it.ip == ip }) current else current + device
            saveDevicesToCache(updated)
            updated
        }
    }

    fun removeDevice(device: AirPlayDevice) {
        _discoveredDevices.update { current ->
            val updated = current.filterNot { it.id == device.id || it.ip == device.ip }
            saveDevicesToCache(updated)
            updated
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
                        val updatedList = if (existingIndex >= 0) {
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
                        saveDevicesToCache(updatedList)
                        updatedList
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Exception resolving service: ${e.message}")
        }
    }

    private fun saveDevicesToCache(devices: List<AirPlayDevice>) {
        try {
            val jsonArray = JSONArray()
            for (dev in devices) {
                val obj = JSONObject().apply {
                    put("id", dev.id)
                    put("name", dev.name)
                    put("ip", dev.ip)
                    put("port", dev.port)
                    put("model", dev.model)
                    put("isHomePod", dev.isHomePod)
                }
                jsonArray.put(obj)
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_KEY_DEVICES, jsonArray.toString())
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache devices: ${e.message}")
        }
    }

    private fun loadDevicesFromCache(): List<AirPlayDevice> {
        try {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_KEY_DEVICES, null) ?: return emptyList()
            val jsonArray = JSONArray(raw)
            val list = mutableListOf<AirPlayDevice>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    AirPlayDevice(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        ip = obj.getString("ip"),
                        port = obj.optInt("port", 5000),
                        model = obj.optString("model", "HomePod"),
                        isHomePod = obj.optBoolean("isHomePod", true)
                    )
                )
            }
            return list
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached devices: ${e.message}")
            return emptyList()
        }
    }
}
