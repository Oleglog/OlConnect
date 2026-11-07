package io.github.oleglog.olcrtc.client.profile.olcrtc

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object OlcrtcUri {
    private val allowedParameters = setOf(
        "key", "k",
        "transport", "t",
        "core",
        "vp8_fps", "f",
        "vp8_batch", "b",
        "client_id", "c",
        "dns", "d",
        "room_password", "rp",
        "keepalive", "ka",
    )

    fun parse(raw: String): OlcrtcProfile {
        val uri = URI(raw)
        require(uri.scheme.equals("olconnect", ignoreCase = true) || uri.scheme.equals("olcrtc", ignoreCase = true)) { "Unsupported URI scheme" }
        val authority = uri.rawAuthority?.split('@')?.takeIf { it.size == 2 }
        val provider = (uri.userInfo ?: authority?.firstOrNull()?.let(::decode))
            ?.let(OlcrtcProfile.Provider::parse)
            ?: throw IllegalArgumentException("Provider is required")
        val roomHost = uri.host ?: authority?.lastOrNull()
            ?.takeIf { it.isNotBlank() && ':' !in it }
        require(!roomHost.isNullOrBlank()) { "olcRTC URI host is required" }

        val params = parseQuery(uri.rawQuery)
        val unknown = params.keys - allowedParameters
        require(unknown.isEmpty()) { "Unsupported olcRTC parameters: ${unknown.sorted().joinToString()}" }

        val transport = parameter(params, "transport", "t")
            ?.let(OlcrtcProfile.Transport::parse)
            ?: OlcrtcProfile.Transport.DATACHANNEL
        val compatibilityMode = parameter(params, "core")
            ?.let(OlcrtcProfile.CompatibilityMode::parse)
            ?: OlcrtcProfile.CompatibilityMode.CURRENT
        // QR payloads use the host; legacy links keep the room in the path.
        val roomId = uri.rawPath
            ?.takeIf { it.isNotBlank() && it != "/" }
            ?.removePrefix("/")
            ?.let(::decode)
            ?: decode(requireNotNull(roomHost))
        val keyHex = required(params, "key", "k")
        val clientId = required(params, "client_id", "c")

        return OlcrtcProfile(
            name = uri.rawFragment?.let(::decode).orEmpty(),
            provider = provider,
            transport = transport,
            compatibilityMode = compatibilityMode,
            roomId = roomId,
            roomPassword = parameter(params, "room_password", "rp"),
            clientId = clientId,
            keyHex = keyHex,
            dnsServer = parameter(params, "dns", "d")?.takeIf(String::isNotBlank),
            vp8Fps = integer(params, OlcrtcProfile.DEFAULT_VP8_FPS, "vp8_fps", "f"),
            vp8BatchSize = integer(params, OlcrtcProfile.DEFAULT_VP8_BATCH, "vp8_batch", "b"),
            keepaliveIntervalSeconds = integer(
                params,
                OlcrtcProfile.DEFAULT_KEEPALIVE_SECONDS,
                "keepalive",
                "ka",
            ),
        )
    }

    fun serialize(profile: OlcrtcProfile): String {
        val query = buildList {
            add("k=${encode(profile.keyHex)}")
            add("t=${encode(profile.transport.value)}")
            add("core=${encode(profile.compatibilityMode.value)}")
            if (profile.transport == OlcrtcProfile.Transport.VP8CHANNEL) {
                add("f=${profile.vp8Fps}")
                add("b=${profile.vp8BatchSize}")
            }
            add("c=${encode(profile.clientId)}")
            profile.roomPassword?.takeIf(String::isNotEmpty)?.let { add("rp=${encode(it)}") }
            profile.dnsServer?.let { add("d=${encode(it)}") }
            add("ka=${profile.keepaliveIntervalSeconds}")
        }.joinToString("&")
        val fragment = profile.name.takeIf(String::isNotEmpty)?.let { "#${encode(it)}" }.orEmpty()
        return "olcrtc://${profile.provider.value}@r/${encodePath(profile.roomId)}?$query$fragment"
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val result = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { item ->
            val pair = item.split('=', limit = 2)
            val key = decode(pair[0])
            require(key.isNotEmpty()) { "Empty olcRTC parameter" }
            require(pair.size == 2) { "Missing value for olcRTC parameter: $key" }
            require(key !in result) { "Duplicate olcRTC parameter: $key" }
            result[key] = decode(pair[1])
        }
        return result
    }

    private fun parameter(params: Map<String, String>, vararg names: String): String? {
        val values = names.mapNotNull(params::get)
        require(values.size <= 1) { "Duplicate aliases for ${names.first()}" }
        return values.singleOrNull()
    }

    private fun required(params: Map<String, String>, vararg names: String): String =
        parameter(params, *names)?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("${names.first()} is required")

    private fun integer(params: Map<String, String>, default: Int, vararg names: String): Int {
        val value = parameter(params, *names) ?: return default
        return value.toIntOrNull()
            ?: throw IllegalArgumentException("${names.first()} must be an integer")
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun encodePath(value: String): String = encode(value)
}
