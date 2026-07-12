package com.sinun.agent.engine

import com.sinun.agent.engine.policy.PolicyEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEngineTest {

    private fun policy(
        defaultNet: String = "block",
        allowed: List<String> = emptyList(),
        blocked: List<String> = emptyList(),
        blockedApps: List<String> = emptyList(),
    ) = JSONObject().apply {
        put("policy_id", "test")
        put("version", 1)
        put("default_network_action", defaultNet)
        put("default_app_action", "block")
        put("allowed_domains", JSONArray(allowed))
        put("blocked_domains", JSONArray(blocked))
        put("allowed_apps", JSONArray())
        put("blocked_apps", JSONArray(blockedApps.map { JSONObject().put("package_name", it) }))
    }

    @Test fun `default deny blocks unknown domains`() {
        val e = PolicyEngine().apply { load(policy(allowed = listOf("google.com"))) }
        assertTrue(e.evaluateDomain("evil.example").isBlock)
        assertEquals(PolicyEngine.Verdict.ALLOW, e.evaluateDomain("mail.google.com").verdict)
    }

    @Test fun `explicit block wins even under default allow`() {
        val e = PolicyEngine().apply { load(policy(defaultNet = "allow", blocked = listOf("bad.example"))) }
        assertTrue(e.evaluateDomain("bad.example").isBlock)
        assertEquals(PolicyEngine.Verdict.ALLOW, e.evaluateDomain("anything.else").verdict)
    }

    @Test fun `anti-bypass blocks DoH endpoints regardless of policy`() {
        // גם כשברירת המחדל allow והכול פתוח — ערוצי ה-DNS החלופיים חסומים.
        val e = PolicyEngine().apply { load(policy(defaultNet = "allow", allowed = listOf("cloudflare-dns.com"))) }
        val d = e.evaluateDomain("cloudflare-dns.com")
        assertTrue(d.isBlock)
        assertEquals(PolicyEngine.Reason.ANTI_BYPASS, d.reason)
    }

    @Test fun `blocked app blocks all its traffic`() {
        val e = PolicyEngine().apply {
            load(policy(defaultNet = "allow", blockedApps = listOf("com.bad.app")))
        }
        assertTrue(e.evaluateDomain("cdn.example", "com.bad.app").isBlock)
        assertEquals(PolicyEngine.Verdict.ALLOW, e.evaluateDomain("cdn.example", "com.good.app").verdict)
    }

    @Test fun `no policy loaded is fail-closed`() {
        val e = PolicyEngine()
        assertTrue(e.evaluateDomain("anything.com").isBlock)
    }
}
