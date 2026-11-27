package com.example.woodscanpro

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.launch

/**
 * Lancia `ScanRepository.uploadAll()` quando il sistema segnala che c'e' rete
 * valida. Resta attivo per la durata del processo: registrato una sola volta
 * da MainActivity, non si deregistra ai cambi di configurazione.
 */
object UploadNetworkWatcher {
    private const val TAG = "WoodScanUploadWatcher"
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val app = context.applicationContext
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val repo = ScanRepository.get(app)
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (repo.pendingCount == 0) return
                    repo.scope.launch {
                        try {
                            repo.uploadAll()
                        } catch (e: Exception) {
                            Log.w(TAG, "uploadAll fallito al ritorno della rete", e)
                        }
                    }
                }
            })
            installed = true
        }
    }
}
