package io.github.oleglog.olcrtc.client.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.oleglog.olcrtc.client.routing.RoutingRule

@Entity(
    tableName = "olcrtc_profiles",
    indices = [Index(value = ["identityHash"])],
)
internal data class OlcrtcProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val identityHash: String?,
    val name: String,
    val provider: String,
    val transport: String,
    @ColumnInfo(defaultValue = "'legacy'") val compatibilityMode: String,
    val roomId: String,
    val roomPassword: ByteArray?,
    val clientId: String,
    val keyHex: ByteArray,
    val dnsServer: String,
    val vp8Fps: Int,
    val vp8BatchSize: Int,
    val keepaliveIntervalSeconds: Int,
)

@Dao
internal interface OlcrtcProfileDao {
    @Query("SELECT * FROM olcrtc_profiles WHERE id = :id")
    fun get(id: Long): OlcrtcProfileEntity?

    @Query("SELECT * FROM olcrtc_profiles ORDER BY name, id")
    fun getAll(): List<OlcrtcProfileEntity>

    @Query("SELECT * FROM olcrtc_profiles WHERE identityHash = :identityHash ORDER BY id LIMIT 1")
    fun findByIdentity(identityHash: String): OlcrtcProfileEntity?

    @Query("DELETE FROM olcrtc_profiles WHERE id = :id")
    fun delete(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(profile: OlcrtcProfileEntity): Long

    @Update
    fun update(profile: OlcrtcProfileEntity)
}

@Entity(
    tableName = "openflux_profiles",
    indices = [Index(value = ["identityHash"])],
)
internal data class OpenFluxProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val identityHash: String?,
    val name: String,
    val documentUrl: ByteArray,
    val transport: String,
    val dnsServer: String,
)

@Dao
internal interface OpenFluxProfileDao {
    @Query("SELECT * FROM openflux_profiles WHERE id = :id")
    fun get(id: Long): OpenFluxProfileEntity?

    @Query("SELECT * FROM openflux_profiles ORDER BY name, id")
    fun getAll(): List<OpenFluxProfileEntity>

    @Query("SELECT * FROM openflux_profiles WHERE identityHash = :identityHash ORDER BY id LIMIT 1")
    fun findByIdentity(identityHash: String): OpenFluxProfileEntity?

    @Query("DELETE FROM openflux_profiles WHERE id = :id")
    fun delete(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(profile: OpenFluxProfileEntity): Long

    @Update
    fun update(profile: OpenFluxProfileEntity)
}

@Entity(
    tableName = "standard_profiles",
    indices = [Index(value = ["identityHash"])],
)
internal data class StandardProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val identityHash: String?,
    val name: String,
    val protocol: String,
    val address: String,
    val port: Int,
    val secret: ByteArray,
)

@Dao
internal interface StandardProfileDao {
    @Query("SELECT * FROM standard_profiles WHERE id = :id")
    fun get(id: Long): StandardProfileEntity?

    @Query("SELECT * FROM standard_profiles ORDER BY name, id")
    fun getAll(): List<StandardProfileEntity>

    @Query("SELECT * FROM standard_profiles WHERE identityHash = :identityHash ORDER BY id LIMIT 1")
    fun findByIdentity(identityHash: String): StandardProfileEntity?

    @Query("DELETE FROM standard_profiles WHERE id = :id")
    fun delete(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(profile: StandardProfileEntity): Long

    @Update
    fun update(profile: StandardProfileEntity)
}

@Entity(tableName = "profile_groups")
internal data class ProfileGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val subscriptionId: Long?,
    val sortOrder: Int,
    val createdAt: Long,
)

@Entity(
    tableName = "subscriptions",
    foreignKeys = [
        ForeignKey(
            entity = ProfileGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["groupId"], unique = true)],
)
internal data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val name: String,
    val kind: String,
    val encryptedUrl: ByteArray,
    val serverVersion: String?,
    val encryptedMirrorType: ByteArray?,
    val encryptedMirrorUrl: ByteArray?,
    val encryptedMirrorKey: ByteArray?,
    val lastSuccessAt: Long?,
    val lastAttemptAt: Long?,
    val lastErrorCode: String?,
    val updateIntervalHours: Int,
    val etag: String?,
    val lastModified: String?,
    val enabled: Boolean,
)

