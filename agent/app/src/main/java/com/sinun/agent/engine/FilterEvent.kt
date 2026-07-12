package com.sinun.agent.engine

import com.sinun.agent.engine.policy.PolicyEngine

/**
 * אירוע סינון בודד שהמנוע מפיק. נאסף מקומית ומשודר לשרת (מטא-דאטה בלבד —
 * דומיין, אפליקציה, סיבה. לעולם לא תוכן. "סינון, לא ריגול").
 */
data class FilterEvent(
    val domain: String,
    val packageName: String?,
    val verdict: PolicyEngine.Verdict,
    val reason: PolicyEngine.Reason,
    val timestamp: Long = System.currentTimeMillis(),
)

/** צרכן אירועים — הימנעות מתלות ישירה של המנוע ב-networking/DB. */
fun interface FilterEventSink {
    fun onEvent(event: FilterEvent)
}
