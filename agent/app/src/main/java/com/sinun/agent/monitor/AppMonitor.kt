package com.sinun.agent.monitor

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.sinun.agent.engine.policy.PolicyEngine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App Control — השכבה הנראית של החסימה.
 *
 * מזהה איזו אפליקציה נמצאת בחזית (דרך UsageStatsManager) ואם היא ברשימת
 * החסימות המפורשת — מפעיל callback שמציג מסך חסימה. בניגוד לסינון ה-DNS
 * (שקוף למשתמש), כאן המשתמש רואה במפורש שהאפליקציה נחסמה ויכול לבקש פתיחה.
 *
 * בטיחות: חוסם *רק* אפליקציות ברשימה המפורשת (isAppBlocked), אף פעם לא את
 * מסך הבית / ההגדרות / האפליקציה שלנו — כדי לא לנעול את המכשיר.
 */
class AppMonitor(
    private val context: Context,
    private val policy: PolicyEngine,
    private val onBlockedApp: (packageName: String) -> Unit,
    private val onAllowedApp: () -> Unit,
) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var lastHandled: String? = null

    /** האפליקציה שבחזית כרגע — משמש את חסימת הדומיינים כדי להציג מסך רק על
     *  ניסיון גלישה יזום (ולא על תעבורת רקע של אפליקציות אחרות). */
    @Volatile
    var foregroundPackage: String? = null
        private set

    fun start() {
        if (!hasUsageAccess(context)) return
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "aegis-app-monitor").apply { start() }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    /** מאלץ הערכה מחדש של האפליקציה שבחזית בסבב הבא (למשל אחרי עדכון policy —
     *  כדי שאפליקציה שאושרה תפסיק להיחסם מיד, בלי להמתין למעבר אפליקציה). */
    fun invalidate() {
        lastHandled = null
    }

    private fun loop() {
        while (running.get()) {
            val fg = currentForegroundApp()
            if (fg != null) foregroundPackage = fg
            if (fg != null && fg != lastHandled) {
                lastHandled = fg
                if (isBlockable(fg) && policy.isAppBlocked(fg)) {
                    onBlockedApp(fg)
                } else {
                    onAllowedApp()
                }
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    /** האפליקציה האחרונה שעברה לחזית בחלון הזמן האחרון. */
    private fun currentForegroundApp(): String? {
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        var pkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                pkg = event.packageName
            }
        }
        return pkg
    }

    private fun isBlockable(pkg: String): Boolean =
        pkg != context.packageName &&
            pkg !in SAFE_PACKAGES &&
            !pkg.startsWith("com.android.systemui") &&
            !pkg.startsWith("com.android.launcher") &&
            !pkg.endsWith(".launcher") &&
            !pkg.contains("inputmethod")

    companion object {
        // poll קצר → זיהוי מהיר של מעבר לחזית; lookback קצר → פחות עיבוד לכל סבב.
        private const val POLL_INTERVAL_MS = 250L
        private const val LOOKBACK_MS = 5_000L

        /** אפליקציות מערכת חיוניות שלעולם לא נחסום (הגנה מפני נעילת מכשיר). */
        private val SAFE_PACKAGES = setOf(
            "com.android.settings",
            "com.android.phone",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.android.systemui",
            "android",
        )

        /** בדיקה אם הוענקה הרשאת Usage Access. */
        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
