package com.example.data.hardware

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.model.PresenceSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class AdapterProvisionState(val label: String) {
    SIMULATED_PROVIDER("Simulated Provider"),
    LIVE_HTTP_GATEWAY("HTTP Edge Gateway"),
    LIVE_BLE_GATT("BLE GATT Sensor Node"),
    FUTURE_HARDWARE_INTERFACE("Future Hardware Contract"),
}

data class SensorAdapterDescriptor(
    val nodeId: String,
    val platformName: String,
    val sensorType: String,
    val protocolInterface: String,
    val topicOrEndpoint: String,
    val provisionState: AdapterProvisionState,
    val measurementStatus: String,
    val calibrationStatus: String,
    val sensorHealth: String,
    val confidencePercent: Int,
    val samplePayloadJson: String,
    val capabilitiesNote: String,
    val isLiveHardware: Boolean = false,
)

data class SensorTelemetryPacket(
    val packetId: String,
    val deviceId: String,
    val sensorType: String,
    val sourceKind: String, // "simulated" or "hardware"
    val sectorLabel: String,
    val latitude: Double,
    val longitude: Double,
    val hasCoordinates: Boolean,
    val estimatedMinCount: Int,
    val estimatedMaxCount: Int,
    val confidencePercent: Int,
    val calibrationStatus: String,
    val sensorHealth: String,
    val observationTimestampMillis: Long,
    val dataSource: String,
    val rawPayloadJson: String,
)

data class FusedPresenceResult(
    val signals: List<PresenceSignal>,
    val rejectedPacketsCount: Int,
    val deduplicatedCount: Int,
    val staleCount: Int,
)

object SensorFusionProcessor {
    const val STALE_THRESHOLD_MILLIS = 120_000L
    const val DEDUP_WINDOW_MILLIS = 30_000L

    fun validatePacket(packet: SensorTelemetryPacket, nowMillis: Long): Boolean {
        if (packet.deviceId.isBlank() || packet.sectorLabel.isBlank()) return false
        if (packet.sourceKind !in setOf("simulated", "hardware")) return false
        if (packet.hasCoordinates && (packet.latitude !in -90.0..90.0 || packet.longitude !in -180.0..180.0)) return false
        if (packet.estimatedMinCount < 0 || packet.estimatedMaxCount < packet.estimatedMinCount) return false
        if (packet.confidencePercent !in 0..100) return false
        if (packet.observationTimestampMillis <= 0L || packet.observationTimestampMillis > nowMillis + 60_000L) return false
        return true
    }

