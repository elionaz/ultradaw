package com.elionaz.ultradaw

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.elionaz.ultradaw.mcu.McuEncoder
import com.elionaz.ultradaw.mcu.McuParser
import com.elionaz.ultradaw.mcu.SurfaceState
import com.elionaz.ultradaw.rtpmidi.AppleMidiSession
import com.elionaz.ultradaw.rtpmidi.BonjourAdvertiser
import com.elionaz.ultradaw.rtpmidi.BonjourBrowser
import com.elionaz.ultradaw.rtpmidi.InviteFailure
import com.elionaz.ultradaw.rtpmidi.MidiService
import com.elionaz.ultradaw.rtpmidi.SessionState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Glue between the network session, the MCU state machine and the UI.
 * One instance per process (owned by [UltraDawApp]).
 *
 * Connection policy: the Mac the user last connected to is remembered and invited
 * automatically (with back-off) whenever we're not connected. If none is remembered and
 * exactly one Mac is on the network, that one is used. The Mac can still invite us too.
 */
class SurfaceController(context: Context) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "background failure", e) },
    )
    private val parser = McuParser()
    private val lock = Any()

    private val _surface = MutableStateFlow(SurfaceState())
    val surface: StateFlow<SurfaceState> = _surface

    private val session = AppleMidiSession(SESSION_NAME, scope, onMidi = ::onMidi)
    val connection: StateFlow<SessionState> = session.state
    val lastFailure: StateFlow<InviteFailure?> = session.lastFailure

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("ultradaw", Context.MODE_PRIVATE)
    private val bonjour = BonjourAdvertiser(appContext)
    private val browser = BonjourBrowser(appContext, SESSION_NAME)
    /** Network MIDI sessions found on the LAN (Macs). */
    val macs: StateFlow<List<MidiService>> = browser.services

    private val _preferredMac = MutableStateFlow(prefs.getString(KEY_PREFERRED, null))
    val preferredMac: StateFlow<String?> = _preferredMac

    private val wifiLock: WifiManager.WifiLock? =
        (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "UltraDAW")
            ?.apply { setReferenceCounted(false) }

    private val jobs = mutableListOf<Job>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        session.start()
        if (session.state.value is SessionState.Listening) {
            bonjour.register(SESSION_NAME, AppleMidiSession.DEFAULT_PORT)
            browser.start()
        }
        jobs += scope.launch { meterDecay() }
        jobs += scope.launch { followConnection() }
        jobs += scope.launch { autoConnect() }
    }

    fun stop() {
        if (!started) return
        started = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        browser.stop()
        bonjour.unregister()
        session.stop()
        runCatching { wifiLock?.release() }
    }

    /** User picked a Mac: remember it and connect now. */
    fun connectTo(mac: MidiService) {
        remember(mac.name)
        session.invite(mac.name, mac.host, mac.port)
    }

    // ---- UI → host ----

    fun press(note: Int, pressed: Boolean) = session.send(McuEncoder.button(note, pressed))

    fun faderTouch(fader: Int, touched: Boolean) = session.send(McuEncoder.faderTouch(fader, touched))

    fun faderMove(fader: Int, value: Int) {
        // Optimistic local update so the fader doesn't jump back before the host echoes it.
        synchronized(lock) {
            _surface.update { s -> s.copy(faders = s.faders.toMutableList().also { it[fader] = value }) }
        }
        session.send(McuEncoder.faderMove(fader, value))
    }

    // ---- host → UI ----

    private fun onMidi(msg: ByteArray) {
        if (BuildConfig.DEBUG) Log.d(TRACE, "IN  ${hex(msg)}")
        val replies = synchronized(lock) {
            val r = parser.handle(_surface.value, msg)
            _surface.value = r.state
            r.replies
        }
        if (replies.isNotEmpty()) {
            if (BuildConfig.DEBUG) replies.forEach { Log.d(TRACE, "OUT ${hex(it)}") }
            session.send(replies)
        }
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    // ---- connection management ----

    private fun remember(name: String) {
        if (_preferredMac.value == name) return
        _preferredMac.value = name
        prefs.edit().putString(KEY_PREFERRED, name).apply()
    }

    /** Which Mac to invite automatically, if any. */
    private fun target(list: List<MidiService>): MidiService? {
        val preferred = _preferredMac.value
        return if (preferred != null) list.firstOrNull { it.name == preferred } else list.singleOrNull()
    }

    private suspend fun autoConnect() {
        var failures = 0
        var nextAttempt = 0L
        while (scope.isActive) {
            delay(1_000)
            val st = session.state.value
            if (st is SessionState.Connected) { failures = 0; nextAttempt = 0; continue }
            if (st !is SessionState.Listening) continue // connecting, stopped or failed
            val mac = target(browser.services.value) ?: continue
            val now = System.currentTimeMillis()
            if (now < nextAttempt) continue
            session.invite(mac.name, mac.host, mac.port)
            failures++
            nextAttempt = now + (3_000L shl (failures - 1).coerceAtMost(4)).coerceAtMost(30_000L)
        }
    }

    /**
     * Fresh surface whenever we connect to a host — including when a different host takes over
     * an existing connection, so no LEDs/names/faders leak from the previous one.
     * Low-latency Wi-Fi only while connected.
     */
    private suspend fun followConnection() {
        var current: Pair<String, java.net.InetAddress?>? = null
        session.state.collect { st ->
            if (st is SessionState.Connected) {
                val host = st.peerName to st.address
                if (host != current) {
                    synchronized(lock) { _surface.value = SurfaceState() }
                    runCatching { wifiLock?.acquire() }
                    announceJob?.cancel()
                    announceJob = scope.launch { announce(host) }
                    // If the Mac invited us, remember it so we can reconnect on our own next time.
                    browser.services.value.firstOrNull { it.host == st.address }?.let { remember(it.name) }
                    current = host
                }
            } else if (current != null) {
                announceJob?.cancel()
                synchronized(lock) { _surface.update { it.copy(hostOnline = false) } }
                runCatching { wifiLock?.release() }
                current = null
            }
        }
    }

    private var announceJob: Job? = null

    /**
     * Like a hardware MCU at power-up, introduce ourselves until the DAW answers (it then
     * re-sends LCD, LEDs and faders). Gives up after ~20 s; the DAW can still query us later.
     */
    private suspend fun announce(host: Pair<String, java.net.InetAddress?>) {
        delay(500) // let clock sync settle first
        repeat(10) {
            val st = session.state.value
            if (st !is SessionState.Connected || st.peerName to st.address != host) return
            if (_surface.value.hostOnline) return
            session.send(parser.hostConnectionQuery())
            delay(2_000)
        }
    }

    /** Hardware MCU meters fall by themselves; the host only sends peaks. */
    private suspend fun meterDecay() {
        while (scope.isActive) {
            delay(METER_DECAY_MS)
            synchronized(lock) {
                _surface.update { s ->
                    if (s.meters.all { it == 0 }) s else s.copy(meters = s.meters.map { (it - 1).coerceAtLeast(0) })
                }
            }
        }
    }

    companion object {
        const val SESSION_NAME = "UltraDAW"
        private const val METER_DECAY_MS = 150L
        private const val TAG = "UltraDAW"
        private const val TRACE = "UltraDAW/MIDI"
        private const val KEY_PREFERRED = "preferred_mac"
    }
}
