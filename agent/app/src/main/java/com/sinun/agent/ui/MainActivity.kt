package com.sinun.agent.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sinun.agent.BuildConfig
import com.sinun.agent.R
import com.sinun.agent.SinunApp
import com.sinun.agent.admin.SinunDeviceAdminReceiver
import com.sinun.agent.data.PolicyState
import com.sinun.agent.monitor.AppMonitor
import com.sinun.agent.vpn.FilterVpnService
import com.sinun.agent.work.HeartbeatWorker
import kotlinx.coroutines.launch

/**
 * מסך הסטטוס (שבוע 1): מוגן / לא מוגן / שגיאה, מצב ה-policy, וכפתור "בקש פתיחה".
 */
class MainActivity : AppCompatActivity() {

    private val repo by lazy { (application as SinunApp).policyRepository }

    private lateinit var statusText: TextView
    private lateinit var policyText: TextView

    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) startVpnService()
        else Toast.makeText(this, "בלי אישור VPN אין הגנה", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        policyText = findViewById(R.id.policy_text)

        findViewById<Button>(R.id.btn_start_vpn).setOnClickListener { requestVpn() }
        findViewById<Button>(R.id.btn_request_opening).setOnClickListener { sendDemoOpeningRequest() }
        findViewById<Button>(R.id.btn_usage_access).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.btn_overlay).setOnClickListener {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ))
        }
        findViewById<Button>(R.id.btn_device_admin).setOnClickListener {
            startActivity(SinunDeviceAdminReceiver.activationIntent(this))
        }
        findViewById<Button>(R.id.btn_request_removal).setOnClickListener { promptRemovalRequest() }
        findViewById<Button>(R.id.btn_enter_removal_code).setOnClickListener { promptRemovalCode() }

        HeartbeatWorker.schedule(this)
        if (repo.isEnrolled) loadPolicy() else promptForEnrollmentCode()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        renderPermissions()
    }

    /** מציג אילו הרשאות ל-App Control כבר הוענקו. שתיהן נדרשות למסך החסימה. */
    private fun renderPermissions() {
        val usage = AppMonitor.hasUsageAccess(this)
        val overlay = Settings.canDrawOverlays(this)
        findViewById<Button>(R.id.btn_usage_access).apply {
            text = getString(if (usage) R.string.perm_usage else R.string.perm_usage_missing)
            isEnabled = !usage
        }
        findViewById<Button>(R.id.btn_overlay).apply {
            text = getString(if (overlay) R.string.perm_overlay else R.string.perm_overlay_missing)
            isEnabled = !overlay
        }
        val admin = SinunDeviceAdminReceiver.isActive(this)
        findViewById<Button>(R.id.btn_device_admin).apply {
            text = getString(if (admin) R.string.perm_admin else R.string.perm_admin_missing)
            isEnabled = !admin
        }
    }

    /** מסך ההצטרפות: הלקוח מזין את הקוד החד-פעמי שקיבל מהמנהל. */
    private fun promptForEnrollmentCode() {
        val input = EditText(this).apply {
            hint = getString(R.string.enroll_code_hint)
            inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.enroll_title)
            .setMessage(R.string.enroll_message)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(R.string.enroll_confirm) { _, _ -> enroll(input.text.toString()) }
            .show()
    }

    private fun enroll(code: String) {
        if (code.isBlank()) {
            promptForEnrollmentCode()
            return
        }
        lifecycleScope.launch {
            try {
                repo.enroll(code, BuildConfig.VERSION_NAME)
                loadPolicy()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.enroll_failed, e.message), Toast.LENGTH_LONG).show()
                promptForEnrollmentCode()
            }
        }
    }

    private fun loadPolicy() {
        lifecycleScope.launch {
            try {
                renderPolicy(repo.refreshPolicy())
            } catch (e: Exception) {
                statusText.text = getString(R.string.status_error, e.message)
                renderPolicy(repo.cachedPolicy())
            }
            renderStatus()
        }
    }

    private fun renderStatus() {
        if (!::statusText.isInitialized) return
        val protected = FilterVpnService.running
        statusText.text = if (protected) getString(R.string.status_protected)
                          else getString(R.string.status_unprotected)
        // צביעת הנקודה: ירוק = מוגן, אדום = לא מוגן
        val dotColor = if (protected) 0xFF3FB950.toInt() else 0xFFF85149.toInt()
        val dot = findViewById<android.view.View?>(R.id.status_dot)
        dot?.setBackgroundColor(dotColor)
    }

    private fun renderPolicy(state: PolicyState) {
        policyText.text = when (state) {
            is PolicyState.Active -> {
                val source = if (state.fromCache) getString(R.string.policy_from_cache) else getString(R.string.policy_from_server)
                val allowed = state.policy.optJSONArray("allowed_domains")?.length() ?: 0
                val blocked = state.policy.optJSONArray("blocked_domains")?.length() ?: 0
                getString(
                    R.string.policy_summary,
                    state.policy.optString("policy_id"), source, allowed, blocked,
                )
            }
            PolicyState.NoPolicy -> getString(R.string.policy_none)
        }
    }

    private fun requestVpn() {
        val consentIntent = VpnService.prepare(this)
        if (consentIntent != null) vpnConsent.launch(consentIntent) else startVpnService()
    }

    private fun startVpnService() {
        startForegroundService(Intent(this, FilterVpnService::class.java))
        statusText.postDelayed({ renderStatus() }, 500)
    }

    /** שלד לזרימת "בקש פתיחה" — בשבוע 4 יוחלף בדיאלוג אמיתי מתוך מסך החסימה. */
    private fun sendDemoOpeningRequest() {
        lifecycleScope.launch {
            try {
                repo.requestOpening(type = "domain", target = "example.com", reason = "בדיקת זרימת בקשות")
                Toast.makeText(this@MainActivity, R.string.request_sent, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.request_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * שלב 1 מתוך 2: המשתמש שולח בקשת הסרה למנהל.
     * המנהל רואה את הבקשה בפאנל ויכול ליצור קוד חד-פעמי.
     */
    private fun promptRemovalRequest() {
        val input = EditText(this).apply {
            hint = getString(R.string.removal_request_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.removal_request_title)
            .setMessage(R.string.removal_request_message)
            .setView(input)
            .setPositiveButton(getString(R.string.enroll_confirm)) { _, _ ->
                val reason = input.text.toString().trim()
                lifecycleScope.launch {
                    try {
                        repo.requestRemoval(reason.ifBlank { "ללא סיבה" })
                        Toast.makeText(this@MainActivity, R.string.removal_request_sent, Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.removal_request_failed, e.message),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * שלב 2 מתוך 2: המשתמש מזין את הקוד שקיבל מהמנהל.
     * אם תקין — מבטל Device Admin ומפתח את אפשרות ההסרה.
     */
    private fun promptRemovalCode() {
        val input = EditText(this).apply {
            hint = getString(R.string.removal_code_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.removal_code_title)
            .setMessage(R.string.removal_code_message)
            .setView(input)
            .setPositiveButton(getString(R.string.removal_code_confirm)) { _, _ ->
                val code = input.text.toString().trim()
                if (code.length != 6) {
                    Toast.makeText(this, R.string.removal_code_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val authorized = repo.verifyUninstallCode(code)
                    if (!authorized) {
                        Toast.makeText(this@MainActivity, R.string.removal_code_invalid, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    Toast.makeText(this@MainActivity, R.string.removal_deactivating, Toast.LENGTH_SHORT).show()
                    // שלב 1: ביטול Device Admin (כדי שהמערכת תאפשר הסרה)
                    SinunDeviceAdminReceiver.deactivate(this@MainActivity)
                    // שלב 2: פתיחת מסך הסרת האפליקציה של המערכת
                    startActivity(
                        Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
