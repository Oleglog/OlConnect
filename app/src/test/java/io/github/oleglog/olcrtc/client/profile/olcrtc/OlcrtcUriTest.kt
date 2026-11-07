package io.github.oleglog.olcrtc.client.profile.olcrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OlcrtcUriTest {
    private val key = "a".repeat(64)

    @Test
    fun parsesCurrentManagerQrUri() {
        val profile = OlcrtcUri.parse(
            "olcrtc://wbstream@r/room%2Fone?k=$key&t=vp8channel&f=120&b=64&c=android%20client&d=1.1.1.1%3A53#Main+instance",
        )

        assertEquals("Main instance", profile.name)
        assertEquals("room/one", profile.roomId)
        assertEquals("android client", profile.clientId)
        assertEquals("1.1.1.1:53", profile.dnsServer)
        assertEquals(120, profile.vp8Fps)
        assertEquals(64, profile.vp8BatchSize)
        assertEquals(OlcrtcProfile.Provider.WBSTREAM, profile.provider)
        assertEquals(OlcrtcProfile.Transport.VP8CHANNEL, profile.transport)
        assertEquals(OlcrtcProfile.CompatibilityMode.CURRENT, profile.compatibilityMode)
    }

    @Test
    fun parsesCompactUri() {
        val profile = OlcrtcUri.parse(
            "olcrtc://wbstream@r/room%201?k=$key&t=vp8channel&f=120&b=64&c=client%201&d=77.88.8.8%3A53&ka=15#Main%20profile",
        )

        assertEquals("Main profile", profile.name)
        assertEquals("room 1", profile.roomId)
        assertEquals("client 1", profile.clientId)
        assertEquals(OlcrtcProfile.Provider.WBSTREAM, profile.provider)
    }

    @Test
    fun parsesRoomIdFromQrHost() {
        val profile = OlcrtcUri.parse(
            "olcrtc://wbstream@room%201?k=$key&t=vp8channel&c=client",
        )

        assertEquals("room 1", profile.roomId)
    }

    @Test
    fun parsesVerboseAliasesWithMaxPacingDefaults() {
        val profile = OlcrtcUri.parse(
            "olcrtc://jitsi@room/test?key=$key&client_id=client",
        )

        assertEquals(OlcrtcProfile.Transport.DATACHANNEL, profile.transport)
        assertEquals(60, profile.vp8Fps)
        assertEquals(8, profile.vp8BatchSize)
        assertEquals(15, profile.keepaliveIntervalSeconds)
        assertEquals(OlcrtcProfile.CompatibilityMode.CURRENT, profile.compatibilityMode)
    }

    @Test
    fun parsesAndSerializesLegacyCompatibilityMode() {
        val profile = OlcrtcUri.parse(
            "olcrtc://wbstream@r/room?k=$key&t=vp8channel&core=legacy&c=client",
        )

        assertEquals(OlcrtcProfile.CompatibilityMode.LEGACY, profile.compatibilityMode)
        assertTrue(OlcrtcUri.serialize(profile).contains("core=legacy"))
    }

    @Test
    fun manualProfileUsesNewDefaults() {
        val profile = OlcrtcProfile.manual("Manual", "room", "client", key)

        assertEquals(OlcrtcProfile.Provider.WBSTREAM, profile.provider)
        assertEquals(OlcrtcProfile.Transport.VP8CHANNEL, profile.transport)
        assertEquals(120, profile.vp8Fps)
        assertEquals(64, profile.vp8BatchSize)
        assertEquals(null, profile.dnsServer)
        assertEquals(OlcrtcProfile.CompatibilityMode.CURRENT, profile.compatibilityMode)
    }

    @Test
    fun rejectsRemovedAuthTokenParameter() {
        // The wbstream auth token was removed from the client (commit 25302ec):
        // links carrying a=/auth_token/auth.token are rejected as unknown.
        assertThrows(IllegalArgumentException::class.java) {
            OlcrtcUri.parse("olcrtc://wbstream@r/room?k=$key&t=vp8channel&c=client&a=token")
        }
    }

    @Test
    fun roundTripsReservedCharacters() {
        val profile = OlcrtcProfile(
            name = "Main + profile",
            provider = OlcrtcProfile.Provider.WBSTREAM,
            transport = OlcrtcProfile.Transport.VP8CHANNEL,
            roomId = "room/one+two",
            clientId = "client+one",
            keyHex = key,
        )

        val serialized = OlcrtcUri.serialize(profile)

        assertTrue(serialized.contains("/room%2Fone%2Btwo?"))
        assertEquals(profile, OlcrtcUri.parse(serialized))
    }

    @Test
    fun acceptsCaseInsensitiveHost() {
        val profile = OlcrtcUri.parse(
            "olcrtc://jitsi@ROOM/test?k=$key&t=datachannel&c=client",
        )

        assertEquals("test", profile.roomId)
    }

    @Test
    fun rejectsDuplicateAliases() {
        assertThrows(IllegalArgumentException::class.java) {
            OlcrtcUri.parse("olcrtc://jitsi@r/room?k=$key&key=$key&t=datachannel&c=client")
        }
    }

    @Test
    fun rejectsUnsupportedCombination() {
        assertThrows(IllegalArgumentException::class.java) {
            OlcrtcUri.parse("olcrtc://wbstream@r/room?k=$key&t=datachannel&c=client")
        }
    }

    @Test
    fun rejectsUnknownParameter() {
        assertThrows(IllegalArgumentException::class.java) {
            OlcrtcUri.parse("olcrtc://jitsi@r/room?k=$key&t=datachannel&c=client&security=none")
        }
    }

    @Test
    fun rejectsInvalidKeyAndMissingClientId() {
        assertThrows(IllegalArgumentException::class.java) {
            OlcrtcUri.parse("olcrtc://jitsi@r/room?k=abc&t=datachannel&c=client")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OlcrtcUri.parse("olcrtc://jitsi@r/room?k=$key&t=datachannel")
        }
    }
}
