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
 */
class BlockOverlay(private val context: Context) {

    enum class Kind { APP, DOMAIN }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var currentKind: Kind? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun show(kind: Kind, title: String, subtitle: String, onRequestOpening: () -> Unit) {
        main.post {
            if (view != null) return@post
            if (!Settings.canDrawOverlays(context)) return@post

            val webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(android.graphics.Color.parseColor("#0D1117"))
                addJavascriptInterface(BlockBridge(kind, subtitle, onRequestOpening), "SinunBridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, url: String) {
                        val type = if (kind == Kind.APP) "app" else "domain"
                        val t = subtitle.replace("\\", "\\\\").replace("\"", "\\\"")
                        val h = title.replace("\\", "\\\\").replace("\"", "\\\"")
                        v.evaluateJavascript("init(\"$type\",\"$t\",\"$h\")", null)
                    }
                }
                loadUrl("file:///android_asset/block_page.html")
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE,
            )
            runCatching { wm.addView(webView, params); view = webView; currentKind = kind }
        }
    }

    fun hide() = main.post { removeView() }
    fun hideIfKind(kind: Kind) = main.post { if (currentKind == kind) removeView() }

    private fun removeView() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        currentKind = null
    }

    private inner class BlockBridge(
        private val kind: Kind,
        private val target: String,
        private val onRequestOpening: () -> Unit,
    ) {
        @JavascriptInterface
        fun requestOpening(type: String, target: String, reason: String) {
            onRequestOpening()
            main.post { removeView() }
        }

        @JavascriptInterface
        fun goHome() {
            main.post {
                context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                removeView()
            }
        }
    }
}
