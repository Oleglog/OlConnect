package io.github.oleglog.olcrtc.client.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.oleglog.olcrtc.client.importer.SubscriptionBundle
import io.github.oleglog.olcrtc.client.profile.ImportedProfile
import io.github.oleglog.olcrtc.client.profile.ProfileIdentity
import io.github.oleglog.olcrtc.client.profile.olcrtc.OlcrtcProfile
import io.github.oleglog.olcrtc.client.profile.standard.StandardProfile
import io.github.oleglog.olcrtc.client.routing.RoutingRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileRepositoryTest {
    private lateinit var database: ClientDatabase
    private lateinit var olcrtcDao: OlcrtcProfileDao
    private lateinit var standardDao: StandardProfileDao
    private lateinit var repository: ProfileRepository

    @Before
    fun setUp() {
        database = ClientDatabase.inMemory(ApplicationProvider.getApplicationContext())
        olcrtcDao = database.olcrtcProfiles()
        standardDao = database.standardProfiles()
        repository = ProfileRepository(
            olcrtcDao,
            standardDao,
            database.subscriptions(),
            database.routingRules(),
            SecretCipher(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun storesEncryptedSecretsAndReadsProfile() {
        val profile = OlcrtcProfile(
            name = "test",
            provider = OlcrtcProfile.Provider.WBSTREAM,
            transport = OlcrtcProfile.Transport.VP8CHANNEL,
            roomId = "room",
            roomPassword = "password",
            clientId = "client",
            keyHex = "a".repeat(64),
            dnsServer = "[2001:db8::1]:53",
        )

        val id = repository.insert(profile)
        val stored = olcrtcDao.get(id)!!

        assertFalse(stored.roomPassword!!.contentEquals("password".encodeToByteArray()))
        assertFalse(stored.keyHex.contentEquals(profile.keyHex.encodeToByteArray()))
        assertEquals("current", stored.compatibilityMode)
        assertEquals(profile, repository.getOlcrtc(id))
    }

    @Test
    fun storesEncryptedStandardConfigAndReadsProfile() {
        val profile = StandardProfile(
            name = "VLESS",
            protocol = StandardProfile.Protocol.VLESS,
            address = "example.com",
            port = 443,
            uuid = "00000000-0000-0000-0000-000000000001",
            transport = StandardProfile.Transport.WS,
            security = StandardProfile.Security.TLS,
            webSocketPath = "/ws",
            dnsServer = "77.88.8.8:5353",
        )

        val id = repository.insert(profile)
        val stored = standardDao.get(id - ProfileRepository.STANDARD_ID_OFFSET)!!

        assertFalse(stored.secret.decodeToString().contains(profile.uuid!!))
        assertEquals(ProfileConfig.Standard(profile), repository.get(id))
    }

    @Test
    fun keepsProfileTypesInSeparateIdNamespaces() {
        val olcrtcId = repository.insert(OlcrtcProfile(
            name = "olcRTC",
            provider = OlcrtcProfile.Provider.WBSTREAM,
            transport = OlcrtcProfile.Transport.VP8CHANNEL,
            roomId = "room",
            clientId = "client",
            keyHex = "a".repeat(64),
        ))
        val standardId = repository.insert(StandardProfile(
            name = "VMess",
            protocol = StandardProfile.Protocol.VMESS,
            address = "example.com",
            port = 443,
            uuid = "00000000-0000-0000-0000-000000000001",
        ))

        assertEquals(1L, olcrtcId)
        assertEquals(ProfileRepository.STANDARD_ID_OFFSET + 1, standardId)
        assertTrue(repository.get(olcrtcId) is ProfileConfig.Olcrtc)
        assertTrue(repository.get(standardId) is ProfileConfig.Standard)
    }

    @Test
    fun findsDuplicateRegardlessOfNameAndAllowsCopy() {
        val first = StandardProfile(
            name = "First",
            protocol = StandardProfile.Protocol.VLESS,
            address = "example.com",
            port = 443,
            uuid = "00000000-0000-0000-0000-000000000001",
            transport = StandardProfile.Transport.WS,
            security = StandardProfile.Security.TLS,
            webSocketPath = "/ws",
        )
        val firstId = repository.insert(first)
        val renamed = first.copy(name = "Second")

        assertEquals(firstId, repository.findDuplicate(renamed))
        val copyId = repository.insert(renamed)
        assertTrue(copyId > firstId)
        assertEquals(firstId, repository.findDuplicate(renamed))
    }

    @Test
    fun updatesExistingDuplicateInPlace() {
        val first = OlcrtcProfile(
            name = "First",
            provider = OlcrtcProfile.Provider.WBSTREAM,
            transport = OlcrtcProfile.Transport.VP8CHANNEL,
            roomId = "room",
            clientId = "client",
            keyHex = "a".repeat(64),
        )
        val id = repository.insert(first)
        val renamed = first.copy(name = "Updated")

        repository.update(id, renamed)

        assertEquals(renamed, repository.getOlcrtc(id))
        assertEquals(id, repository.findDuplicate(first))
    }

    @Test
    fun createsLocalGroupForNewDatabase() {
        val local = database.subscriptions().getLocalGroup()

        assertEquals("Local", local?.name)
        assertEquals(1, database.subscriptions().countGroups())
    }

    @Test
    fun normalizesReplacesAndOrdersRoutingRules() {
        val suffixId = repository.saveRoutingRule(routingRule(
            RoutingRule.MatchType.DOMAIN_SUFFIX,
            ".Example.COM",
            RoutingRule.Action.DIRECT,
            sortOrder = 0,
        ))
        val cidrId = repository.saveRoutingRule(routingRule(
            RoutingRule.MatchType.CIDR,
            "192.0.2.129/24",
            RoutingRule.Action.BLOCK,
            sortOrder = 5,
        ))
        val replacementId = repository.saveRoutingRule(routingRule(
            RoutingRule.MatchType.DOMAIN_SUFFIX,
            "example.com",
            RoutingRule.Action.VPN,
            sortOrder = 9,
        ))
        repository.saveRoutingRule(routingRule(
            RoutingRule.MatchType.IP,
            "192.0.2.1",
            RoutingRule.Action.DIRECT,
            enabled = false,
        ))

        val enabled = repository.getEnabledRoutingRules()

        assertEquals(suffixId, replacementId)
        assertEquals(listOf(cidrId, suffixId), enabled.map(RoutingRule::id))
        assertEquals("192.0.2.0/24", enabled[0].value)
        assertEquals(RoutingRule.Action.VPN, enabled[1].action)
        assertEquals(2, database.routingRules().getEnabled().size)
    }

    @Test
    fun rejectsUpdateOfMissingRoutingRule() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveRoutingRule(routingRule(
                RoutingRule.MatchType.DOMAIN,
                "example.com",
                RoutingRule.Action.VPN,
                id = 99,
            ))
        }
    }

    private fun routingRule(
        matchType: RoutingRule.MatchType,
        value: String,
        action: RoutingRule.Action,
        id: Long = 0,
        enabled: Boolean = true,
        sortOrder: Int = 0,
    ) = RoutingRule.create(
        id = id,
        matchType = matchType,
        value = value,
        action = action,
        enabled = enabled,
        sortOrder = sortOrder,
    )

    @Test
    fun insertsEncryptedSubscriptionAndProfilesAtomically() {
        val profile = StandardProfile(
            name = "VLESS",
            protocol = StandardProfile.Protocol.VLESS,
            address = "example.com",
            port = 443,
            uuid = "00000000-0000-0000-0000-000000000001",
        )
        val subscriptionId = repository.insertSubscription(
            subscriptionBundle(listOf(ImportedProfile.Standard(profile), ImportedProfile.Standard(profile.copy(name = "Copy")))),
            now = 1234,
        )
        val dao = database.subscriptions()
        val relation = dao.getSubscriptionGroup(subscriptionId)!!
        val group = dao.getGroup(relation.groupId)!!
        val subscription = dao.getSubscription(subscriptionId)!!
        val storedProfiles = dao.getProfiles(relation.groupId)

        assertEquals(subscriptionId, group.subscriptionId)
        assertEquals("SUBSCRIPTION", group.type)
        assertEquals(relation.groupId, subscription.groupId)
        assertEquals("GENERIC", subscription.kind)
        assertNull(subscription.lastSuccessAt)
        assertNull(subscription.lastAttemptAt)
        assertEquals(1, storedProfiles.size)
        assertEquals(listOf(ImportedProfile.Standard(profile)), repository.getSubscriptionProfiles(subscriptionId))
        assertEquals("https://example.com/subscription", repository.getSubscriptionSource(subscriptionId)?.url)
        assertEquals("https://example.com/mirror", repository.getSubscriptionSource(subscriptionId)?.mirrorUrl)
        assertFalse(subscription.encryptedUrl.decodeToString().contains("example.com"))
        assertFalse(subscription.encryptedMirrorKey!!.decodeToString().contains("aaaa"))
        assertFalse(storedProfiles.single().encryptedConfigJson.decodeToString().contains(profile.uuid!!))
        assertFalse(storedProfiles.single().encryptedUpstreamConfigJson!!.contentEquals(storedProfiles.single().encryptedConfigJson))
    }

    @Test
    fun preservesStoredMirrorWhenReimportedWithoutMirror() {
        val subscriptionId = repository.insertSubscription(
            subscriptionBundle(listOf(ImportedProfile.Standard(profile))),
            now = 1,
        )
        assertEquals("https://example.com/mirror", repository.getSubscriptionSource(subscriptionId)?.mirrorUrl)

        // Re-import the same subscription via a bare /open deep link — no mirror
        // fields. The stored Yandex fallback must survive; otherwise an "open in
        // app" re-import silently drops the only path that works when the server
        // is down or blocked.
        val bareDeepLinkBundle = SubscriptionBundle(
            name = "Test subscription",
            slug = "test",
            url = "https://example.com/subscription",
            serverVersion = null,
            mirrors = emptyList(),
            mirrorKey = null,
            deduplication = true,
            updateWhenConnectedOnly = false,
            profiles = emptyList(),
            rejectedProfiles = emptyList(),
        )
        val reimportId = repository.insertSubscription(bareDeepLinkBundle, now = 2)

        assertEquals(subscriptionId, reimportId)
        val source = repository.getSubscriptionSource(reimportId)
        assertEquals("https://example.com/subscription", source?.url)
        assertEquals("https://example.com/mirror", source?.mirrorUrl)
    }

    @Test
    fun restoresOlcrtcSubscriptionProfileAndKind() {
        val profile = ImportedProfile.Olcrtc(OlcrtcProfile(
            name = "olcRTC",
            provider = OlcrtcProfile.Provider.WBSTREAM,
            transport = OlcrtcProfile.Transport.VP8CHANNEL,
            roomId = "room",
            roomPassword = "password",
            clientId = "client",
            keyHex = "a".repeat(64),
        ))

        val subscriptionId = repository.insertSubscription(subscriptionBundle(listOf(profile)), now = 1)

        assertEquals("OLCRTC", database.subscriptions().getSubscription(subscriptionId)?.kind)
        assertEquals(listOf(profile), repository.getSubscriptionProfiles(subscriptionId))
    }

    @Test
    fun preservesPerProfileCompatibilityModeAcrossSubscriptionRefreshAndReset() {
        val upstream = ImportedProfile.Olcrtc(OlcrtcProfile(
            name = "olcRTC",
            provider = OlcrtcProfile.Provider.WBSTREAM,
            transport = OlcrtcProfile.Transport.VP8CHANNEL,
            compatibilityMode = OlcrtcProfile.CompatibilityMode.CURRENT,
            roomId = "room",
            clientId = "client",
            keyHex = "a".repeat(64),
        ))
        val subscriptionId = repository.insertSubscription(subscriptionBundle(listOf(upstream)), now = 1)
        val dao = database.subscriptions()
        val groupId = dao.getSubscriptionGroup(subscriptionId)!!.groupId
        val profileId = dao.getProfiles(groupId).single().id
        val legacy = ImportedProfile.Olcrtc(
            upstream.value.copy(compatibilityMode = OlcrtcProfile.CompatibilityMode.LEGACY),
        )

        repository.updateSubscriptionProfile(profileId, legacy, now = 2)
        repository.replaceSubscriptionProfiles(subscriptionId, listOf(upstream), now = 3)

        assertEquals(
            OlcrtcProfile.CompatibilityMode.LEGACY,
            (repository.getSubscriptionProfile(profileId) as ProfileConfig.Olcrtc).value.compatibilityMode,
        )
        assertEquals("legacy", dao.getProfile(profileId)!!.compatibilityMode)

        repository.resetSubscriptionProfile(profileId, now = 4)

        assertEquals(
            OlcrtcProfile.CompatibilityMode.LEGACY,
            (repository.getSubscriptionProfile(profileId) as ProfileConfig.Olcrtc).value.compatibilityMode,
        )
    }

    @Test
    fun loadsSubscriptionProfilesByUuidForVpn() {
        val olcrtc = OlcrtcProfile(
            name = "olcRTC",
            provider = OlcrtcProfile.Provider.WBSTREAM,
            transport = OlcrtcProfile.Transport.VP8CHANNEL,
            roomId = "room",
            roomPassword = "password",
            clientId = "client",
            keyHex = "a".repeat(64),
        )
        val standard = StandardProfile(
            name = "VLESS",
            protocol = StandardProfile.Protocol.VLESS,
            address = "example.com",
            port = 443,
            uuid = "00000000-0000-0000-0000-000000000001",
        )
        val subscriptionId = repository.insertSubscription(
            subscriptionBundle(listOf(ImportedProfile.Olcrtc(olcrtc), ImportedProfile.Standard(standard))),
            now = 1,
        )
        val dao = database.subscriptions()
        val groupId = dao.getSubscriptionGroup(subscriptionId)!!.groupId
        val profiles = dao.getProfiles(groupId).associateBy { it.type }

        assertEquals(ProfileConfig.Olcrtc(olcrtc), repository.getSubscriptionProfile(profiles.getValue("OLCRTC").id))
        assertEquals(ProfileConfig.Standard(standard), repository.getSubscriptionProfile(profiles.getValue("VLESS").id))
        assertNull(repository.getSubscriptionProfile("missing"))
    }

    @Test
    fun deduplicatesSubscriptionWhenLegacyFlagIsDisabled() {
        val profile = ImportedProfile.Standard(StandardProfile(
            name = "VLESS",
            protocol = StandardProfile.Protocol.VLESS,
            address = "example.com",
            port = 443,
            uuid = "00000000-0000-0000-0000-000000000001",
        ))

        val subscriptionId = repository.insertSubscription(
            subscriptionBundle(listOf(profile, profile), deduplication = false),
            now = 1,
        )

        assertEquals(listOf(profile), repository.getSubscriptionProfiles(subscriptionId))
        assertEquals(1, database.subscriptions().countProfiles())
    }

    @Test
    fun keepsSameIdentityInSeparateSubscriptions() {
        val profile = ImportedProfile.Olcrtc(OlcrtcProfile(
            name = "olcRTC",
            provider = OlcrtcProfile.Provider.WBSTREAM,
            transport = OlcrtcProfile.Transport.VP8CHANNEL,
            roomId = "room",
            clientId = "client",
            keyHex = "a".repeat(64),
        ))

        repository.insertSubscription(subscriptionBundle(listOf(profile)), now = 1)
        repository.insertSubscription(
            subscriptionBundle(listOf(profile), url = "https://example.com/other-subscription"),
            now = 2,
        )

        assertEquals(2, database.subscriptions().countSubscriptions())
        assertEquals(2, database.subscriptions().countProfiles())
    }

    @Test
    fun reusesSameSubscriptionUrlAndPreservesProfilesUntilRefresh() {
        val profile = ImportedProfile.Standard(StandardProfile(
            name = "VLESS",
            protocol = StandardProfile.Protocol.VLESS,
            address = "example.com",
            port = 443,
            uuid = "00000000-0000-0000-0000-000000000001",
        ))
        val firstId = repository.insertSubscription(subscriptionBundle(listOf(profile)), now = 1)

        val secondId = repository.insertSubscription(
            subscriptionBundle(emptyList()).copy(serverVersion = "1.9.56"),
            now = 2,
        )

        assertEquals(firstId, secondId)
        assertEquals(1, database.subscriptions().countSubscriptions())
        assertEquals(listOf(profile), repository.getSubscriptionProfiles(firstId))
        assertEquals("1.9.56", database.subscriptions().getSubscription(firstId)!!.serverVersion)
    }

    @Test
    fun refreshesSubscriptionProfilesAtomically() {
        val original = StandardProfile(
            name = "Original",
            protocol = StandardProfile.Protocol.VLESS,
            address = "example.com",
            port = 443,
            uuid = "00000000-0000-0000-0000-000000000001",
        )
        val removed = StandardProfile(
            name = "Removed",
            protocol = StandardProfile.Protocol.VLESS,
            address = "removed.example.com",
            port = 443,
            uuid = "00000000-0000-0000-0000-000000000002",
        )
        val subscriptionId = repository.insertSubscription(
            subscriptionBundle(listOf(ImportedProfile.Standard(original), ImportedProfile.Standard(removed))),
            now = 1,
        )
        val renamed = original.copy(name = "Renamed")

        repository.replaceSubscriptionProfiles(
            subscriptionId,
            listOf(ImportedProfile.Standard(renamed)),
            now = 2,
            serverVersion = "1.9.46",
            etag = "etag",
            lastModified = "last-modified",
        )

        assertEquals(listOf(ImportedProfile.Standard(renamed)), repository.getSubscriptionProfiles(subscriptionId))
        assertEquals(1, database.subscriptions().countProfiles())
        val subscription = database.subscriptions().getSubscription(subscriptionId)!!
        assertEquals(2L, subscription.lastSuccessAt)
        assertEquals(2L, subscription.lastAttemptAt)
        assertEquals("1.9.46", subscription.serverVersion)
        assertEquals("etag", subscription.etag)
        assertEquals("last-modified", subscription.lastModified)
    }

    @Test
    fun preservesLocallyModifiedProfileDuringRefreshAndResetsToLatestUpstream() {
        val profile = ImportedProfile.Standard(StandardProfile(
            name = "Upstream",
            protocol = StandardProfile.Protocol.VLESS,
            address = "example.com",
            port = 443,
            uuid = "00000000-0000-0000-0000-000000000001",
        ))
        val subscriptionId = repository.insertSubscription(subscriptionBundle(listOf(profile)), now = 1)
        val dao = database.subscriptions()
        val groupId = dao.getSubscriptionGroup(subscriptionId)!!.groupId
        val profileId = dao.getProfiles(groupId).single().id
        val local = profile.value.copy(name = "Local", port = 8443)

        repository.updateSubscriptionProfile(profileId, ImportedProfile.Standard(local), now = 2)
        repository.replaceSubscriptionProfiles(
            subscriptionId,
            listOf(ImportedProfile.Standard(profile.value.copy(name = "New upstream", port = 9443))),
            now = 3,
        )

        val modified = dao.getProfiles(groupId).single()
        assertEquals("Local", modified.name)
        assertTrue(modified.isLocallyModified)
        assertEquals(listOf(ImportedProfile.Standard(local)), repository.getSubscriptionProfiles(subscriptionId))

        repository.replaceSubscriptionProfiles(subscriptionId, emptyList(), now = 4)

        assertEquals(1, dao.countProfiles())

        repository.resetSubscriptionProfile(profileId, now = 5)

        val reset = dao.getProfiles(groupId).single()
        assertEquals("New upstream", reset.name)
        assertFalse(reset.isLocallyModified)
        assertEquals(
            listOf(ImportedProfile.Standard(profile.value.copy(name = "New upstream", port = 9443))),
            repository.getSubscriptionProfiles(subscriptionId),
        )
    }

    @Test
    fun deletedSubscriptionProfileStaysDeletedAfterRefresh() {
        val profile = ImportedProfile.Standard(StandardProfile(
            name = "VLESS",
            protocol = StandardProfile.Protocol.VLESS,
            address = "example.com",
            port = 443,
            uuid = "00000000-0000-0000-0000-000000000001",
        ))
        val subscriptionId = repository.insertSubscription(subscriptionBundle(listOf(profile)), now = 1)
        val dao = database.subscriptions()
        val groupId = dao.getSubscriptionGroup(subscriptionId)!!.groupId
        val profileId = dao.getProfiles(groupId).single().id

        repository.deleteSubscriptionProfile(profileId, now = 2)
        repository.replaceSubscriptionProfiles(subscriptionId, listOf(profile), now = 3)

        // The tombstone survives a refresh: a profile the user deleted must not
        // be brought back by the next subscription sync.
        assertTrue(dao.getProfile(profileId)!!.isDeleted)
        assertTrue(repository.getSubscriptionProfiles(subscriptionId).isEmpty())
        assertTrue(repository.listSubscriptionProfiles(subscriptionId).isEmpty())
        assertNull(repository.getSubscriptionProfile(profileId))
        assertEquals(0, repository.listSubscriptions().single().profileCount)

        // Reset is the explicit way back.
        repository.resetSubscriptionProfile(profileId, now = 4)
        assertFalse(dao.getProfile(profileId)!!.isDeleted)
        assertEquals(1, repository.listSubscriptionProfiles(subscriptionId).size)
    }

    @Test
    fun deletesSubscriptionWithProfiles() {
        val profile = ImportedProfile.Standard(StandardProfile(
            name = "VLESS",
            protocol = StandardProfile.Protocol.VLESS,
            address = "example.com",
            port = 443,
            uuid = "00000000-0000-0000-0000-000000000001",
        ))
        val subscriptionId = repository.insertSubscription(subscriptionBundle(listOf(profile)), now = 1)

        repository.deleteSubscription(subscriptionId, retainProfiles = false)

        val dao = database.subscriptions()
        assertEquals(1, dao.countGroups())
        assertEquals(0, dao.countSubscriptions())
        assertEquals(0, dao.countProfiles())
        assertNull(repository.findDuplicate(profile.value))
    }

    @Test
    fun preservesSubscriptionProfilesAsEncryptedLocalProfiles() {
        val olcrtc = OlcrtcProfile(
            name = "olcRTC",
            provider = OlcrtcProfile.Provider.WBSTREAM,
            transport = OlcrtcProfile.Transport.VP8CHANNEL,
            roomId = "room",
            clientId = "client",
            keyHex = "a".repeat(64),
        )
        val standard = StandardProfile(
            name = "VLESS",
            protocol = StandardProfile.Protocol.VLESS,
            address = "example.com",
            port = 443,
            uuid = "00000000-0000-0000-0000-000000000001",
        )
        val subscriptionId = repository.insertSubscription(
            subscriptionBundle(listOf(ImportedProfile.Olcrtc(olcrtc), ImportedProfile.Standard(standard))),
            now = 1,
        )

        repository.deleteSubscription(subscriptionId, retainProfiles = true)

        val dao = database.subscriptions()
        assertEquals(1, dao.countGroups())
        assertEquals(0, dao.countSubscriptions())
        assertEquals(0, dao.countProfiles())
        assertEquals(olcrtc, repository.getOlcrtc(repository.findDuplicate(olcrtc)!!))
        assertEquals(ProfileConfig.Standard(standard), repository.get(repository.findDuplicate(standard)!!))
        assertFalse(olcrtcDao.findByIdentity(ProfileIdentity.hash(olcrtc))!!.keyHex.contentEquals(olcrtc.keyHex.encodeToByteArray()))
        assertFalse(standardDao.findByIdentity(ProfileIdentity.hash(standard))!!.secret.decodeToString().contains(standard.uuid!!))
    }

    @Test
    fun returnsOnlyEnabledSubscriptionsWhoseRefreshIntervalElapsed() {
        val neverRefreshed = repository.insertSubscription(
            subscriptionBundle(emptyList(), url = "https://example.com/never"),
            now = 1,
        )
        val recent = repository.insertSubscription(
            subscriptionBundle(emptyList(), url = "https://example.com/recent"),
            now = 2,
        )
        val elapsed = repository.insertSubscription(
            subscriptionBundle(emptyList(), url = "https://example.com/elapsed"),
            now = 3,
        )
        val disabled = repository.insertSubscription(
            subscriptionBundle(emptyList(), url = "https://example.com/disabled"),
            now = 4,
        )
        repository.markSubscriptionRefresh(recent, errorCode = null, now = 1_000, successful = true)
        repository.markSubscriptionRefresh(elapsed, errorCode = null, now = 0, successful = true)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE subscriptions SET enabled = 0 WHERE id = ?",
            arrayOf(disabled),
        )

        assertEquals(
            listOf(neverRefreshed, elapsed),
            repository.getStaleSubscriptionIds(now = 24 * 3_600_000L),
        )
    }

    @Test
    fun recordsRefreshFailureWithoutChangingProfilesOrLastSuccess() {
        val profile = ImportedProfile.Standard(StandardProfile(
            name = "VLESS",
            protocol = StandardProfile.Protocol.VLESS,
            address = "example.com",
            port = 443,
            uuid = "00000000-0000-0000-0000-000000000001",
        ))
        val subscriptionId = repository.insertSubscription(subscriptionBundle(listOf(profile)), now = 1)
        repository.replaceSubscriptionProfiles(subscriptionId, listOf(profile), now = 2, etag = "etag")

        repository.markSubscriptionRefresh(subscriptionId, errorCode = "NETWORK", now = 3)

        val subscription = database.subscriptions().getSubscription(subscriptionId)!!
        assertEquals(2L, subscription.lastSuccessAt)
        assertEquals(3L, subscription.lastAttemptAt)
        assertEquals("NETWORK", subscription.lastErrorCode)
        assertEquals("etag", subscription.etag)
        assertEquals(listOf(profile), repository.getSubscriptionProfiles(subscriptionId))
    }

    @Test
    fun recordsNotModifiedAsSuccessfulRefresh() {
        val profile = ImportedProfile.Standard(StandardProfile(
            name = "VLESS",
            protocol = StandardProfile.Protocol.VLESS,
            address = "example.com",
            port = 443,
            uuid = "00000000-0000-0000-0000-000000000001",
        ))
        val subscriptionId = repository.insertSubscription(subscriptionBundle(listOf(profile)), now = 1)

        repository.markSubscriptionRefresh(
            subscriptionId = subscriptionId,
            errorCode = null,
            now = 4,
            etag = "etag",
            lastModified = "date",
            successful = true,
        )

        val subscription = database.subscriptions().getSubscription(subscriptionId)!!
        assertEquals(4L, subscription.lastSuccessAt)
        assertEquals(4L, subscription.lastAttemptAt)
        assertNull(subscription.lastErrorCode)
        assertEquals("etag", subscription.etag)
        assertEquals("date", subscription.lastModified)
        assertEquals(listOf(profile), repository.getSubscriptionProfiles(subscriptionId))
    }

    @Test
    fun rollsBackSubscriptionWhenProfileInsertFails() {
        val duplicate = SubscriptionProfileEntity(
            id = "profile-1",
            groupId = 0,
            type = "OLCRTC",
            name = "Profile",
            compatibilityMode = "legacy",
            encryptedConfigJson = byteArrayOf(1),
            encryptedUpstreamConfigJson = byteArrayOf(2),
            identityHash = "same",
            isLocallyModified = false,
            favorite = false,
            sortOrder = 0,
            lastLatencyMs = null,
            lastCheckedAt = null,
            createdAt = 1,
            updatedAt = 1,
        )
        val dao = database.subscriptions()

        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            dao.insertSubscriptionGroup(
                ProfileGroupEntity(name = "Broken", type = "SUBSCRIPTION", subscriptionId = null, sortOrder = 0, createdAt = 1),
                SubscriptionEntity(
                    groupId = 0,
                    name = "Broken",
                    kind = "OLCRTC",
                    encryptedUrl = byteArrayOf(1),
                    serverVersion = null,
                    encryptedMirrorType = null,
                    encryptedMirrorUrl = null,
                    encryptedMirrorKey = null,
                    lastSuccessAt = null,
                    lastAttemptAt = null,
                    lastErrorCode = null,
                    updateIntervalHours = 24,
                    etag = null,
                    lastModified = null,
                    enabled = true,
                ),
                listOf(duplicate, duplicate.copy(id = "profile-2")),
            )
        }

        assertEquals(1, dao.countGroups())
        assertEquals(0, dao.countSubscriptions())
        assertEquals(0, dao.countProfiles())
    }

    private fun subscriptionBundle(
        profiles: List<ImportedProfile>,
        deduplication: Boolean = true,
        url: String = "https://example.com/subscription",
    ) = SubscriptionBundle(
        name = "Test subscription",
        slug = "test",
        url = url,
        serverVersion = "1.9.45",
        mirrors = listOf(SubscriptionBundle.Mirror("yandex", "https://example.com/mirror", true, "AES-256-GCM")),
        mirrorKey = "a".repeat(43),
        deduplication = deduplication,
        updateWhenConnectedOnly = false,
        profiles = profiles,
        rejectedProfiles = emptyList(),
    )

    @Test
    fun returnsNullForUnknownProfile() {
        assertNull(repository.getOlcrtc(ProfileRepository.STANDARD_ID_OFFSET + 1))
        assertNull(repository.get(ProfileRepository.STANDARD_ID_OFFSET + 404))
    }
}
