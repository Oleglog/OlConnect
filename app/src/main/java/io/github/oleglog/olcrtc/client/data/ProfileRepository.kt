package io.github.oleglog.olcrtc.client.data

import android.content.Context
import io.github.oleglog.olcrtc.client.importer.SubscriptionBundle
import io.github.oleglog.olcrtc.client.profile.ImportedProfile
import io.github.oleglog.olcrtc.client.profile.ProfileIdentity
import io.github.oleglog.olcrtc.client.profile.olcrtc.OlcrtcProfile
import io.github.oleglog.olcrtc.client.profile.olcrtc.OlcrtcUri
import io.github.oleglog.olcrtc.client.profile.standard.StandardProfile
import io.github.oleglog.olcrtc.client.profile.standard.StandardUri
import io.github.oleglog.olcrtc.client.routing.RoutingRule
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlin.system.measureTimeMillis

internal data class ProfileSummary(
    val id: Long,
    val name: String,
    val type: String,
    val endpoint: String,
)

internal data class SubscriptionSource(
    val url: String,
    val kind: String,
    val mirrorType: String?,
    val mirrorUrl: String?,
    val mirrorKey: String?,
    val etag: String?,
    val lastModified: String?,
)

internal data class SubscriptionSummary(
    val id: Long,
    val name: String,
    val kind: String,
    val serverVersion: String?,
    val lastSuccessAt: Long?,
    val lastAttemptAt: Long?,
    val lastErrorCode: String?,
    val enabled: Boolean,
    val profileCount: Int,
    val mirrorAvailable: Boolean,
)

internal data class SubscriptionProfileSummary(
    val id: String,
    val subscriptionId: Long,
    val name: String,
    val type: String,
    val locallyModified: Boolean,
    val favorite: Boolean,
    val lastLatencyMs: Long?,
    val endpoint: String,
)