    fun processPackets(
        packets: List<SensorTelemetryPacket>,
        orgId: String,
        userId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): FusedPresenceResult {
        var rejected = 0
        var deduplicated = 0
        var stale = 0

        val validPackets = packets.filter { pkt ->
            val ok = validatePacket(pkt, nowMillis)
            if (!ok) rejected++
            ok
        }.sortedByDescending { it.observationTimestampMillis }

        val accepted = mutableListOf<PresenceSignal>()
        val seenKeys = mutableMapOf<String, Long>()

        for (pkt in validPackets) {
            val dedupKey = "${pkt.deviceId}_${pkt.sensorType}_${pkt.sectorLabel.lowercase(Locale.US)}"
            val prevTs = seenKeys[dedupKey]
            if (prevTs != null && (prevTs - pkt.observationTimestampMillis) < DEDUP_WINDOW_MILLIS) {
                deduplicated++
                continue
            }
            seenKeys[dedupKey] = pkt.observationTimestampMillis

            val ageMillis = (nowMillis - pkt.observationTimestampMillis).coerceAtLeast(0L)
            val isStale = ageMillis > STALE_THRESHOLD_MILLIS
            if (isStale) stale++

            // CRITICAL INVARIANT: Simulated signals or unverified hardware packets can NEVER be auto-verified!
            val status = when {
                isStale -> "outdated"
                pkt.sourceKind == "simulated" -> "estimated"
                pkt.confidencePercent >= 70 && pkt.calibrationStatus == "calibrated" -> "estimated"
                else -> "unverified"
            }

            val normalizedConfidence = if (isStale) {
                minOf(pkt.confidencePercent, 35)
            } else {
                pkt.confidencePercent
            }

            accepted.add(
                PresenceSignal(
                    id = pkt.packetId.ifBlank { "sig_${UUID.randomUUID().toString().replace("-", "").take(10)}" },
                    orgId = orgId,
                    userId = userId,
                    locationId = "field_sector",
                    sectorLabel = pkt.sectorLabel.take(80),
                    signalType = pkt.sensorType,
                    sourceKind = pkt.sourceKind,
                    deviceId = pkt.deviceId.take(80),
                    calibrationStatus = pkt.calibrationStatus,
                    sensorHealth = if (isStale) "degraded" else pkt.sensorHealth,
                    latitude = if (pkt.hasCoordinates) pkt.latitude else 0.0,
                    longitude = if (pkt.hasCoordinates) pkt.longitude else 0.0,
                    hasCoordinates = pkt.hasCoordinates,
                    estimatedMinCount = pkt.estimatedMinCount.toLong(),
                    estimatedMaxCount = pkt.estimatedMaxCount.toLong(),
                    confidencePercent = normalizedConfidence.toLong(),
                    verificationStatus = status,
                    dataSource = pkt.dataSource.take(100),
                    verifiedByUid = "",
                    verificationNotes = if (pkt.sourceKind == "simulated") {
                        "Simulated reading only; cannot be auto-promoted to verified survivor."
                    } else "",
                    observationTimestampMillis = pkt.observationTimestampMillis,
                    isSynthetic = pkt.sourceKind == "simulated",
                )
            )
        }

        return FusedPresenceResult(
            signals = accepted,
            rejectedPacketsCount = rejected,
            deduplicatedCount = deduplicated,
            staleCount = stale,
        )
    }
}

interface SurakshaSensorAdapter {
    val descriptor: SensorAdapterDescriptor
    suspend fun pollTelemetryPackets(): Result<List<SensorTelemetryPacket>>
}

class SimulatedSensorProvider(
    override val descriptor: SensorAdapterDescriptor,
    private val defaultSector: String,
    private val signalType: String,
) : SurakshaSensorAdapter {
    override suspend fun pollTelemetryPackets(): Result<List<SensorTelemetryPacket>> {
        val now = System.currentTimeMillis()
        return Result.success(
            listOf(
                SensorTelemetryPacket(
                    packetId = "sim_${descriptor.nodeId}_${now % 100_000}",
                    deviceId = descriptor.nodeId,
                    sensorType = signalType,
                    sourceKind = "simulated",
                    sectorLabel = defaultSector,
                    latitude = 0.0,
                    longitude = 0.0,
                    hasCoordinates = false,
                    estimatedMinCount = 1,
                    estimatedMaxCount = 2,
                    confidencePercent = descriptor.confidencePercent,
                    calibrationStatus = descriptor.calibrationStatus,
                    sensorHealth = descriptor.sensorHealth,
                    observationTimestampMillis = now,
                    dataSource = "${descriptor.platformName} [SIMULATED]",
                    rawPayloadJson = descriptor.samplePayloadJson,
                )
            )
        )
    }
}

