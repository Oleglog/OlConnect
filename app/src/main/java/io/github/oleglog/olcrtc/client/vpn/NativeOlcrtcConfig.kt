package io.github.oleglog.olcrtc.client.vpn

import io.github.oleglog.olcrtc.client.data.ProfileConfig
import io.github.oleglog.olcrtc.client.profile.olcrtc.OlcrtcProfile
import io.github.oleglog.olcrtc.client.routing.DnsEndpoint

internal data class SessionDns(
    val tunnel: DnsEndpoint,
    val carrier: DnsEndpoint? = null,
)

internal fun sessionDns(profile: ProfileConfig, globalDns: String?): SessionDns = when (profile) {
    is ProfileConfig.Olcrtc -> SessionDns(
        tunnel = DnsEndpoint.parse(globalDns ?: DnsEndpoint.TUNNEL_DEFAULT),
        carrier = DnsEndpoint.parse(profile.value.dnsServer ?: globalDns ?: DnsEndpoint.DEFAULT),
    )
    is ProfileConfig.OpenFlux -> SessionDns(
        tunnel = DnsEndpoint.parse(globalDns ?: DnsEndpoint.TUNNEL_DEFAULT),
        carrier = DnsEndpoint.parse(profile.value.dnsServer ?: globalDns ?: DnsEndpoint.DEFAULT),
    )
    is ProfileConfig.Standard -> SessionDns(
        tunnel = DnsEndpoint.parse(profile.value.dnsServer ?: globalDns ?: DnsEndpoint.TUNNEL_DEFAULT),
    )
}

internal data class NativeOlcrtcConfig(
    val provider: String,
    val transport: String,
    val compatibilityMode: String,
    val roomId: String,
    val clientId: String,
    val keyHex: String,
    val dnsServer: String,
    val vp8Fps: Int,
    val vp8BatchSize: Int,
    val keepaliveSeconds: Int,
    val udpRelay: Boolean = false,
    val socksPort: Int,
    val readyTimeoutMillis: Int = DEFAULT_READY_TIMEOUT_MILLIS,
) {
    companion object {
        fun from(
            profile: OlcrtcProfile,
            socksPort: Int,
            dns: DnsEndpoint,
            udpRelay: Boolean = false,
        ) = NativeOlcrtcConfig(
            provider = profile.provider.value,
            transport = profile.transport.value,
            compatibilityMode = profile.compatibilityMode.value,
            roomId = profile.roomId,
            clientId = profile.clientId,
            keyHex = profile.keyHex,
            dnsServer = dns.toString(),
            vp8Fps = profile.vp8Fps,
            vp8BatchSize = profile.vp8BatchSize,
            keepaliveSeconds = profile.keepaliveIntervalSeconds,
            udpRelay = udpRelay,
            socksPort = socksPort,
            readyTimeoutMillis = when (profile.provider) {
                OlcrtcProfile.Provider.WBSTREAM -> WBSTREAM_READY_TIMEOUT_MILLIS
                // Jitsi handshake is multi-stage (MUC join -> Jingle session-initiate
                // -> bridge negotiation) and far slower to reach ready than a
                // signaling-only carrier; allow it the same budget as wbstream
                // instead of the 15s default which fires false-ready timeouts.
                OlcrtcProfile.Provider.JITSI -> JITSI_READY_TIMEOUT_MILLIS
                // Telemost over vp8channel needs ICE, two peer connections, KCP
                // and only then the smux control stream. The 15 s default fired
                // mid-bring-up on a real device: the client tore the tunnel
                // down and retried, and every retry left another participant
                // behind in the conference (observed as a 15 s reconnect loop
                // in logcat).
                OlcrtcProfile.Provider.TELEMOST -> TELEMOST_READY_TIMEOUT_MILLIS
            },
        )

        const val DEFAULT_READY_TIMEOUT_MILLIS = 15_000
        const val WBSTREAM_READY_TIMEOUT_MILLIS = 45_000
        const val JITSI_READY_TIMEOUT_MILLIS = 45_000
        const val TELEMOST_READY_TIMEOUT_MILLIS = 90_000
    }
}
