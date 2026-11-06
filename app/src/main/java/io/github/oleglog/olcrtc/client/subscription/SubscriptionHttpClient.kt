package io.github.oleglog.olcrtc.client.subscription

import io.github.oleglog.olcrtc.client.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal data class SubscriptionResponse(
    val body: ByteArray?,
    val etag: String?,
    val lastModified: String?,
    val notModified: Boolean,
)

internal class SubscriptionHttpClient(
    private val proxy: Proxy? = null,
    private val openConnection: ((URL, Proxy?) -> HttpsURLConnection)? = null,
) {
    constructor(openConnection: (URL) -> HttpsURLConnection) : this(
        openConnection = { url, _ -> openConnection(url) },
    )
    fun get(
        url: String,
        etag: String? = null,
        lastModified: String? = null,
        allowUntrustedCertificate: Boolean = false,
    ): SubscriptionResponse = try {
        request(url, etag, lastModified, null)
    } catch (error: SSLHandshakeException) {
        if (!allowUntrustedCertificate) throw error
        request(url, etag, lastModified, untrustedContext.socketFactory)
    }

    private fun request(
        initialUrl: String,
        etag: String?,
        lastModified: String?,
        socketFactory: javax.net.ssl.SSLSocketFactory?,
    ): SubscriptionResponse {
        var current = httpsUri(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirects ->
            val connection = open(current.toURL()).apply {
                instanceFollowRedirects = false
                useCaches = false
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "text/plain, application/octet-stream, application/json")
                // Subscription panels (Remnawave and friends) route by User-Agent:
                // the same URL may serve JSON for one client and a URI list for
                // another. Announce ourselves instead of the JVM default.
                setRequestProperty("User-Agent", "olcRTC-Client/${BuildConfig.VERSION_NAME} (Android)")
                setRequestProperty("Cache-Control", "no-cache, no-store")
                setRequestProperty("Pragma", "no-cache")
                etag?.let { setRequestProperty("If-None-Match", it) }
                lastModified?.let { setRequestProperty("If-Modified-Since", it) }
                socketFactory?.let { sslSocketFactory = it }
            }
            try {
                when (val status = connection.responseCode) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> return SubscriptionResponse(
                        body = null,
                        etag = connection.getHeaderField("ETag") ?: etag,
                        lastModified = connection.getHeaderField("Last-Modified") ?: lastModified,
                        notModified = true,
                    )
                    in REDIRECT_CODES -> {
                        require(redirects < MAX_REDIRECTS) { "Too many subscription redirects" }
                        current = httpsUri(current.resolve(connection.getHeaderField("Location")
                            ?: throw IllegalArgumentException("Subscription redirect has no location")))
                    }
                    in 200..299 -> return SubscriptionResponse(
                        body = readBounded(connection),
                        etag = connection.getHeaderField("ETag"),
                        lastModified = connection.getHeaderField("Last-Modified"),
                        notModified = false,
                    )
                    else -> throw IllegalArgumentException("Subscription HTTP status $status")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("Unreachable")
    }

    private fun open(url: URL): HttpsURLConnection =
        openConnection?.invoke(url, proxy)
            ?: (if (proxy == null) url.openConnection() else url.openConnection(proxy)) as HttpsURLConnection

    private fun readBounded(connection: HttpsURLConnection): ByteArray {
        val declaredLength = connection.contentLengthLong
        require(declaredLength < 0 || declaredLength <= MAX_RESPONSE_BYTES) { "Subscription response is too large" }
        return connection.inputStream.use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_RESPONSE_BYTES) { "Subscription response is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    private fun httpsUri(value: String): URI = runCatching { URI(value) }
        .getOrElse { throw IllegalArgumentException("Invalid subscription URL", it) }
        .also {
            require(it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank()) {
                "Subscription URL must use HTTPS"
            }
        }

    private fun httpsUri(value: URI): URI = value.also {
        require(it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank()) {
            "Subscription redirect must use HTTPS"
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val TIMEOUT_MS = 5_000
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val untrustedContext by lazy {
            val trustManager = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            }
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            }
        }
    }
}
