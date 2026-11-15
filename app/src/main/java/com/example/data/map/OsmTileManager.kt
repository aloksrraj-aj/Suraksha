package com.example.data.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import com.example.data.local.CachedMapTileEntity
import com.example.data.local.OfflineMapRegionEntity
import com.example.data.local.SurakshaLocalDao
import com.example.data.model.BlockedRoadMarker
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
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Locale
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

data class RescueRoute(
    val points: List<Pair<Double, Double>>,
    val distanceKm: Double,
    val durationMinutes: Double,
    val isRoadRouted: Boolean,
    val sourceLabel: String,
    val nearBlockedWarnings: List<String> = emptyList(),
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
        customTileTemplateUrl: String = "",
    ): Bitmap? = withContext(Dispatchers.IO) {
        val key = tileKey(layerKind, z, x, y)
        memoryCache.get(key)?.let { return@withContext it }

        val file = tileFile(layerKind, z, x, y)
        if (file.exists() && file.length() > 0L) {
            val decoded = BitmapFactory.decodeFile(file.absolutePath)
            if (decoded != null) {
                memoryCache.put(key, decoded)
                return@withContext decoded
            } else {
                // Corrupt local cache file; prune so it can re-fetch
                runCatching { file.delete() }
            }
        }

        if (!allowNetwork || !inFlightTiles.add(key)) {
            return@withContext null
        }

        try {
            val fetchResult = fetchTileBytesFromNetwork(
                layerKind = layerKind,
                z = z,
                x = x,
                y = y,
                customTileTemplateUrl = customTileTemplateUrl,
                isBulkRegionPrefetch = false,
            )
            val bytes = fetchResult.bytes
            if (bytes != null && isValidImageBytes(bytes)) {
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (decoded != null) {
                    evictLruCacheIfNeeded()
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
            } else if (fetchResult.error != null) {
                _lastTileNetworkError.value = fetchResult.error
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Tile fetch failed for $key: ${e.message}")
            _lastTileNetworkError.value = e.localizedMessage ?: "Map tile fetch failed"
            null
        } finally {
            inFlightTiles.remove(key)
        }
    }

    /**
     * Policy-compliant offline region caching:
     * - Strictly blocks bulk region downloads from public tile.openstreetmap.org (OSM Tile Usage Policy).
     * - Uses policy-compliant terrain/topo or user-configured custom tile mirror, capped at MAX_REGION_TILES,
     *   honoring HTTP 429 Retry-After headers and verifying image magic bytes.
     */
    fun startRegionDownload(
        scope: CoroutineScope,
        region: OfflineMapRegionEntity,
        layerKind: String = "topo",
        customTileTemplateUrl: String = "",
    ) {
        if (activeJobs[region.regionId]?.isActive == true) return
        val effectiveLayer = if (layerKind == "osm" && customTileTemplateUrl.isBlank()) "topo" else layerKind

        val job = scope.launch(Dispatchers.IO) {
            val coords = enumerateTilesForRegion(region).take(MAX_REGION_TILES)
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
                    val key = tileKey(effectiveLayer, coord.z, coord.x, coord.y)
                    val file = tileFile(effectiveLayer, coord.z, coord.x, coord.y)
                    if (file.exists() && file.length() > 0L) {
                        val size = file.length()
                        dao.upsertTile(
                            CachedMapTileEntity(
                                tileKey = key,
                                regionId = region.regionId,
                                layerKind = effectiveLayer,
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
                        val res = fetchTileBytesFromNetwork(
                            layerKind = effectiveLayer,
                            z = coord.z,
                            x = coord.x,
                            y = coord.y,
                            customTileTemplateUrl = customTileTemplateUrl,
                            isBulkRegionPrefetch = true,
                        )
                        if (res.retryAfterSeconds > 0) {
                            delay((res.retryAfterSeconds.coerceAtMost(15L)) * 1000L)
                        }
                        val bytes = res.bytes
                        if (bytes != null && isValidImageBytes(bytes)) {
                            evictLruCacheIfNeeded()
                            file.parentFile?.mkdirs()
                            file.writeBytes(bytes)
                            val size = bytes.size.toLong()
                            dao.upsertTile(
                                CachedMapTileEntity(
                                    tileKey = key,
                                    regionId = region.regionId,
                                    layerKind = effectiveLayer,
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
                            delay(250L) // Gentle 4 req/sec rate limit for compliant region caching
                        } else {
                            throw IllegalStateException(res.error ?: "Invalid/failed tile at z=${coord.z}, x=${coord.x}, y=${coord.y}")
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
                        errorMessage = "Download paused ($storedCount/$total tiles cached)",
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

    suspend fun computeRescueRoute(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        blockedRoads: List<BlockedRoadMarker>,
        allowNetwork: Boolean,
    ): RescueRoute = withContext(Dispatchers.IO) {
        val activeBlocked = blockedRoads.filter { it.active }
        if (allowNetwork) {
            val osrm = runCatching {
                val url = String.format(
                    Locale.US,
                    "https://router.project-osrm.org/route/v1/driving/%.5f,%.5f;%.5f,%.5f?overview=full&geometries=geojson",
                    fromLon,
                    fromLat,
                    toLon,
                    toLat,
                )
                val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val routes = json.optJSONArray("routes") ?: return@use null
                    if (routes.length() == 0) return@use null
                    val r0 = routes.getJSONObject(0)
                    val distKm = r0.optDouble("distance", 0.0) / 1000.0
                    val durMin = r0.optDouble("duration", 0.0) / 60.0
                    val coords = r0.getJSONObject("geometry").getJSONArray("coordinates")
                    val pts = (0 until coords.length()).map { idx ->
                        val c = coords.getJSONArray(idx)
                        c.getDouble(1) to c.getDouble(0)
                    }
                    val warnings = findBlockedProximityWarnings(pts, activeBlocked)
                    RescueRoute(
                        points = pts,
                        distanceKm = distKm,
                        durationMinutes = durMin,
                        isRoadRouted = true,
                        sourceLabel = "OSRM Open Road Routing (Live)",
                        nearBlockedWarnings = warnings,
                    )
                }
            }.getOrNull()
            if (osrm != null && osrm.points.size >= 2) {
                return@withContext osrm
            }
        }

        val straightPts = listOf(fromLat to fromLon, toLat to toLon)
        val distKm = haversineDistanceKm(fromLat, fromLon, toLat, toLon)
        val warnings = findBlockedProximityWarnings(straightPts, activeBlocked)
        RescueRoute(
            points = straightPts,
            distanceKm = distKm,
            durationMinutes = (distKm / 30.0) * 60.0,
            isRoadRouted = false,
            sourceLabel = "Offline Haversine Direct Vector (No Local Road Graph)",
            nearBlockedWarnings = warnings,
        )
    }

    suspend fun searchLocation(query: String, allowNetwork: Boolean): List<IndiaPlace> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        parseCoordinates(trimmed)?.let { (lat, lon) ->
            return listOf(
                IndiaPlace(
                    name = String.format(Locale.US, "Coordinates %.4f°, %.4f°", lat, lon),
                    stateOrUt = "Direct Coordinate Target",
                    latitude = lat,
                    longitude = lon,
                    source = "WGS84 Coordinate Parser",
                )
            )
        }

        val localMatches = INDIA_GAZETTEER.filter {
            it.name.contains(trimmed, ignoreCase = true) ||
                it.stateOrUt.contains(trimmed, ignoreCase = true)
        }
        if (!allowNetwork || localMatches.size >= 3) {
            return localMatches
        }

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

    private suspend fun evictLruCacheIfNeeded() {
        val total = dao.getTotalCachedBytesSync()
        if (total < MAX_DISK_CACHE_BYTES) return
        val oldest = dao.getOldestViewportTiles(40)
        for (tile in oldest) {
            runCatching { File(tile.localPath).delete() }
            memoryCache.remove(tile.tileKey)
            dao.deleteTileByKey(tile.tileKey)
        }
    }

    private data class TileFetchOutcome(
        val bytes: ByteArray? = null,
        val error: String? = null,
        val retryAfterSeconds: Long = 0L,
    )

    private fun fetchTileBytesFromNetwork(
        layerKind: String,
        z: Int,
        x: Int,
        y: Int,
        customTileTemplateUrl: String,
        isBulkRegionPrefetch: Boolean,
    ): TileFetchOutcome {
        val maxIdx = (1 shl z)
        if (z < 0 || z > 18 || x < 0 || x >= maxIdx || y < 0 || y >= maxIdx) {
            return TileFetchOutcome(error = "Tile out of bounds")
        }
        if (isBulkRegionPrefetch && layerKind == "osm" && customTileTemplateUrl.isBlank()) {
            return TileFetchOutcome(
                error = "Bulk pre-fetch from tile.openstreetmap.org is prohibited by OSM Usage Policy. Use Topo or Custom Server."
            )
        }

        val url = resolveTileUrl(layerKind, z, x, y, customTileTemplateUrl)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (response.code == 429 || response.code == 503) {
                val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: 5L
                return TileFetchOutcome(
                    error = "Tile server rate limited (HTTP ${response.code})",
                    retryAfterSeconds = retryAfter,
                )
            }
            if (!response.isSuccessful) {
                return TileFetchOutcome(error = "HTTP ${response.code} from tile server")
            }
            val cc = response.header("Cache-Control").orEmpty()
            if (isBulkRegionPrefetch && cc.contains("no-store", ignoreCase = true)) {
                return TileFetchOutcome(error = "Tile server Cache-Control forbids offline storage")
            }
            val bytes = response.body?.bytes()
            if (bytes == null || !isValidImageBytes(bytes)) {
                return TileFetchOutcome(error = "Corrupt or non-image tile payload")
            }
            return TileFetchOutcome(bytes = bytes)
        }
    }

    private fun tileKey(layerKind: String, z: Int, x: Int, y: Int): String = "$layerKind/$z/$x/$y"

    private fun tileFile(layerKind: String, z: Int, x: Int, y: Int): File =
        File(tileRootDir, "$layerKind/$z/$x/$y.png")

    companion object {
        private const val TAG = "OsmTileManager"
        const val DATA_VERSION = "topo-osm-v2"
        const val USER_AGENT = "SurakshaRescueApp/2.0 (humanitarian-disaster-response)"
        const val MAX_REGION_TILES = 300
        const val MAX_DISK_CACHE_BYTES = 150L * 1024L * 1024L

        fun resolveTileUrl(
            layerKind: String,
            z: Int,
            x: Int,
            y: Int,
            customTileTemplateUrl: String = "",
        ): String {
            val cleanCustom = customTileTemplateUrl.trim()
            if (cleanCustom.startsWith("https://") && cleanCustom.contains("{z}")) {
                return cleanCustom
                    .replace("{z}", z.toString())
                    .replace("{x}", x.toString())
                    .replace("{y}", y.toString())
            }
            return when (layerKind) {
                "topo" -> "https://tile.opentopomap.org/$z/$x/$y.png"
                else -> "https://tile.openstreetmap.org/$z/$x/$y.png"
            }
        }

        fun isValidImageBytes(bytes: ByteArray): Boolean {
            if (bytes.size < 16) return false
            // PNG header: 89 50 4E 47
            val isPng = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
            // JPEG header: FF D8 FF
            val isJpg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
            // WebP header: RIFF....WEBP
            val isWebp = bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte()
            return isPng || isJpg || isWebp
        }

        fun findBlockedProximityWarnings(
            routePoints: List<Pair<Double, Double>>,
            blockedRoads: List<BlockedRoadMarker>,
        ): List<String> {
            val warnings = mutableListOf<String>()
            for (road in blockedRoads) {
                val minKm = routePoints.minOfOrNull { (lat, lon) ->
                    haversineDistanceKm(lat, lon, road.latitude, road.longitude)
                } ?: Double.MAX_VALUE
                if (minKm <= 0.35) {
                    warnings.add("${road.label} (${road.severity}) within ${(minKm * 1000).toInt()}m of route")
                }
            }
            return warnings
        }

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
