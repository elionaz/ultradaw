package com.elionaz.ultradaw.rtpmidi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.net.InetAddress
import java.net.NetworkInterface

/** A Network MIDI session advertised on the LAN (typically a Mac's session in Audio MIDI Setup). */
data class MidiService(val name: String, val host: InetAddress, val port: Int)

/**
 * Discovers `_apple-midi._udp` sessions (excluding our own) and resolves them one at a time,
 * because NsdManager on API 29 only allows a single resolve in flight.
 */
class BonjourBrowser(context: Context, private val ownName: String) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val multicast: WifiManager.MulticastLock? =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("UltraDAW-mdns")?.apply { setReferenceCounted(false) }

    private val _services = MutableStateFlow<List<MidiService>>(emptyList())
    val services: StateFlow<List<MidiService>> = _services

    private var discovery: NsdManager.DiscoveryListener? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        if (discovery != null) return
        runCatching { multicast?.acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed: $errorCode")
                discovery = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == ownName) return
                synchronized(queue) {
                    if (queue.none { it.serviceName == info.serviceName }) queue.addLast(info)
                }
                resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                _services.update { list -> list.filterNot { it.name == info.serviceName } }
            }
        }
        discovery = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { Log.w(TAG, "discoverServices", it); discovery = null }
    }

    fun stop() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        synchronized(queue) { queue.clear(); resolving = false }
        runCatching { multicast?.release() }
        _services.value = emptyList()
    }

    private fun resolveNext() {
        val next = synchronized(queue) {
            if (resolving || queue.isEmpty()) return
            resolving = true
            queue.removeFirst()
        }
        @Suppress("DEPRECATION") // resolveService is the only API on Android 10
        val ok = runCatching {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "resolve ${info.serviceName} failed: $errorCode")
                    done()
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = info.host
                    if (host != null && !isLocal(host)) {
                        val svc = MidiService(info.serviceName, host, info.port)
                        _services.update { list -> (list.filterNot { it.name == svc.name } + svc).sortedBy { it.name } }
                    }
                    done()
                }
            })
        }.isSuccess
        if (!ok) done()
    }

    private fun done() {
        synchronized(queue) { resolving = false }
        resolveNext()
    }

    private fun isLocal(addr: InetAddress): Boolean = runCatching {
        addr.isLoopbackAddress || NetworkInterface.getNetworkInterfaces().toList()
            .any { nif -> nif.inetAddresses.toList().any { it == addr } }
    }.getOrDefault(false)

    private companion object {
        const val TAG = "UltraDAW/Browse"
        const val SERVICE_TYPE = "_apple-midi._udp"
    }
}
