package io.github.oleglog.olcrtc.client.subscription

import io.github.oleglog.olcrtc.client.data.ProfileRepository
import io.github.oleglog.olcrtc.client.data.SubscriptionSource
import io.github.oleglog.olcrtc.client.importer.Json
import io.github.oleglog.olcrtc.client.importer.SubscriptionPayload
import io.github.oleglog.olcrtc.client.profile.ImportedProfile
import io.github.oleglog.olcrtc.client.profile.ProfileIdentity
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

internal class SubscriptionRefresher(
    private val repository: ProfileRepository,
    private val userHttp: SubscriptionHttpClient = SubscriptionHttpClient(),
    private val strictHttp: SubscriptionHttpClient = SubscriptionHttpClient(),
) {
    data class Result(
        val success: Boolean,
        val added: Int,
        val removed: Int,
        val total: Int,
        val source: Source? = null,
    )

    enum class Source(val wireCode: Int) {
        PRIMARY(1),
        MIRROR(2),
        ;

        companion object {
            fun fromWireCode(value: Int): Source? = entries.firstOrNull { it.wireCode == value }
        }
    }

    fun refreshStale(now: Long = System.currentTimeMillis()): Int =
        repository.getStaleSubscriptionIds(now).count { refresh(it, now) }

    fun refreshEnabled(now: Long = System.currentTimeMillis()): Int =
        repository.listSubscriptions().filter { it.enabled }.count { refresh(it.id, now, force = false) }

    fun refresh(
        subscriptionId: Long,
        now: Long = System.currentTimeMillis(),
        force: Boolean = false,
    ): Boolean = refreshSource(subscriptionId, now, force) != null

    private fun refreshSource(
        subscriptionId: Long,
        now: Long,
        force: Boolean,
    ): Source? {
        val source = requireNotNull(repository.getSubscriptionSource(subscriptionId)) { "Subscription not found" }
        return runCatching {
            runPrimaryWithDeadline(subscriptionId, source, now, force)
            Source.PRIMARY
        }
            .recoverCatching { primaryError ->
                if (source.mirrorUrl == null || source.mirrorKey == null) {
                    throw primaryError
                }
                refreshMirror(subscriptionId, source, now)
                Source.MIRROR
            }
            .fold(
                onSuccess = { it },
                onFailure = { error ->
                    repository.markSubscriptionRefresh(subscriptionId, errorCode(error), now)
                    null
                },
            )
    }

    // ponytail: primary runs on a background thread under a hard deadline so a
    // hanging primary server fails over to the Yandex mirror in ~PRIMARY_DEADLINE_MS
    // instead of waiting out the full HTTP timeouts. The abandoned task is
    // cancelled; its HTTP socket tears down on the next connect/read tick
    // (<= HTTP TIMEOUT_MS). A short-lived single-thread executor is fine: refresh
    // runs on the already-serialized subscriptionRefresh/single-thread executor,
    // so at most one refresh races a primary at a time.
    private fun runPrimaryWithDeadline(
        subscriptionId: Long,
        source: SubscriptionSource,
        now: Long,
        force: Boolean,
    ) {
        val task = java.util.concurrent.FutureTask {
            refreshPrimary(subscriptionId, source, now, force)
        }
        primaryRaceExecutor.execute(task)
        try {
            task.get(PRIMARY_DEADLINE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            task.cancel(true)
            throw e
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
            throw e
        }
    }

    fun refreshWithChanges(subscriptionId: Long, now: Long = System.currentTimeMillis()): Result {
        val before = repository.getSubscriptionProfiles(subscriptionId).mapTo(mutableSetOf(), ::identity)
        val source = refreshSource(subscriptionId, now, force = true)
            ?: return Result(false, 0, 0, before.size)
        val after = repository.getSubscriptionProfiles(subscriptionId).mapTo(mutableSetOf(), ::identity)
        return Result(
            success = true,
            added = (after - before).size,
            removed = (before - after).size,
            total = after.size,
            source = source,
        )
    }

    private fun refreshPrimary(
        subscriptionId: Long,
        source: SubscriptionSource,
        now: Long,
        force: Boolean,
    ): Boolean {
        val response = userHttp.get(
            source.url,
            etag = source.etag.takeUnless { force },
            lastModified = source.lastModified.takeUnless { force },
            allowUntrustedCertificate = true,
        )
        if (response.notModified) {
            repository.markSubscriptionRefresh(
                subscriptionId = subscriptionId,
                errorCode = null,
                now = now,
                etag = response.etag,
                lastModified = response.lastModified,
                successful = true,
            )
            return true
        }
        val profiles = SubscriptionPayload.parse(requireNotNull(response.body)).profiles
        repository.replaceSubscriptionProfiles(
            subscriptionId = subscriptionId,
            profiles = profiles,
            now = now,
            etag = response.etag,
            lastModified = response.lastModified,
        )
        return true
    }

    private fun refreshMirror(subscriptionId: Long, source: SubscriptionSource, now: Long): Boolean {
        require(source.mirrorType.equals("yandex_disk", ignoreCase = true)) { "Unsupported subscription mirror" }
        val metadataUrl = YANDEX_DOWNLOAD_ENDPOINT + URLEncoder.encode(source.mirrorUrl, StandardCharsets.UTF_8.name())
        val metadata = requireNotNull(strictHttp.get(metadataUrl).body)
        val href = Json.parse(metadata.decodeToString()).objectValue("yandex download")
            .getValue("href")
            .stringValue("href")
        requireHttps(href)
        val envelope = requireNotNull(strictHttp.get(href).body)
        val profiles = SubscriptionPayload.decryptMirror(envelope, source.mirrorKey!!).profiles
        repository.replaceSubscriptionProfiles(subscriptionId, profiles, now)
        return true
    }

    private fun requireHttps(value: String) {
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("Invalid mirror download URL", it) }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "Mirror download URL must use HTTPS"
        }
    }

    private fun errorCode(error: Throwable): String = when (error) {
        is javax.net.ssl.SSLException -> "TLS"
        is java.net.SocketTimeoutException -> "TIMEOUT"
        is java.io.IOException -> "NETWORK"
        else -> "INVALID_PAYLOAD"
    }

    private fun identity(profile: ImportedProfile): String = when (profile) {
        is ImportedProfile.Olcrtc -> ProfileIdentity.hash(profile.value)
        is ImportedProfile.OpenFlux -> ProfileIdentity.hash(profile.value)
        is ImportedProfile.Standard -> ProfileIdentity.hash(profile.value)
    }

    private companion object {
        const val YANDEX_DOWNLOAD_ENDPOINT =
            "https://cloud-api.yandex.net/v1/disk/public/resources/download?public_key="

        // ponytail: hard deadline for the primary fetch. If the subscription host is
        // unreachable, fail over to the Yandex mirror within this many milliseconds
        // instead of waiting out HTTP connect/read timeouts. Bump up if the primary
        // file genuinely streams slower than this on flaky links.
        const val PRIMARY_DEADLINE_MS = 2_000L

        // Reused across fresheners so a hung primary thread is abandoned (cancel(true))
        // and the same worker serves the next race. Single thread: refresh already
        // serializes upstream on subscriptionRefresh; no two primaries race here.
        val primaryRaceExecutor by lazy {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "olcrtc-sub-primary").apply { isDaemon = true }
            }
        }
    }
}
