package com.sinun.agent.engine.policy

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * המוח של המנוע. מקבל את ה-policy (מהשרת / cache), מקמפל אותו למבני נתונים מהירים,
 * ומכריע allow/block לכל דומיין — ברמת רשת וברמת אפליקציה.
 *
 * עקרונות:
 *  - Default deny: דומיין שלא הוכרע במפורש → נחסם (אם ה-policy ב-block).
 *  - שכבות: כלל פר-אפליקציה גובר על כלל גלובלי; allow ידני גובר על block כללי.
 *  - Anti-bypass: רשימת דומיינים/IP של DoH ו-DoT ידועים תמיד נחסמת כדי לכפות
 *    את כל רזולוציית ה-DNS דרך המסנן שלנו.
 *  - Hot-swap: החלפת policy אטומית (AtomicReference) — הלולאה בזמן אמת לא ננעלת.
 */
class PolicyEngine {

    enum class Verdict { ALLOW, BLOCK }

    /** מצב מקומפל ואימיוטבילי — מוחלף כמקשה אחת בכל עדכון policy. */
    private class Compiled(
        val domains: DomainMatcher,
        val perApp: Map<String, DomainMatcher>,  // packageName → matcher
        val blockedPackages: Set<String>,
        val allowedPackages: Set<String>,
        val defaultNetworkBlock: Boolean,
        val defaultAppBlock: Boolean,
        val policyId: String,
        val version: Long,
    )

    private val state = AtomicReference(EMPTY)

    val policyId: String get() = state.get().policyId
    val version: Long get() = state.get().version
    val hasPolicy: Boolean get() = state.get() !== EMPTY

    /** טוען policy מה-JSON שהשרת מפיק (ראה backend/policy_engine.py). */
    fun load(policy: JSONObject) {
        val domains = DomainMatcher()
        policy.optJSONArray("allowed_domains")?.let { arr ->
            for (i in 0 until arr.length()) domains.add(arr.getString(i), DomainMatcher.Decision.ALLOW)
        }
        policy.optJSONArray("blocked_domains")?.let { arr ->
            for (i in 0 until arr.length()) domains.add(arr.getString(i), DomainMatcher.Decision.BLOCK)
        }

        val blocked = HashSet<String>()
        policy.optJSONArray("blocked_apps")?.let { arr ->
            for (i in 0 until arr.length()) blocked.add(arr.getJSONObject(i).getString("package_name"))
        }
        val allowed = HashSet<String>()
        policy.optJSONArray("allowed_apps")?.let { arr ->
            for (i in 0 until arr.length()) allowed.add(arr.getJSONObject(i).getString("package_name"))
        }

        val defaultNetBlock = policy.optString("default_network_action", "block") == "block"
        val defaultAppBlock = policy.optString("default_app_action", "block") == "block"

        state.set(Compiled(
            domains = domains,
            perApp = emptyMap(),   // מקום לכללי per-app-domain עתידיים
            blockedPackages = blocked,
            allowedPackages = allowed,
            defaultNetworkBlock = defaultNetBlock,
            defaultAppBlock = defaultAppBlock,
            policyId = policy.optString("policy_id", "unknown"),
            version = policy.optLong("version", 0),
        ))
    }

