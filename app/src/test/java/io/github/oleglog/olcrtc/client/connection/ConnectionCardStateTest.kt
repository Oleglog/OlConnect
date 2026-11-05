package io.github.oleglog.olcrtc.client.connection

import io.github.oleglog.olcrtc.client.vpn.VpnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCardStateTest {
    @Test
    fun connectedAndSwitchTargetHaveDistinctHighlights() {
        assertEquals(
            ConnectionCardState.CONNECTED,
            connectionCardState(selected = true, connected = true),
        )
        assertEquals(
            ConnectionCardState.SELECTED,
            connectionCardState(selected = true, connected = false),
        )
    }

    @Test
    fun selectionIsVisibleWhileDisconnected() {
        assertEquals(
            ConnectionCardState.SELECTED,
            connectionCardState(selected = true, connected = false),
        )
    }

    @Test
    fun profileTapSelectsWithoutStartingAReconnect() {
        assertTrue(canSelectProfile(VpnState.CONNECTED))
        assertTrue(canSelectProfile(VpnState.DISCONNECTED))
        assertFalse(canSelectProfile(VpnState.CONNECTING))
        assertFalse(canSelectProfile(VpnState.RECONNECTING))
    }

    @Test
    fun activeVpnSwitchesOnlyAfterExplicitPrimaryAction() {
        assertTrue(shouldSwitchProfile(VpnState.CONNECTED, hasPendingProfileSwitch = true))
        assertFalse(shouldSwitchProfile(VpnState.CONNECTED, hasPendingProfileSwitch = false))
        assertFalse(shouldSwitchProfile(VpnState.DISCONNECTED, hasPendingProfileSwitch = true))
    }

    @Test
    fun defaultGlowIsVisibleAndDisconnectedGlowStaysQuiet() {
        val connected = connectionGlowAlpha(intensity = 60, connected = true)
        val disconnected = connectionGlowAlpha(intensity = 60, connected = false)
        assertTrue(connected > 0.5f)
        assertTrue(disconnected in 0.05f..0.1f)
        assertEquals(0f, connectionGlowAlpha(intensity = 0, connected = true), 0f)
    }

    @Test
    fun contentStateDistinguishesLoadingEmptyAndFailure() {
        assertEquals(
            ConnectionContentState.LOADING,
            connectionContentState(loading = true, hasProfiles = false, failed = false),
        )
        assertEquals(
            ConnectionContentState.EMPTY,
            connectionContentState(loading = false, hasProfiles = false, failed = false),
        )
        assertEquals(
            ConnectionContentState.CONTENT,
            connectionContentState(loading = false, hasProfiles = true, failed = false),
        )
        assertEquals(
            ConnectionContentState.ERROR,
            connectionContentState(loading = false, hasProfiles = true, failed = true),
        )
    }

    @Test
    fun profileTypeUsesUserFacingNames() {
        assertEquals("WBStream", connectionTypeLabel("olcRTC", "wbstream · room"))
        assertEquals("Telemost", connectionTypeLabel("olcrtc", "telemost · room"))
        assertEquals("VMess", connectionTypeLabel("vmess", "example.com:443"))
        // Two Telemost profiles differ only by room, and the type label still
        // has to name the carrier for both.
        assertEquals("Telemost", connectionTypeLabel("olcrtc", "telemost · abc123def456"))
        assertEquals("Telemost", connectionTypeLabel("olcrtc", "telemost · zzz999yyy888"))
    }

    @Test
    fun latencyErrorsHaveStableUserCategories() {
        assertEquals(LatencyErrorKind.TIMEOUT, latencyErrorKind(IllegalStateException("request timeout")))
        assertEquals(LatencyErrorKind.DNS, latencyErrorKind(IllegalStateException("lookup: no such host")))
        assertEquals(
            LatencyErrorKind.NO_ACTIVE_SESSION,
            latencyErrorKind(IllegalStateException("VPN is not connected")),
        )
        assertEquals(LatencyErrorKind.OTHER, latencyErrorKind(IllegalStateException("carrier closed")))
    }

    @Test
    fun staleLatencyResultIsRejectedAfterStateChange() {
        assertEquals(true, shouldShowLatencyResult(4, 4, connected = true))
        assertEquals(false, shouldShowLatencyResult(4, 5, connected = true))
        assertEquals(false, shouldShowLatencyResult(4, 4, connected = false))
    }
}
