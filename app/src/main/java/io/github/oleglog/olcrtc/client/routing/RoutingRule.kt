package io.github.oleglog.olcrtc.client.routing

import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

internal data class RoutingRule(
    val id: Long = 0,
    val matchType: MatchType,
    val value: String,
    val action: Action,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
) {
    enum class MatchType {
        DOMAIN,
        DOMAIN_SUFFIX,
        IP,
        CIDR,
        PACKAGE,
    }

    enum class Action {
        DIRECT,
        VPN,
        BLOCK,
    }

    companion object {
        fun create(
            id: Long = 0,
            matchType: MatchType,
            value: String,
            action: Action,
            enabled: Boolean = true,
            sortOrder: Int = 0,
        ): RoutingRule = RoutingRule(
            id = id,
            matchType = matchType,
            value = normalize(matchType, value),
            action = action,
            enabled = enabled,
            sortOrder = sortOrder,
        )

        private fun normalize(matchType: MatchType, value: String): String = when (matchType) {
            MatchType.DOMAIN -> normalizeDomain(value)
            MatchType.DOMAIN_SUFFIX -> normalizeDomain(value.trim().removePrefix("."))
            MatchType.IP -> parseAddress(value).hostAddress
            MatchType.CIDR -> normalizeCidr(value)
            MatchType.PACKAGE -> normalizePackage(value)
        }

        private fun normalizePackage(value: String): String {
            val candidate = value.trim().lowercase(Locale.ROOT)
            require(candidate.isNotEmpty()) { "Package name must not be empty" }
            require(candidate.matches(Regex("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$"))) {
                "Invalid package name format"
            }
            return candidate
        }

        private fun normalizeDomain(value: String): String {
            val normalized = IDN.toASCII(value.trim().trimEnd('.'), IDN.USE_STD3_ASCII_RULES)
                .lowercase(Locale.ROOT)
            require(normalized.isNotEmpty() && normalized.length <= 253) { "Invalid domain" }
            require(normalized.split('.').all { it.isNotEmpty() && it.length <= 63 }) { "Invalid domain" }
            return normalized
        }

        private fun normalizeCidr(value: String): String {
            val parts = value.trim().split('/')
            require(parts.size == 2) { "Invalid CIDR" }
            val address = parseAddress(parts[0])
            val prefix = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid CIDR prefix")
            val maxPrefix = address.address.size * 8
            require(prefix in 0..maxPrefix) { "Invalid CIDR prefix" }
            val bytes = address.address.copyOf()
            for (bit in prefix until maxPrefix) {
                val byteIndex = bit / 8
                bytes[byteIndex] = (bytes[byteIndex].toInt() and (1 shl (7 - bit % 8)).inv()).toByte()
            }
            return "${InetAddress.getByAddress(bytes).hostAddress}/$prefix"
        }

        private fun parseAddress(value: String): InetAddress {
            val candidate = value.trim()
            require('%' !in candidate) { "Scoped IP addresses are not supported" }
            val ipv4 = candidate.split('.')
            if (ipv4.size == 4) {
                require(ipv4.all { part ->
                    part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                        part.toInt() in 0..255 && (part == "0" || !part.startsWith('0'))
                }) { "Invalid IP address" }
                return InetAddress.getByAddress(ByteArray(4) { ipv4[it].toInt().toByte() })
            }
            require(candidate.contains(':')) { "Invalid IP address" }
            val address = runCatching { InetAddress.getByName(candidate) }
                .getOrElse { throw IllegalArgumentException("Invalid IP address", it) }
            require(address is Inet6Address) { "Invalid IP address" }
            return address
        }
    }
}
