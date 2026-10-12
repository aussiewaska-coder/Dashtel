package dev.dialer.voice.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetworkStatus(
    val available: Boolean = false,
    val validated: Boolean = false,
    val transport: String = "No network",
) {
    val label: String
        get() = when {
            !available -> "No network"
            validated -> transport
            else -> "$transport · internet not validated"
        }
}

/** Observes the active Android network; Twilio itself owns media reconnection. */
object NetworkMonitor {

    private val _status = MutableStateFlow(NetworkStatus())
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    fun init(context: Context) {
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        update(manager, manager.activeNetwork)
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update(manager, network)
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                _status.value = capabilities.toStatus()
            }
            override fun onLost(network: Network) = update(manager, manager.activeNetwork)
        })
    }

    private fun update(manager: ConnectivityManager, network: Network?) {
        _status.value = network?.let(manager::getNetworkCapabilities)?.toStatus() ?: NetworkStatus()
    }

    private fun NetworkCapabilities.toStatus(): NetworkStatus {
        val transport = when {
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Network"
        }
        return NetworkStatus(
            available = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            transport = transport,
        )
    }
}
