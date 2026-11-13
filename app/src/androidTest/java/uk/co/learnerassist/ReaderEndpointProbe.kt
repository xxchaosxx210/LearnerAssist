package uk.co.learnerassist

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import uk.co.learnerassist.core.ElmSession
import uk.co.learnerassist.core.SafeCommand
import java.net.InetSocketAddress
import java.net.Socket

/** Manual, opt-in device diagnostic. Skipped in all ordinary automated test runs. */
class ReaderEndpointProbe {
    @Test fun discoverExplicitlySelectedReader() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Physical reader discovery must be explicitly enabled", arguments.getString("readerProbe") == "true")
        val context = instrumentation.targetContext
        val host = requireNotNull(arguments.getString("readerHost"))
        val phoneIp = requireNotNull(arguments.getString("readerPhoneIp"))
        require(host.split('.').let { it.size == 4 && it.all { octet -> octet.toIntOrNull() in 0..255 } })
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.allNetworks.single { candidate ->
            manager.getNetworkCapabilities(candidate)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                manager.getLinkProperties(candidate)?.linkAddresses?.any { it.address.hostAddress == phoneIp } == true
        }
        fun report(message: String) {
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
        }
        fun open(port: Int): Socket {
            val socket = Socket()
            try {
                network.bindSocket(socket)
                socket.soTimeout = 3000
                socket.connect(InetSocketAddress(host, port), 1200)
                return socket
            } catch (error: Exception) { socket.close(); throw error }
        }
        var identified = false
        for (port in listOf(35000, 23, 2000, 6666, 5000, 3000, 10000, 12345)) {
            val opened = runCatching { open(port).use { } }
            if (opened.isFailure) { report("TCP $host:$port: ${opened.exceptionOrNull()?.javaClass?.simpleName}"); continue }
            report("TCP OPEN $host:$port (zero diagnostic bytes sent)")
            if (arguments.getString("readerIdentity") != "true") continue
            val identity = runCatching {
                open(port).use { socket ->
                    ElmSession(socket.getInputStream(), socket.getOutputStream(), { socket.close() }).use {
                        it.request(SafeCommand.IDENTITY).trim()
                    }
                }
            }
            report("Identity $host:$port: ${identity.getOrElse { it.javaClass.simpleName + ": " + it.message }}")
            if (identity.getOrNull()?.contains("ELM", ignoreCase = true) == true) {
                identified = true
                if (arguments.getString("saveReaderEndpoint") == "true") {
                    val store = SettingsStore(context)
                    val ssid = requireNotNull(arguments.getString("readerSsid"))
                    store.save(store.load().copy(ssid = ssid, host = host, port = port.toString()))
                    // Instrumentation exits immediately: wait for SharedPreferences' pending
                    // apply write to reach disk before the target process can be terminated.
                    check(context.getSharedPreferences("learnerassist", Context.MODE_PRIVATE).edit().commit())
                    report("Saved confirmed ELM endpoint and observed SSID to LearnerAssist settings. Live monitoring remains disabled.")
                }
                break
            }
        }
        if (arguments.getString("readerIdentity") == "true") check(identified) { "No ELM interface confirmed on the checked ports" }
    }
}
