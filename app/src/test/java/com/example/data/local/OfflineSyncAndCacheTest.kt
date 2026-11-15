package com.example.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.map.OsmTileManager
import com.example.data.model.SyncStatus
import com.example.data.network.NetworkMonitor
import com.example.data.network.NetworkState
import com.example.data.sync.OfflineSyncManager
import com.example.data.sync.SyncOperationExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineSyncAndCacheTest {

    private lateinit var context: Context
    private lateinit var inMemoryDb: SurakshaLocalDatabase
    private lateinit var syncScope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        inMemoryDb = Room.inMemoryDatabaseBuilder(context, SurakshaLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        inMemoryDb.close()
        syncScope.cancel()
        context.deleteDatabase("migration_v1_v2_test.db")
    }

    @Test
    fun migration1To2_resetsFakeDownloadedFlagsAndCreatesV2Tables() = runBlocking {
        val dbName = "migration_v1_v2_test.db"
        context.deleteDatabase(dbName)
        val dbPath = context.getDatabasePath(dbName)
        dbPath.parentFile?.mkdirs()

        // Create legacy v1 SQLite database manually with pre-v2 tables and a fake downloaded region
        val rawDb = SQLiteDatabase.openOrCreateDatabase(dbPath, null)
        rawDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS offline_map_regions (
                regionId TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                boundingBoxLabel TEXT NOT NULL,
                tileCount INTEGER NOT NULL,
                sizeMb REAL NOT NULL,
                isDownloaded INTEGER NOT NULL,
                lastSyncedMillis INTEGER NOT NULL,
                attribution TEXT NOT NULL
            )
            """.trimIndent()
        )
        rawDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_sensor_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                nodeId TEXT NOT NULL,
                sensorType TEXT NOT NULL,
                summary TEXT NOT NULL,
                confidencePercent INTEGER NOT NULL,
                isSynthetic INTEGER NOT NULL,
                capturedAtMillis INTEGER NOT NULL
            )
            """.trimIndent()
        )
        rawDb.execSQL(
            """
            INSERT INTO offline_map_regions (regionId, title, boundingBoxLabel, tileCount, sizeMb, isDownloaded, lastSyncedMillis, attribution)
            VALUES ('india_north_himalaya', 'North India', '30N-36N', 100, 42.0, 1, 123456, 'OSM')
            """.trimIndent()
        )
        rawDb.version = 1
        rawDb.close()

        val migratedDb = Room.databaseBuilder(context, SurakshaLocalDatabase::class.java, dbName)
            .addMigrations(SurakshaLocalDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        try {
            val dao = migratedDb.localDao()
            val regions = dao.observeOfflineRegions().first()
            assertEquals(1, regions.size)
            assertFalse(regions.first().isDownloaded)
            assertEquals(0, regions.first().tilesStored)
            assertEquals(0L, regions.first().bytesStored)
            assertEquals(0.0, regions.first().sizeMb, 0.001)

            // Verify new v2 tables accept writes cleanly
            dao.upsertPendingSyncOperation(
                PendingSyncOperationEntity(
                    operationId = "op_mig_1",
                    orgId = "org_1",
                    entityType = "sos_create",
                    payloadJson = "{}",
                    syncStatus = SyncStatus.PENDING_SYNC.value,
                    retryCount = 0,
                    lastError = "",
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                )
            )
            assertEquals(1, dao.getRetryableOperations().size)
        } finally {
            migratedDb.close()
        }
    }

    @Test
    fun offlineSyncManager_queuesWhenOffline_tracksFailureAndFlushesOnReconnect() = runBlocking {
        val dao = inMemoryDb.localDao()
        // Verify capability evaluation logic directly
        val unvalidatedCaps = ShadowNetworkCapabilities.newInstance().also {
            Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        assertEquals(NetworkState.OFFLINE, NetworkMonitor.evaluateCapabilities(unvalidatedCaps))

        val wifiCaps = ShadowNetworkCapabilities.newInstance().also {
            Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        assertEquals(NetworkState.ONLINE_UNMETERED, NetworkMonitor.evaluateCapabilities(wifiCaps))

        var simulatedNetworkState = NetworkState.OFFLINE
        val networkMonitor = NetworkMonitor(context) { simulatedNetworkState }
        val syncManager = OfflineSyncManager(dao, networkMonitor, syncScope)

        var shouldFailNext = true
        val executedIds = mutableListOf<String>()
        syncManager.attachExecutor(object : SyncOperationExecutor {
            override suspend fun executeOperation(op: PendingSyncOperationEntity): Result<Unit> {
                executedIds.add(op.operationId)
                return if (shouldFailNext) {
                    Result.failure(IllegalStateException("Temporary Firestore 503"))
                } else {
                    Result.success(Unit)
                }
            }
        })

        // Enqueue with idempotent ID while offline
        val opId = syncManager.enqueuePendingOperation(
            orgId = "org_test",
            entityType = "sos_create",
            payload = JSONObject().put("description", "Bridge collapse"),
            operationId = "op_fixed_123",
        )
        assertEquals("op_fixed_123", opId)

        // Still offline -> flush does not dispatch
        val offlineFlush = syncManager.flushPendingQueue()
        assertEquals(0 to 0, offlineFlush)
        assertEquals(1, dao.observePendingSyncOperations().first().size)

        // Simulate online validated unmetered network
        simulatedNetworkState = NetworkState.ONLINE_UNMETERED

        // First flush fails -> retryCount increments and status becomes sync_failed
        val failedFlush = syncManager.flushPendingQueue()
        assertEquals(0 to 1, failedFlush)
        val failedRow = dao.getRetryableOperations().first()
        assertEquals(1, failedRow.retryCount)
        assertEquals(SyncStatus.FAILED.value, failedRow.syncStatus)

        // Second flush succeeds -> row removed from pending queue
        shouldFailNext = false
        val okFlush = syncManager.flushPendingQueue()
        assertEquals(1 to 0, okFlush)
        assertTrue(dao.observePendingSyncOperations().first().isEmpty())
        assertEquals(2, executedIds.size)
        networkMonitor.unregister()
    }

    @Test
    fun osmTileManager_slippyMathHaversineCoordinatesAndRealCacheDelete_verified() = runBlocking {
        val dao = inMemoryDb.localDao()
        val cacheRepo = OfflineCacheRepository(dao)
        cacheRepo.ensureDefaultRegionsSeeded()

        val tile = OsmTileManager.latLonToTile(28.6139, 77.2090, 6)
        assertEquals(6, tile.z)
        assertEquals(45, tile.x)
        assertEquals(26, tile.y)

        val (lat, lon) = OsmTileManager.tileToLatLon(tile.x + 0.5, tile.y + 0.5, 6)
        assertTrue(lat in 24.0..33.0)
        assertTrue(lon in 73.0..82.0)

        val distKm = OsmTileManager.haversineDistanceKm(28.6139, 77.2090, 34.1526, 77.5771)
        assertTrue("Expected Delhi-Leh distance ~615km, got $distKm", distKm in 580.0..660.0)

        val parsed = OsmTileManager.parseCoordinates("34.1526, 77.5771")
        assertNotNull(parsed)
        assertEquals(34.1526, parsed!!.first, 0.0001)
        assertNull(OsmTileManager.parseCoordinates("95.5, 77.0"))
        assertNull(OsmTileManager.parseCoordinates("Leh Ladakh"))

        val tileDir = File(context.cacheDir, "test_osm_tiles").apply { mkdirs() }
        val dummyTileFile = File(tileDir, "6_45_26.png").apply { writeBytes(ByteArray(1024) { 1 }) }
        val manager = OsmTileManager(context = context, dao = dao, tileRootDir = tileDir)

        dao.upsertTile(
            CachedMapTileEntity(
                tileKey = "osm/6/45/26",
                regionId = "india_north_himalaya",
                layerKind = "osm",
                zoom = 6,
                x = 45,
                y = 26,
                localPath = dummyTileFile.absolutePath,
                byteSize = 1024L,
                downloadedAtMillis = System.currentTimeMillis(),
                dataVersion = OsmTileManager.DATA_VERSION,
            )
        )
        assertEquals(1024L, dao.sumTileBytesForRegion("india_north_himalaya"))

        manager.deleteRegionCache("india_north_himalaya")
        assertFalse(dummyTileFile.exists())
        assertEquals(0, dao.countTilesForRegion("india_north_himalaya"))
        assertEquals(0L, dao.sumTileBytesForRegion("india_north_himalaya"))
        val regionAfterDelete = dao.getRegionById("india_north_himalaya")
        assertNotNull(regionAfterDelete)
        assertFalse(regionAfterDelete!!.isDownloaded)
    }
}
