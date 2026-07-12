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
import com.sinun.agent.monitor.AppMonitor
import com.sinun.agent.ui.BlockOverlay
import com.sinun.agent.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private var appMonitor: AppMonitor? = null
    private var blockOverlay: BlockOverlay? = null
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

        // App Control — השכבה הנראית: מסך חסימה כשאפליקציה אסורה עולה לחזית.
        startAppControl()

        // מדווחים את מלאי האפליקציות המותקנות לשרת (רקע) כדי שהפאנל יהיה עדכני.
        scope.launch {
            (application as SinunApp).policyRepository.reportInstalledApps()
        }

        // רענון policy תכוף — כדי שאישור בקשת פתיחה (או חסימה חדשה) ייכנס לתוקף
        // תוך שניות במקום להמתין ל-heartbeat של 15 דק'. אחרת אפליקציה שאושרה
        // ממשיכה להיחסם כי המנוע עדיין מחזיק את ה-policy הישן.
        startPolicyRefreshLoop()

        running = true
        updateNotification("🛡️ מוגן · policy ${policyEngine.policyId}")
    }

    private val attributor by lazy { ConnectionAttributor(this) }

    private fun startAppControl() {
        val overlay = BlockOverlay(this)
        overlay.prewarm()  // טוען את דף החסימה מראש → הצגה מיידית בזמן חסימה
        val monitor = AppMonitor(
            context = this,
            policy = policyEngine,
            onBlockedApp = { pkg ->
                overlay.show(BlockOverlay.Kind.APP, "האפליקציה חסומה", attributor.labelFor(pkg)) {
                    sendOpeningRequest("app", pkg)
                }
                scope.launch {
                    (application as SinunApp).policyRepository.reportEvent(
                        "app_blocked", JSONObject().put("package", pkg),
                    )
                }
            },
            // מחליפים לאפליקציה מותרת → סוגרים רק overlay של אפליקציה (לא של אתר).
            onAllowedApp = { overlay.hideIfKind(BlockOverlay.Kind.APP) },
        )
        monitor.start()
        appMonitor = monitor
        blockOverlay = overlay
    }

    /** לולאת רענון policy: מושכת מהשרת כל POLICY_REFRESH_MS, ואם הגרסה השתנתה —
     *  טוענת מחדש את המנוע ומאלצת הערכה מחדש של האפליקציה בחזית (כדי להסיר חסימה
     *  שאושרה, או להחיל חסימה חדשה, מיד). */
    private fun startPolicyRefreshLoop() {
        scope.launch {
            val repo = (application as SinunApp).policyRepository
            while (isActive) {
                delay(POLICY_REFRESH_MS)
                val state = repo.refreshPolicy()
                if (state is PolicyState.Active) {
                    val newVersion = state.policy.optLong("version", -1)
                    if (newVersion != policyEngine.version) {
                        policyEngine.load(state.policy)
                        appMonitor?.invalidate()
                        // אם האפליקציה שבחזית כבר לא חסומה — מסירים את מסך החסימה.
                        val fg = appMonitor?.foregroundPackage
                        if (fg == null || !policyEngine.isAppBlocked(fg)) {
                            blockOverlay?.hideIfKind(BlockOverlay.Kind.APP)
                        }
                        updateNotification("🛡️ מוגן · policy ${policyEngine.policyId}")
                    }
                }
            }
        }
    }

    private fun sendOpeningRequest(type: String, target: String) {
        scope.launch {
            (application as SinunApp).policyRepository
                .runCatching { requestOpening(type, target, "בקשה מהמכשיר") }
        }
    }

    /** קריאה חוזרת מהמנוע על כל הכרעה. חוסם → דיווח לשרת (מטא-דאטה בלבד). */
    override fun onEvent(event: FilterEvent) {
        if (event.verdict != PolicyEngine.Verdict.BLOCK) return
        if (!shouldReport(event.domain)) return  // דדופ: פעם בדקה לכל דומיין

        // מסך חסימה לאתר — מוצג כשנראה שמדובר בגלישה יזומה (לא תעבורת רקע).
        // לוגיקת החלטה:
        //  - fg ו-pkg ידועים ושווים → ודאי גלישה יזומה (המקרה האידיאלי).
        //  - pkg ידוע אך fg לא (אין Usage Access) → מציגים, כי אין לנו מידע טוב יותר.
        //  - fg ידוע אך pkg לא (Attributor נכשל) → מציגים, כי יש אפליקציה בחזית.
        //  - שניהם לא ידועים → לא מציגים (אי-אפשר להבדיל מתעבורת רקע).
        val pkg = event.packageName
        val fg = appMonitor?.foregroundPackage
        val likelyForeground = when {
            pkg != null && fg != null -> pkg == fg   // שניהם ידועים: חייבים להתאים
            pkg != null && fg == null -> true         // אין Usage Access: מציגים בכל מקרה
            pkg == null && fg != null -> true         // Attributor נכשל: יש fg → מציגים
            else -> false                             // שניהם null: אין מידע — דלג
        }
        if (likelyForeground) {
            blockOverlay?.show(BlockOverlay.Kind.DOMAIN, "האתר חסום", event.domain) {
                sendOpeningRequest("domain", event.domain)
            }
        }

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
        appMonitor?.stop()
        appMonitor = null
        blockOverlay?.destroy()
        blockOverlay = null
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
        private const val POLICY_REFRESH_MS = 20_000L

        @Volatile
        var running: Boolean = false
            private set
    }
}