internal class ProfileRepository(
    private val olcrtcProfiles: OlcrtcProfileDao,
    private val standardProfiles: StandardProfileDao,
    private val openfluxProfiles: OpenFluxProfileDao,
    private val subscriptions: SubscriptionDao,
    private val routingRules: RoutingRuleDao,
    private val secrets: SecretCipher,
) {
    fun getEnabledRoutingRules(): List<RoutingRule> = RoutingRuleRepository(routingRules).getEnabled()

    fun getAllRoutingRules(): List<RoutingRule> = RoutingRuleRepository(routingRules).getAll()

    fun saveRoutingRule(rule: RoutingRule): Long = RoutingRuleRepository(routingRules).save(rule)

    fun setRoutingRuleEnabled(id: Long, enabled: Boolean) =
        RoutingRuleRepository(routingRules).setEnabled(id, enabled)

    fun deleteRoutingRule(id: Long) = RoutingRuleRepository(routingRules).delete(id)

    fun listLocal(): List<ProfileSummary> =
        olcrtcProfiles.getAll().map {
            ProfileSummary(it.id, it.name, "olcRTC", "${it.provider} · ${it.roomId}")
        } + openfluxProfiles.getAll().map {
            val transportLabel = when (it.transport.lowercase()) {
                "mailru" -> "Mail.ru · Docs"
                "vyandex" -> "Yandex · Volga"
                else -> "Yandex · Docs"
            }
            ProfileSummary(OPENFLUX_ID_OFFSET + it.id, it.name, "OpenFlux", transportLabel)
        } + standardProfiles.getAll().map {
            ProfileSummary(STANDARD_ID_OFFSET + it.id, it.name, it.protocol, "${it.address}:${it.port}")
        }

    fun listSubscriptions(): List<SubscriptionSummary> = subscriptions.getSubscriptions().map { subscription ->
        SubscriptionSummary(
            id = subscription.id,
            name = subscription.name,
            kind = subscription.kind,
            serverVersion = subscription.serverVersion,
            lastSuccessAt = subscription.lastSuccessAt,
            lastAttemptAt = subscription.lastAttemptAt,
            lastErrorCode = subscription.lastErrorCode,
            enabled = subscription.enabled,
            profileCount = subscriptions.getVisibleProfiles(subscription.groupId).size,
            mirrorAvailable = subscription.encryptedMirrorUrl != null && subscription.encryptedMirrorKey != null,
        )
    }

    fun listSubscriptionProfiles(subscriptionId: Long): List<SubscriptionProfileSummary> {
        val group = subscriptions.getSubscriptionGroup(subscriptionId) ?: return emptyList()
        return subscriptions.getVisibleProfiles(group.groupId).map { profile ->
            val imported = runCatching { profile.toImportedProfile() }.getOrNull()
            SubscriptionProfileSummary(
                id = profile.id,
                subscriptionId = subscriptionId,
                name = profile.name,
                type = profile.type,
                locallyModified = profile.isLocallyModified,
                favorite = profile.favorite,
                lastLatencyMs = profile.lastLatencyMs,
                endpoint = imported.endpointDescription(),
            )
        }
    }

    fun deleteLocal(id: Long) {
        val deleted = when {
            id >= OPENFLUX_ID_OFFSET -> openfluxProfiles.delete(id - OPENFLUX_ID_OFFSET)
            id >= STANDARD_ID_OFFSET -> standardProfiles.delete(id - STANDARD_ID_OFFSET)
            else -> olcrtcProfiles.delete(id)
        }
        require(deleted == 1) { "Profile not found" }
    }

    fun get(id: Long): ProfileConfig? = when {
        id <= 0 -> null
        id >= OPENFLUX_ID_OFFSET -> openfluxProfiles.get(id - OPENFLUX_ID_OFFSET)?.toProfile()?.let(ProfileConfig::OpenFlux)
        id >= STANDARD_ID_OFFSET -> standardProfiles.get(id - STANDARD_ID_OFFSET)?.toProfile()?.let(ProfileConfig::Standard)
        else -> olcrtcProfiles.get(id)?.toProfile()?.let(ProfileConfig::Olcrtc)
    }

    fun getOlcrtc(id: Long): OlcrtcProfile? =
        if (id in 1 until STANDARD_ID_OFFSET) olcrtcProfiles.get(id)?.toProfile() else null

    fun exportProfileUri(id: Long): String {
        val profile = get(id) ?: throw IllegalArgumentException("Profile not found")
        return profile.exportUri()
    }

    fun exportSubscriptionProfileUri(profileId: String): String {
        val profile = getSubscriptionProfile(profileId) ?: throw IllegalArgumentException("Subscription profile not found")
        return profile.exportUri()
    }

    fun testLocalProfileLatency(id: Long): Long = measureLatency(get(id) ?: throw IllegalArgumentException("Profile not found"))

    fun testSubscriptionProfileLatency(profileId: String, now: Long = System.currentTimeMillis()): Long {
        val profile = getSubscriptionProfile(profileId) ?: throw IllegalArgumentException("Subscription profile not found")
        val latency = measureLatency(profile)
        subscriptions.updateProfileLatency(profileId, latency, now)
        return latency
    }

    fun setSubscriptionProfileFavorite(profileId: String, favorite: Boolean) {
        require(subscriptions.updateProfileFavorite(profileId, favorite) == 1) {
            "Subscription profile not found"
        }
    }

    private fun measureLatency(profile: ProfileConfig): Long = when (profile) {
        is ProfileConfig.Standard -> profile.value.measureTcpLatency()
        is ProfileConfig.Olcrtc -> throw IllegalArgumentException("Latency test requires an endpoint profile")
        is ProfileConfig.OpenFlux -> throw IllegalArgumentException("Latency test not supported for OpenFlux")
    }

    private fun StandardProfile.measureTcpLatency(timeoutMillis: Int = 5_000): Long {
        require(address.isNotBlank()) { "address is required" }
        require(port in 1..65535) { "port must be in 1..65535" }
        var socket: Socket? = null
        return try {
            measureTimeMillis {
                socket = Socket()
                socket!!.connect(InetSocketAddress(address, port), timeoutMillis)
            }
        } finally {
            socket?.close()
        }
    }

    fun findDuplicate(profile: OlcrtcProfile): Long? =
        olcrtcProfiles.findByIdentity(ProfileIdentity.hash(profile))?.id

    fun findDuplicate(profile: StandardProfile): Long? =
        standardProfiles.findByIdentity(ProfileIdentity.hash(profile))?.id?.let { STANDARD_ID_OFFSET + it }

    fun insert(profile: OlcrtcProfile): Long = olcrtcProfiles.insert(profile.toEntity())

    fun insert(profile: StandardProfile): Long = STANDARD_ID_OFFSET + standardProfiles.insert(profile.toEntity())

    fun update(id: Long, profile: OlcrtcProfile) {
        require(id in 1 until STANDARD_ID_OFFSET) { "Invalid olcRTC profile ID" }
        require(olcrtcProfiles.get(id) != null) { "Profile not found" }
        olcrtcProfiles.update(profile.toEntity(id))
    }

    fun update(id: Long, profile: StandardProfile) {
        require(id >= STANDARD_ID_OFFSET) { "Invalid standard profile ID" }
        val databaseId = id - STANDARD_ID_OFFSET
        require(standardProfiles.get(databaseId) != null) { "Profile not found" }
        standardProfiles.update(profile.toEntity(databaseId))
    }

    fun getSubscriptionProfiles(subscriptionId: Long): List<ImportedProfile> {
        val groupId = subscriptions.getSubscriptionGroup(subscriptionId)?.groupId ?: return emptyList()
        return subscriptions.getVisibleProfiles(groupId).map { it.toImportedProfile() }
    }

    fun getSubscriptionProfile(profileId: String): ProfileConfig? =
        subscriptions.getProfile(profileId)?.takeUnless(SubscriptionProfileEntity::isDeleted)?.toImportedProfile()?.let { profile ->
            when (profile) {
                is ImportedProfile.Olcrtc -> ProfileConfig.Olcrtc(profile.value)
                is ImportedProfile.OpenFlux -> ProfileConfig.OpenFlux(profile.value)
                is ImportedProfile.Standard -> ProfileConfig.Standard(profile.value)
            }
        }

    fun getSubscriptionSource(subscriptionId: Long): SubscriptionSource? =
        subscriptions.getSubscription(subscriptionId)?.let { subscription ->
            SubscriptionSource(
                url = secrets.decrypt(subscription.encryptedUrl),
                kind = subscription.kind,
                mirrorType = subscription.encryptedMirrorType?.let(secrets::decrypt),
                mirrorUrl = subscription.encryptedMirrorUrl?.let(secrets::decrypt),
                mirrorKey = subscription.encryptedMirrorKey?.let(secrets::decrypt),
                etag = subscription.etag,
                lastModified = subscription.lastModified,
            )
        }

    fun updateSubscriptionSource(subscriptionId: Long, name: String, url: String) {
        require(name.isNotBlank()) { "Subscription name is required" }
        require(url.startsWith("https://", ignoreCase = true)) { "Subscription URL must use HTTPS" }
        require(subscriptions.updateSubscriptionSource(subscriptionId, name.trim(), secrets.encrypt(url.trim())) == 1) {
            "Subscription not found"
        }
    }

    fun insertSubscriptionSource(name: String, url: String, kind: String = "GENERIC", now: Long = System.currentTimeMillis()): Long {
        require(name.isNotBlank()) { "Subscription name is required" }
        require(url.startsWith("https://", ignoreCase = true)) { "Subscription URL must use HTTPS" }
        val normalizedUrl = url.trim()
        val normalizedKind = kind.trim().uppercase().takeIf { it == "OLCRTC" || it == "GENERIC" } ?: "GENERIC"
        findSubscription(normalizedUrl)?.let { existing ->
            subscriptions.updateSubscriptionMetadata(
                existing.copy(
                    kind = normalizedKind,
                    encryptedUrl = secrets.encrypt(normalizedUrl),
                    enabled = true,
                ),
            )
            return existing.id
        }
        return subscriptions.insertSubscriptionGroup(
            ProfileGroupEntity(
                name = name.trim(),
                type = "SUBSCRIPTION",
                subscriptionId = null,
                sortOrder = 0,
                createdAt = now,
            ),
            SubscriptionEntity(
                groupId = 0,
                name = name.trim(),
                kind = normalizedKind,
                encryptedUrl = secrets.encrypt(normalizedUrl),
                serverVersion = null,
                encryptedMirrorType = null,
                encryptedMirrorUrl = null,
                encryptedMirrorKey = null,
                lastSuccessAt = null,
                lastAttemptAt = null,
                lastErrorCode = null,
                updateIntervalHours = DEFAULT_SUBSCRIPTION_INTERVAL_HOURS,
                etag = null,
                lastModified = null,
                enabled = true,
            ),
            emptyList(),
        )
    }

    fun getStaleSubscriptionIds(
        now: Long = System.currentTimeMillis(),
    ): List<Long> = subscriptions.getStaleSubscriptionIds(now)

    fun markSubscriptionRefresh(
        subscriptionId: Long,
        errorCode: String?,
        now: Long = System.currentTimeMillis(),
        etag: String? = null,
        lastModified: String? = null,
        successful: Boolean = false,
    ) {
        subscriptions.markSubscriptionRefresh(
            subscriptionId = subscriptionId,
            now = now,
            errorCode = errorCode,
            etag = etag,
            lastModified = lastModified,
            successful = successful,
        )
    }

    fun insertSubscription(
        bundle: SubscriptionBundle,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val mirror = bundle.mirrors.firstOrNull()
        val existing = findSubscription(bundle.url)
        val kind = if (bundle.profiles.any { it is ImportedProfile.Olcrtc }) "OLCRTC" else existing?.kind ?: "GENERIC"
        if (existing != null) {
            // A bare /open deep link (re-import via the web "open in app" page)
            // carries no mirror fields. Overwriting the stored mirror with null
            // would drop the Yandex fallback — the only path when the server is
            // down or blocked. Preserve the existing mirror when the bundle omits
            // it, and only replace it when a fresh QR/bootstrap bundle supplies one.
            val mirrorType = mirror?.type?.let(secrets::encrypt) ?: existing.encryptedMirrorType
            val mirrorUrl = mirror?.url?.let(secrets::encrypt) ?: existing.encryptedMirrorUrl
            val mirrorKey = bundle.mirrorKey?.let(secrets::encrypt) ?: existing.encryptedMirrorKey
            subscriptions.updateSubscriptionMetadata(
                existing.copy(
                    kind = kind,
                    encryptedUrl = secrets.encrypt(bundle.url.trim()),
                    serverVersion = bundle.serverVersion ?: existing.serverVersion,
                    encryptedMirrorType = mirrorType,
                    encryptedMirrorUrl = mirrorUrl,
                    encryptedMirrorKey = mirrorKey,
                    enabled = true,
                ),
            )
            if (bundle.profiles.isNotEmpty()) {
                replaceSubscriptionProfiles(existing.id, bundle.profiles, now, bundle.serverVersion)
            }
            return existing.id
        }
        return subscriptions.insertSubscriptionGroup(
            ProfileGroupEntity(
                name = bundle.name,
                type = "SUBSCRIPTION",
                subscriptionId = null,
                sortOrder = 0,
                createdAt = now,
            ),
            SubscriptionEntity(
                groupId = 0,
                name = bundle.name,
                kind = kind,
                encryptedUrl = secrets.encrypt(bundle.url.trim()),
                serverVersion = bundle.serverVersion,
                encryptedMirrorType = mirror?.type?.let(secrets::encrypt),
                encryptedMirrorUrl = mirror?.url?.let(secrets::encrypt),
                encryptedMirrorKey = bundle.mirrorKey?.let(secrets::encrypt),
                lastSuccessAt = null,
                lastAttemptAt = null,
                lastErrorCode = null,
                updateIntervalHours = 24,
                etag = null,
                lastModified = null,
                enabled = true,
            ),
            subscriptionEntities(bundle.profiles, now),
        )
    }

    private fun findSubscription(url: String): SubscriptionEntity? {
        val normalizedUrl = url.trim()
        return subscriptions.getSubscriptions().firstOrNull {
            secrets.decrypt(it.encryptedUrl).trim() == normalizedUrl
        }
    }

    fun replaceSubscriptionProfiles(
        subscriptionId: Long,
        profiles: List<ImportedProfile>,
        now: Long = System.currentTimeMillis(),
        serverVersion: String? = null,
        etag: String? = null,
        lastModified: String? = null,
    ) {
        val subscription = requireNotNull(subscriptions.getSubscription(subscriptionId)) { "Subscription not found" }
        subscriptions.replaceSubscriptionProfiles(
            subscription.copy(
                serverVersion = serverVersion ?: subscription.serverVersion,
                lastSuccessAt = now,
                lastAttemptAt = now,
                lastErrorCode = null,
                etag = etag,
                lastModified = lastModified,
            ),
            subscriptionEntities(profiles, now),
        )
    }

    fun updateSubscriptionProfile(
        profileId: String,
        profile: ImportedProfile,
        now: Long = System.currentTimeMillis(),
    ) {
        val current = requireNotNull(subscriptions.getProfile(profileId)) { "Subscription profile not found" }
        val updated = subscriptionProfile(profile)
        require(updated.type == current.type) { "Subscription profile type cannot be changed" }
        subscriptions.updateProfile(
            current.copy(
                name = updated.name,
                compatibilityMode = updated.compatibilityMode,
                encryptedConfigJson = secrets.encrypt(updated.configJson),
                isLocallyModified = true,
                updatedAt = now,
            ),
        )
    }

    fun deleteSubscriptionProfile(
        profileId: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val current = requireNotNull(subscriptions.getProfile(profileId)) { "Subscription profile not found" }
        subscriptions.updateProfile(current.copy(isDeleted = true, updatedAt = now))
    }

    fun resetSubscriptionProfile(
        profileId: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val current = requireNotNull(subscriptions.getProfile(profileId)) { "Subscription profile not found" }
        val upstreamConfig = requireNotNull(current.encryptedUpstreamConfigJson) { "Upstream profile not found" }
        val upstream = current.toImportedProfile(secrets.decrypt(upstreamConfig))
        val reset = subscriptionProfile(upstream)
        subscriptions.updateProfile(
            current.copy(
                type = reset.type,
                name = reset.name,
                encryptedConfigJson = upstreamConfig,
                identityHash = reset.identityHash,
                isLocallyModified = false,
                updatedAt = now,
            ),
        )
    }

    fun deleteSubscription(
        subscriptionId: Long,
        retainProfiles: Boolean,
    ) {
        val profiles = if (retainProfiles) getSubscriptionProfiles(subscriptionId) else emptyList()
        subscriptions.deleteSubscription(
            subscriptionId = subscriptionId,
            retainedOlcrtcProfiles = profiles.filterIsInstance<ImportedProfile.Olcrtc>()
                .map { it.value.toEntity() },
            retainedStandardProfiles = profiles.filterIsInstance<ImportedProfile.Standard>()
                .map { it.value.toEntity() },
        )
    }

    private fun subscriptionEntities(
        profiles: List<ImportedProfile>,
        now: Long,
    ): List<SubscriptionProfileEntity> = profiles
        .map(::subscriptionProfile)
        .distinctBy(PreparedSubscriptionProfile::identityHash)
        .mapIndexed { index, profile ->
            SubscriptionProfileEntity(
                id = UUID.randomUUID().toString(),
                groupId = 0,
                type = profile.type,
                name = profile.name,
                compatibilityMode = profile.compatibilityMode,
                encryptedConfigJson = secrets.encrypt(profile.configJson),
                encryptedUpstreamConfigJson = secrets.encrypt(profile.configJson),
                identityHash = profile.identityHash,
                isLocallyModified = false,
                favorite = false,
                sortOrder = index,
                lastLatencyMs = null,
                lastCheckedAt = null,
                createdAt = now,
                updatedAt = now,
            )
        }

    private fun subscriptionProfile(profile: ImportedProfile): PreparedSubscriptionProfile = when (profile) {
        is ImportedProfile.Olcrtc -> PreparedSubscriptionProfile(
            type = "OLCRTC",
            name = profile.value.name,
            compatibilityMode = profile.value.compatibilityMode.value,
            identityHash = ProfileIdentity.hash(profile.value),
            configJson = profile.value.toJson(),
        )
        is ImportedProfile.OpenFlux -> PreparedSubscriptionProfile(
            type = "OPENFLUX",
            name = profile.value.name,
            compatibilityMode = OlcrtcProfile.CompatibilityMode.CURRENT.value,
            identityHash = ProfileIdentity.hash(profile.value),
            configJson = profile.value.toJson(),
        )
        is ImportedProfile.Standard -> PreparedSubscriptionProfile(
            type = profile.value.protocol.name,
            name = profile.value.name,
            compatibilityMode = OlcrtcProfile.CompatibilityMode.CURRENT.value,
            identityHash = ProfileIdentity.hash(profile.value),
            configJson = profile.value.toJson(),
        )
    }

    private fun SubscriptionProfileEntity.toImportedProfile(
        configJson: String = secrets.decrypt(encryptedConfigJson),
    ): ImportedProfile {
        val value = JSONObject(configJson)
        val profileName = value.stringOrNull("name") ?: name
        return when (type) {
            "OLCRTC" -> {
                ImportedProfile.Olcrtc(
                    OlcrtcProfile(
                        name = profileName,
                        provider = OlcrtcProfile.Provider.parse(value.getString("provider")),
                        transport = OlcrtcProfile.Transport.parse(value.getString("transport")),
                        compatibilityMode = OlcrtcProfile.CompatibilityMode.parse(compatibilityMode),
                        roomId = value.getString("roomId"),
                        roomPassword = value.stringOrNull("roomPassword"),
                        clientId = value.getString("clientId"),
                        keyHex = value.getString("keyHex"),
                        dnsServer = value.stringOrNull("dnsServer"),
                        vp8Fps = value.getInt("vp8Fps"),
                        vp8BatchSize = value.getInt("vp8BatchSize"),
                        keepaliveIntervalSeconds = value.getInt("keepaliveIntervalSeconds"),
                    ),
                )
            }
            "OPENFLUX" -> {
                ImportedProfile.OpenFlux(
                    io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxProfile(
                        name = profileName,
                        documentUrl = value.getString("documentUrl"),
                        transport = io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxProfile.Transport.parse(value.getString("transport")),
                        codec = value.optString("codec").takeIf(String::isNotBlank)?.let(io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxProfile.Codec::parse)
                            ?: io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxProfile.Codec.BATCHED,
                        dnsServer = value.stringOrNull("dnsServer"),
                        encryptionKey = value.stringOrNull("encryptionKey"),
                    ),
                )
            }
            else -> {
                ImportedProfile.Standard(
                    value.toStandardProfile(profileName, type, value.getString("address"), value.getInt("port")),
                )
            }
        }
    }

    private fun io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxProfile.toJson(): String = JSONObject()
        .put("name", name)
        .put("documentUrl", documentUrl)
        .put("transport", transport.value)
        .put("codec", codec.value)
        .put("dnsServer", dnsServer)
        .put("encryptionKey", encryptionKey)
        .toString()

    private fun OlcrtcProfile.toJson(): String = JSONObject()
        .put("name", name)
        .put("provider", provider.value)
        .put("transport", transport.value)
        .put("compatibilityMode", compatibilityMode.value)
        .put("roomId", roomId)
        .put("roomPassword", roomPassword)
        .put("clientId", clientId)
        .put("keyHex", keyHex)
        .put("dnsServer", dnsServer)
        .put("vp8Fps", vp8Fps)
        .put("vp8BatchSize", vp8BatchSize)
        .put("keepaliveIntervalSeconds", keepaliveIntervalSeconds)
        .toString()

    private fun StandardProfile.toJson(): String = JSONObject()
        .put("name", name)
        .put("protocol", protocol.name)
        .put("address", address)
        .put("port", port)
        .put("uuid", uuid)
        .put("password", password)
        .put("alterId", alterId)
        .put("cipher", cipher)
        .put("transport", transport.name)
        .put("security", security.name)
        .put("flow", flow)
        .put("serverName", serverName)
        .put("alpn", JSONArray(alpn))
        .put("fingerprint", fingerprint)
        .put("allowInsecure", allowInsecure)
        .put("realityPublicKey", realityPublicKey)
        .put("realityShortId", realityShortId)
        .put("realitySpiderX", realitySpiderX)
        .put("webSocketHost", webSocketHost)
        .put("webSocketPath", webSocketPath)
        .put("grpcServiceName", grpcServiceName)
        .put("xhttpMode", xhttpMode)
        .put("xhttpHost", xhttpHost)
        .put("xhttpPath", xhttpPath)
        .put("xhttpExtraJson", xhttpExtraJson)
        .put("dnsServer", dnsServer)
        .toString()

    private data class PreparedSubscriptionProfile(
        val type: String,
        val name: String,
        val compatibilityMode: String,
        val identityHash: String,
        val configJson: String,
    )

    private fun OlcrtcProfile.toEntity(id: Long = 0) = OlcrtcProfileEntity(
        id = id,
        identityHash = ProfileIdentity.hash(this),
        name = name,
        provider = provider.value,
        transport = transport.value,
        compatibilityMode = compatibilityMode.value,
        roomId = roomId,
        roomPassword = roomPassword?.let(secrets::encrypt),
        clientId = clientId,
        keyHex = secrets.encrypt(keyHex),
        dnsServer = dnsServer.orEmpty(),
        vp8Fps = vp8Fps,
        vp8BatchSize = vp8BatchSize,
        keepaliveIntervalSeconds = keepaliveIntervalSeconds,
    )

    private fun OlcrtcProfileEntity.toProfile() = OlcrtcProfile(
        name = name,
        provider = OlcrtcProfile.Provider.parse(provider),
        transport = OlcrtcProfile.Transport.parse(transport),
        compatibilityMode = OlcrtcProfile.CompatibilityMode.parse(compatibilityMode),
        roomId = roomId,
        roomPassword = roomPassword?.let(secrets::decrypt),
        clientId = clientId,
        keyHex = secrets.decrypt(keyHex),
        dnsServer = dnsServer.takeIf(String::isNotBlank),
        vp8Fps = vp8Fps,
        vp8BatchSize = vp8BatchSize,
        keepaliveIntervalSeconds = keepaliveIntervalSeconds,
    )

    private fun StandardProfile.toEntity(id: Long = 0) = StandardProfileEntity(
        id = id,
        identityHash = ProfileIdentity.hash(this),
        name = name,
        protocol = protocol.name,
        address = address,
        port = port,
        secret = secrets.encrypt(
            JSONObject()
                .put("uuid", uuid)
                .put("password", password)
                .put("alterId", alterId)
                .put("cipher", cipher)
                .put("transport", transport.name)
                .put("security", security.name)
                .put("flow", flow)
                .put("serverName", serverName)
                .put("alpn", JSONArray(alpn))
                .put("fingerprint", fingerprint)
                .put("allowInsecure", allowInsecure)
                .put("realityPublicKey", realityPublicKey)
                .put("realityShortId", realityShortId)
                .put("realitySpiderX", realitySpiderX)
                .put("webSocketHost", webSocketHost)
                .put("webSocketPath", webSocketPath)
                .put("grpcServiceName", grpcServiceName)
                .put("xhttpMode", xhttpMode)
                .put("xhttpHost", xhttpHost)
                .put("xhttpPath", xhttpPath)
                .put("xhttpExtraJson", xhttpExtraJson)
                .put("dnsServer", dnsServer)
                .toString(),
        ),
    )

    private fun StandardProfileEntity.toProfile(): StandardProfile =
        JSONObject(secrets.decrypt(secret)).toStandardProfile(name, protocol, address, port)

    private fun JSONObject.toStandardProfile(
        name: String,
        protocol: String,
        address: String,
        port: Int,
    ) = StandardProfile(
        name = name,
        protocol = StandardProfile.Protocol.valueOf(protocol),
        address = address,
        port = port,
        uuid = stringOrNull("uuid"),
        password = stringOrNull("password"),
        alterId = getInt("alterId"),
        cipher = getString("cipher"),
        transport = StandardProfile.Transport.valueOf(getString("transport")),
        security = StandardProfile.Security.valueOf(getString("security")),
        flow = stringOrNull("flow"),
        serverName = stringOrNull("serverName"),
        alpn = getJSONArray("alpn").let { array ->
            List(array.length()) { index -> array.getString(index) }
        },
        fingerprint = stringOrNull("fingerprint"),
        allowInsecure = getBoolean("allowInsecure"),
        realityPublicKey = stringOrNull("realityPublicKey"),
        realityShortId = stringOrNull("realityShortId"),
        realitySpiderX = stringOrNull("realitySpiderX"),
        webSocketHost = stringOrNull("webSocketHost"),
        webSocketPath = stringOrNull("webSocketPath"),
        grpcServiceName = stringOrNull("grpcServiceName"),
        xhttpMode = stringOrNull("xhttpMode"),
        xhttpHost = stringOrNull("xhttpHost"),
        xhttpPath = stringOrNull("xhttpPath"),
        xhttpExtraJson = stringOrNull("xhttpExtraJson"),
        dnsServer = stringOrNull("dnsServer"),
    )

    private fun ProfileConfig.exportUri(): String = when (this) {
        is ProfileConfig.Olcrtc -> OlcrtcUri.serialize(value)
        is ProfileConfig.OpenFlux -> io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxUri.serialize(value)
        is ProfileConfig.Standard -> StandardUri.serialize(value)
    }

    private fun ImportedProfile?.endpointDescription(): String = when (this) {
        is ImportedProfile.Olcrtc -> "${value.provider.value} · ${value.roomId}"
        is ImportedProfile.OpenFlux -> when (value.transport.value.lowercase()) {
            "mailru" -> "Mail.ru · Docs"
            "vyandex" -> "Yandex · Volga"
            else -> "Yandex · Docs"
        }
        is ImportedProfile.Standard -> "${value.address}:${value.port}"
        null -> "unavailable"
    }

    private fun JSONObject.stringOrNull(name: String): String? =
        if (isNull(name)) null else getString(name)

    fun insertLocal(profile: io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxProfile): Long {
        val entity = profile.toEntity()
        return OPENFLUX_ID_OFFSET + openfluxProfiles.insert(entity)
    }

    fun update(id: Long, profile: io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxProfile) {
        val entity = profile.toEntity(id - OPENFLUX_ID_OFFSET)
        openfluxProfiles.update(entity)
    }

    private fun io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxProfile.toEntity(id: Long = 0) = OpenFluxProfileEntity(
        id = id,
        identityHash = ProfileIdentity.hash(this),
        name = name,
        documentUrl = secrets.encrypt(documentUrl),
        transport = transport.value,
        dnsServer = dnsServer.orEmpty(),
    )

    private fun OpenFluxProfileEntity.toProfile() = io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxProfile(
        name = name,
        documentUrl = secrets.decrypt(documentUrl),
        transport = io.github.oleglog.olcrtc.client.profile.openflux.OpenFluxProfile.Transport.parse(transport),
        dnsServer = dnsServer.takeIf(String::isNotBlank),
    )

    companion object {
        internal const val STANDARD_ID_OFFSET = 1L shl 62
        internal const val OPENFLUX_ID_OFFSET = 1L shl 61
        private const val DEFAULT_SUBSCRIPTION_INTERVAL_HOURS = 24

        fun open(context: Context): ProfileRepository {
            val database = ClientDatabase.open(context)
            return ProfileRepository(
                database.olcrtcProfiles(),
                database.standardProfiles(),
                database.openfluxProfiles(),
                database.subscriptions(),
                database.routingRules(),
                SecretCipher(),
            )
        }
    }
}
