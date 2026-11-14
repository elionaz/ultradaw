package com.elionaz.ultradaw.rtpmidi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

sealed interface SessionState {
    data object Stopped : SessionState
    data class Listening(val port: Int) : SessionState
    data class Connected(val peerName: String, val latencyMs: Double?) : SessionState
    data class Failed(val message: String) : SessionState
}

/**
 * AppleMIDI session in responder role: macOS (Audio MIDI Setup → Network) invites us.
 * Control port = [controlPort], data port = controlPort + 1.
 *
 * Single peer at a time; a new invitation replaces the previous peer.
 */
class AppleMidiSession(
    private val name: String,
    private val scope: CoroutineScope,
    private val controlPort: Int = DEFAULT_PORT,
    private val onMidi: (ByteArray) -> Unit,
) {
    companion object {
        const val DEFAULT_PORT = 5004
        private const val TAG = "UltraDAW/Session"
        /** macOS re-syncs the clock every ~10 s once connected; treat 60 s of silence as gone. */
        private const val PEER_TIMEOUT_MS = 60_000L
    }

    private data class Peer(
        val ssrc: Int,
        val name: String,
        val control: SocketAddress,
        val data: SocketAddress?,
    )

    private val _state = MutableStateFlow<SessionState>(SessionState.Stopped)
    val state: StateFlow<SessionState> = _state

    private val ssrc = Random.nextInt()
    private val sequence = AtomicInteger(Random.nextInt(0, 0xFFFF))
    private val startNanos = System.nanoTime()
    private val sysex = SysexAssembler()
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "UltraDAW-send").apply { isDaemon = true } }

    @Volatile private var control: DatagramSocket? = null
    @Volatile private var data: DatagramSocket? = null
    @Volatile private var peer: Peer? = null
    @Volatile private var lastHeard = 0L
    private var latencyMs: Double? = null
    private val jobs = mutableListOf<Job>()

    /** Clock in 100 µs units since session start, as AppleMIDI expects. */
    private fun now(): Long = (System.nanoTime() - startNanos) / 100_000

    fun start() {
        if (control != null) return
        try {
            control = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(controlPort)) }
            data = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(controlPort + 1)) }
        } catch (e: SocketException) {
            close()
            _state.value = SessionState.Failed("Cannot open UDP ${controlPort}/${controlPort + 1}: ${e.message}")
            return
        }
        _state.value = SessionState.Listening(controlPort)
        jobs += scope.launch(Dispatchers.IO) { receiveLoop(control!!, isData = false) }
        jobs += scope.launch(Dispatchers.IO) { receiveLoop(data!!, isData = true) }
        jobs += scope.launch(Dispatchers.IO) { watchdog() }
    }

    fun stop() {
        peer?.let { sendBye(it) }
        peer = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        close()
        _state.value = SessionState.Stopped
    }

    val isConnected: Boolean get() = peer?.data != null

    /**
     * Sends MIDI messages to the connected peer (no-op if nobody is connected).
     * Safe to call from the main thread: packets go out on a single sender thread,
     * which also keeps them in order.
     */
    fun send(messages: List<ByteArray>) {
        if (messages.isEmpty()) return
        val p = peer ?: return
        val dest = p.data ?: return
        val socket = data ?: return
        sender.execute {
            try {
                // Sequence number and timestamp are assigned on the sender thread so that
                // packets always leave in sequence order, whichever thread called send().
                val seq = sequence.incrementAndGet() and 0xFFFF
                val bytes = RtpMidiCodec.encode(seq, now() and 0xFFFFFFFFL, ssrc, messages)
                socket.send(DatagramPacket(bytes, bytes.size, dest))
            } catch (e: Exception) {
                Log.w(TAG, "send failed", e)
            }
        }
    }

    fun send(message: ByteArray) = send(listOf(message))

    private fun close() {
        runCatching { control?.close() }
        runCatching { data?.close() }
        control = null
        data = null
    }

    private suspend fun receiveLoop(socket: DatagramSocket, isData: Boolean) {
        val buf = ByteArray(4096)
        while (scope.isActive && !socket.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: SocketException) {
                break
            }
            try {
                handlePacket(buf, packet.length, packet.socketAddress, socket, isData)
            } catch (e: Exception) {
                Log.w(TAG, "dropping bad packet", e)
            }
        }
    }

    private fun handlePacket(buf: ByteArray, len: Int, from: SocketAddress, socket: DatagramSocket, isData: Boolean) {
        if (AppleMidiPackets.isAppleMidi(buf, len)) {
            val pkt = AppleMidiPackets.decode(buf, len) ?: return
            // A CK from a session we don't know (e.g. macOS still thinks the app that was killed
            // is connected): tell it to go away so the user can reconnect cleanly.
            if (pkt is AppleMidiPacket.ClockSync && !isKnown(pkt.ssrc)) return rejectStale(socket, from)
            lastHeard = System.currentTimeMillis()
            handleControl(pkt, from, socket, isData)
        } else if (isData) {
            val rtp = RtpMidiCodec.decode(buf, len, sysex) ?: return
            if (!isKnown(rtp.ssrc)) return rejectStale(socket, from)
            lastHeard = System.currentTimeMillis()
            rtp.messages.forEach(onMidi)
        }
    }

    private fun isKnown(remoteSsrc: Int?): Boolean {
        val p = peer ?: return false
        return remoteSsrc == p.ssrc && p.data != null
    }

    @Volatile private var lastReject = 0L

    private fun rejectStale(socket: DatagramSocket, to: SocketAddress) {
        val t = System.currentTimeMillis()
        if (t - lastReject < 1_000) return
        lastReject = t
        reply(socket, to, AppleMidiPacket.Exchange(AppleMidiPacket.Command.BY, 0, ssrc))
    }

    private fun handleControl(pkt: AppleMidiPacket, from: SocketAddress, socket: DatagramSocket, isData: Boolean) {
        when (pkt) {
            is AppleMidiPacket.Exchange -> when (pkt.command) {
                AppleMidiPacket.Command.IN -> {
                    reply(socket, from, AppleMidiPacket.Exchange(AppleMidiPacket.Command.OK, pkt.token, ssrc, name))
                    val current = peer
                    peer = if (!isData) {
                        Peer(pkt.ssrc, pkt.name ?: "Mac", from, null)
                    } else {
                        (current?.takeIf { it.ssrc == pkt.ssrc } ?: Peer(pkt.ssrc, pkt.name ?: "Mac", controlFor(from), null))
                            .copy(data = from)
                    }
                    if (isData) _state.value = SessionState.Connected(peer!!.name, null)
                }
                AppleMidiPacket.Command.BY -> {
                    if (peer?.ssrc == pkt.ssrc) disconnect()
                }
                AppleMidiPacket.Command.OK, AppleMidiPacket.Command.NO -> Unit // we never invite
            }
            is AppleMidiPacket.ClockSync -> when (pkt.count) {
                0 -> reply(socket, from, pkt.copy(ssrc = ssrc, count = 1, ts2 = now()))
                1 -> reply(socket, from, pkt.copy(ssrc = ssrc, count = 2, ts3 = now()))
                2 -> {
                    // Round trip measured by the initiator: ts3 - ts1, in 100 µs units.
                    latencyMs = (pkt.ts3 - pkt.ts1) / 10.0
                    val p = peer
                    if (p?.data != null) _state.value = SessionState.Connected(p.name, latencyMs)
                }
            }
            is AppleMidiPacket.ReceiverFeedback -> Unit
        }
    }

    /** The peer's control port is its data port − 1. */
    private fun controlFor(data: SocketAddress): SocketAddress =
        (data as? InetSocketAddress)?.let { InetSocketAddress(it.address, it.port - 1) } ?: data

    private fun reply(socket: DatagramSocket, to: SocketAddress, pkt: AppleMidiPacket) {
        val bytes = AppleMidiPackets.encode(pkt)
        runCatching { socket.send(DatagramPacket(bytes, bytes.size, to)) }
    }

    private fun sendBye(p: Peer) {
        val bye = AppleMidiPackets.encode(AppleMidiPacket.Exchange(AppleMidiPacket.Command.BY, 0, ssrc))
        val socket = control ?: return
        // stop() may run on the main thread; block briefly so BY leaves before sockets close.
        runCatching { sender.submit { socket.send(DatagramPacket(bye, bye.size, p.control)) }.get(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    private fun disconnect() {
        peer = null
        latencyMs = null
        _state.value = SessionState.Listening(controlPort)
    }

    private suspend fun watchdog() {
        while (scope.isActive) {
            delay(5_000)
            if (peer != null && System.currentTimeMillis() - lastHeard > PEER_TIMEOUT_MS) disconnect()
        }
    }
}
