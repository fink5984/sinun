package com.sinun.agent.engine

import com.sinun.agent.engine.dns.DnsMessage
import com.sinun.agent.engine.tls.ClientHello
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class ProtocolParsingTest {

    // --- DNS ---

    /** בונה שאילתת DNS type A עבור שם, לבדיקת ה-parser. */
    private fun dnsQuery(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34))       // transaction id
        out.write(byteArrayOf(0x01, 0x00))       // flags: standard query, RD
        out.write(byteArrayOf(0x00, 0x01))       // QDCOUNT=1
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00)) // AN/NS/AR
        for (label in name.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)                             // root label
        out.write(byteArrayOf(0x00, 0x01))       // QTYPE=A
        out.write(byteArrayOf(0x00, 0x01))       // QCLASS=IN
        return out.toByteArray()
    }

    @Test fun `parses dns query name`() {
        val q = dnsQuery("mail.google.com")
        val msg = DnsMessage.parseQuery(q, q.size)
        assertNotNull(msg)
        assertEquals("mail.google.com", msg!!.qName)
        assertEquals(0x1234, msg.transactionId)
    }

    @Test fun `nxdomain response sets rcode and matches txid`() {
        val q = dnsQuery("bad.example")
        val msg = DnsMessage.parseQuery(q, q.size)!!
        val resp = msg.buildNxDomainResponse()
        // txid נשמר
        assertEquals(0x12, resp[0].toInt() and 0xFF)
        assertEquals(0x34, resp[1].toInt() and 0xFF)
        // flags: QR=1 + RCODE=3 (NXDOMAIN) → 0x8183
        assertEquals(0x81, resp[2].toInt() and 0xFF)
        assertEquals(0x83, resp[3].toInt() and 0xFF)
    }

    // --- TLS SNI ---

    /** בונה TLS ClientHello מינימלי עם SNI לבדיקת ה-parser. */
    private fun clientHello(host: String): ByteArray {
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        val sni = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x00, 0x00))                                  // ext type: server_name
            val list = ByteArrayOutputStream().apply {
                write(0x00)                                                 // name type: host_name
                write(hostBytes.size ushr 8); write(hostBytes.size and 0xFF)
                write(hostBytes)
            }.toByteArray()
            val listLen = list.size
            val extBody = ByteArrayOutputStream().apply {
                write(listLen ushr 8); write(listLen and 0xFF)             // server_name_list length
                write(list)
            }.toByteArray()
            write(extBody.size ushr 8); write(extBody.size and 0xFF)       // extension length
            write(extBody)
        }.toByteArray()

        val handshakeBody = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x03, 0x03))                                  // client version
            write(ByteArray(32))                                           // random
            write(0x00)                                                    // session id length
            write(byteArrayOf(0x00, 0x02, 0x13, 0x01))                    // cipher suites (len=2)
            write(byteArrayOf(0x01, 0x00))                                // compression methods (len=1, null)
            write(sni.size ushr 8); write(sni.size and 0xFF)              // extensions length
            write(sni)
        }.toByteArray()

        val handshake = ByteArrayOutputStream().apply {
            write(0x01)                                                    // ClientHello
            write(0x00); write(handshakeBody.size ushr 8); write(handshakeBody.size and 0xFF)
            write(handshakeBody)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write(0x16)                                                    // record: handshake
            write(byteArrayOf(0x03, 0x01))                                // record version
            write(handshake.size ushr 8); write(handshake.size and 0xFF)
            write(handshake)
        }.toByteArray()
    }

    @Test fun `extracts sni from client hello`() {
        val bytes = clientHello("www.example.com")
        assertEquals("www.example.com", ClientHello.extractSni(bytes, bytes.size))
    }

    @Test fun `non-tls data yields null sni`() {
        val junk = byteArrayOf(0x47, 0x45, 0x54, 0x20, 0x2f) // "GET /"
        assertNull(ClientHello.extractSni(junk, junk.size))
    }
}
