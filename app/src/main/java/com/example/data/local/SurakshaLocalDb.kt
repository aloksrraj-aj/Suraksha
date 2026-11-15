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
)

@Dao
interface SurakshaLocalDao {
    @Query("SELECT * FROM offline_map_regions ORDER BY title ASC")
    fun observeOfflineRegions(): Flow<List<OfflineMapRegionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRegions(regions: List<OfflineMapRegionEntity>)

    @Query("UPDATE offline_map_regions SET isDownloaded = :downloaded, lastSyncedMillis = :syncedAt WHERE regionId = :regionId")
    suspend fun setRegionDownloaded(regionId: String, downloaded: Boolean, syncedAt: Long)

    @Query("SELECT * FROM cached_sensor_logs ORDER BY capturedAtMillis DESC LIMIT 40")
    fun observeRecentSensorLogs(): Flow<List<CachedSensorLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSensorLog(log: CachedSensorLogEntity)
}

@Database(
    entities = [OfflineMapRegionEntity::class, CachedSensorLogEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class SurakshaLocalDatabase : RoomDatabase() {
    abstract fun localDao(): SurakshaLocalDao

    companion object {
        @Volatile
        private var instance: SurakshaLocalDatabase? = null

        fun getInstance(context: Context): SurakshaLocalDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SurakshaLocalDatabase::class.java,
                    "suraksha_offline_cache.db"
                ).build().also { instance = it }
            }
        }
    }
}

class OfflineCacheRepository(private val dao: SurakshaLocalDao) {
    val offlineRegions: Flow<List<OfflineMapRegionEntity>> = dao.observeOfflineRegions()
    val recentSensorLogs: Flow<List<CachedSensorLogEntity>> = dao.observeRecentSensorLogs()

    suspend fun ensureDefaultRegionsSeeded() {
        val now = System.currentTimeMillis()
        dao.upsertRegions(
            listOf(
                OfflineMapRegionEntity(
                    regionId = "india_north_himalaya",
                    title = "J&K, Ladakh & Uttarakhand Sector",
                    boundingBoxLabel = "30.0°N–37.1°N, 72.5°E–81.2°E",
                    tileCount = 4820,
                    sizeMb = 64.5,
                    isDownloaded = true,
                    lastSyncedMillis = now,
                    attribution = "Survey of India ref + OSM Vector Cache",
                ),
                OfflineMapRegionEntity(
                    regionId = "india_northeast_brahmaputra",
                    title = "Assam & Brahmaputra Flood Basin",
                    boundingBoxLabel = "24.2°N–28.3°N, 89.7°E–96.1°E",
                    tileCount = 3190,
                    sizeMb = 42.0,
                    isDownloaded = true,
                    lastSyncedMillis = now,
                    attribution = "OSM ODbL + Bhuvan Terrain Contour",
                ),
                OfflineMapRegionEntity(
                    regionId = "india_west_kutch",
                    title = "Kutch & Gujarat Seismic Belt",
                    boundingBoxLabel = "22.1°N–24.8°N, 68.1°E–71.9°E",
                    tileCount = 2410,
                    sizeMb = 31.8,
                    isDownloaded = false,
                    lastSyncedMillis = 0L,
                    attribution = "OSM ODbL Offline Package",
                ),
                OfflineMapRegionEntity(
                    regionId = "india_coastal_east",
                    title = "Odisha & Bay of Bengal Cyclone Coast",
                    boundingBoxLabel = "18.8°N–22.0°N, 84.3°E–87.6°E",
                    tileCount = 2890,
                    sizeMb = 38.2,
                    isDownloaded = false,
                    lastSyncedMillis = 0L,
                    attribution = "OSM ODbL Offline Package",
                ),
            )
        )
    }

    suspend fun toggleRegionDownload(regionId: String, downloaded: Boolean) {
        dao.setRegionDownloaded(
            regionId = regionId,
            downloaded = downloaded,
            syncedAt = if (downloaded) System.currentTimeMillis() else 0L
        )
    }

    suspend fun recordSensorSnapshot(
        nodeId: String,
        sensorType: String,
        summary: String,
        confidencePercent: Int,
        isSynthetic: Boolean = true,
    ) {
        dao.insertSensorLog(
            CachedSensorLogEntity(
                nodeId = nodeId,
                sensorType = sensorType,
                summary = summary,
                confidencePercent = confidencePercent,
                isSynthetic = isSynthetic,
                capturedAtMillis = System.currentTimeMillis(),
            )
        )
    }
}
