package com.example.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NetworkState(val label: String) {
    ONLINE_UNMETERED("Online (Wi-Fi / Unmetered)"),
    ONLINE_METERED("Online (Cellular / Metered)"),
    OFFLINE("Offline (No Network)"),
}

class NetworkMonitor(
    context: Context,
    private val stateOverride: (() -> NetworkState)? = null,
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _networkState = MutableStateFlow(NetworkState.OFFLINE)
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _networkState.value = readCurrentState()
        }

        override fun onLost(network: Network) {
            _networkState.value = readCurrentState()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            _networkState.value = stateOverride?.invoke() ?: evaluateCapabilities(networkCapabilities)
        }
    }

    init {
        _networkState.value = readCurrentState()
        if (stateOverride == null) {
            try {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager?.registerNetworkCallback(request, callback)
            } catch (_: Exception) {
                _networkState.value = readCurrentState()
            }
        }
    }

    fun refreshNow(): NetworkState {
        val state = readCurrentState()
        _networkState.value = state
        return state
    }

    fun unregister() {
        if (stateOverride != null) return
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // Safe ignore if already unregistered
        }
    }

    private fun readCurrentState(): NetworkState {
        stateOverride?.let { return it.invoke() }
        val cm = connectivityManager ?: return NetworkState.OFFLINE
        val activeNetwork = cm.activeNetwork ?: return NetworkState.OFFLINE
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkState.OFFLINE
        return evaluateCapabilities(caps)
    }

    companion object {
        fun evaluateCapabilities(caps: NetworkCapabilities?): NetworkState {
            if (caps == null) return NetworkState.OFFLINE
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (!hasInternet) return NetworkState.OFFLINE
            return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                NetworkState.ONLINE_UNMETERED
            } else {
                NetworkState.ONLINE_METERED
            }
        }
    }
}
