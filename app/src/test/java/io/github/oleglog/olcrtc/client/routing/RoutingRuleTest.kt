package io.github.oleglog.olcrtc.client.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RoutingRuleTest {
    @Test
    fun normalizesSupportedMatches() {
        assertEquals(
            "xn--e1afmkfd.xn--p1ai",
            rule(RoutingRule.MatchType.DOMAIN, "Пример.рф.").value,
        )
        assertEquals(
            "example.com",
            rule(RoutingRule.MatchType.DOMAIN_SUFFIX, "  .Example.COM").value,
        )
        assertEquals(
            "192.0.2.1",
            rule(RoutingRule.MatchType.IP, "192.0.2.1").value,
        )
        assertEquals(
            "192.0.2.0/24",
            rule(RoutingRule.MatchType.CIDR, "192.0.2.129/24").value,
        )
        assertEquals(
            "2001:db8:0:0:0:0:0:0/32",
            rule(RoutingRule.MatchType.CIDR, "2001:db8::1/32").value,
        )
        assertEquals(
            "com.google.android.youtube",
            rule(RoutingRule.MatchType.PACKAGE, "  com.google.android.youtube  ").value,
        )
        assertEquals(
            "org.telegram.messenger",
            rule(RoutingRule.MatchType.PACKAGE, "Org.Telegram.Messenger").value,
        )
    }

    @Test
    fun rejectsInvalidMatchesWithoutDnsLookup() {
        listOf(
            RoutingRule.MatchType.DOMAIN to "bad_domain.example",
            RoutingRule.MatchType.IP to "example.com",
            RoutingRule.MatchType.IP to "192.168.001.1",
            RoutingRule.MatchType.CIDR to "192.0.2.1/33",
            RoutingRule.MatchType.CIDR to "2001:db8::1/129",
            RoutingRule.MatchType.IP to "fe80::1%1",
            RoutingRule.MatchType.PACKAGE to "invalid",
            RoutingRule.MatchType.PACKAGE to "com..bad",
            RoutingRule.MatchType.PACKAGE to "com.bad-name.app",
        ).forEach { (type, value) ->
            assertThrows(IllegalArgumentException::class.java) { rule(type, value) }
        }
    }

    private fun rule(type: RoutingRule.MatchType, value: String): RoutingRule =
        RoutingRule.create(matchType = type, value = value, action = RoutingRule.Action.VPN)
}