@Entity(
    tableName = "subscription_profiles",
    foreignKeys = [
        ForeignKey(
            entity = ProfileGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["groupId"]),
        Index(value = ["groupId", "identityHash"], unique = true),
    ],
)
internal data class SubscriptionProfileEntity(
    @PrimaryKey val id: String,
    val groupId: Long,
    val type: String,
    val name: String,
    @ColumnInfo(defaultValue = "'legacy'") val compatibilityMode: String,
    val encryptedConfigJson: ByteArray,
    val encryptedUpstreamConfigJson: ByteArray?,
    val identityHash: String,
    val isLocallyModified: Boolean,
    val favorite: Boolean,
    val sortOrder: Int,
    val lastLatencyMs: Long?,
    val lastCheckedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val isDeleted: Boolean = false,
)

internal data class SubscriptionGroupRow(
    @ColumnInfo(name = "group_id") val groupId: Long,
    @ColumnInfo(name = "subscription_id") val subscriptionId: Long,
)

@Entity(
    tableName = "connection_sessions",
    indices = [
        Index(value = ["profileId"]),
        Index(value = ["startedAt"]),
    ],
)
internal data class ConnectionSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String?,
    val profileNameSnapshot: String,
    val protocolSnapshot: String,
    val startedAt: Long,
    val endedAt: Long?,
    val bytesUp: Long,
    val bytesDown: Long,
    val disconnectReason: String?,
    val networkType: String,
)

internal data class ConnectionSessionTotals(
    val count: Int,
    val durationMillis: Long,
    val bytesUp: Long,
    val bytesDown: Long,
)

@Entity(tableName = "app_routing_entries")
internal data class AppRoutingEntryEntity(
    @PrimaryKey val packageName: String,
    val selected: Boolean,
    val labelSnapshot: String,
)

@Dao
internal interface AppRoutingEntryDao {
    @Query("SELECT * FROM app_routing_entries ORDER BY labelSnapshot, packageName")
    fun getAll(): List<AppRoutingEntryEntity>

    @Query("SELECT packageName FROM app_routing_entries WHERE selected = 1 ORDER BY packageName")
    fun getSelectedPackages(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entries: List<AppRoutingEntryEntity>)

    @Query("UPDATE app_routing_entries SET selected = :selected WHERE packageName IN (:packageNames)")
    fun setSelected(packageNames: List<String>, selected: Boolean): Int

    @Query("DELETE FROM app_routing_entries WHERE packageName IN (:packageNames)")
    fun delete(packageNames: List<String>): Int
}

@Dao
internal interface ConnectionSessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(session: ConnectionSessionEntity): Long

    @Query(
        "UPDATE connection_sessions SET endedAt = :endedAt, bytesUp = :bytesUp, " +
            "bytesDown = :bytesDown, disconnectReason = :disconnectReason WHERE id = :id AND endedAt IS NULL",
    )
    fun finish(
        id: Long,
        endedAt: Long,
        bytesUp: Long,
        bytesDown: Long,
        disconnectReason: String?,
    ): Int

    @Query("SELECT * FROM connection_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun getActive(): ConnectionSessionEntity?

    @Query("SELECT * FROM connection_sessions ORDER BY startedAt DESC LIMIT :limit")
    fun getRecent(limit: Int): List<ConnectionSessionEntity>

    @Query("DELETE FROM connection_sessions WHERE endedAt IS NOT NULL")
    fun clear(): Int

    @Query(
        "SELECT COUNT(*) AS count, " +
            "COALESCE(SUM(COALESCE(endedAt, :now) - startedAt), 0) AS durationMillis, " +
            "COALESCE(SUM(bytesUp), 0) AS bytesUp, COALESCE(SUM(bytesDown), 0) AS bytesDown " +
            "FROM connection_sessions WHERE startedAt >= :since",
    )
    fun totalsSince(since: Long, now: Long): ConnectionSessionTotals
}

