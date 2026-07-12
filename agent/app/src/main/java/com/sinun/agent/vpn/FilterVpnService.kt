package com.sinun.agent.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.sinun.agent.SinunApp
import com.sinun.agent.data.PolicyState
import com.sinun.agent.engine.DnsProxy
import com.sinun.agent.engine.FilterEvent
import com.sinun.agent.engine.FilterEventSink
import com.sinun.agent.engine.net.ConnectionAttributor
import com.sinun.agent.engine.policy.PolicyEngine
import com.sinun.agent.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * ה-VpnService שמריץ את מנוע Aegis.
 *
 * ניתוב: מגדירים שרת DNS וירטואלי (10.111.222.2) ומנתבים רק אותו ל-TUN. כך *כל*
 * שאילתות ה-DNS של המכשיר מגיעות אלינו (כולל אלה של אפליקציות), אבל שאר התעבורה
 * זורמת ישירות ברשת — יעיל. סינון ה-DNS + anti-bypass (חסימת DoH/DoT) יוצרים
 * אכיפה מלאה בלי userspace TCP stack ובלי MITM.
 */
class FilterVpnService : VpnService(), FilterEventSink {

    private var tun: ParcelFileDescriptor? = null
    private var dnsProxy: DnsProxy? = null
    private val policyEngine = PolicyEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // מניעת הצפת השרת בדיווחי חסימה כפולים על אותו דומיין בפרק זמן קצר.
    private val recentBlocks = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Long>) = size > 256
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification("מפעיל הגנה…"))
        scope.launch { bringUp() }
        return START_STICKY
    }

    private suspend fun bringUp() {
        val repo = (application as SinunApp).policyRepository
        val state = repo.refreshPolicy()
        if (state is PolicyState.Active) {
            policyEngine.load(state.policy)
        } else {
            // fail closed: אין policy → מנוע ריק במצב default-deny (חוסם הכל).
            policyEngine.load(JSONObject().put("default_network_action", "block"))
        }
        establish()
    }

    private fun establish() {
        if (tun != null) return
        val fd = Builder()
            .setSession("Aegis Filter")
            .addAddress(TUN_ADDRESS, 24)
            .addDnsServer(VIRTUAL_DNS)
            // מנתבים רק את שרת ה-DNS הווירטואלי → רק DNS נכנס ל-TUN.
            .addRoute(VIRTUAL_DNS, 32)
            .setBlocking(true)
            .establish() ?: run {
                updateNotification("ההגנה לא פעילה — נדרש אישור VPN")
                stopSelf()
                return
            }
        tun = fd

        val proxy = DnsProxy(
            tunFd = fd,
            policy = policyEngine,
            attributor = ConnectionAttributor(this),
            eventSink = this,
            protect = { socket: DatagramSocket -> protect(socket) },
            upstreamDns = InetAddress.getByName(UPSTREAM_DNS),
            tunAddress = InetAddress.getByName(TUN_ADDRESS),
        )
        proxy.start()
        dnsProxy = proxy
        running = true
        updateNotification("🛡️ מוגן · policy ${policyEngine.policyId}")
    }

    /** קריאה חוזרת מהמנוע על כל הכרעה. חוסם → דיווח לשרת (מטא-דאטה בלבד). */
    override fun onEvent(event: FilterEvent) {
        if (event.verdict != PolicyEngine.Verdict.BLOCK) return
        if (!shouldReport(event.domain)) return
        scope.launch {
            val repo = (application as SinunApp).policyRepository
            val details = JSONObject()
                .put("domain", event.domain)
                .put("package", event.packageName ?: "unknown")
                .put("reason", event.reason.name.lowercase())
            repo.reportEvent("block", details)
        }
    }

    @Synchronized
    private fun shouldReport(domain: String): Boolean {
        val now = System.currentTimeMillis()
        val last = recentBlocks[domain]
        if (last != null && now - last < BLOCK_DEDUP_MS) return false
        recentBlocks[domain] = now
        return true
    }

    private fun stopVpn() {
        dnsProxy?.stop()
        dnsProxy = null
        tun?.close()
        tun = null
        running = false
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        // המשתמש/מערכת כיבו את ה-VPN — אירוע אבטחה (שלב 5: fail closed + alert).
        running = false
        scope.launch {
            (application as SinunApp).policyRepository
                .reportEvent("vpn_revoked", JSONObject().put("source", "onRevoke"))
        }
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, SinunApp.CHANNEL_ID)
            .setContentTitle("Sinun")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(android.app.NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_STOP = "com.sinun.agent.STOP_VPN"
        private const val TUN_ADDRESS = "10.111.222.1"
        private const val VIRTUAL_DNS = "10.111.222.2"
        private const val UPSTREAM_DNS = "8.8.8.8"
        private const val NOTIFICATION_ID = 1
        private const val BLOCK_DEDUP_MS = 60_000L

        @Volatile
        var running: Boolean = false
            private set
    }
}
