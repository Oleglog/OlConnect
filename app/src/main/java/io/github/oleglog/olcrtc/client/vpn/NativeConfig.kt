package io.github.oleglog.olcrtc.client.vpn

import io.github.oleglog.olcrtc.client.profile.standard.StandardProfile
import io.github.oleglog.olcrtc.client.routing.DnsEndpoint
import io.github.oleglog.olcrtc.client.routing.RoutingPolicy
import io.github.oleglog.olcrtc.client.routing.RoutingRule

internal object NativeConfig {
    fun xray(
        socksPort: Int,
        olcrtcSocksPort: Int? = null,
        dns: DnsEndpoint = DnsEndpoint.parse(DnsEndpoint.DEFAULT),
        routingRules: List<RoutingRule> = emptyList(),
        routingPolicy: RoutingPolicy = RoutingPolicy(),
    ): String {
        val outbound = if (olcrtcSocksPort == null) {
            """{ "protocol": "freedom", "tag": "proxy" }"""
        } else {
            """{
              "protocol": "socks",
              "tag": "proxy",
              "settings": { "servers": [{ "address": "127.0.0.1", "port": $olcrtcSocksPort }] }
            }""".trimIndent()
        }
        return config(socksPort, outbound, dns, routingRules, routingPolicy)
    }

    fun xray(
        socksPort: Int,
        profile: StandardProfile,
        dns: DnsEndpoint = DnsEndpoint.parse(DnsEndpoint.DEFAULT),
        routingRules: List<RoutingRule> = emptyList(),
        routingPolicy: RoutingPolicy = RoutingPolicy(),
    ): String = config(socksPort, standardOutbound(profile), dns, routingRules, routingPolicy)

    fun profileProbe(profiles: List<StandardProfile>): String {
        require(profiles.isNotEmpty()) { "At least one profile is required" }
        val outbounds = profiles.mapIndexed { index, profile ->
            standardOutbound(profile, profileProbeTag(index))
        }
        val rules = profiles.indices.map { index ->
            val tag = profileProbeTag(index)
            """{ "type": "field", "inboundTag": ["$tag"], "outboundTag": "$tag" }"""
        }
        return """
            {
              "log": { "loglevel": "warning" },
              "outbounds": [${outbounds.joinToString(",")}],
              "routing": { "domainStrategy": "AsIs", "rules": [${rules.joinToString(",")}] }
            }
        """.trimIndent()
    }

    fun profileProbeTag(index: Int): String {
        require(index >= 0) { "Invalid profile probe index" }
        return "profile-probe-$index"
    }

    fun hev(socksPort: Int): ByteArray = """
        tunnel:
          mtu: 1500
          ipv4: 10.0.0.2
        socks5:
          port: $socksPort
          address: 127.0.0.1
          udp: udp
        misc:
          log-level: warn
    """.trimIndent().encodeToByteArray()

    private fun config(
        socksPort: Int,
        proxyOutbound: String,
        dns: DnsEndpoint,
        routingRules: List<RoutingRule>,
        routingPolicy: RoutingPolicy,
        domainStrategy: String = "IPIfNonMatch",
    ) = """
        {
          "log": { "loglevel": "warning" },
          "dns": {
            "servers": [{ "address": "${escapeJson(dnsTcpUrl(dns))}", "tag": "$DNS_TAG" }]
          },
          "inbounds": [{
            "listen": "127.0.0.1",
            "port": $socksPort,
            "protocol": "socks",
            "settings": { "udp": true }
          }],
          "outbounds": [
            $proxyOutbound,
            { "protocol": "dns", "tag": "$DNS_OUT_TAG", "settings": { "nonIPQuery": "reject" } },
            { "protocol": "freedom", "tag": "direct" },
            { "protocol": "blackhole", "tag": "block" }
          ]${routing(routingRules, routingPolicy, domainStrategy)}
        }
    """.trimIndent()

