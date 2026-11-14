package com.elionaz.ultradaw.rtpmidi

import android.util.Log
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

sealed interface SessionState {
    data object Stopped : SessionState
    data class Listening(val port: Int) : SessionState
    data class Connecting(val peerName: String) : SessionState
    data class Connected(val peerName: String, val latencyMs: Double?, val address: InetAddress?) : SessionState
    data class Failed(val message: String) : SessionState
}

/** Why the last outgoing invitation did not end in a connection. */
enum class InviteFailure { REJECTED, NO_ANSWER }

/**
 * AppleMIDI (macOS Network MIDI) session, single peer at a time.
 *
 * - Responder: macOS invites us (Audio MIDI Setup → Connect).
 * - Initiator: we invite a Mac session found via Bonjour ([invite]); we then drive clock sync.
 *
 * Control port = [controlPort], data port = controlPort + 1.
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
        private const val INVITE_ATTEMPTS = 8
        private const val INVITE_RETRY_MS = 1_000L
    }

    private data class Peer(
        val ssrc: Int,
        val name: String,
        val control: SocketAddress,
        val data: SocketAddress?,
        /** True when we invited the peer and therefore own clock synchronisation. */
        val initiator: Boolean = false,
    )

    /** Outgoing invitation in progress. stage 0 = control port, 1 = data port. */
    private class Invite(val token: Int, val peerName: String, val control: InetSocketAddress) {
        @Volatile var stage = 0
        @Volatile var peerSsrc = 0
        val data: InetSocketAddress get() = InetSocketAddress(control.address, control.port + 1)
    }

    private val _state = MutableStateFlow<SessionState>(SessionState.Stopped)
    val state: StateFlow<SessionState> = _state

    private val _lastFailure = MutableStateFlow<InviteFailure?>(null)
    /** Result of the last [invite] that failed; cleared on connection. */
    val lastFailure: StateFlow<InviteFailure?> = _lastFailure

    private val ssrc = Random.nextInt()
    private val sequence = AtomicInteger(Random.nextInt(0, 0xFFFF))
    private val startNanos = System.nanoTime()
    private val sysex = SysexAssembler()
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "UltraDAW-send").apply { isDaemon = true } }
    private val lock = Any()

    @Volatile private var control: DatagramSocket? = null
    @Volatile private var data: DatagramSocket? = null
    @Volatile private var peer: Peer? = null
    @Volatile private var invite: Invite? = null
    @Volatile private var lastHeard = 0L
    @Volatile private var lastReject = 0L
    @Volatile private var latencyMs: Double? = null
    private val jobs = mutableListOf<Job>()
    private var syncJob: Job? = null

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
        synchronized(lock) {
            peer = null
            invite = null
        }
        syncJob?.cancel()
        jobs.forEach { it.cancel() }
        jobs.clear()
        close()
        _state.value = SessionState.Stopped
    }

    val isConnected: Boolean get() = peer?.data != null

    /**
     * Invites a remote session (e.g. a Mac found via Bonjour). No-op while connected or while
     * another invitation is running. Progress is reported through [state] and [lastFailure].
     */
    fun invite(peerName: String, address: InetAddress, port: Int) {
        if (control == null || isConnected || invite != null) return
        val inv = Invite(Random.nextInt(), peerName, InetSocketAddress(address, port))
        synchronized(lock) { invite = inv }
        _state.value = SessionState.Connecting(peerName)
        scope.launch(Dispatchers.IO) { runInvite(inv) }
    }

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

    // ---- initiator ----

    private suspend fun runInvite(inv: Invite) {
        repeat(INVITE_ATTEMPTS) {
            if (invite !== inv) return
            val toData = inv.stage == 1
            val socket = (if (toData) data else control) ?: return
            val dest = if (toData) inv.data else inv.control
            enqueue(socket, dest, AppleMidiPacket.Exchange(AppleMidiPacket.Command.IN, inv.token, ssrc, name))
            delay(INVITE_RETRY_MS)
        }
        val gaveUp = synchronized(lock) {
            if (invite === inv) { invite = null; true } else false
        }
        if (gaveUp) {
            _lastFailure.value = InviteFailure.NO_ANSWER
            if (!isConnected) _state.value = SessionState.Listening(controlPort)
        }
    }

    private fun onInviteAccepted(pkt: AppleMidiPacket.Exchange, from: SocketAddress, isData: Boolean) {
        val inv = invite ?: return
        if (pkt.token != inv.token) return
        if (!isData && inv.stage == 0) {
            inv.peerSsrc = pkt.ssrc
            inv.stage = 1
            // Don't wait for the retry timer: invite the data port right away.
            data?.let { enqueue(it, inv.data, AppleMidiPacket.Exchange(AppleMidiPacket.Command.IN, inv.token, ssrc, name)) }
        } else if (isData && inv.stage == 1) {
            val p = Peer(pkt.ssrc, inv.peerName, inv.control, from, initiator = true)
            synchronized(lock) {
                if (invite !== inv) return
                invite = null
                peer = p
            }
            lastHeard = System.currentTimeMillis()
            latencyMs = null
            _lastFailure.value = null
            _state.value = SessionState.Connected(p.name, null, inv.control.address)
            startClockSync(p)
        }
    }

    private fun onInviteRejected(pkt: AppleMidiPacket.Exchange) {
        val rejected = synchronized(lock) {
            val inv = invite
            if (inv != null && pkt.token == inv.token) { invite = null; true } else false
        }
        if (rejected) {
            _lastFailure.value = InviteFailure.REJECTED
            if (!isConnected) _state.value = SessionState.Listening(controlPort)
        }
    }

    /** As initiator we must keep the clock in sync (a burst at first, then every 10 s). */
    private fun startClockSync(p: Peer) {
        syncJob?.cancel()
        syncJob = scope.launch(Dispatchers.IO) {
            var n = 0
            while (isActive && peer?.ssrc == p.ssrc) {
                val socket = data ?: break
                val dest = p.data ?: break
                enqueue(socket, dest, AppleMidiPacket.ClockSync(ssrc, 0, now(), 0, 0))
                delay(if (n++ < 6) 1_500 else 10_000)
            }
        }
    }

    // ---- receive ----

    private suspend fun receiveLoop(socket: DatagramSocket, isData: Boolean) {
        val buf = ByteArray(4096)
        while (scope.isActive && !socket.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: SocketException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "receive", e)
                continue
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

    private fun rejectStale(socket: DatagramSocket, to: SocketAddress) {
        val t = System.currentTimeMillis()
        if (t - lastReject < 1_000) return
        lastReject = t
        reply(socket, to, AppleMidiPacket.Exchange(AppleMidiPacket.Command.BY, 0, ssrc))
    }

    private fun handleControl(pkt: AppleMidiPacket, from: SocketAddress, socket: DatagramSocket, isData: Boolean) {
        when (pkt) {
            is AppleMidiPacket.Exchange -> when (pkt.command) {
                AppleMidiPacket.Command.IN -> onInvited(pkt, from, socket, isData)
                AppleMidiPacket.Command.OK -> onInviteAccepted(pkt, from, isData)
                AppleMidiPacket.Command.NO -> onInviteRejected(pkt)
                AppleMidiPacket.Command.BY -> if (peer?.ssrc == pkt.ssrc) disconnect()
            }
            is AppleMidiPacket.ClockSync -> when (pkt.count) {
                0 -> reply(socket, from, pkt.copy(ssrc = ssrc, count = 1, ts2 = now()))
                1 -> {
                    val ts3 = now()
                    reply(socket, from, pkt.copy(ssrc = ssrc, count = 2, ts3 = ts3))
                    updateLatency((ts3 - pkt.ts1) / 10.0)
                }
                2 -> updateLatency((pkt.ts3 - pkt.ts1) / 10.0)
            }
            is AppleMidiPacket.ReceiverFeedback -> Unit
        }
    }

    /** Responder role: the Mac invites us. Accepting cancels any invitation we had running. */
    private fun onInvited(pkt: AppleMidiPacket.Exchange, from: SocketAddress, socket: DatagramSocket, isData: Boolean) {
        reply(socket, from, AppleMidiPacket.Exchange(AppleMidiPacket.Command.OK, pkt.token, ssrc, name))
        val connected: Peer? = synchronized(lock) {
            invite = null
            val current = peer
            peer = if (!isData) {
                Peer(pkt.ssrc, pkt.name ?: "Mac", from, null)
            } else {
                (current?.takeIf { it.ssrc == pkt.ssrc } ?: Peer(pkt.ssrc, pkt.name ?: "Mac", controlFor(from), null))
                    .copy(data = from)
            }
            peer?.takeIf { isData }
        }
        if (connected != null) {
            syncJob?.cancel()
            latencyMs = null
            _lastFailure.value = null
            _state.value = SessionState.Connected(connected.name, null, (from as? InetSocketAddress)?.address)
        }
    }

    private fun updateLatency(ms: Double) {
        latencyMs = ms
        val st = _state.value
        if (st is SessionState.Connected) _state.value = st.copy(latencyMs = ms)
    }

    /** The peer's control port is its data port − 1. */
    private fun controlFor(data: SocketAddress): SocketAddress =
        (data as? InetSocketAddress)?.let { InetSocketAddress(it.address, it.port - 1) } ?: data

    private fun reply(socket: DatagramSocket, to: SocketAddress, pkt: AppleMidiPacket) = enqueue(socket, to, pkt)

    private fun enqueue(socket: DatagramSocket, to: SocketAddress, pkt: AppleMidiPacket) {
        val bytes = AppleMidiPackets.encode(pkt)
        sender.execute {
            try {
                socket.send(DatagramPacket(bytes, bytes.size, to))
            } catch (e: Exception) {
                Log.w(TAG, "control send failed", e)
            }
        }
    }

    private fun sendBye(p: Peer) {
        val bye = AppleMidiPackets.encode(AppleMidiPacket.Exchange(AppleMidiPacket.Command.BY, 0, ssrc))
        val socket = control ?: return
        // stop() may run on the main thread; block briefly so BY leaves before sockets close.
        runCatching {
            sender.submit { socket.send(DatagramPacket(bye, bye.size, p.control)) }.get(500, TimeUnit.MILLISECONDS)
        }
    }

    private fun disconnect() {
        syncJob?.cancel()
        synchronized(lock) { peer = null }
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
