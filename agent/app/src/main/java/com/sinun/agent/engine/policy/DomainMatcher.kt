package com.sinun.agent.engine.policy

/**
 * התאמת דומיינים מהירה מבוססת suffix-trie.
 *
 * דומיין נשמר הפוך (com.google.mail) כדי שהתאמת סיומת תהיה מעבר במורד העץ.
 * כלל על "google.com" תופס אוטומטית את כל תת-הדומיינים (mail.google.com, ...),
 * וזה מה שהופך את ההתאמה ל-O(מספר הרכיבים בדומיין) ולא תלוי בגודל רשימת הכללים.
 *
 * הכרעה: allow גובר על block באותה רמת ספציפיות (כלל ידני מפורש), אחרת הכלל
 * הספציפי ביותר (העמוק בעץ) קובע.
 */
class DomainMatcher {

    enum class Decision { ALLOW, BLOCK, UNKNOWN }

    private class Node {
        val children = HashMap<String, Node>()
        var decision: Decision = Decision.UNKNOWN
        // כלל שחל גם על כל תת-הדומיינים (ברירת המחדל) לעומת התאמה מדויקת בלבד
        var appliesToSubdomains: Boolean = true
    }

    private val root = Node()

    fun add(domain: String, decision: Decision, includeSubdomains: Boolean = true) {
        val labels = normalize(domain) ?: return
        var node = root
        for (label in labels.asReversed()) {
            node = node.children.getOrPut(label) { Node() }
        }
        // allow ידני לא נדרס ע"י block כללי שנוסף אחריו
        if (node.decision == Decision.ALLOW && decision == Decision.BLOCK) return
        node.decision = decision
        node.appliesToSubdomains = includeSubdomains
    }

    /**
     * מחזיר את ההכרעה הספציפית ביותר לדומיין. עוברים במורד העץ לפי הרכיבים;
     * זוכרים את ההכרעה האחרונה שראינו שחלה על תת-דומיינים (סיומת תואמת).
     */
    fun match(domain: String): Decision {
        val labels = normalize(domain) ?: return Decision.UNKNOWN
        var node = root
        var best = Decision.UNKNOWN
        for ((idx, label) in labels.asReversed().withIndex()) {
            val next = node.children[label] ?: break
            node = next
            val isExact = idx == labels.size - 1
            if (node.decision != Decision.UNKNOWN && (node.appliesToSubdomains || isExact)) {
                best = node.decision
            }
        }
        return best
    }

    fun clear() {
        root.children.clear()
        root.decision = Decision.UNKNOWN
    }

    private fun normalize(domain: String): List<String>? {
        val trimmed = domain.trim().trimEnd('.').lowercase()
        if (trimmed.isEmpty()) return null
        val labels = trimmed.split('.')
        return if (labels.any { it.isEmpty() }) null else labels
    }
}
