package com.elionaz.ultradaw.rtpmidi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Two real sessions over UDP loopback: one invites (like the CoreBAR), one responds (like the Mac). */
class SessionLoopbackTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gotByMac = CopyOnWriteArrayList<String>()
    private val gotByBar = CopyOnWriteArrayList<String>()
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    private val mac = AppleMidiSession("Mac", scope, controlPort = 25004) { gotByMac += hex(it) }
    private val bar = AppleMidiSession("UltraDAW", scope, controlPort = 26004) { gotByBar += hex(it) }

    @After fun tearDown() {
        bar.stop(); mac.stop(); scope.cancel()
    }

    private fun waitFor(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out")
            Thread.sleep(20)
        }
    }

    @Test fun `initiator connects, syncs clock and exchanges midi both ways`() {
        mac.start(); bar.start()
        bar.invite("Mac", InetAddress.getLoopbackAddress(), 25004)

        waitFor { bar.state.value is SessionState.Connected && mac.state.value is SessionState.Connected }
        assertEquals("Mac", (bar.state.value as SessionState.Connected).peerName)
        assertEquals("UltraDAW", (mac.state.value as SessionState.Connected).peerName)

        // Initiator drives clock sync → latency gets measured.
        waitFor { (bar.state.value as? SessionState.Connected)?.latencyMs != null }

        bar.send(byteArrayOf(0x90.toByte(), 0x5E, 0x7F))
        mac.send(byteArrayOf(0xF0.toByte(), 0x00, 0x00, 0x66, 0x14, 0x00, 0xF7.toByte()))
        waitFor { gotByMac.isNotEmpty() && gotByBar.isNotEmpty() }
        assertEquals("90 5E 7F", gotByMac.first())
        assertEquals("F0 00 00 66 14 00 F7", gotByBar.first())
    }

    @Test fun `rejected invitation is reported`() {
        bar.start()
        // A fake Mac that answers every invitation with NO.
        val fake = DatagramSocket(27004, InetAddress.getLoopbackAddress())
        val t = Thread {
            val buf = ByteArray(512)
            runCatching {
                while (true) {
                    val p = DatagramPacket(buf, buf.size); fake.receive(p)
                    val inv = AppleMidiPackets.decode(buf, p.length) as? AppleMidiPacket.Exchange ?: continue
                    val no = AppleMidiPackets.encode(AppleMidiPacket.Exchange(AppleMidiPacket.Command.NO, inv.token, 42, "Mac"))
                    fake.send(DatagramPacket(no, no.size, p.socketAddress))
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            bar.invite("Mac", InetAddress.getLoopbackAddress(), 27004)
            waitFor { bar.lastFailure.value == InviteFailure.REJECTED }
            assertTrue(bar.state.value is SessionState.Listening)
        } finally {
            fake.close(); t.join(1_000)
        }
    }
}
