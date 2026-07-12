package com.sinun.agent.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * מסך החסימה הנראה. מצויר כ-overlay מעל כל אפליקציה דרך WindowManager
 * (TYPE_APPLICATION_OVERLAY) — מופיע גם מעל אפליקציות אחרות ולא כפוף להגבלות
 * הפעלת Activity מהרקע. דורש הרשאת "הצגה מעל אפליקציות אחרות".
 *
 * משמש לשני סוגי חסימה:
 *  - APP: אפליקציה אסורה עלתה לחזית.
 *  - DOMAIN: ניסיון גלישה לאתר חסום באפליקציה שבחזית.
 * הפרדת ה-kind מאפשרת ל-App Monitor להסתיר רק overlay של אפליקציה כשמחליפים
 * אפליקציה, בלי לסגור overlay של אתר חסום שמוצג מעל דפדפן מותר.
 */
class BlockOverlay(private val context: Context) {

    enum class Kind { APP, DOMAIN }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var currentKind: Kind? = null

    fun show(kind: Kind, title: String, subtitle: String, onRequestOpening: () -> Unit) {
        main.post {
            if (view != null) return@post
            if (!Settings.canDrawOverlays(context)) return@post
            val root = buildView(title, subtitle, onRequestOpening)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                android.graphics.PixelFormat.OPAQUE,
            )
            runCatching { wm.addView(root, params); view = root; currentKind = kind }
        }
    }

    /** מסתיר תמיד (למשל אחרי פעולת משתמש). */
    fun hide() = main.post { removeView() }

    /** מסתיר רק אם ה-overlay הנוכחי הוא מהסוג הזה (כדי לא לסגור overlay של אתר). */
    fun hideIfKind(kind: Kind) = main.post { if (currentKind == kind) removeView() }

    private fun removeView() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        currentKind = null
    }

    private fun buildView(title: String, subtitle: String, onRequestOpening: () -> Unit): View {
        val d = context.resources.displayMetrics.density
        val pad = (24 * d).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0D1117"))
            setPadding(pad, pad, pad, pad)

            addView(TextView(context).apply { text = "🚫"; textSize = 64f; gravity = Gravity.CENTER })
            addView(TextView(context).apply {
                text = title
                setTextColor(Color.parseColor("#F85149"))
                textSize = 26f; gravity = Gravity.CENTER
                setPadding(0, pad, 0, pad / 3)
            })
            addView(TextView(context).apply {
                text = subtitle
                setTextColor(Color.parseColor("#E6EDF3"))
                textSize = 18f; gravity = Gravity.CENTER
                setPadding(0, 0, 0, pad)
            })
            addView(Button(context).apply {
                text = "בקש פתיחה"
                setOnClickListener { onRequestOpening(); goHome(); hide() }
            })
            addView(TextView(context).apply {
                text = "חזרה למסך הבית"
                setTextColor(Color.parseColor("#8B949E"))
                textSize = 15f; gravity = Gravity.CENTER
                setPadding(0, pad, 0, 0)
                setOnClickListener { goHome(); hide() }
            })
        }
    }

    private fun goHome() {
        context.startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }
}