@Dao
internal abstract class SubscriptionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertGroup(group: ProfileGroupEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertSubscription(subscription: SubscriptionEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertProfiles(profiles: List<SubscriptionProfileEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertOlcrtcProfiles(profiles: List<OlcrtcProfileEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertStandardProfiles(profiles: List<StandardProfileEntity>)

    @Update
    protected abstract fun updateSubscription(subscription: SubscriptionEntity)

    @Update
    protected abstract fun updateProfiles(profiles: List<SubscriptionProfileEntity>)

    @Query("DELETE FROM subscription_profiles WHERE id IN (:ids)")
    protected abstract fun deleteProfiles(ids: List<String>)

    @Query("DELETE FROM profile_groups WHERE id = :groupId")
    protected abstract fun deleteGroup(groupId: Long)

    @Query("UPDATE profile_groups SET subscriptionId = :subscriptionId WHERE id = :groupId")
    protected abstract fun attachSubscription(groupId: Long, subscriptionId: Long)

    @Query("SELECT * FROM profile_groups WHERE id = :id")
    abstract fun getGroup(id: Long): ProfileGroupEntity?

    @Query("SELECT * FROM profile_groups WHERE type = 'LOCAL'")
    abstract fun getLocalGroup(): ProfileGroupEntity?

    @Query("SELECT COUNT(*) FROM profile_groups")
    abstract fun countGroups(): Int

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    abstract fun getSubscription(id: Long): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions ORDER BY name, id")
    abstract fun getSubscriptions(): List<SubscriptionEntity>

    @Query("UPDATE subscriptions SET name = :name, encryptedUrl = :encryptedUrl WHERE id = :id")
    abstract fun updateSubscriptionSource(id: Long, name: String, encryptedUrl: ByteArray): Int

    @Transaction
    open fun updateSubscriptionMetadata(subscription: SubscriptionEntity) = updateSubscription(subscription)

    @Query("SELECT * FROM subscription_profiles WHERE groupId = :groupId ORDER BY sortOrder")
    abstract fun getProfiles(groupId: Long): List<SubscriptionProfileEntity>

    @Query("SELECT * FROM subscription_profiles WHERE groupId = :groupId AND isDeleted = 0 ORDER BY sortOrder")
    abstract fun getVisibleProfiles(groupId: Long): List<SubscriptionProfileEntity>

    @Query("SELECT * FROM subscription_profiles WHERE id = :id")
    abstract fun getProfile(id: String): SubscriptionProfileEntity?

    @Query("UPDATE subscription_profiles SET lastLatencyMs = :latencyMs, lastCheckedAt = :checkedAt WHERE id = :id")
    abstract fun updateProfileLatency(id: String, latencyMs: Long?, checkedAt: Long): Int

    @Query("UPDATE subscription_profiles SET favorite = :favorite WHERE id = :id")
    abstract fun updateProfileFavorite(id: String, favorite: Boolean): Int

    @Update
    abstract fun updateProfile(profile: SubscriptionProfileEntity)

    @Query("SELECT COUNT(*) FROM subscriptions")
    abstract fun countSubscriptions(): Int

    @Query(
        "SELECT id FROM subscriptions WHERE enabled = 1 " +
            "AND (lastSuccessAt IS NULL OR lastSuccessAt + updateIntervalHours * 3600000 <= :now) " +
            "ORDER BY id",
    )
    abstract fun getStaleSubscriptionIds(now: Long): List<Long>

    @Query("SELECT COUNT(*) FROM subscription_profiles")
    abstract fun countProfiles(): Int

    @Query("SELECT g.id AS group_id, s.id AS subscription_id FROM profile_groups g JOIN subscriptions s ON s.groupId = g.id WHERE s.id = :subscriptionId")
    abstract fun getSubscriptionGroup(subscriptionId: Long): SubscriptionGroupRow?

    @Transaction
    open fun insertSubscriptionGroup(
        group: ProfileGroupEntity,
        subscription: SubscriptionEntity,
        profiles: List<SubscriptionProfileEntity>,
    ): Long {
        val groupId = insertGroup(group)
        val subscriptionId = insertSubscription(subscription.copy(groupId = groupId))
        insertProfiles(profiles.map { it.copy(groupId = groupId) })
        attachSubscription(groupId, subscriptionId)
        return subscriptionId
    }

    @Transaction
    open fun deleteSubscription(
        subscriptionId: Long,
        retainedOlcrtcProfiles: List<OlcrtcProfileEntity> = emptyList(),
        retainedStandardProfiles: List<StandardProfileEntity> = emptyList(),
    ) {
        val subscription = requireNotNull(getSubscription(subscriptionId)) { "Subscription not found" }
        if (retainedOlcrtcProfiles.isNotEmpty()) insertOlcrtcProfiles(retainedOlcrtcProfiles)
        if (retainedStandardProfiles.isNotEmpty()) insertStandardProfiles(retainedStandardProfiles)
        deleteGroup(subscription.groupId)
    }

    @Transaction
    open fun markSubscriptionRefresh(
        subscriptionId: Long,
        now: Long,
        errorCode: String?,
        etag: String? = null,
        lastModified: String? = null,
        successful: Boolean = false,
    ) {
        val subscription = requireNotNull(getSubscription(subscriptionId)) { "Subscription not found" }
        updateSubscription(
            subscription.copy(
                lastSuccessAt = if (successful) now else subscription.lastSuccessAt,
                lastAttemptAt = now,
                lastErrorCode = errorCode,
                etag = etag ?: subscription.etag,
                lastModified = lastModified ?: subscription.lastModified,
            ),
        )
    }

    @Transaction
    open fun replaceSubscriptionProfiles(
        subscription: SubscriptionEntity,
        profiles: List<SubscriptionProfileEntity>,
    ) {
        val existing = getProfiles(subscription.groupId)
        val existingByIdentity = existing.associateBy(SubscriptionProfileEntity::identityHash)
        val incomingIdentities = profiles.mapTo(mutableSetOf(), SubscriptionProfileEntity::identityHash)
        val matchedIds = mutableSetOf<String>()
        val updates = profiles.map { incoming ->
            val current = existingByIdentity[incoming.identityHash]
                ?.takeUnless { it.id in matchedIds }
                ?: existing.firstOrNull { candidate ->
                    candidate.id !in matchedIds &&
                        candidate.isLocallyModified &&
                        candidate.type == incoming.type &&
                        candidate.sortOrder == incoming.sortOrder
                }
            if (current != null) matchedIds += current.id
            if (current == null) {
                incoming.copy(groupId = subscription.groupId)
            } else {
                incoming.copy(
                    id = current.id,
                    groupId = subscription.groupId,
                    name = if (current.isLocallyModified) current.name else incoming.name,
                    encryptedConfigJson = if (current.isLocallyModified) {
                        current.encryptedConfigJson
                    } else {
                        incoming.encryptedConfigJson
                    },
                    compatibilityMode = current.compatibilityMode,
                    isLocallyModified = current.isLocallyModified,
                    isDeleted = false,
                    favorite = current.favorite,
                    lastLatencyMs = current.lastLatencyMs,
                    lastCheckedAt = current.lastCheckedAt,
                    createdAt = current.createdAt,
                )
            }
        }
        val existingIds = existing.mapTo(mutableSetOf(), SubscriptionProfileEntity::id)
        val inserts = updates.filterNot { it.id in existingIds }
        val changed = updates.filter { it.id in existingIds }
        val deleted = existing
            .filter { !it.isLocallyModified && !it.isDeleted && it.identityHash !in incomingIdentities }
            .map(SubscriptionProfileEntity::id)

        if (inserts.isNotEmpty()) insertProfiles(inserts)
        if (changed.isNotEmpty()) updateProfiles(changed)
        if (deleted.isNotEmpty()) deleteProfiles(deleted)
        updateSubscription(subscription)
    }
}

@Entity(
    tableName = "routing_rules",
    indices = [Index(value = ["matchType", "value"], unique = true)],
)
internal data class RoutingRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val matchType: String,
    val value: String,
    val action: String,
    val enabled: Boolean,
    val sortOrder: Int,
)

@Dao
internal interface RoutingRuleDao {
    @Query("SELECT * FROM routing_rules ORDER BY sortOrder, id")
    fun getAll(): List<RoutingRuleEntity>

    @Query("SELECT * FROM routing_rules WHERE enabled = 1 ORDER BY sortOrder, id")
    fun getEnabled(): List<RoutingRuleEntity>

    @Query("SELECT * FROM routing_rules WHERE matchType = :matchType AND value = :value LIMIT 1")
    fun find(matchType: String, value: String): RoutingRuleEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(rule: RoutingRuleEntity): Long

    @Update
    fun update(rule: RoutingRuleEntity): Int

    @Query("UPDATE routing_rules SET enabled = :enabled WHERE id = :id")
    fun setEnabled(id: Long, enabled: Boolean): Int

    @Query("DELETE FROM routing_rules WHERE id = :id")
    fun delete(id: Long): Int
}

internal class RoutingRuleRepository(
    private val rules: RoutingRuleDao,
) {
    fun getAll(): List<RoutingRule> = rules.getAll().map { it.toRule() }

    fun getEnabled(): List<RoutingRule> = rules.getEnabled()
        .map { it.toRule() }
        .sortedWith(
            compareByDescending<RoutingRule> { it.specificity }
                .thenBy(RoutingRule::sortOrder)
                .thenBy(RoutingRule::id),
        )

    fun save(rule: RoutingRule): Long {
        val normalized = RoutingRule.create(
            id = rule.id,
            matchType = rule.matchType,
            value = rule.value,
            action = rule.action,
            enabled = rule.enabled,
            sortOrder = rule.sortOrder,
        )
        val entity = normalized.toEntity()
        val existing = rules.find(entity.matchType, entity.value)
        return when {
            normalized.id > 0 -> {
                require(rules.update(entity) == 1) { "Routing rule not found" }
                normalized.id
            }
            existing != null -> {
                check(rules.update(entity.copy(id = existing.id)) == 1)
                existing.id
            }
            else -> rules.insert(entity)
        }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        require(rules.setEnabled(id, enabled) == 1) { "Routing rule not found" }
    }

    fun delete(id: Long) {
        require(rules.delete(id) == 1) { "Routing rule not found" }
    }

    private val RoutingRule.specificity: Int
        get() = when (matchType) {
            RoutingRule.MatchType.PACKAGE -> 2_000
            RoutingRule.MatchType.IP -> 1_000
            RoutingRule.MatchType.CIDR -> 500 + value.substringAfter('/').toInt()
            RoutingRule.MatchType.DOMAIN -> 400 + value.length
            RoutingRule.MatchType.DOMAIN_SUFFIX -> 300 + value.length
        }

    private fun RoutingRuleEntity.toRule(): RoutingRule = RoutingRule(
        id = id,
        matchType = RoutingRule.MatchType.valueOf(matchType),
        value = value,
        action = RoutingRule.Action.valueOf(action),
        enabled = enabled,
        sortOrder = sortOrder,
    )

    private fun RoutingRule.toEntity(): RoutingRuleEntity = RoutingRuleEntity(
        id = id,
        matchType = matchType.name,
        value = value,
        action = action.name,
        enabled = enabled,
        sortOrder = sortOrder,
    )
}

@Database(
    entities = [
        OlcrtcProfileEntity::class,
        OpenFluxProfileEntity::class,
        StandardProfileEntity::class,
        ProfileGroupEntity::class,
        SubscriptionEntity::class,
        SubscriptionProfileEntity::class,
        ConnectionSessionEntity::class,
        AppRoutingEntryEntity::class,
        RoutingRuleEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
internal abstract class ClientDatabase : RoomDatabase() {
    abstract fun olcrtcProfiles(): OlcrtcProfileDao
    abstract fun openfluxProfiles(): OpenFluxProfileDao
    abstract fun standardProfiles(): StandardProfileDao
    abstract fun subscriptions(): SubscriptionDao
    abstract fun connectionSessions(): ConnectionSessionDao
    abstract fun appRoutingEntries(): AppRoutingEntryDao
    abstract fun routingRules(): RoutingRuleDao

    companion object {
        internal fun inMemory(context: Context): ClientDatabase = Room.inMemoryDatabaseBuilder(
            context.applicationContext,
            ClientDatabase::class.java,
        ).addCallback(localGroupCallback).build()

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `standard_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `protocol` TEXT NOT NULL, `address` TEXT NOT NULL, `port` INTEGER NOT NULL, `secret` BLOB NOT NULL)""",
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `olcrtc_profiles` ADD COLUMN `identityHash` TEXT")
                database.execSQL("ALTER TABLE `standard_profiles` ADD COLUMN `identityHash` TEXT")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_olcrtc_profiles_identityHash` ON `olcrtc_profiles` (`identityHash`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_standard_profiles_identityHash` ON `standard_profiles` (`identityHash`)")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `profile_groups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `subscriptionId` INTEGER, `sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)""",
                )
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `subscriptions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `groupId` INTEGER NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `encryptedUrl` BLOB NOT NULL, `serverVersion` TEXT, `encryptedMirrorType` BLOB, `encryptedMirrorUrl` BLOB, `encryptedMirrorKey` BLOB, `lastSuccessAt` INTEGER, `lastAttemptAt` INTEGER, `lastErrorCode` TEXT, `updateIntervalHours` INTEGER NOT NULL, `etag` TEXT, `lastModified` TEXT, `enabled` INTEGER NOT NULL, FOREIGN KEY(`groupId`) REFERENCES `profile_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_subscriptions_groupId` ON `subscriptions` (`groupId`)")
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `subscription_profiles` (`id` TEXT NOT NULL, `groupId` INTEGER NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL, `encryptedConfigJson` BLOB NOT NULL, `encryptedUpstreamConfigJson` BLOB, `identityHash` TEXT NOT NULL, `isLocallyModified` INTEGER NOT NULL, `favorite` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `lastLatencyMs` INTEGER, `lastCheckedAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`groupId`) REFERENCES `profile_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_subscription_profiles_groupId` ON `subscription_profiles` (`groupId`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_subscription_profiles_groupId_identityHash` ON `subscription_profiles` (`groupId`, `identityHash`)")
                database.execSQL(INSERT_LOCAL_GROUP)
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `routing_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `matchType` TEXT NOT NULL, `value` TEXT NOT NULL, `action` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL)""",
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_routing_rules_matchType_value` ON `routing_rules` (`matchType`, `value`)",
                )
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `connection_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profileId` TEXT, `profileNameSnapshot` TEXT NOT NULL, `protocolSnapshot` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `bytesUp` INTEGER NOT NULL, `bytesDown` INTEGER NOT NULL, `disconnectReason` TEXT, `networkType` TEXT NOT NULL)""",
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_connection_sessions_profileId` ON `connection_sessions` (`profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_connection_sessions_startedAt` ON `connection_sessions` (`startedAt`)")
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `app_routing_entries` (`packageName` TEXT NOT NULL, `selected` INTEGER NOT NULL, `labelSnapshot` TEXT NOT NULL, PRIMARY KEY(`packageName`))""",
                )
            }
        }

        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `subscription_profiles` ADD COLUMN `isDeleted` INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `olcrtc_profiles` ADD COLUMN `compatibilityMode` TEXT NOT NULL DEFAULT 'legacy'",
                )
                database.execSQL(
                    "ALTER TABLE `subscription_profiles` ADD COLUMN `compatibilityMode` TEXT NOT NULL DEFAULT 'legacy'",
                )
            }
        }

        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `openflux_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `identityHash` TEXT, `name` TEXT NOT NULL, `documentUrl` BLOB NOT NULL, `transport` TEXT NOT NULL, `dnsServer` TEXT NOT NULL)""",
                )
                database.execSQL(
                    """CREATE INDEX IF NOT EXISTS `index_openflux_profiles_identityHash` ON `openflux_profiles` (`identityHash`)""",
                )
            }
        }

        fun open(context: Context): ClientDatabase = Room.databaseBuilder(
            context.applicationContext,
            ClientDatabase::class.java,
            "olcrtc-client.db",
        ).addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
        )
            .addCallback(localGroupCallback)
            .enableMultiInstanceInvalidation()
            .build()

        private val localGroupCallback = object : Callback() {
            override fun onCreate(database: SupportSQLiteDatabase) {
                database.execSQL(INSERT_LOCAL_GROUP)
            }
        }

        private const val INSERT_LOCAL_GROUP =
            "INSERT INTO `profile_groups` (`name`, `type`, `subscriptionId`, `sortOrder`, `createdAt`) " +
                "SELECT 'Local', 'LOCAL', NULL, 0, CAST(strftime('%s', 'now') AS INTEGER) * 1000 " +
                "WHERE NOT EXISTS (SELECT 1 FROM `profile_groups` WHERE `type` = 'LOCAL')"
    }
}
