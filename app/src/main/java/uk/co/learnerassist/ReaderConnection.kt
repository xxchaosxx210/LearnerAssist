package uk.co.learnerassist

import android.content.Context
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import kotlinx.coroutines.suspendCancellableCoroutine
import uk.co.learnerassist.core.*
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No process-wide network binding: only this reader's socket uses the requested Wi-Fi. */
internal class ReaderConnection(context: Context) : Closeable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var closed = false

    suspend fun selectNetwork(ssid: String, onLost: () -> Unit): Network = suspendCancellableCoroutine { continuation ->
        require(ssid.isNotBlank() && ssid.toByteArray().size <= 32) { "Enter the reader SSID" }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (continuation.isActive) continuation.resume(network)
            }
            override fun onUnavailable() {
                if (continuation.isActive) continuation.resumeWithException(java.io.IOException("Wi-Fi selection declined or timed out"))
            }
            override fun onLost(network: Network) { close(); onLost() }
        }
        callback = cb
        continuation.invokeOnCancellation { close() }
        try {
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(WifiNetworkSpecifier.Builder().setSsid(ssid).build()).build()
            manager.requestNetwork(request, cb, 30_000)
        } catch (e: Exception) { close(); if (continuation.isActive) continuation.resumeWithException(e) }
    }

    /** Called only inside the explicitly activated foreground live session. */
    fun openSession(network: Network, host: String, port: Int): DiagnosticSession {
        check(!closed)
        require(port in 1..65535)
        // Literal IPv4 avoids DNS lookups and accidental internet destinations.
        val octets = host.split('.').map { it.toIntOrNull() }
        require(octets.size == 4 && octets.all { it != null && it in 0..255 }) { "Enter a verified IPv4 address" }
        val s = Socket()
        socket = s
        if (closed) { s.close(); error("Connection cancelled") }
        try {
            network.bindSocket(s)
            s.soTimeout = 8000
            s.connect(InetSocketAddress(host, port), 5000)
            return ElmSession(s.getInputStream(), s.getOutputStream(), { s.close() })
        } catch (e: Exception) { s.close(); throw e }
    }
    override fun close() {
        closed = true
        runCatching { socket?.close() }; socket = null
        callback?.let { runCatching { manager.unregisterNetworkCallback(it) } }; callback = null
    }
}
