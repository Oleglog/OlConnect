package io.github.oleglog.olcrtc.client.vpn

import io.github.oleglog.olcrtc.client.routing.RoutingPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPolicyTest {
    @Test
    fun retriesTransientFailuresButStopsForInvalidOrNativeFatalErrors() {
        assertFalse(isFatalConnectionError(IllegalStateException("VPN datapath timed out")) { false })
        assertTrue(isFatalConnectionError(IllegalArgumentException("bad profile")) { false })
        assertTrue(isFatalConnectionError(IllegalStateException("profile 42 not found")) { false })
        assertTrue(isFatalConnectionError(IllegalStateException("carrier auth failed")) { true })
    }

    @Test
    fun datapathProbesAreRetriedBeforeGivingUp() {
        // A single dropped DNS datagram during a fresh carrier lane's bitrate
        // ramp must not tear the just-built tunnel down.
        assertTrue(TunnelHealthPolicy.DATAPATH_ATTEMPTS >= 2)
    }

    @Test
    fun preparesGeoAssetsOnlyForRussiaDirectPreset() {
        assertFalse(requiresGeoAssets(RoutingPolicy(RoutingPolicy.Preset.ALL_VPN)))
        assertTrue(requiresGeoAssets(RoutingPolicy(RoutingPolicy.Preset.RUSSIA_DIRECT)))
    }

    @Test
    fun reconnectsOnlyAfterSecondConsecutiveHealthFailure() {
        assertFalse(shouldReconnectAfterHealthFailures(0))
        assertFalse(shouldReconnectAfterHealthFailures(1))
        assertTrue(shouldReconnectAfterHealthFailures(2))
        assertTrue(shouldReconnectAfterHealthFailures(3))
    }

    @Test
    fun newNetworkReconnectsImmediatelyButReplacementIsDebounced() {
        assertTrue(networkReconnectDelay(replacingExistingNetwork = false) == 0L)
        assertTrue(networkReconnectDelay(replacingExistingNetwork = true) > 0L)
    }

    @Test
    fun acceptsOnlyCurrentNonCancelledStartupResult() {
        assertTrue(shouldAcceptConnectionResult(4, 4, cancelled = false))
        assertFalse(shouldAcceptConnectionResult(5, 4, cancelled = false))
        assertFalse(shouldAcceptConnectionResult(4, 4, cancelled = true))
        assertFalse(shouldAcceptConnectionResult(null, 4, cancelled = false))
    }
}
