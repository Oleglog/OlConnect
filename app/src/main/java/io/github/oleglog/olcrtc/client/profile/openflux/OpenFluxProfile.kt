package io.github.oleglog.olcrtc.client.profile.openflux

import io.github.oleglog.olcrtc.client.routing.DnsEndpoint

data class OpenFluxProfile(
    val name: String,
    val documentUrl: String,
    val transport: Transport = Transport.AUTO,
    val codec: Codec = Codec.BATCHED,
    val dnsServer: String? = null,
    val encryptionKey: String? = null,
) {
    init {
        require(name.isNotBlank()) { "name is required" }
        require(documentUrl.isNotBlank()) { "documentUrl is required" }
        require(documentUrl.startsWith("http://") || documentUrl.startsWith("https://")) {
            "documentUrl must be a valid HTTP or HTTPS URL"
        }
        dnsServer?.let(DnsEndpoint::parse)
    }

    enum class Transport(val value: String) {
        AUTO("auto"),
        VYANDEX("vyandex"),
        YANDEX("yandex"),
        MAILRU("mailru");

        companion object {
            fun parse(value: String): Transport = entries.firstOrNull { it.value == value.trim().lowercase() }
                ?: AUTO
        }
    }

    enum class Codec(val value: String) {
        BATCHED("batched"),
        LEGACY("legacy");

        companion object {
            fun parse(value: String): Codec = entries.firstOrNull { it.value == value.trim().lowercase() }
                ?: BATCHED
        }
    }
}
