package com.bestiapop.android.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class ConnectivityObserver(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val isOnline: Flow<Boolean> = callbackFlow {
        trySend(isCurrentlyOnline())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isCurrentlyOnline())
            }

            override fun onLost(network: Network) {
                trySend(isCurrentlyOnline())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val overridden = testOverrides?.currentlyOnline
                trySend(
                    overridden ?: (
                        networkCapabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_INTERNET
                        ) &&
                            networkCapabilities.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_VALIDATED
                            )
                        )
                )
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)
        awaitClose {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()

    fun isCurrentlyOnline(): Boolean {
        testOverrides?.let { return it.currentlyOnline }
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** True when the active network lacks [NetworkCapabilities.NET_CAPABILITY_NOT_METERED]. */
    fun isMetered(): Boolean {
        testOverrides?.let { return it.metered }
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun networkTypeLabel(): String {
        if (!isCurrentlyOnline()) return "Sin conexión"
        testOverrides?.let { return if (it.metered) "Datos" else "Wi‑Fi" }
        val network = connectivityManager.activeNetwork ?: return "Sin conexión"
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return "Sin conexión"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Datos"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            isMetered() -> "Datos"
            else -> "Wi‑Fi"
        }
    }

    private data class TestOverrides(
        val currentlyOnline: Boolean,
        val metered: Boolean
    )

    companion object {
        @Volatile
        private var testOverrides: TestOverrides? = null

        internal fun configureForTest(
            currentlyOnline: Boolean,
            metered: Boolean
        ) {
            testOverrides = TestOverrides(
                currentlyOnline = currentlyOnline,
                metered = metered
            )
        }

        internal fun resetTestOverrides() {
            testOverrides = null
        }
    }
}