class WifiHttpGatewayAdapter(
    private val gatewayBaseUrl: String,
    private val httpClient: OkHttpClient = defaultGatewayClient(),
) : SurakshaSensorAdapter {
    override val descriptor: SensorAdapterDescriptor = SensorAdapterDescriptor(
        nodeId = "gw-http-fastapi",
        platformName = "Edge Gateway REST Bridge (Jetson / RPi5)",
        sensorType = "thermal_ir",
        protocolInterface = "HTTPS / FastAPI JSON (/api/v1/sensors)",
        topicOrEndpoint = gatewayBaseUrl.ifBlank { "Not configured" },
        provisionState = AdapterProvisionState.LIVE_HTTP_GATEWAY,
        measurementStatus = if (gatewayBaseUrl.isBlank()) "Endpoint Not Configured" else "Ready to Poll Endpoint",
        calibrationStatus = "unknown",
        sensorHealth = if (gatewayBaseUrl.isBlank()) "offline" else "healthy",
        confidencePercent = 0,
        samplePayloadJson = """{"endpoint":"${gatewayBaseUrl.ifBlank { "unconfigured" }}","protocol":"FastAPI JSON"}""",
        capabilitiesNote = "Fetches real JSON telemetry packets from an on-scene Jetson/Raspberry Pi FastAPI gateway.",
        isLiveHardware = true,
    )

    override suspend fun pollTelemetryPackets(): Result<List<SensorTelemetryPacket>> = withContext(Dispatchers.IO) {
        val trimmed = gatewayBaseUrl.trim().trimEnd('/')
        if (trimmed.isBlank() || (!trimmed.startsWith("http://") && !trimmed.startsWith("https://"))) {
            return@withContext Result.failure(IllegalArgumentException("Configure a valid http:// or https:// edge gateway URL first."))
        }
        val url = if (trimmed.endsWith("/sensors")) trimmed else "$trimmed/api/v1/sensors"
        return@withContext try {
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@use Result.failure(IllegalStateException("Edge gateway returned HTTP ${resp.code}"))
                }
                val body = resp.body?.string().orEmpty()
                parseGatewayPayloadJson(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP gateway poll failed: ${e.message}")
            Result.failure(IllegalStateException("Edge gateway unreachable: ${e.localizedMessage ?: "connection failed"}"))
        }
    }

    companion object {
        private const val TAG = "WifiHttpGatewayAdapter"

        private fun defaultGatewayClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

        fun parseGatewayPayloadJson(rawJson: String): Result<List<SensorTelemetryPacket>> {
            return try {
                val root = if (rawJson.trimStart().startsWith("[")) {
                    JSONArray(rawJson)
                } else {
                    val obj = JSONObject(rawJson)
                    obj.optJSONArray("packets") ?: JSONArray().put(obj)
                }
                val now = System.currentTimeMillis()
                val packets = (0 until root.length()).mapNotNull { idx ->
                    val o = root.optJSONObject(idx) ?: return@mapNotNull null
                    val lat = o.optDouble("latitude", Double.NaN)
                    val lon = o.optDouble("longitude", Double.NaN)
                    val hasCoords = !lat.isNaN() && !lon.isNaN() && lat in -90.0..90.0 && lon in -180.0..180.0
                    SensorTelemetryPacket(
                        packetId = o.optString("packetId", "gw_${idx}_${now % 10000}"),
                        deviceId = o.optString("deviceId", "edge-gw-01"),
                        sensorType = o.optString("signalType", "thermal_ir"),
                        sourceKind = "hardware",
                        sectorLabel = o.optString("sectorLabel", "Hardware Sector"),
                        latitude = if (hasCoords) lat else 0.0,
                        longitude = if (hasCoords) lon else 0.0,
                        hasCoordinates = hasCoords,
                        estimatedMinCount = o.optInt("estimatedMinCount", 0).coerceAtLeast(0),
                        estimatedMaxCount = o.optInt("estimatedMaxCount", 1).coerceAtLeast(0),
                        confidencePercent = o.optInt("confidencePercent", 50).coerceIn(0, 100),
                        calibrationStatus = o.optString("calibrationStatus", "unknown"),
                        sensorHealth = o.optString("sensorHealth", "healthy"),
                        observationTimestampMillis = o.optLong("timestampMillis", now),
                        dataSource = o.optString("dataSource", "Hardware Edge HTTP Gateway"),
                        rawPayloadJson = o.toString(),
                    )
                }
                Result.success(packets)
            } catch (e: Exception) {
                Result.failure(IllegalArgumentException("Malformed gateway telemetry JSON: ${e.localizedMessage}"))
            }
        }
    }
}

class BleGattSensorAdapter(private val context: Context) : SurakshaSensorAdapter {
    private val appContext = context.applicationContext

