package com.sinun.agent.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.sinun.agent.SinunApp
import com.sinun.agent.ui.MainActivity

/**
 * שלד ה-VPN המקומי (שבוע 2 בתוכנית).
 *
 * שלב נוכחי: establish של TUN + foreground notification.
 * שבוע 2: קריאת פקטות מה-TUN, יירוט שאילתות DNS, החלטת allow/block לפי ה-policy.
 */
class FilterVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        establish()
        return START_STICKY
    }

    private fun establish() {
        if (tun != null) return
        tun = Builder()
            .setSession("Sinun Filter")
            .addAddress("10.111.222.1", 24)
            // מנתבים אלינו רק DNS בשלב הזה — סינון דומיינים בלי לגעת בשאר התעבורה
            .addDnsServer(DNS_PROXY_ADDRESS)
            .addRoute(DNS_PROXY_ADDRESS, 32)
            .establish()

        // TODO שבוע 2: לולאת קריאה מ-tun.fileDescriptor —
        //  parsing של DNS queries, בדיקה מול PolicyRepository, תשובת NXDOMAIN לחסומים.
        running = true
    }

    private fun stopVpn() {
        tun?.close()
        tun = null
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        tun?.close()
        tun = null
        running = false
        super.onDestroy()
    }

    override fun onRevoke() {
        // המשתמש (או המערכת) כיבה את ה-VPN — אירוע אבטחה. שבוע 5: fail closed + alert.
        running = false
        super.onRevoke()
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, SinunApp.CHANNEL_ID)
            .setContentTitle("Sinun — ההגנה פעילה")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.sinun.agent.STOP_VPN"
        const val DNS_PROXY_ADDRESS = "10.111.222.2"
        private const val NOTIFICATION_ID = 1

        /** מצב ריצה גלוי ל-UI (שבוע 5: יוחלף בניטור אמיתי + אירועים). */
        @Volatile
        var running: Boolean = false
            private set
    }
}
