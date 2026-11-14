package com.elionaz.ultradaw

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.elionaz.ultradaw.mcu.McuEncoder
import com.elionaz.ultradaw.mcu.McuParser
import com.elionaz.ultradaw.mcu.SurfaceState
import com.elionaz.ultradaw.rtpmidi.AppleMidiSession
import com.elionaz.ultradaw.rtpmidi.BonjourAdvertiser
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

    private val appContext = context.applicationContext
    private val bonjour = BonjourAdvertiser(appContext)
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
        }
        jobs += scope.launch { meterDecay() }
        jobs += scope.launch { followConnection() }
    }

    fun stop() {
        if (!started) return
        started = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        bonjour.unregister()
        session.stop()
        runCatching { wifiLock?.release() }
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

    /** Fresh surface on every new connection; low-latency Wi-Fi only while connected. */
    private suspend fun followConnection() {
        var wasConnected = false
        session.state.collect { st ->
            val connected = st is SessionState.Connected
            if (connected && !wasConnected) {
                synchronized(lock) { _surface.value = SurfaceState() }
                runCatching { wifiLock?.acquire() }
            } else if (!connected && wasConnected) {
                synchronized(lock) { _surface.update { it.copy(hostOnline = false) } }
                runCatching { wifiLock?.release() }
            }
            wasConnected = connected
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
    }
}