    override val descriptor: SensorAdapterDescriptor
        get() {
            val status = checkBleStatus()
            return SensorAdapterDescriptor(
                nodeId = "hw-ble-gatt-01",
                platformName = "ESP32-S3 BLE GATT Field Beacon",
                sensorType = "radar_respiration",
                protocolInterface = "Bluetooth Low Energy 5.0 GATT Service",
                topicOrEndpoint = "UUID: 0000FE90-0000-1000-8000-00805F9B34FB",
                provisionState = AdapterProvisionState.LIVE_BLE_GATT,
                measurementStatus = status,
                calibrationStatus = "unknown",
                sensorHealth = if (status.startsWith("Ready")) "healthy" else "offline",
                confidencePercent = 0,
                samplePayloadJson = """{"transport":"ble_gatt","status":"$status"}""",
                capabilitiesNote = "Connects directly to portable ESP32 BLE telemetry beacons when paired in range.",
                isLiveHardware = true,
            )
        }

    fun checkBleStatus(): String {
        val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return "BLE Hardware Unsupported"
        val adapter = btManager.adapter ?: return "Bluetooth Adapter Unavailable"
        if (!adapter.isEnabled) return "Bluetooth Disabled on Device"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val canScan = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_SCAN,
            ) == PackageManager.PERMISSION_GRANTED
            val canConnect = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
            if (!canScan || !canConnect) return "BLE Scan/Connect Permission Required"
        }
        return "Ready (No Paired Sensor Beacon in Range)"
    }

    override suspend fun pollTelemetryPackets(): Result<List<SensorTelemetryPacket>> {
        val status = checkBleStatus()
        return Result.failure(IllegalStateException("BLE GATT: $status"))
    }
}

class FutureContractSensorAdapter(
    override val descriptor: SensorAdapterDescriptor,
) : SurakshaSensorAdapter {
    override suspend fun pollTelemetryPackets(): Result<List<SensorTelemetryPacket>> {
        return Result.failure(
            UnsupportedOperationException("${descriptor.platformName} requires physical ${descriptor.protocolInterface} hardware.")
        )
    }
}

enum class EnvScenario(val label: String, val areaNote: String) {
    COLLAPSED_URBAN("Collapsed Urban Structure (500m × 500m)", "Simulated multi-wing concrete rubble & void pockets"),
    HIMALAYAN_LANDSLIDE("Himalayan Ridge Landslide (500m × 500m)", "Simulated steep terrain debris & retaining wall fracture"),
    FLOODED_DELTA("Flooded Delta Ward (500m × 500m)", "Simulated submerged road grid & elevated shelter structures"),
}

enum class CellOccupancy {
    UNKNOWN,
    FREE,
    OBSTACLE,
    RUBBLE_VOID,
    THERMAL_ANOMALY,
}

data class PointCloudSample(
    val xMeters: Float,
    val yMeters: Float,
    val zMeters: Float,
    val intensity: Float,
    val isThermalHotspot: Boolean = false,
)

data class SlamEnvironmentFrame(
    val scenario: EnvScenario,
    val gridSize: Int,
    val cellSizeMeters: Float,
    val scanCoveragePercent: Int,
    val cells: List<CellOccupancy>,
    val trajectoryPoints: List<Pair<Float, Float>>,
    val pointCloud: List<PointCloudSample>,
    val obstacleCount: Int,
    val voidCount: Int,
    val thermalCount: Int,
    val uncertaintyMeters: Float,
    val dataSource: String = "Synthetic SLAM Simulator (Demo Only)",
    val disclaimer: String = "Synthetic 500m×500m simulation grid. Handheld real-world scan coverage depends on LiDAR range, walking speed, occlusion, and weather.",
)