    /**
     * הכרעה לדומיין שנשאל ב-DNS או מופיע ב-SNI.
     * @param packageName האפליקציה שיזמה את החיבור (אם ידוע), לסינון פר-אפליקציה.
     */
    fun evaluateDomain(domain: String, packageName: String? = null): Decision {
        val s = state.get()

        // 1. Anti-bypass — לפני הכל: חוסמים ערוצי DNS חלופיים תמיד.
        if (BYPASS_DOMAINS.match(domain) == DomainMatcher.Decision.BLOCK) {
            return Decision(Verdict.BLOCK, Reason.ANTI_BYPASS, packageName)
        }

        // 2. כלל פר-אפליקציה מפורש (אפליקציה חסומה → כל התעבורה שלה חסומה).
        if (packageName != null) {
            if (packageName in s.blockedPackages) return Decision(Verdict.BLOCK, Reason.APP_BLOCKED, packageName)
            s.perApp[packageName]?.let { m ->
                when (m.match(domain)) {
                    DomainMatcher.Decision.ALLOW -> return Decision(Verdict.ALLOW, Reason.APP_RULE, packageName)
                    DomainMatcher.Decision.BLOCK -> return Decision(Verdict.BLOCK, Reason.APP_RULE, packageName)
                    DomainMatcher.Decision.UNKNOWN -> {}
                }
            }
        }

        // 3. כלל דומיין גלובלי.
        when (s.domains.match(domain)) {
            DomainMatcher.Decision.ALLOW -> return Decision(Verdict.ALLOW, Reason.DOMAIN_RULE, packageName)
            DomainMatcher.Decision.BLOCK -> return Decision(Verdict.BLOCK, Reason.DOMAIN_RULE, packageName)
            DomainMatcher.Decision.UNKNOWN -> {}
        }

        // 4. ברירת מחדל.
        return if (s.defaultNetworkBlock) {
            Decision(Verdict.BLOCK, Reason.DEFAULT_DENY, packageName)
        } else {
            Decision(Verdict.ALLOW, Reason.DEFAULT_ALLOW, packageName)
        }
    }

    /**
     * האם האפליקציה חסומה *במפורש* (ברשימת blocked_apps).
     * שונה מ-evaluateApp: לא מחיל default-deny — כדי שה-App Monitor לא יחסום את
     * כל המכשיר (מסך הבית, הגדרות...) אלא רק אפליקציות שסומנו לחסימה.
     */
    fun isAppBlocked(packageName: String): Boolean = packageName in state.get().blockedPackages

    /** הכרעה לפתיחת אפליקציה (Launcher סגור — שכבה עתידית, מחיל default-deny). */
    fun evaluateApp(packageName: String): Decision {
        val s = state.get()
        if (packageName in s.blockedPackages) return Decision(Verdict.BLOCK, Reason.APP_BLOCKED, packageName)
        if (packageName in s.allowedPackages) return Decision(Verdict.ALLOW, Reason.APP_RULE, packageName)
        return if (s.defaultAppBlock) {
            Decision(Verdict.BLOCK, Reason.DEFAULT_DENY, packageName)
        } else {
            Decision(Verdict.ALLOW, Reason.DEFAULT_ALLOW, packageName)
        }
    }

    enum class Reason { ANTI_BYPASS, APP_BLOCKED, APP_RULE, DOMAIN_RULE, DEFAULT_DENY, DEFAULT_ALLOW }

    data class Decision(val verdict: Verdict, val reason: Reason, val packageName: String?) {
        val isBlock: Boolean get() = verdict == Verdict.BLOCK
    }

    companion object {
        private val EMPTY = Compiled(
            DomainMatcher(), emptyMap(), emptySet(), emptySet(),
            defaultNetworkBlock = true, defaultAppBlock = true,
            policyId = "none", version = 0,
        )

        /**
         * ערוצי DNS חלופיים שחוסמים תמיד כדי למנוע עקיפה של סינון ה-DNS:
         * DNS-over-HTTPS ציבוריים (Chrome מפעיל DoH אוטומטית!) ו-DoT.
         * זה הלב של האכיפה נגד "משתמש שעלול לעקוף".
         */
        private val BYPASS_DOMAINS = DomainMatcher().apply {
            listOf(
                "dns.google", "dns.google.com",
                "cloudflare-dns.com", "one.one.one.one", "mozilla.cloudflare-dns.com",
                "dns.quad9.net", "doh.opendns.com", "dns.adguard.com",
                "dns.nextdns.io", "doh.cleanbrowsing.org", "chrome.cloudflare-dns.com",
                "dns11.quad9.net", "family.cloudflare-dns.com", "security.cloudflare-dns.com",
            ).forEach { add(it, DomainMatcher.Decision.BLOCK) }
        }
    }
}
