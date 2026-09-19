package io.github.oleglog.olcrtc.client.profile.openflux

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object OpenFluxUri {
    fun parse(raw: String): OpenFluxProfile {
        val trimmed = raw.trim()
        require(trimmed.startsWith("openflux://", ignoreCase = true)) {
            "OpenFlux profile URI must start with openflux://"
        }

        val uri = URI(trimmed)
        val params = parseQuery(uri.rawQuery)
        val urlParam = params["url"] ?: params["u"]
        require(!urlParam.isNullOrBlank()) { "OpenFlux URI requires 'url' parameter" }

        val transportParam = params["t"] ?: params["transport"] ?: "auto"
        val dnsParam = params["d"] ?: params["dns"]
        val keyParam = params["k"] ?: params["key"]
        val fragment = uri.rawFragment?.let(::decode)?.takeIf(String::isNotBlank)

        val name = fragment ?: "OpenFlux"

        return OpenFluxProfile(
            name = name,
            documentUrl = urlParam,
            transport = OpenFluxProfile.Transport.parse(transportParam),
            dnsServer = dnsParam,
            encryptionKey = keyParam,
        )
    }

    fun serialize(profile: OpenFluxProfile): String {
        val encodedUrl = encode(profile.documentUrl)
        val host = if (profile.transport == OpenFluxProfile.Transport.MAILRU || profile.documentUrl.contains("mail.ru")) "mailru" else "yandex"
        val builder = StringBuilder("openflux://$host?url=").append(encodedUrl)
        if (profile.transport != OpenFluxProfile.Transport.AUTO) {
            builder.append("&t=").append(encode(profile.transport.value))
        }
        if (!profile.dnsServer.isNullOrBlank()) {
            builder.append("&d=").append(encode(profile.dnsServer))
        }
        if (!profile.encryptionKey.isNullOrBlank()) {
            builder.append("&k=").append(encode(profile.encryptionKey))
        }
        if (profile.name.isNotBlank()) {
            builder.append("#").append(encode(profile.name))
        }
        return builder.toString()
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val result = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { item ->
            val pair = item.split('=', limit = 2)
            val key = decode(pair[0])
            if (key.isNotEmpty() && pair.size == 2) {
                result[key] = decode(pair[1])
            }
        }
        return result
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
