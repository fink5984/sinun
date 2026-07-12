package com.sinun.agent.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sinun.agent.BuildConfig
import com.sinun.agent.R
import com.sinun.agent.SinunApp
import com.sinun.agent.data.PolicyState
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

        HeartbeatWorker.schedule(this)
        registerAndLoadPolicy()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    private fun registerAndLoadPolicy() {
        lifecycleScope.launch {
            try {
                repo.ensureRegistered(BuildConfig.VERSION_NAME)
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
        statusText.text = if (FilterVpnService.running) {
            getString(R.string.status_protected)
        } else {
            getString(R.string.status_unprotected)
        }
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
}
