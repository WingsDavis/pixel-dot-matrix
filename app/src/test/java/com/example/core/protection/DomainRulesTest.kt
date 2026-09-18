package com.example.core.protection

import org.junit.Assert.*
import org.junit.Test

class DomainRulesTest {
    @Test fun normalizesUrlsCaseAndTrailingDots() = assertEquals("example.com", DomainRules.normalize("HTTPS://Example.COM./feed"))
    @Test fun rejectsInvalidLabels() { assertNull(DomainRules.normalize("bad domain.com")); assertNull(DomainRules.normalize("-bad.com")) }
    @Test fun matchesOnlyDomainAndSubdomains() {
        assertTrue(DomainRules.matches("www.reddit.com", setOf("reddit.com")))
        assertFalse(DomainRules.matches("notreddit.com", setOf("reddit.com")))
    }
}
