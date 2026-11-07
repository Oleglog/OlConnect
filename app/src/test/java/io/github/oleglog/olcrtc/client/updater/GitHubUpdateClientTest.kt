package io.github.oleglog.olcrtc.client.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Proxy
import java.net.URL
import java.security.cert.CertificateException
import javax.net.ssl.HttpsURLConnection

class GitHubUpdateClientTest {
    @Test
    fun routesFetchThroughConfiguredProxyWhenProvided() {
        val proxy = Proxy(Proxy.Type.SOCKS, java.net.InetSocketAddress.createUnresolved("127.0.0.1", 1080))
        val openedWith = mutableListOf<Proxy?>()
        val connection = FakeConnection(200, body = latestReleaseJson(tag = "v1.2.3", assetName = "olcRTC-Client-v1.2.3-universal.apk"))
        val client = GitHubUpdateClient(
            currentVersion = "v1.2.0",
            supportedAbis = listOf("arm64-v8a"),
            proxy = proxy,
            openConnection = { _, usedProxy ->
                openedWith += usedProxy
                connection
            },
        )

        val result = client.check()

        assertEquals(listOf(proxy), openedWith)
        assertEquals("v1.2.3", result.release.tagName)
        assertTrue(result.newerThanCurrent)
        assertNotNull(result.selectedAsset)
        assertEquals("olcRTC-Client-v1.2.3-universal.apk", result.selectedAsset?.name)
        assertEquals("application/vnd.github+json", connection.requestProperties["Accept"]?.single())
        assertEquals("olcRTC-Client", connection.requestProperties["User-Agent"]?.single())
        assertTrue(connection.disconnected)
    }

    @Test
    fun fallsBackToDirectRequestWhenProxyIsNull() {
        val openedWith = mutableListOf<Proxy?>()
        val connection = FakeConnection(200, body = latestReleaseJson(tag = "v1.0.0"))
        val client = GitHubUpdateClient(
            currentVersion = "v1.0.0",
            supportedAbis = listOf("arm64-v8a"),
            proxy = null,
            openConnection = { _, usedProxy ->
                openedWith += usedProxy
                connection
            },
        )

        val result = client.check()

        assertEquals(listOf(null), openedWith)
        assertEquals("v1.0.0", result.release.tagName)
        // Same-tag release is not newer than current.
        assertEquals(false, result.newerThanCurrent)
    }

    private fun latestReleaseJson(tag: String, assetName: String? = null): ByteArray {
        val assets = if (assetName == null) "[]" else """[{"name":"$assetName","browser_download_url":"https://example.com/$assetName","size":1234}]"""
        return """{"tag_name":"$tag","name":"olcRTC Client $tag","prerelease":false,"body":"notes","assets":$assets}""".encodeToByteArray()
    }

    private class FakeConnection(
        private val status: Int,
        private val body: ByteArray = byteArrayOf(),
    ) : HttpsURLConnection(URL("https://api.github.com/repos/Oleglog/OlConnect/releases/latest")) {
        var disconnected = false

        override fun getResponseCode(): Int = status
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
        override fun getCipherSuite(): String = ""
        override fun getLocalCertificates() = null
        override fun getServerCertificates() = throw CertificateException()
    }
}
