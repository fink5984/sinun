package com.sinun.agent.engine.net

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import java.net.InetSocketAddress

/**
 * ייחוס חיבור לאפליקציה שיזמה אותו — הבסיס לסינון פר-אפליקציה.
 *
 * ב-API 29+ יש getConnectionOwnerUid: נותנים לו 5-tuple (protocol, local, remote)
 * והוא מחזיר את ה-uid של הבעלים. את ה-uid ממפים ל-package name.
 * מתחת ל-API 29 אין ממשק ציבורי לכך → מחזירים null (סינון לפי דומיין בלבד).
 */
class ConnectionAttributor(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val pm = context.packageManager
    private val uidToPackage = HashMap<Int, String?>()

    fun packageForUdp(local: InetSocketAddress, remote: InetSocketAddress): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val uid = try {
            cm.getConnectionOwnerUid(Ipv4Packet.PROTO_UDP, local, remote)
        } catch (_: Exception) {
            return null
        }
        if (uid == Process.INVALID_UID || uid < 0) return null
        return uidToPackage.getOrPut(uid) { resolvePackage(uid) }
    }

    private fun resolvePackage(uid: Int): String? {
        val packages = pm.getPackagesForUid(uid) ?: return null
        // uid משותף → מחזירים את הראשון; מספיק להכרעת policy ברמת חבילה.
        return packages.firstOrNull()
    }

    fun labelFor(packageName: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }
}
