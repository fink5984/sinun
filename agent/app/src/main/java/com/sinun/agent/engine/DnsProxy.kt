package com.sinun.agent.engine

import android.os.ParcelFileDescriptor
import com.sinun.agent.engine.dns.DnsMessage
import com.sinun.agent.engine.net.ConnectionAttributor
import com.sinun.agent.engine.net.Ipv4Packet
import com.sinun.agent.engine.policy.PolicyEngine
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ליבת המנוע: קוראת פקטות DNS מה-TUN, מכריעה לפי ה-PolicyEngine, וממשיכה:
 *  - חסום  → מחזירה NXDOMAIN מיד (לא נוצר חיבור), מפיקה FilterEvent.
 *  - מותר  → מעבירה את השאילתה ל-upstream DNS דרך socket מוגן (protect),
 *            ומזרימה את התשובה בחזרה ל-TUN.
 *
 * למה זה יעיל: רק תעבורת DNS (זעירה, נדירה יחסית) עוברת עיבוד ב-userspace.
 * שאר התעבורה כלל לא מנותבת לכאן — היא זורמת ישירות ברשת. אין userspace TCP stack,
 * אין העתקת כל בייט, אין ניקוז סוללה.
 *
 * upstream: DatagramSocket מוגן ע"י VpnService.protect() כדי שלא ילופ בחזרה ל-TUN.
 */
class DnsProxy(
    private val tunFd: ParcelFileDescriptor,
    private val policy: PolicyEngine,
    private val attributor: ConnectionAttributor,
    private val eventSink: FilterEventSink,
    private val protect: (DatagramSocket) -> Boolean,
    private val upstreamDns: InetAddress,
    private val tunAddress: InetAddress,
) {
    private val running = AtomicBoolean(false)
    private lateinit var workers: ExecutorService
    private lateinit var readerThread: Thread

    fun start() {
        if (!running.compareAndSet(false, true)) return
        workers = Executors.newFixedThreadPool(4)
        readerThread = Thread({ readLoop() }, "aegis-dns-reader").apply { start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { readerThread.interrupt() }
        runCatching { workers.shutdownNow() }
    }

    private fun readLoop() {
        val input = FileInputStream(tunFd.fileDescriptor)
        val output = FileOutputStream(tunFd.fileDescriptor)
        val buffer = ByteArray(MAX_PACKET)

        while (running.get()) {
            val length = try {
                input.read(buffer)
            } catch (_: Exception) {
                if (running.get()) continue else break
            }
            if (length <= 0) continue

            // ניתוב מהיר: רק UDP/53 מעניין אותנו. כל השאר — מדלגים (לא אמור להגיע
            // אם ניתבנו רק את שרת ה-DNS; הבדיקה מגנה מפני הגדרת ניתוב רחבה יותר).
            if (Ipv4Packet.protocolOf(buffer, length) != Ipv4Packet.PROTO_UDP) continue
            val udp = Ipv4Packet.parseUdp(buffer, length) ?: continue
            if (udp.dstPort != DNS_PORT) continue

            val packetCopy = buffer.copyOf(length)
            val udpCopy = udp
            workers.execute { handleDnsPacket(udpCopy, output) }
        }
    }

    private fun handleDnsPacket(udp: Ipv4Packet.Udp, output: FileOutputStream) {
        val query = DnsMessage.parseQuery(udp.payload, udp.payload.size) ?: return

        val pkg = attributor.packageForUdp(
            local = InetSocketAddress(udp.srcAddr, udp.srcPort),
            remote = InetSocketAddress(udp.dstAddr, udp.dstPort),
        )
        val decision = policy.evaluateDomain(query.qName, pkg)

        eventSink.onEvent(FilterEvent(query.qName, pkg, decision.verdict, decision.reason))

        val responsePayload = if (decision.isBlock) {
            query.buildNxDomainResponse()
        } else {
            resolveUpstream(udp.payload) ?: return
        }

        // בונים פקטת IP/UDP חזרה אל האפליקציה: src=שרת ה-DNS הווירטואלי, dst=האפליקציה.
        val reply = Ipv4Packet.buildUdp(
            srcAddr = udp.dstAddr,
            dstAddr = udp.srcAddr,
            srcPort = udp.dstPort,
            dstPort = udp.srcPort,
            payload = responsePayload,
        )
        synchronized(output) {
            runCatching { output.write(reply); output.flush() }
        }
    }

    /** מעביר שאילתה מאושרת ל-upstream DNS אמיתי דרך socket מוגן ומחזיר את התשובה. */
    private fun resolveUpstream(queryPayload: ByteArray): ByteArray? {
        DatagramSocket().use { socket ->
            if (!protect(socket)) return null
            socket.soTimeout = UPSTREAM_TIMEOUT_MS
            return try {
                socket.send(DatagramPacket(queryPayload, queryPayload.size, upstreamDns, DNS_PORT))
                val respBuf = ByteArray(MAX_PACKET)
                val resp = DatagramPacket(respBuf, respBuf.size)
                socket.receive(resp)
                respBuf.copyOf(resp.length)
            } catch (_: Exception) {
                null
            }
        }
    }

    companion object {
        private const val DNS_PORT = 53
        private const val MAX_PACKET = 32767
        private const val UPSTREAM_TIMEOUT_MS = 5000
    }
}