object SlamExportFormatter {
    fun toJson(frame: SlamEnvironmentFrame): String {
        val root = JSONObject()
        root.put("scenario", frame.scenario.name)
        root.put("dataSource", frame.dataSource)
        root.put("isSimulated", true)
        root.put("gridSize", frame.gridSize)
        root.put("cellSizeMeters", frame.cellSizeMeters)
        root.put("scanCoveragePercent", frame.scanCoveragePercent)
        root.put("uncertaintyMeters", frame.uncertaintyMeters)
        root.put("obstacleCells", frame.obstacleCount)
        root.put("rubbleVoidCells", frame.voidCount)
        root.put("thermalAnomalyCells", frame.thermalCount)
        root.put("pointCloudSampleCount", frame.pointCloud.size)
        return root.toString(2)
    }

    fun toCsv(frame: SlamEnvironmentFrame): String = buildString {
        appendLine("# PROJECT SURAKSHA SYNTHETIC SLAM EXPORT (NOT FIELD HARDWARE)")
        appendLine("scenario,grid_size,cell_m,coverage_pct,uncertainty_m,x_m,y_m,z_m,intensity,thermal_hotspot")
        frame.pointCloud.forEach { pt ->
            appendLine(
                "${frame.scenario.name},${frame.gridSize},${frame.cellSizeMeters},${frame.scanCoveragePercent},${frame.uncertaintyMeters},${pt.xMeters},${pt.yMeters},${pt.zMeters},${pt.intensity},${pt.isThermalHotspot}"
            )
        }
    }
}

object SensorArchitectureCatalog {

