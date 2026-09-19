package io.github.oleglog.olcrtc.client.profile.openflux

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenFluxUriTest {
    @Test
    fun parseValidOpenFluxUri() {
        val raw = "openflux://yandex?url=https%3A%2F%2Fdisk.yandex.ru%2Fi%2Ftest1234&t=vyandex&d=1.1.1.1%3A53#Office+Tunnel"
        val profile = OpenFluxUri.parse(raw)

        assertEquals("Office Tunnel", profile.name)
        assertEquals("https://disk.yandex.ru/i/test1234", profile.documentUrl)
        assertEquals(OpenFluxProfile.Transport.VYANDEX, profile.transport)
        assertEquals("1.1.1.1:53", profile.dnsServer)
    }

    @Test
    fun serializeRoundTrip() {
        val original = OpenFluxProfile(
            name = "Test Profile",
            documentUrl = "https://disk.yandex.ru/i/JsCiJYtJjOP-Aw",
            transport = OpenFluxProfile.Transport.VYANDEX,
            dnsServer = "8.8.8.8:53",
        )
        val uri = OpenFluxUri.serialize(original)
        val parsed = OpenFluxUri.parse(uri)

        assertEquals(original.name, parsed.name)
        assertEquals(original.documentUrl, parsed.documentUrl)
        assertEquals(original.transport, parsed.transport)
        assertEquals(original.dnsServer, parsed.dnsServer)
    }

    @Test
    fun parseMailruOpenFluxUri() {
        val raw = "openflux://mailru?url=https%3A%2F%2Fcloud.mail.ru%2Fpublic%2Fabcd%2Fefgh&t=mailru#Mailru+Tunnel"
        val profile = OpenFluxUri.parse(raw)

        assertEquals("Mailru Tunnel", profile.name)
        assertEquals("https://cloud.mail.ru/public/abcd/efgh", profile.documentUrl)
        assertEquals(OpenFluxProfile.Transport.MAILRU, profile.transport)
    }

    @Test
    fun serializeRoundTripMailru() {
        val original = OpenFluxProfile(
            name = "Mailru Profile",
            documentUrl = "https://cloud.mail.ru/public/abcd/efgh",
            transport = OpenFluxProfile.Transport.MAILRU,
            dnsServer = "77.88.8.8:53",
        )
        val uri = OpenFluxUri.serialize(original)
        val parsed = OpenFluxUri.parse(uri)

        assertEquals(original.name, parsed.name)
        assertEquals(original.documentUrl, parsed.documentUrl)
        assertEquals(original.transport, parsed.transport)
        assertEquals(original.dnsServer, parsed.dnsServer)
    }

    @Test
    fun serializeRoundTripWithKey() {
        val original = OpenFluxProfile(
            name = "Encrypted Profile",
            documentUrl = "https://disk.yandex.ru/i/JsCiJYtJjOP-Aw",
            transport = OpenFluxProfile.Transport.YANDEX,
            dnsServer = "8.8.8.8:53",
            encryptionKey = "0123456789abcdef0123456789abcdef",
        )
        val uri = OpenFluxUri.serialize(original)
        val parsed = OpenFluxUri.parse(uri)

        assertEquals(original.name, parsed.name)
        assertEquals(original.documentUrl, parsed.documentUrl)
        assertEquals(original.transport, parsed.transport)
        assertEquals(original.dnsServer, parsed.dnsServer)
        assertEquals(original.encryptionKey, parsed.encryptionKey)
    }
}
