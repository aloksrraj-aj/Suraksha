package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "offline_map_regions")
data class OfflineMapRegionEntity(
    @PrimaryKey val regionId: String,
    val title: String,
    val boundingBoxLabel: String,
    val tileCount: Int,
    val sizeMb: Double,
    val isDownloaded: Boolean,
    val lastSyncedMillis: Long,
    val attribution: String,
    val minLat: Double = 28.0,
    val maxLat: Double = 36.0,
    val minLon: Double = 73.0,
    val maxLon: Double = 81.0,
    val minZoom: Int = 6,
    val maxZoom: Int = 8,
    val tilesStored: Int = 0,
    val bytesStored: Long = 0L,
    val dataVersion: String = "topo-raster-v2",
)

@Entity(tableName = "cached_map_tiles")
data class CachedMapTileEntity(
    @PrimaryKey val tileKey: String,
    val regionId: String,
    val layerKind: String,
    val zoom: Int,
    val x: Int,
    val y: Int,
    val localPath: String,
    val byteSize: Long,
    val downloadedAtMillis: Long,
    val dataVersion: String,
)

@Entity(tableName = "pending_sync_operations")
data class PendingSyncOperationEntity(
    @PrimaryKey val operationId: String,
    val orgId: String,
    val entityType: String,
    val payloadJson: String,
    val syncStatus: String,
    val retryCount: Int,
    val lastError: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val idempotencyKey: String = "",
    val nextRetryAtMillis: Long = 0L,
    val conflictNote: String = "",
)

@Entity(tableName = "sync_history")
data class SyncHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val operationId: String,
    val orgId: String,
    val entityType: String,
    val outcome: String,
    val detail: String,
    val syncedAtMillis: Long,
)

@Entity(tableName = "joined_organizations")
data class JoinedOrgEntity(
    @PrimaryKey val orgId: String,
    val orgName: String,
    val orgCode: String,
    val role: String,
    val lastAccessedMillis: Long,
)

@Entity(tableName = "cached_member_locations")
data class CachedMemberLocationEntity(
    @PrimaryKey val cacheKey: String, // "$missionId:$userId"
    val orgId: String,
    val missionId: String,
    val userId: String,
    val displayName: String,
    val callsign: String,
    val role: String,
    val sharingEnabled: Boolean,
    val hasValidLocation: Boolean,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val headingDegrees: Double,
    val headingReliable: Boolean,
    val batteryPercent: Long,
    val connectionStatus: String,
    val locationTimestampMillis: Long,
    val unavailableReason: String,
    val cachedAtMillis: Long,
)

@Entity(tableName = "cached_sensor_health")
data class CachedSensorHealthEntity(
    @PrimaryKey val sensorId: String,
    val orgId: String,
    val missionId: String,
    val sensorType: String,
    val displayName: String,
    val connectionStatus: String,
    val healthState: String,
    val lastHeartbeatMillis: Long,
    val packetsReceived: Long,
    val packetsRejected: Long,
    val diagnosticNote: String,
    val cachedAtMillis: Long,
)

@Entity(tableName = "cached_sensor_events")
data class CachedSensorEventEntity(
    @PrimaryKey val eventId: String,
    val orgId: String,
    val missionId: String,
    val sensorId: String,
    val sensorType: String,
    val timestampUtcMillis: Long,
    val detectionType: String,
    val estimatedRangeMeters: Double?,
    val estimatedAzimuthDegrees: Double?,
    val estimatedLatitude: Double?,
    val estimatedLongitude: Double?,
    val confidence: Double,
    val signalQuality: String,
    val sourceDevice: String,
    val dataValidity: String,
    val status: String,
    val cachedAtMillis: Long,
)

@Dao
interface SurakshaLocalDao {
    @Query("SELECT * FROM offline_map_regions ORDER BY title ASC")
    fun observeOfflineRegions(): Flow<List<OfflineMapRegionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRegionsIfMissing(regions: List<OfflineMapRegionEntity>)