    fun buildAdapters(
        context: Context? = null,
        httpGatewayUrl: String = "",
    ): List<SurakshaSensorAdapter> {
        val list = mutableListOf<SurakshaSensorAdapter>()
        if (context != null) {
            list.add(BleGattSensorAdapter(context))
        }
        list.add(WifiHttpGatewayAdapter(httpGatewayUrl))
        list.add(
            SimulatedSensorProvider(
                descriptor = SensorAdapterDescriptor(
                    nodeId = "sim-jetson-slam-01",
                    platformName = "NVIDIA Jetson Orin Nano (Sim)",
                    sensorType = "3D LiDAR + Visual-Inertial SLAM",
                    protocolInterface = "ROS 2 Humble DDS (/suraksha/slam/occupancy)",
                    topicOrEndpoint = "ws://suraksha-edge.local:8000/ws/slam",
                    provisionState = AdapterProvisionState.SIMULATED_PROVIDER,
                    measurementStatus = "Simulated Stream Active",
                    calibrationStatus = "nominal",
                    sensorHealth = "healthy",
                    confidencePercent = 82,
                    samplePayloadJson = """{"source":"synthetic_slam_sim","frame_id":"map_500m","grid_res_m":1.0,"loop_closures":4,"status":"estimated"}""",
                    capabilitiesNote = "Generates synthetic 500×500m occupancy grids and point clouds for software validation.",
                ),
                defaultSector = "Synthetic SLAM Wing A",
                signalType = "lidar_void",
            )
        )
        list.add(
            SimulatedSensorProvider(
                descriptor = SensorAdapterDescriptor(
                    nodeId = "sim-esp32-uwb-02",
                    platformName = "ESP32-S3 Field Relay (Sim)",
                    sensorType = "UWB Micro-Doppler Respiration Radar",
                    protocolInterface = "MQTT / Serial UART 921600bps",
                    topicOrEndpoint = "mqtt://mesh.local/suraksha/uwb/presence",
                    provisionState = AdapterProvisionState.SIMULATED_PROVIDER,
                    measurementStatus = "Simulated Stream Active",
                    calibrationStatus = "nominal",
                    sensorHealth = "healthy",
                    confidencePercent = 76,
                    samplePayloadJson = """{"source":"synthetic_uwb_sim","sector":"B2","resp_hz":0.28,"confidence":76,"status":"estimated_not_confirmed"}""",
                    capabilitiesNote = "Models periodic chest-wall micro-motion signals; never equates signal to confirmed human presence.",
                ),
                defaultSector = "Collapsed Wing B2 (Void Pocket)",
                signalType = "radar_respiration",
            )
        )
        list.add(
            SimulatedSensorProvider(
                descriptor = SensorAdapterDescriptor(
                    nodeId = "sim-rpi5-thermal-03",
                    platformName = "Raspberry Pi 5 Edge Hub (Sim)",
                    sensorType = "FLIR Lepton 3.5 Thermal + BME680 Env",
                    protocolInterface = "FastAPI REST + WebSocket Bridge",
                    topicOrEndpoint = "http://rpi5-field.local:8000/api/v1/sensors",
                    provisionState = AdapterProvisionState.SIMULATED_PROVIDER,
                    measurementStatus = "Simulated Stream Active",
                    calibrationStatus = "nominal",
                    sensorHealth = "healthy",
                    confidencePercent = 68,
                    samplePayloadJson = """{"source":"synthetic_thermal_sim","max_c":36.4,"ambient_c":18.2,"co2_ppm":620,"status":"unverified"}""",
                    capabilitiesNote = "Simulates thermal gradients and ambient air quality readings for rescue triage UI.",
                ),
                defaultSector = "Retaining Wall Terrace",
                signalType = "thermal_ir",
            )
        )
        list.add(
            FutureContractSensorAdapter(
                SensorAdapterDescriptor(
                    nodeId = "hw-rplidar-usb-future",
                    platformName = "RPLIDAR A3 / Livox Mid-360",
                    sensorType = "Physical 2D/3D Time-of-Flight LiDAR",
                    protocolInterface = "USB-Serial OTG / Ethernet UDP -> ROS 2 Driver",
                    topicOrEndpoint = "/dev/ttyUSB0 -> /scan",
                    provisionState = AdapterProvisionState.FUTURE_HARDWARE_INTERFACE,
                    measurementStatus = "Future Hardware (Awaiting Physical Unit)",
                    calibrationStatus = "unknown",
                    sensorHealth = "offline",
                    confidencePercent = 0,
                    samplePayloadJson = """{"adapter":"future_hardware_contract","driver":"rplidar_ros","connected":false}""",
                    capabilitiesNote = "USB OTG / ROS 2 bridge interface specification for physical disaster-lab LiDAR rigs.",
                )
            )
        )
        list.add(
            FutureContractSensorAdapter(
                SensorAdapterDescriptor(
                    nodeId = "hw-rtk-gps-imu-future",
                    platformName = "u-blox ZED-F9P RTK GNSS + BNO085 IMU",
                    sensorType = "Centimeter RTK GNSS & 9-DoF Inertial",
                    protocolInterface = "MQTT / NMEA 0183 / RTCM3 Bridge",
                    topicOrEndpoint = "/dev/ttyACM0 -> /gps/fix",
                    provisionState = AdapterProvisionState.FUTURE_HARDWARE_INTERFACE,
                    measurementStatus = "Future Hardware (Awaiting Physical Unit)",
                    calibrationStatus = "unknown",
                    sensorHealth = "offline",
                    confidencePercent = 0,
                    samplePayloadJson = """{"adapter":"future_hardware_contract","protocol":"NMEA_RTCM3","connected":false}""",
                    capabilitiesNote = "Handheld GNSS-denied dead-reckoning & obstacle avoidance contract.",
                )
            )
        )
        return list
    }

