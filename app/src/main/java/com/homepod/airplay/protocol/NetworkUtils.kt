package com.homepod.airplay.protocol

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.net.DatagramSocket
import java.net.Socket

object NetworkUtils {
    private const val TAG = "NetworkUtils"

    fun getWifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            val isWifiOrEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            val isNotVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            if (isWifiOrEthernet && isNotVpn) {
                return net
            }
        }
        return null
    }

    fun bindSocketToWifi(network: Network?, socket: Socket?) {
        if (network == null || socket == null) return
        try {
            network.bindSocket(socket)
            Log.d(TAG, "Successfully bound TCP socket to Wi-Fi network (bypassing VPN)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bind TCP socket to Wi-Fi: ${e.message}")
        }
    }

    fun bindSocketToWifi(network: Network?, socket: DatagramSocket?) {
        if (network == null || socket == null) return
        try {
            network.bindSocket(socket)
            Log.d(TAG, "Successfully bound UDP socket to Wi-Fi network (bypassing VPN)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bind UDP socket to Wi-Fi: ${e.message}")
        }
    }

    fun bindProcessToWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val wifiNet = getWifiNetwork(context) ?: return false
        return try {
            val success = cm.bindProcessToNetwork(wifiNet)
            Log.d(TAG, "bindProcessToNetwork($wifiNet) result: $success")
            success
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bindProcessToNetwork: ${e.message}")
            false
        }
    }

    fun unbindProcessFromWifi(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            cm.bindProcessToNetwork(null)
        } catch (_: Exception) {}
    }

    fun getWifiIpAddress(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val wifiNet = getWifiNetwork(context) ?: return null
        val linkProps = cm.getLinkProperties(wifiNet) ?: return null
        for (linkAddr in linkProps.linkAddresses) {
            val addr = linkAddr.address
            if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                return addr.hostAddress
            }
        }
        return null
    }

    fun isVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNet = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNet) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }
}
