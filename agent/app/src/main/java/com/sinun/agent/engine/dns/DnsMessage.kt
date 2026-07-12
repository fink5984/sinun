package com.sinun.agent.engine.dns

import java.nio.ByteBuffer

/**
 * פענוח מינימלי של הודעת DNS (RFC 1035) — מספיק כדי לשלוף את שם הדומיין הנשאל
 * ולבנות תשובת חסימה (NXDOMAIN) בלי להיפנות ל-upstream.
 *
 * לא מפענחים את כל ה-message; רק את מה שצריך למסנן. ה-payload המקורי נשמר כדי
 * להעביר אותו כמו-שהוא ל-upstream כשמחליטים לאשר.
 */
class DnsMessage private constructor(
    val transactionId: Int,
    val qName: String,
    val qType: Int,
    val raw: ByteArray,
) {
    companion object {
        private const val TYPE_A = 1
        private const val TYPE_AAAA = 28

        /** מפענח שאילתה; מחזיר null אם זה לא query תקין שאפשר לסנן. */
        fun parseQuery(data: ByteArray, length: Int): DnsMessage? {
            if (length < 12) return null
            val buf = ByteBuffer.wrap(data, 0, length)
            val txId = buf.short.toInt() and 0xFFFF
            val flags = buf.short.toInt() and 0xFFFF
            val isResponse = (flags and 0x8000) != 0
            if (isResponse) return null
            val qdCount = buf.short.toInt() and 0xFFFF
            buf.short // anCount
            buf.short // nsCount
            buf.short // arCount
            if (qdCount < 1) return null

            val name = readName(buf, length) ?: return null
            if (buf.remaining() < 4) return null
            val qType = buf.short.toInt() and 0xFFFF
            buf.short // qClass

            return DnsMessage(txId, name, qType, data.copyOf(length))
        }

        private fun readName(buf: ByteBuffer, length: Int): String? {
            val sb = StringBuilder()
            var guard = 0
            while (buf.hasRemaining()) {
                if (guard++ > 128) return null
                val len = buf.get().toInt() and 0xFF
                if (len == 0) break
                if ((len and 0xC0) == 0xC0) {
                    // compression pointer — לא צפוי בשאילתה; מדלגים על הבייט השני
                    if (!buf.hasRemaining()) return null
                    buf.get()
                    break
                }
                if (len > buf.remaining()) return null
                if (sb.isNotEmpty()) sb.append('.')
                repeat(len) { sb.append((buf.get().toInt() and 0xFF).toChar()) }
            }
            return sb.toString().ifEmpty { null }
        }
    }

    val isAddressQuery: Boolean get() = qType == TYPE_A || qType == TYPE_AAAA

    /**
     * בונה תשובת NXDOMAIN (Name Error) עבור השאילתה — הדרך הנקייה לחסום דומיין:
     * האפליקציה מקבלת "הדומיין לא קיים" ולא נוצר חיבור בכלל.
     */
    fun buildNxDomainResponse(): ByteArray {
        val q = ByteBuffer.wrap(raw)
        // מדלגים על header כדי להעתיק את אזור השאלה כמו-שהוא
        q.position(12)
        readName(q, raw.size)
        q.short // qType
        q.short // qClass
        val questionEnd = q.position()

        val out = ByteBuffer.allocate(questionEnd)
        out.putShort(transactionId.toShort())
        // QR=1, Opcode=0, AA=0, TC=0, RD=1, RA=1, RCODE=3 (NXDOMAIN)
        out.putShort(0x8183.toShort())
        out.putShort(1) // QDCOUNT
        out.putShort(0) // ANCOUNT
        out.putShort(0) // NSCOUNT
        out.putShort(0) // ARCOUNT
        out.put(raw, 12, questionEnd - 12) // אזור השאלה המקורי
        return out.array()
    }
}
