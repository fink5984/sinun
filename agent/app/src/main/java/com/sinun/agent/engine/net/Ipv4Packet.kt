package com.sinun.agent.engine.net

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * פענוח/הרכבה של פקטת IPv4 + UDP — מספיק כדי לטפל בתעבורת DNS שעוברת דרך ה-TUN.
 *
 * ה-TUN מספק פקטות IP גולמיות. אנחנו קוראים כותרת IPv4, מזהים UDP (פרוטוקול 17),
 * שולפים את מטען ה-DNS, ואחרי הכרעה בונים פקטת תשובה עם src/dst הפוכים.
 */
object Ipv4Packet {

    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    class Udp(
        val srcAddr: InetAddress,
        val dstAddr: InetAddress,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray,
    )

    /** מפענח פקטת IPv4/UDP; מחזיר null אם זה לא IPv4/UDP תקין. */
    fun parseUdp(packet: ByteArray, length: Int): Udp? {
        if (length < 28) return null
        val version = (packet[0].toInt() ushr 4) and 0xF
        if (version != 4) return null
        val ihl = (packet[0].toInt() and 0xF) * 4
        if (ihl < 20 || length < ihl + 8) return null
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != PROTO_UDP) return null

        val src = InetAddress.getByAddress(packet.copyOfRange(12, 16))
        val dst = InetAddress.getByAddress(packet.copyOfRange(16, 20))
        val buf = ByteBuffer.wrap(packet, ihl, 8)
        val srcPort = buf.short.toInt() and 0xFFFF
        val dstPort = buf.short.toInt() and 0xFFFF
        val udpLen = buf.short.toInt() and 0xFFFF
        val payloadLen = (udpLen - 8).coerceIn(0, length - ihl - 8)
        val payload = packet.copyOfRange(ihl + 8, ihl + 8 + payloadLen)
        return Udp(src, dst, srcPort, dstPort, payload)
    }

    /** מזהה את פרוטוקול הפקטה בלי פענוח מלא (לניתוב מהיר). */
    fun protocolOf(packet: ByteArray, length: Int): Int {
        if (length < 20 || ((packet[0].toInt() ushr 4) and 0xF) != 4) return -1
        return packet[9].toInt() and 0xFF
    }

    /**
     * בונה פקטת IPv4/UDP חדשה (לתשובת DNS): מחליף src↔dst ומחשב checksums.
     */
    fun buildUdp(
        srcAddr: InetAddress,
        dstAddr: InetAddress,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val totalLen = 20 + 8 + payload.size
        val buf = ByteBuffer.allocate(totalLen)
        // --- IPv4 header ---
        buf.put(0x45.toByte())          // version 4, IHL 5
        buf.put(0)                       // DSCP/ECN
        buf.putShort(totalLen.toShort())
        buf.putShort(0)                  // identification
        buf.putShort(0x4000.toShort())   // flags: Don't Fragment
        buf.put(64)                      // TTL
        buf.put(PROTO_UDP.toByte())
        buf.putShort(0)                  // header checksum placeholder
        buf.put(srcAddr.address)
        buf.put(dstAddr.address)
        // --- UDP header ---
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort((8 + payload.size).toShort())
        buf.putShort(0)                  // UDP checksum (0 = ללא, מותר ב-IPv4)
        buf.put(payload)

        val bytes = buf.array()
        val checksum = ipChecksum(bytes, 0, 20)
        bytes[10] = (checksum ushr 8).toByte()
        bytes[11] = checksum.toByte()
        return bytes
    }

    private fun ipChecksum(data: ByteArray, offset: Int, len: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + len - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (len % 2 == 1) sum += (data[offset + len - 1].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }
}
