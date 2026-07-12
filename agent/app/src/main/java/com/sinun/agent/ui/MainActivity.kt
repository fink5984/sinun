package com.sinun.agent.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sinun.agent.BuildConfig
import com.sinun.agent.SinunApp
import com.sinun.agent.admin.SinunDeviceAdminReceiver
import com.sinun.agent.data.PolicyState
import com.sinun.agent.monitor.AppMonitor
import com.sinun.agent.vpn.FilterVpnService
import com.sinun.agent.work.HeartbeatWorker
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * מסך האפליקציה — WebView יחיד שטוען app.html ומנהל שלושה מצבים: הרשמה, אשף
 * הגדרה מודרך (שלב אחר שלב), ומסך בית. הלוגיקה הנייטיבית (הרשאות, VPN, בקשות)
 * נחשפת ל-HTML דרך גשר "Native", והמצב נדחף חזרה ל-WebView בכל onResume.
 */
class MainActivity : AppCompatActivity() {

    private val repo by lazy { (application as SinunApp).policyRepository }
    private lateinit var web: WebView
    private var pageReady = false

    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) startVpnService()
        else Toast.makeText(this, "בלי אישור VPN אין הגנה", Toast.LENGTH_LONG).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF173A67.toInt()

        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(0xFFEEF3FB.toInt())
            addJavascriptInterface(Native(), "Native")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, url: String) {
                    pageReady = true
                    pushState()
                }
            }
            loadUrl("file:///android_asset/app.html")
        }
        setContentView(web)

        HeartbeatWorker.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        pushState()
    }

    // ==================== גשר ל-HTML ====================

    private inner class Native {
        @JavascriptInterface
        fun enroll(code: String) {
            lifecycleScope.launch {
                try {
                    repo.enroll(code, BuildConfig.VERSION_NAME)
                    runCatching { repo.reportInstalledApps() }
                    pushState()
                } catch (e: Exception) {
                    val msg = JSONObject.quote(getString(errMsg(e)))
                    runOnUiThread { web.evaluateJavascript("enrollError($msg)", null) }
                }
            }
        }

        @JavascriptInterface
        fun action(id: String) {
            runOnUiThread {
                when (id) {
                    "usage" -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    "overlay" -> startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ))
                    "admin" -> startActivity(SinunDeviceAdminReceiver.activationIntent(this@MainActivity))
                    "vpn" -> requestVpn()
                    "request_opening" -> promptOpeningRequest()
                    "refresh" -> pushState()
                }
            }
        }
    }

    private fun errMsg(e: Exception): Int =
        com.sinun.agent.R.string.enroll_failed_short

    // ==================== מצב → WebView ====================

    private fun pushState() {
        if (!pageReady) return
        lifecycleScope.launch {
            val state = buildState()
            val quoted = JSONObject.quote(state.toString())
            runOnUiThread {
                if (pageReady) web.evaluateJavascript("setState($quoted)", null)
            }
        }
    }

    private suspend fun buildState(): JSONObject {
        val enrolled = repo.isEnrolled
        val usage = AppMonitor.hasUsageAccess(this)
        val overlay = Settings.canDrawOverlays(this)
        val admin = SinunDeviceAdminReceiver.isActive(this)
        val vpn = FilterVpnService.running

        val steps = JSONArray()
            .put(step("usage", usage))
            .put(step("overlay", overlay))
            .put(step("admin", admin))
            .put(step("vpn", vpn))
        val allDone = usage && overlay && admin && vpn

        val json = JSONObject()
            .put("enrolled", enrolled)
            .put("allDone", allDone)
            .put("protected", vpn)
            .put("version", "v" + BuildConfig.VERSION_NAME)
            .put("steps", steps)

        if (enrolled) {
            json.put("perms", JSONArray()
                .put(permRow("זיהוי אפליקציות", usage))
                .put(permRow("מסך חסימה", overlay))
                .put(permRow("הגנה מפני הסרה", admin))
                .put(permRow("סינון פעיל", vpn)))
            when (val s = repo.cachedPolicy()) {
                is PolicyState.Active -> {
                    json.put("policyName", s.policy.optString("policy_id", ""))
                    json.put("allowedCount", s.policy.optJSONArray("allowed_domains")?.length() ?: 0)
                    json.put("blockedCount", s.policy.optJSONArray("blocked_domains")?.length() ?: 0)
                }
                PolicyState.NoPolicy -> {}
            }
        }
        return json
    }

    private fun step(id: String, done: Boolean) = JSONObject().put("id", id).put("done", done)
    private fun permRow(title: String, ok: Boolean) = JSONObject().put("title", title).put("ok", ok)

    // ==================== פעולות ====================

    private fun requestVpn() {
        val consentIntent = VpnService.prepare(this)
        if (consentIntent != null) vpnConsent.launch(consentIntent) else startVpnService()
    }

    private fun startVpnService() {
        startForegroundService(Intent(this, FilterVpnService::class.java))
        web.postDelayed({ pushState() }, 900)
    }

    /** דיאלוג בקשת פתיחה מהמסך הראשי — המשתמש מזין אתר/אפליקציה וסיבה. */
    private fun promptOpeningRequest() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val targetInput = EditText(this).apply {
            hint = "כתובת אתר או שם אפליקציה"
            inputType = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val reasonInput = EditText(this).apply {
            hint = "סיבת הבקשה (אופציונלי)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        container.addView(targetInput)
        container.addView(reasonInput)

        AlertDialog.Builder(this)
            .setTitle("בקשת פתיחה")
            .setMessage("הבקשה תישלח למנהל לאישור.")
            .setView(container)
            .setPositiveButton("שלח") { _, _ ->
                val target = targetInput.text.toString().trim()
                if (target.isEmpty()) {
                    Toast.makeText(this, "נא להזין אתר או אפליקציה", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val reason = reasonInput.text.toString().trim().ifBlank { "ללא סיבה" }
                lifecycleScope.launch {
                    try {
                        repo.requestOpening("domain", target, reason)
                        Toast.makeText(this@MainActivity, "הבקשה נשלחה למנהל", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "שליחת הבקשה נכשלה", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
