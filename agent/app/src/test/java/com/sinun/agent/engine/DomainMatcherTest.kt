package com.sinun.agent.engine

import com.sinun.agent.engine.policy.DomainMatcher
import com.sinun.agent.engine.policy.DomainMatcher.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class DomainMatcherTest {

    @Test fun `allow rule covers subdomains`() {
        val m = DomainMatcher()
        m.add("google.com", Decision.ALLOW)
        assertEquals(Decision.ALLOW, m.match("google.com"))
        assertEquals(Decision.ALLOW, m.match("mail.google.com"))
        assertEquals(Decision.ALLOW, m.match("a.b.google.com"))
    }

    @Test fun `unknown domain is unknown`() {
        val m = DomainMatcher()
        m.add("google.com", Decision.ALLOW)
        assertEquals(Decision.UNKNOWN, m.match("example.com"))
    }

    @Test fun `block rule covers subdomains`() {
        val m = DomainMatcher()
        m.add("bad.example", Decision.BLOCK)
        assertEquals(Decision.BLOCK, m.match("bad.example"))
        assertEquals(Decision.BLOCK, m.match("tracker.bad.example"))
    }

    @Test fun `more specific allow overrides broader block`() {
        val m = DomainMatcher()
        m.add("ads.com", Decision.BLOCK)
        m.add("good.ads.com", Decision.ALLOW)
        assertEquals(Decision.ALLOW, m.match("good.ads.com"))
        assertEquals(Decision.BLOCK, m.match("evil.ads.com"))
        assertEquals(Decision.BLOCK, m.match("ads.com"))
    }

    @Test fun `case and trailing dot are normalized`() {
        val m = DomainMatcher()
        m.add("Google.COM", Decision.ALLOW)
        assertEquals(Decision.ALLOW, m.match("MAIL.google.com."))
    }
}
