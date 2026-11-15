package com.example.data.hardware

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class AdapterProvisionState {
    SIMULATED_PROVIDER,
    FUTURE_HARDWARE_INTERFACE,
}

data class SensorAdapterDescriptor(
    val nodeId: String,
    val platformName: String,
    val sensorType: String,
    val protocolInterface: String,
    val topicOrEndpoint: String,
    val provisionState: AdapterProvisionState,
    val measurementStatus: String,
    val confidencePercent: Int,
    val samplePayloadJson: String,
    val capabilitiesNote: String,
)

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
    val dataSource: String = "Synthetic SLAM Simulator (Demo)",
    val disclaimer: String = "Synthetic 500m×500m demo sector. Real handheld scan coverage depends on sensor range, movement speed, terrain, and weather.",
)

interface SurakshaSensorAdapter {
    val descriptor: SensorAdapterDescriptor
    fun readLatestPacketJson(): String
}

class SimulatedSensorProvider(
    override val descriptor: SensorAdapterDescriptor,
) : SurakshaSensorAdapter {
    override fun readLatestPacketJson(): String = descriptor.samplePayloadJson
}

object SensorArchitectureCatalog {

    fun buildAdapters(): List<SurakshaSensorAdapter> = listOf(
        SimulatedSensorProvider(
            SensorAdapterDescriptor(
                nodeId = "sim-jetson-slam-01",
                platformName = "NVIDIA Jetson Orin Nano (Sim)",
                sensorType = "3D LiDAR + Visual-Inertial SLAM",
                protocolInterface = "ROS 2 Humble DDS (/suraksha/slam/occupancy)",
                topicOrEndpoint = "ws://suraksha-edge.local:8000/ws/slam",
                provisionState = AdapterProvisionState.SIMULATED_PROVIDER,
                measurementStatus = "Simulated Stream Active",
                confidencePercent = 82,
                samplePayloadJson = """{"source":"synthetic_slam_sim","frame_id":"map_500m","grid_res_m":1.0,"loop_closures":4,"status":"estimated"}""",
                capabilitiesNote = "Generates synthetic 500×500m occupancy grids and point clouds for software validation.",
            )
        ),
        SimulatedSensorProvider(
            SensorAdapterDescriptor(
                nodeId = "sim-esp32-uwb-02",
                platformName = "ESP32-S3 Field Relay (Sim)",
                sensorType = "UWB Micro-Doppler Respiration Radar",
                protocolInterface = "MQTT / Serial UART 921600bps",
                topicOrEndpoint = "mqtt://mesh.local/suraksha/uwb/presence",
                provisionState = AdapterProvisionState.SIMULATED_PROVIDER,
                measurementStatus = "Simulated Stream Active",
                confidencePercent = 76,
                samplePayloadJson = """{"source":"synthetic_uwb_sim","sector":"B2","resp_hz":0.28,"confidence":76,"status":"estimated_not_confirmed"}""",
                capabilitiesNote = "Models periodic chest-wall micro-motion signals; never equates signal to confirmed human presence.",
            )
        ),
        SimulatedSensorProvider(
            SensorAdapterDescriptor(
                nodeId = "sim-rpi5-thermal-03",
                platformName = "Raspberry Pi 5 Edge Hub (Sim)",
                sensorType = "FLIR Lepton 3.5 Thermal + BME680 Env",
                protocolInterface = "FastAPI REST + WebSocket Bridge",
                topicOrEndpoint = "http://rpi5-field.local:8000/api/v1/sensors",
                provisionState = AdapterProvisionState.SIMULATED_PROVIDER,
                measurementStatus = "Simulated Stream Active",
                confidencePercent = 68,
                samplePayloadJson = """{"source":"synthetic_thermal_sim","max_c":36.4,"ambient_c":18.2,"co2_ppm":620,"status":"unverified"}""",
                capabilitiesNote = "Simulates thermal gradients and ambient air quality readings for rescue triage UI.",
            )
        ),
        SimulatedSensorProvider(
            SensorAdapterDescriptor(
                nodeId = "hw-rplidar-future",
                platformName = "RPLIDAR A3 / Livox Mid-360",
                sensorType = "Physical 2D/3D Time-of-Flight LiDAR",
                protocolInterface = "USB-Serial / Ethernet UDP -> ROS 2 Driver",
                topicOrEndpoint = "/dev/ttyUSB0 -> /scan",
                provisionState = AdapterProvisionState.FUTURE_HARDWARE_INTERFACE,
                measurementStatus = "Future Hardware (Awaiting Physical Unit)",
                confidencePercent = 0,
                samplePayloadJson = """{"adapter":"future_hardware_contract","driver":"rplidar_ros","connected":false}""",
                capabilitiesNote = "Documented adapter contract for Phase 5–6 controlled research lab testing.",
            )
        ),
        SimulatedSensorProvider(
            SensorAdapterDescriptor(
                nodeId = "hw-gps-imu-future",
                platformName = "u-blox ZED-F9P RTK GPS + BNO085 IMU",
                sensorType = "High-Precision GNSS & 9-DoF Inertial",
                protocolInterface = "NMEA 0183 / I2C -> FastAPI Telemetry Daemon",
                topicOrEndpoint = "/dev/ttyACM0 -> /gps/fix",
                provisionState = AdapterProvisionState.FUTURE_HARDWARE_INTERFACE,
                measurementStatus = "Future Hardware (Awaiting Physical Unit)",
                confidencePercent = 0,
                samplePayloadJson = """{"adapter":"future_hardware_contract","protocol":"NMEA_RTCM3","connected":false}""",
                capabilitiesNote = "Future portable handheld device positioning module with dead-reckoning fallback.",
            )
        ),
    )

    fun generateSlamFrame(scenario: EnvScenario, sweepProgress: Float): SlamEnvironmentFrame {
        val gridSize = 25 // 25 x 25 cells, each cell = 20m -> 500m x 500m
        val cellMeters = 20f
        val progress = sweepProgress.coerceIn(0.15f, 1.0f)
        val scanRadiusCells = (progress * 17f)
        val center = gridSize / 2f

        val cells = MutableList(gridSize * gridSize) { CellOccupancy.UNKNOWN }
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
            }
        }

        val trajectory = (0..12).map { step ->
            val t = (step / 12f) * progress
            val angle = t * 5.2f
            val r = 0.08f + t * 0.34f
            Pair(0.5f + r * cos(angle), 0.5f + r * sin(angle))
        }

        val cloud = mutableListOf<PointCloudSample>()
        for (row in 0 until gridSize step 1) {
            for (col in 0 until gridSize step 1) {
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
        )
    }
}
