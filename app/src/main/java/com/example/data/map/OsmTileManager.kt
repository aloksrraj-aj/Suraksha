package com.example.data.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import com.example.data.local.CachedMapTileEntity
import com.example.data.local.OfflineMapRegionEntity
import com.example.data.local.SurakshaLocalDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

data class TileCoord(val z: Int, val x: Int, val y: Int)

data class RegionDownloadProgress(
    val regionId: String,
    val completedTiles: Int,
    val totalTiles: Int,
    val downloadedBytes: Long,
    val isDownloading: Boolean,
    val errorMessage: String? = null,
)

data class IndiaPlace(
    val name: String,
    val stateOrUt: String,
    val latitude: Double,
    val longitude: Double,
    val source: String = "Offline India Gazetteer",
)

class OsmTileManager(
    context: Context,
    private val dao: SurakshaLocalDao,
    private val httpClient: OkHttpClient = defaultClient(),
    private val tileRootDir: File = File(context.applicationContext.filesDir, "osm_tiles"),
) {
    private val memoryCache = object : LruCache<String, Bitmap>(80) {}
    private val activeJobs = mutableMapOf<String, Job>()

    private val _downloadProgress = MutableStateFlow<Map<String, RegionDownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, RegionDownloadProgress>> = _downloadProgress.asStateFlow()

    private val _lastTileNetworkError = MutableStateFlow<String?>(null)
    val lastTileNetworkError: StateFlow<String?> = _lastTileNetworkError.asStateFlow()

    private val inFlightTiles = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    init {
        if (!tileRootDir.exists()) {
            tileRootDir.mkdirs()
        }
    }

    fun getMemoryCachedTile(layerKind: String, z: Int, x: Int, y: Int): Bitmap? {
        return memoryCache.get(tileKey(layerKind, z, x, y))
    }

    suspend fun loadOrFetchTile(
        layerKind: String,
        z: Int,
        x: Int,
        y: Int,
        allowNetwork: Boolean,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val key = tileKey(layerKind, z, x, y)
        memoryCache.get(key)?.let { return@withContext it }

        val file = tileFile(layerKind, z, x, y)
        if (file.exists() && file.length() > 0L) {
            val decoded = BitmapFactory.decodeFile(file.absolutePath)
            if (decoded != null) {
                memoryCache.put(key, decoded)
                return@withContext decoded
            }
        }

        if (!allowNetwork || !inFlightTiles.add(key)) {
            return@withContext null
        }

        try {
            val bytes = fetchTileBytesFromNetwork(layerKind, z, x, y)
            if (bytes != null && bytes.isNotEmpty()) {
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (decoded != null) {
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                    memoryCache.put(key, decoded)
                    dao.upsertTile(
                        CachedMapTileEntity(
                            tileKey = key,
                            regionId = "ad_hoc_viewport",
                            layerKind = layerKind,
                            zoom = z,
                            x = x,
                            y = y,
                            localPath = file.absolutePath,
                            byteSize = bytes.size.toLong(),
                            downloadedAtMillis = System.currentTimeMillis(),
                            dataVersion = DATA_VERSION,
                        )
                    )
                    _lastTileNetworkError.value = null
                    return@withContext decoded
                }
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Tile fetch failed for $key: ${e.message}")
            _lastTileNetworkError.value = e.localizedMessage ?: "OSM tile fetch failed"
            null
        } finally {
            inFlightTiles.remove(key)
        }
    }

    fun startRegionDownload(scope: CoroutineScope, region: OfflineMapRegionEntity, layerKind: String = "osm") {
        if (activeJobs[region.regionId]?.isActive == true) return
        val job = scope.launch(Dispatchers.IO) {
            val coords = enumerateTilesForRegion(region)
            val total = coords.size
            if (total == 0) return@launch

            var completed = 0
            var totalBytes = 0L
            _downloadProgress.update {
                it + (region.regionId to RegionDownloadProgress(
                    regionId = region.regionId,
                    completedTiles = 0,
                    totalTiles = total,
                    downloadedBytes = 0L,
                    isDownloading = true,
                ))
            }

            try {
                for (coord in coords) {
                    val key = tileKey(layerKind, coord.z, coord.x, coord.y)
                    val file = tileFile(layerKind, coord.z, coord.x, coord.y)
                    if (file.exists() && file.length() > 0L) {
                        val size = file.length()
                        dao.upsertTile(
                            CachedMapTileEntity(
                                tileKey = key,
                                regionId = region.regionId,
                                layerKind = layerKind,
                                zoom = coord.z,
                                x = coord.x,
                                y = coord.y,
                                localPath = file.absolutePath,
                                byteSize = size,
                                downloadedAtMillis = System.currentTimeMillis(),
                                dataVersion = DATA_VERSION,
                            )
                        )
                        completed++
                        totalBytes += size
                    } else {
                        val bytes = fetchTileBytesFromNetwork(layerKind, coord.z, coord.x, coord.y)
                        if (bytes != null && bytes.isNotEmpty()) {
                            file.parentFile?.mkdirs()
                            file.writeBytes(bytes)
                            val size = bytes.size.toLong()
                            dao.upsertTile(
                                CachedMapTileEntity(
                                    tileKey = key,
                                    regionId = region.regionId,
                                    layerKind = layerKind,
                                    zoom = coord.z,
                                    x = coord.x,
                                    y = coord.y,
                                    localPath = file.absolutePath,
                                    byteSize = size,
                                    downloadedAtMillis = System.currentTimeMillis(),
                                    dataVersion = DATA_VERSION,
                                )
                            )
                            completed++
                            totalBytes += size
                            delay(90L) // Respect OSM tile usage policy
                        } else {
                            throw IllegalStateException("Network tile download failed at z=${coord.z}, x=${coord.x}, y=${coord.y}")
                        }
                    }

                    _downloadProgress.update {
                        it + (region.regionId to RegionDownloadProgress(
                            regionId = region.regionId,
                            completedTiles = completed,
                            totalTiles = total,
                            downloadedBytes = totalBytes,
                            isDownloading = true,
                        ))
                    }
                }

                val storedCount = dao.countTilesForRegion(region.regionId)
                val storedBytes = dao.sumTileBytesForRegion(region.regionId)
                val sizeMb = storedBytes / (1024.0 * 1024.0)
                dao.updateRegionCacheStats(
                    regionId = region.regionId,
                    downloaded = storedCount > 0,
                    tilesStored = storedCount,
                    bytesStored = storedBytes,
                    sizeMb = sizeMb,
                    syncedAt = System.currentTimeMillis(),
                    dataVersion = DATA_VERSION,
                )
                _downloadProgress.update {
                    it + (region.regionId to RegionDownloadProgress(
                        regionId = region.regionId,
                        completedTiles = completed,
                        totalTiles = total,
                        downloadedBytes = storedBytes,
                        isDownloading = false,
                    ))
                }
            } catch (e: CancellationException) {
                val storedCount = dao.countTilesForRegion(region.regionId)
                val storedBytes = dao.sumTileBytesForRegion(region.regionId)
                dao.updateRegionCacheStats(
                    regionId = region.regionId,
                    downloaded = false,
                    tilesStored = storedCount,
                    bytesStored = storedBytes,
                    sizeMb = storedBytes / (1024.0 * 1024.0),
                    syncedAt = 0L,
                    dataVersion = DATA_VERSION,
                )
                _downloadProgress.update {
                    it + (region.regionId to RegionDownloadProgress(
                        regionId = region.regionId,
                        completedTiles = storedCount,
                        totalTiles = total,
                        downloadedBytes = storedBytes,
                        isDownloading = false,
                        errorMessage = "Download paused/cancelled ($storedCount/$total tiles kept for resume)",
                    ))
                }
            } catch (e: Exception) {
                val storedCount = dao.countTilesForRegion(region.regionId)
                val storedBytes = dao.sumTileBytesForRegion(region.regionId)
                dao.updateRegionCacheStats(
                    regionId = region.regionId,
                    downloaded = false,
                    tilesStored = storedCount,
                    bytesStored = storedBytes,
                    sizeMb = storedBytes / (1024.0 * 1024.0),
                    syncedAt = 0L,
                    dataVersion = DATA_VERSION,
                )
                _downloadProgress.update {
                    it + (region.regionId to RegionDownloadProgress(
                        regionId = region.regionId,
                        completedTiles = storedCount,
                        totalTiles = total,
                        downloadedBytes = storedBytes,
                        isDownloading = false,
                        errorMessage = e.localizedMessage ?: "Tile download failed",
                    ))
                }
            }
        }
        activeJobs[region.regionId] = job
    }

    fun cancelRegionDownload(regionId: String) {
        activeJobs[regionId]?.cancel()
        activeJobs.remove(regionId)
    }

    suspend fun deleteRegionCache(regionId: String) = withContext(Dispatchers.IO) {
        cancelRegionDownload(regionId)
        val tiles = dao.getTilesForRegion(regionId)
        tiles.forEach { tile ->
            runCatching { File(tile.localPath).delete() }
            memoryCache.remove(tile.tileKey)
        }
        dao.deleteTilesForRegion(regionId)
        dao.updateRegionCacheStats(
            regionId = regionId,
            downloaded = false,
            tilesStored = 0,
            bytesStored = 0L,
            sizeMb = 0.0,
            syncedAt = 0L,
            dataVersion = DATA_VERSION,
        )
        _downloadProgress.update { it - regionId }
    }

    suspend fun searchLocation(query: String, allowNetwork: Boolean): List<IndiaPlace> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        // 1. Parse raw "lat, lon" coordinates directly
        parseCoordinates(trimmed)?.let { (lat, lon) ->
            return listOf(
                IndiaPlace(
                    name = String.format(java.util.Locale.US, "Coordinates %.4f°, %.4f°", lat, lon),
                    stateOrUt = "Direct Coordinate Target",
                    latitude = lat,
                    longitude = lon,
                    source = "WGS84 Coordinate Parser",
                )
            )
        }

        // 2. Match offline India Gazetteer
        val localMatches = INDIA_GAZETTEER.filter {
            it.name.contains(trimmed, ignoreCase = true) ||
                it.stateOrUt.contains(trimmed, ignoreCase = true)
        }
        if (!allowNetwork || localMatches.size >= 3) {
            return localMatches
        }

        // 3. Query Nominatim online geocoder scoped to India
        val onlineResults = withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(trimmed, "UTF-8")
                val url = "https://nominatim.openstreetmap.org/search?format=json&limit=5&countrycodes=in&q=$encoded"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string() ?: return@withContext emptyList()
                    val array = JSONArray(body)
                    (0 until array.length()).mapNotNull { idx ->
                        val item = array.getJSONObject(idx)
                        val lat = item.optString("lat").toDoubleOrNull()
                        val lon = item.optString("lon").toDoubleOrNull()
                        val displayName = item.optString("display_name")
                        if (lat != null && lon != null && displayName.isNotBlank()) {
                            val parts = displayName.split(",").map { it.trim() }
                            IndiaPlace(
                                name = parts.firstOrNull() ?: trimmed,
                                stateOrUt = parts.drop(1).take(2).joinToString(", "),
                                latitude = lat,
                                longitude = lon,
                                source = "OSM Nominatim Geocoder",
                            )
                        } else null
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Nominatim search failed: ${e.message}")
                emptyList()
            }
        }
        return (localMatches + onlineResults).distinctBy { "${it.name}_${it.latitude.toInt()}" }
    }

    private fun fetchTileBytesFromNetwork(layerKind: String, z: Int, x: Int, y: Int): ByteArray? {
        val maxIdx = (1 shl z)
        if (z < 0 || z > 18 || x < 0 || x >= maxIdx || y < 0 || y >= maxIdx) return null
        val url = when (layerKind) {
            "topo" -> "https://tile.opentopomap.org/$z/$x/$y.png"
            else -> "https://tile.openstreetmap.org/$z/$x/$y.png"
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.bytes()
        }
    }

    private fun tileKey(layerKind: String, z: Int, x: Int, y: Int): String = "$layerKind/$z/$x/$y"

    private fun tileFile(layerKind: String, z: Int, x: Int, y: Int): File =
        File(tileRootDir, "$layerKind/$z/$x/$y.png")

    companion object {
        private const val TAG = "OsmTileManager"
        const val DATA_VERSION = "osm-raster-v1"
        const val USER_AGENT = "SurakshaRescueApp/1.0 (humanitarian-disaster-response)"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        fun enumerateTilesForRegion(region: OfflineMapRegionEntity): List<TileCoord> {
            val out = mutableListOf<TileCoord>()
            for (z in region.minZoom.coerceIn(4, 9)..region.maxZoom.coerceIn(4, 9)) {
                val topLeft = latLonToTile(region.maxLat, region.minLon, z)
                val bottomRight = latLonToTile(region.minLat, region.maxLon, z)
                val xMin = minOf(topLeft.x, bottomRight.x)
                val xMax = maxOf(topLeft.x, bottomRight.x)
                val yMin = minOf(topLeft.y, bottomRight.y)
                val yMax = maxOf(topLeft.y, bottomRight.y)
                for (x in xMin..xMax) {
                    for (y in yMin..yMax) {
                        out.add(TileCoord(z, x, y))
                    }
                }
            }
            return out
        }

        fun latLonToTile(lat: Double, lon: Double, zoom: Int): TileCoord {
            val (fx, fy) = latLonToFractionTile(lat, lon, zoom)
            val maxIdx = (1 shl zoom) - 1
            return TileCoord(
                z = zoom,
                x = floor(fx).toInt().coerceIn(0, maxIdx),
                y = floor(fy).toInt().coerceIn(0, maxIdx),
            )
        }

        fun latLonToFractionTile(lat: Double, lon: Double, zoom: Int): Pair<Double, Double> {
            val n = 2.0.pow(zoom)
            val clampedLat = lat.coerceIn(-85.0511, 85.0511)
            val latRad = Math.toRadians(clampedLat)
            val x = (lon + 180.0) / 360.0 * n
            val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
            return x to y
        }

        fun tileToLatLon(x: Double, y: Double, zoom: Int): Pair<Double, Double> {
            val n = 2.0.pow(zoom)
            val lon = x / n * 360.0 - 180.0
            val latRad = atan(sinh(PI * (1.0 - 2.0 * y / n)))
            val lat = Math.toDegrees(latRad)
            return lat to lon
        }

        fun haversineDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2.0 * r * atan2(sqrt(a), sqrt(1.0 - a))
        }

        fun parseCoordinates(input: String): Pair<Double, Double>? {
            val parts = input.split(",", " ").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size != 2) return null
            val lat = parts[0].toDoubleOrNull() ?: return null
            val lon = parts[1].toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return lat to lon
        }

        val INDIA_GAZETTEER = listOf(
            IndiaPlace("Joshimath", "Chamoli, Uttarakhand", 30.5506, 79.5660),
            IndiaPlace("Srinagar", "Jammu & Kashmir", 34.0837, 74.7973),
            IndiaPlace("Leh", "Ladakh", 34.1526, 77.5771),
            IndiaPlace("Kargil", "Ladakh", 34.5539, 76.1349),
            IndiaPlace("Jammu", "Jammu & Kashmir", 32.7266, 74.8570),
            IndiaPlace("Dehradun", "Uttarakhand", 30.3165, 78.0322),
            IndiaPlace("Shimla", "Himachal Pradesh", 31.1048, 77.1734),
            IndiaPlace("New Delhi", "Delhi NCR", 28.6139, 77.2090),
            IndiaPlace("Bhuj (Kutch)", "Gujarat", 23.2420, 69.6669),
            IndiaPlace("Ahmedabad", "Gujarat", 23.0225, 72.5714),
            IndiaPlace("Guwahati", "Assam", 26.1445, 91.7362),
            IndiaPlace("Majuli / Jorhat", "Assam", 26.9544, 94.1678),
            IndiaPlace("Itanagar", "Arunachal Pradesh", 27.0844, 93.6053),
            IndiaPlace("Tawang", "Arunachal Pradesh", 27.5860, 91.8594),
            IndiaPlace("Gangtok", "Sikkim", 27.3314, 88.6138),
            IndiaPlace("Puri / Bhubaneswar", "Odisha", 20.2961, 85.8245),
            IndiaPlace("Kolkata", "West Bengal", 22.5726, 88.3639),
            IndiaPlace("Mumbai", "Maharashtra", 19.0760, 72.8777),
            IndiaPlace("Bengaluru", "Karnataka", 12.9716, 77.5946),
            IndiaPlace("Chennai", "Tamil Nadu", 13.0827, 80.2707),
            IndiaPlace("Wayanad / Kochi", "Kerala", 11.6854, 76.1320),
            IndiaPlace("Hyderabad", "Telangana", 17.3850, 78.4867),
            IndiaPlace("Port Blair", "Andaman & Nicobar Islands", 11.6234, 92.7265),
            IndiaPlace("Kavaratti", "Lakshadweep", 10.5669, 72.6420),
        )
    }
}
