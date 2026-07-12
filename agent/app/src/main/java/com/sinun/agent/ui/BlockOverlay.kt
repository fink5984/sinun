package com.sinun.agent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * מסך החסימה — WebView overlay מעל כל אפליקציה. טוען block_page.html מהנכסים.
 *
 * ביצועים: ה-WebView נוצר ונטען *מראש* (prewarm) ברגע שההגנה עולה, ונשמר חי.
 * בזמן חסימה רק מעדכנים תוכן (evaluateJavascript) ומצרפים לחלון — כך המסך מופיע
 * כמעט מיידית במקום cold-start של WebView בכל פעם (שגרם לעיכוב הנראה למשתמש).
 */
class BlockOverlay(private val context: Context) {

    enum class Kind { APP, DOMAIN }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var webView: WebView? = null
    private var pageReady = false
    private var attached = false
    private var currentKind: Kind? = null
    private var pendingInit: (() -> Unit)? = null

    private val bridge = BlockBridge()

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.OPAQUE,
    )

    /** יוצר וטוען את דף החסימה מראש כדי שההצגה בזמן אמת תהיה מיידית. */
    fun prewarm() = main.post { ensureWebView() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView() {
        if (webView != null || !Settings.canDrawOverlays(context)) return
        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(android.graphics.Color.parseColor("#0D1117"))
            addJavascriptInterface(bridge, "SinunBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, url: String) {
                    pageReady = true
                    pendingInit?.invoke()
                    pendingInit = null
                }
            }
            loadUrl("file:///android_asset/block_page.html")
        }
    }

    fun show(kind: Kind, title: String, subtitle: String, onRequestOpening: () -> Unit) {
        main.post {
            ensureWebView()
            val wv = webView ?: return@post

            // כבר מוצג מסך חסימה — לא מאתחלים מחדש. חשוב במיוחד לדפדפן: טעינת דף
            // מייצרת עשרות שאילתות DNS חסומות (default-deny), וכל אחת הייתה מאפסת
            // את טופס הבקשה שהמשתמש מילא. משאירים את המסך הקיים עד שנסגר.
            if (attached) return@post

            bridge.kind = kind
            bridge.target = subtitle
            bridge.onRequest = onRequestOpening
            currentKind = kind

            val type = if (kind == Kind.APP) "app" else "domain"
            val t = subtitle.jsEscape()
            val h = title.jsEscape()
            val doInit = { wv.evaluateJavascript("init(\"$type\",\"$t\",\"$h\")", null) }
            if (pageReady) doInit() else pendingInit = doInit

            runCatching { wm.addView(wv, layoutParams); attached = true }
        }
    }

    fun hide() = main.post { detach() }
    fun hideIfKind(kind: Kind) = main.post { if (currentKind == kind) detach() }

    /** משחרר לגמרי את ה-WebView (בכיבוי ההגנה). */
    fun destroy() = main.post {
        detach()
        webView?.let { runCatching { it.destroy() } }
        webView = null
        pageReady = false
    }

    private fun detach() {
        if (attached) {
            webView?.let { runCatching { wm.removeView(it) } }
            attached = false
        }
        currentKind = null
    }

    private fun String.jsEscape() = replace("\\", "\\\\").replace("\"", "\\\"")

    private inner class BlockBridge {
        @Volatile var kind: Kind = Kind.APP
        @Volatile var target: String = ""
        @Volatile var onRequest: () -> Unit = {}

        @JavascriptInterface
        fun requestOpening(type: String, target: String, reason: String) {
            onRequest()
            main.post { detach() }
        }

        @JavascriptInterface
        fun goHome() {
            main.post {
                context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                detach()
            }
        }
    }
}
