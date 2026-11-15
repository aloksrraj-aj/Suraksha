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
    val dataVersion: String = "osm-raster-v1",
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
)

@Entity(tableName = "slam_sessions")
data class SlamSessionEntity(
    @PrimaryKey val sessionId: String,
    val orgId: String,
    val scenario: String,
    val sweepProgress: Float,
    val gridSize: Int,
    val cellSizeMeters: Float,
    val obstacleCount: Int,
    val voidCount: Int,
    val thermalCount: Int,
    val sourceKind: String,
    val uncertaintyNote: String,
    val exportedJson: String,
    val createdAtMillis: Long,
)

@Entity(tableName = "cached_sensor_logs")
data class CachedSensorLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val nodeId: String,
    val sensorType: String,
    val summary: String,
    val confidencePercent: Int,
    val isSynthetic: Boolean,
    val capturedAtMillis: Long,
    val sourceKind: String = "simulated",
    val calibrationStatus: String = "nominal",
    val sensorHealth: String = "healthy",
)

@Dao
interface SurakshaLocalDao {
    @Query("SELECT * FROM offline_map_regions ORDER BY title ASC")
    fun observeOfflineRegions(): Flow<List<OfflineMapRegionEntity>>

    @Query("SELECT * FROM offline_map_regions WHERE regionId = :regionId LIMIT 1")
    suspend fun getRegionById(regionId: String): OfflineMapRegionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRegionsIfMissing(regions: List<OfflineMapRegionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRegion(region: OfflineMapRegionEntity)

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

    @Query("SELECT COUNT(*) FROM cached_map_tiles WHERE regionId = :regionId")
    suspend fun countTilesForRegion(regionId: String): Int

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM cached_map_tiles WHERE regionId = :regionId")
    suspend fun sumTileBytesForRegion(regionId: String): Long

    @Query("DELETE FROM cached_map_tiles WHERE regionId = :regionId")
    suspend fun deleteTilesForRegion(regionId: String)

    @Query("SELECT COUNT(*) FROM cached_map_tiles")
    fun observeTotalCachedTileCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM cached_map_tiles")
    fun observeTotalCachedBytes(): Flow<Long>

    @Query("SELECT * FROM pending_sync_operations ORDER BY createdAtMillis ASC")
    fun observePendingSyncOperations(): Flow<List<PendingSyncOperationEntity>>

    @Query("SELECT * FROM pending_sync_operations WHERE syncStatus IN ('pending_sync', 'sync_failed') ORDER BY createdAtMillis ASC")
    suspend fun getRetryableOperations(): List<PendingSyncOperationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingSyncOperation(op: PendingSyncOperationEntity)

    @Query("DELETE FROM pending_sync_operations WHERE operationId = :operationId")
    suspend fun deletePendingSyncOperation(operationId: String)

    @Query("SELECT * FROM slam_sessions ORDER BY createdAtMillis DESC LIMIT 30")
    fun observeSlamSessions(): Flow<List<SlamSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSlamSession(session: SlamSessionEntity)

    @Query("SELECT * FROM cached_sensor_logs ORDER BY capturedAtMillis DESC LIMIT 60")
    fun observeRecentSensorLogs(): Flow<List<CachedSensorLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSensorLog(log: CachedSensorLogEntity)
}

@Database(
    entities = [
        OfflineMapRegionEntity::class,
        CachedMapTileEntity::class,
        PendingSyncOperationEntity::class,
        SlamSessionEntity::class,
        CachedSensorLogEntity::class,
    ],
    version = 2,
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
                // Reset previously seeded fake downloaded flags so downloaded status only reflects actual local tiles
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

        @Volatile
        private var instance: SurakshaLocalDatabase? = null

        fun getInstance(context: Context): SurakshaLocalDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SurakshaLocalDatabase::class.java,
                    "suraksha_offline_cache.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}

class OfflineCacheRepository(private val dao: SurakshaLocalDao) {
    val offlineRegions: Flow<List<OfflineMapRegionEntity>> = dao.observeOfflineRegions()
    val recentSensorLogs: Flow<List<CachedSensorLogEntity>> = dao.observeRecentSensorLogs()
    val pendingSyncOperations: Flow<List<PendingSyncOperationEntity>> = dao.observePendingSyncOperations()
    val slamSessions: Flow<List<SlamSessionEntity>> = dao.observeSlamSessions()
    val totalCachedTileCount: Flow<Int> = dao.observeTotalCachedTileCount()
    val totalCachedBytes: Flow<Long> = dao.observeTotalCachedBytes()

    suspend fun ensureDefaultRegionsSeeded() {
        // Seed catalog metadata ONLY with isDownloaded = false and 0 bytes stored;
        // real downloaded state is set strictly by OsmTileManager after real disk caching.
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
                    attribution = "OpenStreetMap Contributors (ODbL) + Survey of India Boundary Ref",
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
                    attribution = "OpenStreetMap Contributors (ODbL)",
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
                    attribution = "OpenStreetMap Contributors (ODbL)",
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
                    attribution = "OpenStreetMap Contributors (ODbL)",
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

    suspend fun recordSensorSnapshot(
        nodeId: String,
        sensorType: String,
        summary: String,
        confidencePercent: Int,
        isSynthetic: Boolean = true,
        sourceKind: String = if (isSynthetic) "simulated" else "hardware",
        calibrationStatus: String = "nominal",
        sensorHealth: String = "healthy",
    ) {
        dao.insertSensorLog(
            CachedSensorLogEntity(
                nodeId = nodeId,
                sensorType = sensorType,
                summary = summary,
                confidencePercent = confidencePercent,
                isSynthetic = isSynthetic,
                capturedAtMillis = System.currentTimeMillis(),
                sourceKind = sourceKind,
                calibrationStatus = calibrationStatus,
                sensorHealth = sensorHealth,
            )
        )
    }

    suspend fun saveSlamSession(session: SlamSessionEntity) {
        dao.insertSlamSession(session)
    }
}
