package app.smradio.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler

/** Thin wrapper over ConnectivityManager (needs ACCESS_NETWORK_STATE). */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private fun caps(): NetworkCapabilities? = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }

    val isOnline: Boolean
        get() = caps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    val isCellular: Boolean
        get() = caps()?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } == true

    /** System estimate of downstream speed, 0 = unknown. */
    val downKbps: Int get() = caps()?.linkDownstreamBandwidthKbps ?: 0

    /** [onChange] runs on [handler]'s thread each time the default network (re)appears or changes. */
    fun listen(handler: Handler, onChange: () -> Unit): ConnectivityManager.NetworkCallback {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onChange()
            }
        }
        cm.registerDefaultNetworkCallback(callback, handler)
        return callback
    }

    fun stopListening(callback: ConnectivityManager.NetworkCallback) {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }
}