    private fun routing(
        rules: List<RoutingRule>,
        policy: RoutingPolicy,
        domainStrategy: String = "IPIfNonMatch",
    ): String {
        val xrayRules = buildList {
            add("{ \"type\": \"field\", \"inboundTag\": [\"$LATENCY_TEST_TAG\"], \"outboundTag\": \"proxy\" }")
            add("{ \"type\": \"field\", \"inboundTag\": [\"$DNS_TAG\"], \"outboundTag\": \"proxy\" }")
            add("{ \"type\": \"field\", \"ip\": [\"$VPN_DNS_ADDRESS\"], \"port\": \"53\", \"network\": \"tcp,udp\", \"outboundTag\": \"$DNS_OUT_TAG\" }")
            add("{ \"type\": \"field\", \"port\": \"$PRIVATE_DNS_PORT\", \"network\": \"tcp\", \"outboundTag\": \"block\" }")
            rules.forEach { rule ->
                val field = when (rule.matchType) {
                    RoutingRule.MatchType.DOMAIN -> "domain" to "full:${escapeJson(rule.value)}"
                    RoutingRule.MatchType.DOMAIN_SUFFIX -> "domain" to "domain:${escapeJson(rule.value)}"
                    RoutingRule.MatchType.IP, RoutingRule.MatchType.CIDR -> "ip" to escapeJson(rule.value)
                    RoutingRule.MatchType.PACKAGE -> null
                }
                if (field != null) {
                    val outboundTag = when (rule.action) {
                        RoutingRule.Action.DIRECT -> "direct"
                        RoutingRule.Action.VPN -> "proxy"
                        RoutingRule.Action.BLOCK -> "block"
                    }
                    add(fieldRule(field.first, field.second, outboundTag))
                }
            }
            if (policy.allowLan) {
                add(fieldRule("ip", LAN_RANGES.joinToString(",") { "\"$it\"" }, "direct", rawValue = true))
            }
            if (policy.preset == RoutingPolicy.Preset.RUSSIA_DIRECT) {
                add(fieldRule("ip", "geoip:ru", "direct"))
                RUSSIA_GEOSITES.forEach { add(fieldRule("domain", it, "direct")) }
            }
            add("{ \"type\": \"field\", \"network\": \"tcp,udp\", \"outboundTag\": \"proxy\" }")
        }
        return ",\n  \"routing\": { \"domainStrategy\": \"$domainStrategy\", \"rules\": [${xrayRules.joinToString(",")}] }"
    }

    private fun fieldRule(field: String, value: String, outboundTag: String, rawValue: Boolean = false): String {
        val values = if (rawValue) value else "\"$value\""
        return "{ \"type\": \"field\", \"$field\": [$values], \"outboundTag\": \"$outboundTag\" }"
    }

    private fun standardOutbound(profile: StandardProfile, tag: String = "proxy"): String {
        val settings = when (profile.protocol) {
            StandardProfile.Protocol.VLESS -> """
                "vnext": [{
                  "address": "${profile.json(profile.address)}",
                  "port": ${profile.port},
                  "users": [{ "id": "${profile.json(profile.uuid!!)}", "encryption": "none"${profile.flow?.let { ", \"flow\": \"${profile.json("$it-udp443")}\"" } ?: ""} }]
                }]${profile.flow?.let { ",\n\"packetEncoding\": \"xudp\"" } ?: ""}
            """.trimIndent()
            StandardProfile.Protocol.VMESS -> """
                "vnext": [{
                  "address": "${profile.json(profile.address)}",
                  "port": ${profile.port},
                  "users": [{ "id": "${profile.json(profile.uuid!!)}", "alterId": ${profile.alterId}, "security": "${profile.json(profile.cipher)}" }]
                }]
            """.trimIndent()
            StandardProfile.Protocol.TROJAN -> """
                "servers": [{ "address": "${profile.json(profile.address)}", "port": ${profile.port}, "password": "${profile.json(profile.password!!)}" }]
            """.trimIndent()
            StandardProfile.Protocol.SHADOWSOCKS -> """
                "servers": [{ "address": "${profile.json(profile.address)}", "port": ${profile.port}, "method": "${profile.json(profile.cipher)}", "password": "${profile.json(profile.password!!)}" }]
            """.trimIndent()
        }
        val protocol = profile.protocol.name.lowercase()
        val streamSettings = if (profile.protocol == StandardProfile.Protocol.SHADOWSOCKS) "" else {
            ",\n  \"streamSettings\": ${streamSettings(profile)}"
        }
        return """{
          "protocol": "$protocol",
          "tag": "$tag",
          "settings": { $settings }$streamSettings
        }""".trimIndent()
    }