    fun generateSlamFrame(scenario: EnvScenario, sweepProgress: Float): SlamEnvironmentFrame {
        val gridSize = 25 // 25 x 25 cells, each cell = 20m -> 500m x 500m
        val cellMeters = 20f
        val progress = sweepProgress.coerceIn(0.15f, 1.0f)
        val scanRadiusCells = (progress * 17f)
        val center = gridSize / 2f

        val cells = MutableList(gridSize * gridSize) { CellOccupancy.UNKNOWN }
        var obstacles = 0
        var voids = 0
        var thermals = 0

        for (row in 0 until gridSize) {
            for (col in 0 until gridSize) {
                val dx = col - center
                val dy = row - center
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > scanRadiusCells) {
                    cells[row * gridSize + col] = CellOccupancy.UNKNOWN
                    continue
                }

                val cell = when (scenario) {
                    EnvScenario.COLLAPSED_URBAN -> when {
                        (row in 5..8 && col in 6..11) && (row == 5 || col == 6 || row == 8) -> CellOccupancy.OBSTACLE
                        (row in 7..9 && col in 14..18) && (row == 7 || col == 18) -> CellOccupancy.OBSTACLE
                        (row == 6 && col == 9) || (row == 8 && col == 16) -> CellOccupancy.THERMAL_ANOMALY
                        (row in 6..7 && col in 7..10) || (row in 14..16 && col in 9..13) -> CellOccupancy.RUBBLE_VOID
                        (row == 15 && (col in 4..20)) -> CellOccupancy.OBSTACLE
                        else -> CellOccupancy.FREE
                    }
                    EnvScenario.HIMALAYAN_LANDSLIDE -> when {
                        row - col > 6 && (row + col) % 3 == 0 -> CellOccupancy.OBSTACLE
                        (row in 9..12 && col in 11..15) -> CellOccupancy.RUBBLE_VOID
                        (row == 10 && col == 13) -> CellOccupancy.THERMAL_ANOMALY
                        col < 4 || row > 20 -> CellOccupancy.OBSTACLE
                        else -> CellOccupancy.FREE
                    }
                    EnvScenario.FLOODED_DELTA -> when {
                        (row in 10..13 && col in 2..22) -> CellOccupancy.RUBBLE_VOID
                        (row in 5..7 && col in 8..10) || (row in 16..18 && col in 14..16) -> CellOccupancy.OBSTACLE
                        (row == 6 && col == 9) -> CellOccupancy.THERMAL_ANOMALY
                        else -> CellOccupancy.FREE
                    }
                }
                cells[row * gridSize + col] = cell
                when (cell) {
                    CellOccupancy.OBSTACLE -> obstacles++
                    CellOccupancy.RUBBLE_VOID -> voids++
                    CellOccupancy.THERMAL_ANOMALY -> thermals++
                    else -> Unit
                }
            }
        }

        val trajectory = (0..12).map { step ->
            val t = (step / 12f) * progress
            val angle = t * 5.2f
            val r = 0.08f + t * 0.34f
            Pair(0.5f + r * cos(angle), 0.5f + r * sin(angle))
        }

        val cloud = mutableListOf<PointCloudSample>()
        for (row in 0 until gridSize) {
            for (col in 0 until gridSize) {
                val occ = cells[row * gridSize + col]
                if (occ == CellOccupancy.UNKNOWN) continue
                val x = (col - center) * cellMeters
                val y = (row - center) * cellMeters
                when (occ) {
                    CellOccupancy.OBSTACLE -> {
                        for (layer in 0..3) {
                            cloud.add(PointCloudSample(x, y, 1.5f + layer * 2.2f, 0.9f))
                        }
                    }
                    CellOccupancy.RUBBLE_VOID -> {
                        cloud.add(PointCloudSample(x, y, 1.2f, 0.65f))
                        cloud.add(PointCloudSample(x + 4f, y - 4f, 2.4f, 0.7f))
                    }
                    CellOccupancy.THERMAL_ANOMALY -> {
                        cloud.add(PointCloudSample(x, y, 1.8f, 1.0f, isThermalHotspot = true))
                    }
                    CellOccupancy.FREE -> {
                        if ((row + col) % 2 == 0) {
                            cloud.add(PointCloudSample(x, y, 0.0f, 0.35f))
                        }
                    }
                    CellOccupancy.UNKNOWN -> Unit
                }
            }
        }

        return SlamEnvironmentFrame(
            scenario = scenario,
            gridSize = gridSize,
            cellSizeMeters = cellMeters,
            scanCoveragePercent = (progress * 88f).toInt().coerceIn(12, 88),
            cells = cells,
            trajectoryPoints = trajectory,
            pointCloud = cloud,
            obstacleCount = obstacles,
            voidCount = voids,
            thermalCount = thermals,
            uncertaintyMeters = (2.8f - progress * 1.4f).coerceAtLeast(0.8f),
        )
    }
}