    @Query("UPDATE offline_map_regions SET isDownloaded = :downloaded, tilesStored = :tilesStored, bytesStored = :bytesStored, sizeMb = :sizeMb, lastSyncedMillis = :syncedAt, dataVersion = :dataVersion WHERE regionId = :regionId")
    suspend fun updateRegionCacheStats(
        regionId: String,
        downloaded: Boolean,
        tilesStored: Int,
        bytesStored: Long,
        sizeMb: Double,
        syncedAt: Long,
        dataVersion: String,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTile(tile: CachedMapTileEntity)

    @Query("SELECT * FROM cached_map_tiles WHERE tileKey = :tileKey LIMIT 1")
    suspend fun getTile(tileKey: String): CachedMapTileEntity?

    @Query("SELECT * FROM cached_map_tiles WHERE regionId = :regionId")
    suspend fun getTilesForRegion(regionId: String): List<CachedMapTileEntity>

    @Query("SELECT * FROM cached_map_tiles WHERE regionId = 'ad_hoc_viewport' ORDER BY downloadedAtMillis ASC LIMIT :limit")
    suspend fun getOldestViewportTiles(limit: Int): List<CachedMapTileEntity>

    @Query("DELETE FROM cached_map_tiles WHERE tileKey = :tileKey")
    suspend fun deleteTileByKey(tileKey: String)

    @Query("SELECT COUNT(*) FROM cached_map_tiles WHERE regionId = :regionId")
    suspend fun countTilesForRegion(regionId: String): Int

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM cached_map_tiles WHERE regionId = :regionId")
    suspend fun sumTileBytesForRegion(regionId: String): Long

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM cached_map_tiles")
    suspend fun getTotalCachedBytesSync(): Long

    @Query("DELETE FROM cached_map_tiles WHERE regionId = :regionId")
    suspend fun deleteTilesForRegion(regionId: String)

    @Query("SELECT COUNT(*) FROM cached_map_tiles")
    fun observeTotalCachedTileCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM cached_map_tiles")
    fun observeTotalCachedBytes(): Flow<Long>

    @Query("SELECT * FROM pending_sync_operations ORDER BY createdAtMillis ASC")
    fun observePendingSyncOperations(): Flow<List<PendingSyncOperationEntity>>

    @Query("SELECT * FROM pending_sync_operations WHERE syncStatus IN ('pending_sync', 'sync_failed') AND retryCount < 5 AND nextRetryAtMillis <= :nowMillis ORDER BY createdAtMillis ASC")
    suspend fun getRetryableOperations(nowMillis: Long = System.currentTimeMillis()): List<PendingSyncOperationEntity>

    @Query("SELECT * FROM pending_sync_operations WHERE operationId = :operationId LIMIT 1")
    suspend fun getPendingOperationById(operationId: String): PendingSyncOperationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingSyncOperation(op: PendingSyncOperationEntity)

    @Query("DELETE FROM pending_sync_operations WHERE operationId = :operationId")
    suspend fun deletePendingSyncOperation(operationId: String)

    @Query("SELECT * FROM sync_history ORDER BY syncedAtMillis DESC LIMIT 50")
    fun observeSyncHistory(): Flow<List<SyncHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncHistory(entry: SyncHistoryEntity)

    @Query("SELECT * FROM joined_organizations ORDER BY lastAccessedMillis DESC")
    fun observeJoinedOrganizations(): Flow<List<JoinedOrgEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJoinedOrganization(org: JoinedOrgEntity)

    @Query("SELECT * FROM cached_member_locations WHERE missionId = :missionId ORDER BY displayName ASC")
    fun observeCachedMemberLocations(missionId: String): Flow<List<CachedMemberLocationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedMemberLocations(locations: List<CachedMemberLocationEntity>)

    @Query("DELETE FROM cached_member_locations WHERE missionId = :missionId")
    suspend fun clearCachedMemberLocations(missionId: String)

    @Query("SELECT * FROM cached_sensor_health WHERE missionId = :missionId ORDER BY displayName ASC")
    fun observeCachedSensorHealth(missionId: String): Flow<List<CachedSensorHealthEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedSensorHealth(entries: List<CachedSensorHealthEntity>)

    @Query("SELECT * FROM cached_sensor_events WHERE missionId = :missionId ORDER BY timestampUtcMillis DESC LIMIT 100")
    fun observeCachedSensorEvents(missionId: String): Flow<List<CachedSensorEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedSensorEvents(events: List<CachedSensorEventEntity>)
}

@Database(
    entities = [
        OfflineMapRegionEntity::class,
        CachedMapTileEntity::class,
        PendingSyncOperationEntity::class,
        SyncHistoryEntity::class,
        JoinedOrgEntity::class,
        CachedMemberLocationEntity::class,
        CachedSensorHealthEntity::class,
        CachedSensorEventEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class SurakshaLocalDatabase : RoomDatabase() {
    abstract fun localDao(): SurakshaLocalDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE offline_map_regions ADD COLUMN minLat REAL NOT NULL DEFAULT 28.0")
                db.execSQL("ALTER TABLE offline_map_regions ADD COLUMN maxLat REAL NOT NULL DEFAULT 36.0")
                db.execSQL("ALTER TABLE offline_map_regions ADD COLUMN minLon REAL NOT NULL DEFAULT 73.0")
                db.execSQL("ALTER TABLE offline_map_regions ADD COLUMN maxLon REAL NOT NULL DEFAULT 81.0")
                db.execSQL("ALTER TABLE offline_map_regions ADD COLUMN minZoom INTEGER NOT NULL DEFAULT 6")
                db.execSQL("ALTER TABLE offline_map_regions ADD COLUMN maxZoom INTEGER NOT NULL DEFAULT 8")
                db.execSQL("ALTER TABLE offline_map_regions ADD COLUMN tilesStored INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE offline_map_regions ADD COLUMN bytesStored INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE offline_map_regions ADD COLUMN dataVersion TEXT NOT NULL DEFAULT 'osm-raster-v1'")
                db.execSQL("UPDATE offline_map_regions SET isDownloaded = 0, tilesStored = 0, bytesStored = 0, sizeMb = 0.0")

                db.execSQL("ALTER TABLE cached_sensor_logs ADD COLUMN sourceKind TEXT NOT NULL DEFAULT 'simulated'")
                db.execSQL("ALTER TABLE cached_sensor_logs ADD COLUMN calibrationStatus TEXT NOT NULL DEFAULT 'nominal'")
                db.execSQL("ALTER TABLE cached_sensor_logs ADD COLUMN sensorHealth TEXT NOT NULL DEFAULT 'healthy'")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cached_map_tiles (
                        tileKey TEXT NOT NULL PRIMARY KEY,
                        regionId TEXT NOT NULL,
                        layerKind TEXT NOT NULL,
                        zoom INTEGER NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        localPath TEXT NOT NULL,
                        byteSize INTEGER NOT NULL,
                        downloadedAtMillis INTEGER NOT NULL,
                        dataVersion TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_sync_operations (
                        operationId TEXT NOT NULL PRIMARY KEY,
                        orgId TEXT NOT NULL,
                        entityType TEXT NOT NULL,
                        payloadJson TEXT NOT NULL,
                        syncStatus TEXT NOT NULL,
                        retryCount INTEGER NOT NULL,
                        lastError TEXT NOT NULL,
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS slam_sessions (
                        sessionId TEXT NOT NULL PRIMARY KEY,
                        orgId TEXT NOT NULL,
                        scenario TEXT NOT NULL,
                        sweepProgress REAL NOT NULL,
                        gridSize INTEGER NOT NULL,
                        cellSizeMeters REAL NOT NULL,
                        obstacleCount INTEGER NOT NULL,
                        voidCount INTEGER NOT NULL,
                        thermalCount INTEGER NOT NULL,
                        sourceKind TEXT NOT NULL,
                        uncertaintyNote TEXT NOT NULL,
                        exportedJson TEXT NOT NULL,
                        createdAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_sync_operations ADD COLUMN idempotencyKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pending_sync_operations ADD COLUMN nextRetryAtMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pending_sync_operations ADD COLUMN conflictNote TEXT NOT NULL DEFAULT ''")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        operationId TEXT NOT NULL,
                        orgId TEXT NOT NULL,
                        entityType TEXT NOT NULL,
                        outcome TEXT NOT NULL,
                        detail TEXT NOT NULL,
                        syncedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS joined_organizations (
                        orgId TEXT NOT NULL PRIMARY KEY,
                        orgName TEXT NOT NULL,
                        orgCode TEXT NOT NULL,
                        role TEXT NOT NULL,
                        lastAccessedMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS slam_sessions")
                db.execSQL("DROP TABLE IF EXISTS cached_sensor_logs")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cached_member_locations (
                        cacheKey TEXT NOT NULL PRIMARY KEY,
                        orgId TEXT NOT NULL,
                        missionId TEXT NOT NULL,
                        userId TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        callsign TEXT NOT NULL,
                        role TEXT NOT NULL,
                        sharingEnabled INTEGER NOT NULL,
                        hasValidLocation INTEGER NOT NULL,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        accuracyMeters REAL NOT NULL,
                        headingDegrees REAL NOT NULL,
                        headingReliable INTEGER NOT NULL,
                        batteryPercent INTEGER NOT NULL,
                        connectionStatus TEXT NOT NULL,
                        locationTimestampMillis INTEGER NOT NULL,
                        unavailableReason TEXT NOT NULL,
                        cachedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cached_sensor_health (
                        sensorId TEXT NOT NULL PRIMARY KEY,
                        orgId TEXT NOT NULL,
                        missionId TEXT NOT NULL,
                        sensorType TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        connectionStatus TEXT NOT NULL,
                        healthState TEXT NOT NULL,
                        lastHeartbeatMillis INTEGER NOT NULL,
                        packetsReceived INTEGER NOT NULL,
                        packetsRejected INTEGER NOT NULL,
                        diagnosticNote TEXT NOT NULL,
                        cachedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cached_sensor_events (
                        eventId TEXT NOT NULL PRIMARY KEY,
                        orgId TEXT NOT NULL,
                        missionId TEXT NOT NULL,
                        sensorId TEXT NOT NULL,
                        sensorType TEXT NOT NULL,
                        timestampUtcMillis INTEGER NOT NULL,
                        detectionType TEXT NOT NULL,
                        estimatedRangeMeters REAL,
                        estimatedAzimuthDegrees REAL,
                        estimatedLatitude REAL,
                        estimatedLongitude REAL,
                        confidence REAL NOT NULL,
                        signalQuality TEXT NOT NULL,
                        sourceDevice TEXT NOT NULL,
                        dataValidity TEXT NOT NULL,
                        status TEXT NOT NULL,
                        cachedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var instance: SurakshaLocalDatabase? = null

        fun getInstance(context: Context): SurakshaLocalDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SurakshaLocalDatabase::class.java,
                    "suraksha_offline_cache.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
        }
    }
}

class OfflineCacheRepository(private val dao: SurakshaLocalDao) {
    val offlineRegions: Flow<List<OfflineMapRegionEntity>> = dao.observeOfflineRegions()
    val pendingSyncOperations: Flow<List<PendingSyncOperationEntity>> = dao.observePendingSyncOperations()
    val syncHistory: Flow<List<SyncHistoryEntity>> = dao.observeSyncHistory()
    val joinedOrganizations: Flow<List<JoinedOrgEntity>> = dao.observeJoinedOrganizations()
    val totalCachedTileCount: Flow<Int> = dao.observeTotalCachedTileCount()
    val totalCachedBytes: Flow<Long> = dao.observeTotalCachedBytes()

    fun observeCachedMemberLocations(missionId: String): Flow<List<CachedMemberLocationEntity>> =
        dao.observeCachedMemberLocations(missionId)

    fun observeCachedSensorHealth(missionId: String): Flow<List<CachedSensorHealthEntity>> =
        dao.observeCachedSensorHealth(missionId)

    fun observeCachedSensorEvents(missionId: String): Flow<List<CachedSensorEventEntity>> =
        dao.observeCachedSensorEvents(missionId)

    suspend fun cacheMissionLocations(missionId: String, locations: List<CachedMemberLocationEntity>) {
        if (missionId.isBlank()) return
        dao.clearCachedMemberLocations(missionId)
        if (locations.isNotEmpty()) {
            dao.upsertCachedMemberLocations(locations)
        }
    }

    suspend fun cacheSensorHealth(entries: List<CachedSensorHealthEntity>) {
        if (entries.isNotEmpty()) dao.upsertCachedSensorHealth(entries)
    }

    suspend fun cacheSensorEvents(events: List<CachedSensorEventEntity>) {
        if (events.isNotEmpty()) dao.upsertCachedSensorEvents(events)
    }

    suspend fun ensureDefaultRegionsSeeded() {
        dao.insertRegionsIfMissing(
            listOf(
                OfflineMapRegionEntity(
                    regionId = "india_north_himalaya",
                    title = "J&K, Ladakh & Uttarakhand Sector",
                    boundingBoxLabel = "30.0°N–36.2°N, 73.5°E–79.9°E (z6–z8)",
                    tileCount = 0,
                    sizeMb = 0.0,
                    isDownloaded = false,
                    lastSyncedMillis = 0L,
                    attribution = "OpenTopoMap (CC-BY-SA) + OpenStreetMap (ODbL) + Survey of India Boundary Ref",
                    minLat = 30.0,
                    maxLat = 36.2,
                    minLon = 73.5,
                    maxLon = 79.9,
                    minZoom = 6,
                    maxZoom = 8,
                ),
                OfflineMapRegionEntity(
                    regionId = "india_northeast_brahmaputra",
                    title = "Assam & Brahmaputra Flood Basin",
                    boundingBoxLabel = "25.0°N–28.3°N, 90.0°E–95.8°E (z6–z8)",
                    tileCount = 0,
                    sizeMb = 0.0,
                    isDownloaded = false,
                    lastSyncedMillis = 0L,
                    attribution = "OpenTopoMap (CC-BY-SA) + OpenStreetMap (ODbL)",
                    minLat = 25.0,
                    maxLat = 28.3,
                    minLon = 90.0,
                    maxLon = 95.8,
                    minZoom = 6,
                    maxZoom = 8,
                ),
                OfflineMapRegionEntity(
                    regionId = "india_west_kutch",
                    title = "Kutch & Gujarat Seismic Belt",
                    boundingBoxLabel = "22.2°N–24.5°N, 68.5°E–71.8°E (z6–z8)",
                    tileCount = 0,
                    sizeMb = 0.0,
                    isDownloaded = false,
                    lastSyncedMillis = 0L,
                    attribution = "OpenTopoMap (CC-BY-SA) + OpenStreetMap (ODbL)",
                    minLat = 22.2,
                    maxLat = 24.5,
                    minLon = 68.5,
                    maxLon = 71.8,
                    minZoom = 6,
                    maxZoom = 8,
                ),
                OfflineMapRegionEntity(
                    regionId = "india_coastal_east",
                    title = "Odisha & Bay of Bengal Cyclone Coast",
                    boundingBoxLabel = "19.0°N–21.8°N, 84.5°E–87.5°E (z6–z8)",
                    tileCount = 0,
                    sizeMb = 0.0,
                    isDownloaded = false,
                    lastSyncedMillis = 0L,
                    attribution = "OpenTopoMap (CC-BY-SA) + OpenStreetMap (ODbL)",
                    minLat = 19.0,
                    maxLat = 21.8,
                    minLon = 84.5,
                    maxLon = 87.5,
                    minZoom = 6,
                    maxZoom = 8,
                ),
            )
        )
    }

    suspend fun recordJoinedOrganization(orgId: String, orgName: String, orgCode: String, role: String) {
        if (orgId.isBlank()) return
        dao.upsertJoinedOrganization(
            JoinedOrgEntity(
                orgId = orgId,
                orgName = orgName.ifBlank { orgId },
                orgCode = orgCode.ifBlank { "SUR" },
                role = role,
                lastAccessedMillis = System.currentTimeMillis(),
            )
        )
    }
}