    private fun streamSettings(profile: StandardProfile): String {
        val transport = when (profile.transport) {
            StandardProfile.Transport.TCP ->
                // TFO trims one RTT on every fresh TCP connect (handshake +
                // data in one shot). Server opt-in; harmless when ignored.
                "\"tcpSettings\": { \"header\": { \"type\": \"none\" } }," +
                    " \"sockopt\": { \"tcpFastOpen\": true }"
            StandardProfile.Transport.WS -> "\"wsSettings\": { \"path\": \"${profile.json(profile.webSocketPath!!)}\"${profile.webSocketHost?.let { ", \"headers\": { \"Host\": \"${profile.json(it)}\" }" } ?: ""} }," +
                " \"sockopt\": { \"tcpFastOpen\": true }"
            StandardProfile.Transport.GRPC -> "\"grpcSettings\": { \"serviceName\": \"${profile.json(profile.grpcServiceName!!)}\" }"
            StandardProfile.Transport.XHTTP -> "\"xhttpSettings\": { \"mode\": \"${profile.json(profile.xhttpMode!!)}\", \"path\": \"${profile.json(profile.xhttpPath!!)}\"${profile.xhttpHost?.let { ", \"host\": \"${profile.json(it)}\"" } ?: ""}${profile.xhttpExtraJson?.let { ", \"extra\": $it" } ?: ""} }"
        }
        val security = when (profile.security) {
            StandardProfile.Security.NONE -> "\"security\": \"none\""
            StandardProfile.Security.TLS -> "\"security\": \"tls\", \"tlsSettings\": ${tlsSettings(profile)}"
            StandardProfile.Security.REALITY -> "\"security\": \"reality\", \"realitySettings\": ${realitySettings(profile)}"
        }
        return "{ \"network\": \"${profile.transport.xrayName}\", $security, $transport }"
    }

    private fun tlsSettings(profile: StandardProfile) = """{
        "serverName": "${profile.json(profile.serverName ?: profile.address)}",
        "allowInsecure": ${profile.allowInsecure}${profile.fingerprint?.let { ", \"fingerprint\": \"${profile.json(it)}\"" } ?: ""}${profile.alpn.takeIf { it.isNotEmpty() }?.let { ", \"alpn\": [${it.joinToString(",") { value -> "\"${profile.json(value)}\"" }}]" } ?: ""}
    }""".trimIndent()

    private fun realitySettings(profile: StandardProfile) = """{
        "serverName": "${profile.json(profile.serverName ?: profile.address)}",
        "fingerprint": "${profile.json(profile.fingerprint ?: "chrome")}",
        "publicKey": "${profile.json(profile.realityPublicKey!!)}",
        "shortId": "${profile.json(profile.realityShortId!!)}"${profile.realitySpiderX?.let { ", \"spiderX\": \"${profile.json(it)}\"" } ?: ""}
    }""".trimIndent()

    private val StandardProfile.Transport.xrayName: String
        get() = when (this) {
            StandardProfile.Transport.TCP -> "tcp"
            StandardProfile.Transport.WS -> "ws"
            StandardProfile.Transport.GRPC -> "grpc"
            StandardProfile.Transport.XHTTP -> "xhttp"
        }

    private fun StandardProfile.json(value: String): String = escapeJson(value)

    private fun dnsTcpUrl(dns: DnsEndpoint): String {
        return "tcp://${dnsAddress(dns, dns.port)}"
    }

    private fun dnsAddress(dns: DnsEndpoint, port: Int): String =
        "${if (':' in dns.address) "[${dns.address}]" else dns.address}:$port"

    private fun escapeJson(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }

    private const val DNS_TAG = "dns-proxy"
    private const val DNS_OUT_TAG = "dns-out"
    private const val PRIVATE_DNS_PORT = 853
    private const val LATENCY_TEST_TAG = "latency-test"
    private const val MAX_PARALLEL_PROBES = 64
    const val VPN_DNS_ADDRESS = "10.0.0.1"
    private val LAN_RANGES = listOf(
        "10.0.0.0/8",
        "100.64.0.0/10",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "::1/128",
        "fc00::/7",
        "fe80::/10",
    )
    private val RUSSIA_GEOSITES = listOf(
        "domain:ru",
        "domain:xn--p1ai",
        "geosite:category-ru",
        "geosite:ru-available-only-inside",
    )
}
