package io.github.oleglog.olcrtc.client.vpn

/**
 * Issue #48: tunnel liveness policy in one place.
 *
 * The tunnel is considered alive when real application traffic flows
 * (byte counters move). When idle, [TUNNEL_HEALTH_INTERVAL_MILLIS] of
 * silence triggers an active probe: a DNS query through the tunnel for
 * each of [DATAPATH_DNS_NAMES] (one success is enough, so a single dead
 * name cannot force a reconnect) plus an HTTPS HEAD through the local
 * SOCKS for each of [DATAPATH_HTTPS_URLS]. The tunnel counts as healthy
 * when any DNS name resolves OR any HTTPS URL answers 204; only when
 * both families fail does [healthProbeFailures] grow, and
 * [HEALTH_FAILURES_BEFORE_RECONNECT] consecutive failures reconnect.
 *
 * Probe targets are chosen for reachability from Russian mobile networks:
 * the gstatic/ubuntu captive-check endpoints answer lightweight 204s
 * worldwide, and ya.ru / vk.com are the highest-traffic domestic hosts
 * (SimilarWeb/Semrush July 2026: yandex.ru #1, vk.com/vk.ru top-10).
 */
internal object TunnelHealthPolicy {
    /** How long an idle tunnel waits before an active probe. */
    const val TUNNEL_HEALTH_INTERVAL_MILLIS = 180_000L

    /** Per-address timeout for a single DNS/HTTPS probe attempt. */
    const val TUNNEL_HEALTH_TIMEOUT_MILLIS = 30_000

    /** Timeout for one blocking datapath probe during startup. */
    const val DATAPATH_TIMEOUT_MILLIS = 10_000

    /**
     * Startup datapath probes before giving up. A single DNS datagram can be
     * dropped while a fresh carrier lane ramps its bitrate allowance, so one
     * missed query is not proof the tunnel is dead — but every retry sends a
     * new query, which a longer single timeout would not do.
     */
    const val DATAPATH_ATTEMPTS = 3

    /** Pause between startup datapath probes. */
    const val DATAPATH_RETRY_PAUSE_MILLIS = 500L

    /** Consecutive fully-failed probes before reconnecting. */
    const val HEALTH_FAILURES_BEFORE_RECONNECT = 2

    /** DNS names queried through the tunnel; one success is enough. */
    val DATAPATH_DNS_NAMES: List<List<Any>> = listOf(
        listOf(7, "android", 3, "com"),
        listOf(3, "ya", 2, "ru"),
        listOf(2, "vk", 3, "com"),
    )

    /** HTTPS URLs HEAD-probed through the local SOCKS; one 204 is enough. */
    val DATAPATH_HTTPS_URLS = listOf(
        "https://connectivitycheck.gstatic.com/generate_204",
        "https://connectivity-check.ubuntu.com/",
        "https://www.google.com/generate_204",
    )

    /** Active check URL for the user-visible latency ("Ping VPN") action. */
    const val CONNECTION_TEST_URL = "https://www.google.com/generate_204"

    /** Timeout for [CONNECTION_TEST_URL]. */
    const val CONNECTION_TEST_TIMEOUT_MILLIS = 5_000

    /** Notification traffic sampling cadence. */
    const val NOTIFICATION_SAMPLE_INTERVAL_SECONDS = 5L

    /** Debounce before reconnecting after the underlying network is replaced. */
    const val NETWORK_CHANGE_DEBOUNCE_MILLIS = 400L

    fun shouldReconnectAfterFailures(failures: Int): Boolean =
        failures >= HEALTH_FAILURES_BEFORE_RECONNECT

    fun networkReconnectDelay(replacingExistingNetwork: Boolean): Long =
        if (replacingExistingNetwork) NETWORK_CHANGE_DEBOUNCE_MILLIS else 0L

    /**
     * True when the tunnel counts as healthy: any DNS name resolved or any
     * HTTPS probe answered. Only a total failure of both families counts.
     */
    fun isTunnelHealthy(dnsSuccesses: Int, dnsAttempts: Int, httpsSuccesses: Int): Boolean {
        require(dnsAttempts > 0) { "at least one DNS attempt is required" }
        if (dnsSuccesses > 0) return true
        return httpsSuccesses > 0
    }
}
