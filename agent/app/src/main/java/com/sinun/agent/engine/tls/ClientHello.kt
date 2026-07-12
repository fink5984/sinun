package com.sinun.agent.engine.tls

import java.nio.ByteBuffer

/**
 * חילוץ ה-SNI (Server Name Indication) מ-TLS ClientHello — בלי לפענח את התעבורה.
 *
 * ה-SNI נשלח בטקסט גלוי בבייטים הראשונים של חיבור HTTPS, לפני ההצפנה. כך אפשר
 * לדעת לאיזה דומיין האפליקציה מתחברת ולהחליט allow/block — בלי TLS MITM,
 * בלי להתקין תעודה, ובלי לשבור אפליקציות בנק / certificate pinning.
 *
 * זו שכבה 3 של המנוע (per-app + SNI), משלימה את סינון ה-DNS: תופסת גם ניסיונות
 * להתחבר ישירות ל-IP (עוקף DNS) כל עוד יש SNI.
 */
object ClientHello {

    private const val HANDSHAKE = 22
    private const val CLIENT_HELLO = 1
    private const val EXT_SERVER_NAME = 0

    /**
     * מחזיר את שם ה-host מה-SNI, או null אם זה לא ClientHello עם SNI
     * (או שהבייטים עדיין לא הגיעו במלואם).
     */
    fun extractSni(data: ByteArray, length: Int): String? {
        return try { parse(data, length) } catch (_: Exception) { null }
    }

    private fun parse(data: ByteArray, length: Int): String? {
        val buf = ByteBuffer.wrap(data, 0, length)
        if (buf.remaining() < 5) return null
        if ((buf.get().toInt() and 0xFF) != HANDSHAKE) return null
        buf.short // TLS version (record layer)
        val recordLen = buf.short.toInt() and 0xFFFF
        if (recordLen > buf.remaining()) return null // חבילה חלקית — עוד לא הכל הגיע

        if ((buf.get().toInt() and 0xFF) != CLIENT_HELLO) return null
        skip(buf, 3)  // handshake length
        buf.short     // client version
        skip(buf, 32) // random

        val sessionIdLen = buf.get().toInt() and 0xFF
        skip(buf, sessionIdLen)

        val cipherLen = buf.short.toInt() and 0xFFFF
        skip(buf, cipherLen)

        val compLen = buf.get().toInt() and 0xFF
        skip(buf, compLen)

        if (buf.remaining() < 2) return null
        val extTotal = buf.short.toInt() and 0xFFFF
        var read = 0
        while (read < extTotal && buf.remaining() >= 4) {
            val extType = buf.short.toInt() and 0xFFFF
            val extLen = buf.short.toInt() and 0xFFFF
            read += 4 + extLen
            if (extType == EXT_SERVER_NAME) {
                return parseServerNameExtension(buf, extLen)
            }
            skip(buf, extLen)
        }
        return null
    }

    private fun parseServerNameExtension(buf: ByteBuffer, extLen: Int): String? {
        if (extLen < 5 || buf.remaining() < extLen) return null
        buf.short // server_name_list length
        val nameType = buf.get().toInt() and 0xFF
        if (nameType != 0) return null // 0 = host_name
        val nameLen = buf.short.toInt() and 0xFFFF
        if (nameLen > buf.remaining()) return null
        val bytes = ByteArray(nameLen)
        buf.get(bytes)
        return String(bytes, Charsets.US_ASCII).lowercase()
    }

    private fun skip(buf: ByteBuffer, n: Int) {
        if (n < 0 || n > buf.remaining()) throw IllegalArgumentException("truncated")
        buf.position(buf.position() + n)
    }
}
