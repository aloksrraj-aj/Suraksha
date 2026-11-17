package com.example.data.hardware

import com.example.data.model.SensorDevice
import com.example.data.model.SensorEvent
import com.example.data.model.SensorHealth
import com.example.data.model.SensorType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class HardwareIntegrationStatus(val label: String) {
    AWAITING_HARDWARE_INTEGRATION("AWAITING_HARDWARE_INTEGRATION"),
    CONNECTING("CONNECTING"),
    CONNECTED("CONNECTED"),
    DISCONNECTED("DISCONNECTED"),
    ERROR("ERROR"),
}

enum class GatewayTransportKind(val value: String, val label: String) {
    HTTP_TLS("http_tls", "HTTPS / TLS Polling"),
    WEBSOCKET_TLS("websocket_tls", "WebSocket / TLS Stream"),
    MQTT_TLS("mqtt_tls", "MQTT over TLS (Broker Pending)"),
}

data class SensorCapability(
    val sensorType: SensorType,
    val supportsRange: Boolean,
    val supportsAzimuth: Boolean,
    val supportsElevation: Boolean,
    val supportsRespirationBpm: Boolean,
    val supportsAbsoluteCoordinates: Boolean,
    val nominalMaxRangeMeters: Double,
    val notes: String,
)

data class CalibrationProfile(
    val calibrationVersion: String,
    val calibratedAtMillis: Long,
    val rangeBiasMeters: Double = 0.0,
    val azimuthOffsetDegrees: Double = 0.0,
    val minConfidenceThreshold: Double = 0.40,
    val notes: String = "",
)

data class GatewayTransportConfig(
    val transportKind: GatewayTransportKind,
    val endpointUri: String,
    val orgId: String,
    val missionId: String,
    val bearerToken: String,
)

data class SensorAdapterDescriptor(
    val adapterId: String,
    val sensorType: SensorType,
    val displayName: String,
    val integrationStatus: HardwareIntegrationStatus,
    val capability: SensorCapability,
    val protocolRequirement: String,
    val diagnosticMessage: String,
)

/** 1. Core adapter contract for any external rescue radar, sensor, or registered tag hardware. */
interface SensorAdapter {
    val adapterId: String
    val sensorType: SensorType
    val displayName: String
    val integrationStatus: HardwareIntegrationStatus
    val capability: SensorCapability
    val protocolRequirement: String
    fun diagnosticSummary(): String
}

/** 2. Connection lifecycle contract (no silent fake connections). */
interface SensorConnectionManager {
    val connectionStates: StateFlow<Map<String, HardwareIntegrationStatus>>
    suspend fun connect(sensor: SensorDevice): Result<HardwareIntegrationStatus>
    suspend fun disconnect(sensorId: String)
}

/** 3. Live sensor heartbeat and malformed/valid packet accounting. */
interface SensorHealthMonitor {
    val healthUpdates: Flow<SensorHealth>
    fun recordPacketAccepted(sensorId: String, orgId: String, missionId: String)
    fun recordPacketRejected(sensorId: String, orgId: String, missionId: String, reason: String)
}

/** 4. Raw gateway or device payload parser enforcing schema & safety rules. */
interface SensorDataParser {
    fun parseSingleJsonEvent(
        rawJson: String,
        orgId: String,
        missionId: String,
        operatorUid: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Result<SensorEvent>

    fun parseBatchJsonEvents(
        rawJson: String,
        orgId: String,
        missionId: String,
        operatorUid: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<Result<SensorEvent>>
}

/** 5. Persistence/stream sink for validated events. */
interface SensorEventRepository {
    suspend fun ingestValidatedEvent(event: SensorEvent): Result<String>
    suspend fun clearMissionDemoState()
}

/** 6. Calibration metadata management per sensor unit. */
interface SensorCalibrationManager {
    fun getCalibration(sensorId: String): CalibrationProfile
    fun updateCalibration(sensorId: String, profile: CalibrationProfile)
    fun applyCalibration(event: SensorEvent): SensorEvent
}

/** 7. Edge Gateway / Sensor Hub transport client (HTTP/TLS, WebSocket/TLS, or MQTT/TLS). */
interface SensorGatewayClient {
    val transportKind: GatewayTransportKind
    val incomingEvents: Flow<SensorEvent>
    suspend fun connect(config: GatewayTransportConfig): Result<Unit>
    suspend fun pollOnce(config: GatewayTransportConfig, operatorUid: String): Result<List<SensorEvent>>
    suspend fun disconnect()
}

class DefaultCalibrationManager : SensorCalibrationManager {
    private val profiles = mutableMapOf<String, CalibrationProfile>()

    override fun getCalibration(sensorId: String): CalibrationProfile {
        return profiles[sensorId] ?: CalibrationProfile(
            calibrationVersion = "default-1.0",
            calibratedAtMillis = 0L,
            notes = "Awaiting on-site hardware calibration",
        )
    }

    override fun updateCalibration(sensorId: String, profile: CalibrationProfile) {
        profiles[sensorId] = profile
    }

    override fun applyCalibration(event: SensorEvent): SensorEvent {
        val profile = profiles[event.sensorId] ?: return event
        val calibratedRange = event.estimatedRangeMeters?.let { (it + profile.rangeBiasMeters).coerceAtLeast(0.0) }
        val calibratedAzimuth = event.estimatedAzimuthDegrees?.let {
            (((it + profile.azimuthOffsetDegrees) % 360.0) + 360.0) % 360.0
        }
        return event.copy(
            estimatedRangeMeters = calibratedRange,
            estimatedAzimuthDegrees = calibratedAzimuth,
            calibrationVersion = profile.calibrationVersion,
        )
    }
}

/** Mode A: Building / Collapsed Structure Through-Wall UWB Radar adapter (awaiting physical SDK/gateway). */
class UwbLifeDetectionRadarAdapter : SensorAdapter {
    override val adapterId = "adapter_uwb_life_detection"
    override val sensorType = SensorType.UWB_LIFE_DETECTION_RADAR
    override val displayName = "Mode A • UWB Through-Wall Life-Detection Radar"
    override val integrationStatus = HardwareIntegrationStatus.AWAITING_HARDWARE_INTEGRATION
    override val capability = SensorCapability(
        sensorType = sensorType,
        supportsRange = true,
        supportsAzimuth = true,
        supportsElevation = true,
        supportsRespirationBpm = true,
        supportsAbsoluteCoordinates = false,
        nominalMaxRangeMeters = 30.0,
        notes = "Detects micro-Doppler respiration & subtle motion through rubble/walls.",
    )
    override val protocolRequirement = "Requires physical UWB radar bridged via Edge Gateway JSON v1.0.0 or vendor DSP SDK."
    override fun diagnosticSummary(): String = "$integrationStatus — $protocolRequirement"
}

/** Mode B: Open-Area FMCW Radar adapter (awaiting physical FMCW baseband / gateway). */
class FmcwOpenAreaRadarAdapter : SensorAdapter {
    override val adapterId = "adapter_fmcw_open_area"
    override val sensorType = SensorType.FMCW_RADAR
    override val displayName = "Mode B • Open-Area FMCW Rescue Radar"
    override val integrationStatus = HardwareIntegrationStatus.AWAITING_HARDWARE_INTEGRATION
    override val capability = SensorCapability(
        sensorType = sensorType,
        supportsRange = true,
        supportsAzimuth = true,
        supportsElevation = false,
        supportsRespirationBpm = false,
        supportsAbsoluteCoordinates = true,
        nominalMaxRangeMeters = 250.0,
        notes = "Wide-area range + azimuth sweep for floodplains, landslides, and dark open terrain.",
    )
    override val protocolRequirement = "Requires FMCW point-cloud module bridged via HTTPS/WebSocket/MQTT Edge Gateway."
    override fun diagnosticSummary(): String = "$integrationStatus — $protocolRequirement"
}

class ThermalImagingSensorAdapter : SensorAdapter {
    override val adapterId = "adapter_thermal_ir"
    override val sensorType = SensorType.THERMAL_IMAGING
    override val displayName = "Thermal / Infrared Imaging Sensor"
    override val integrationStatus = HardwareIntegrationStatus.AWAITING_HARDWARE_INTEGRATION
    override val capability = SensorCapability(
        sensorType = sensorType,
        supportsRange = false,
        supportsAzimuth = true,
        supportsElevation = true,
        supportsRespirationBpm = false,
        supportsAbsoluteCoordinates = false,
        nominalMaxRangeMeters = 120.0,
        notes = "Bearing-only thermal signature hotspot detector; range nullable unless paired with laser/radar.",
    )
    override val protocolRequirement = "Requires UVC/RTSP thermal SDK or Edge Gateway hotspot extractor."
    override fun diagnosticSummary(): String = "$integrationStatus — $protocolRequirement"
}

class AcousticVibrationSensorAdapter : SensorAdapter {
    override val adapterId = "adapter_acoustic_vibration"
    override val sensorType = SensorType.ACOUSTIC_VIBRATION
    override val displayName = "Acoustic / Seismic Rubble Vibration Probe"
    override val integrationStatus = HardwareIntegrationStatus.AWAITING_HARDWARE_INTEGRATION
    override val capability = SensorCapability(
        sensorType = sensorType,
        supportsRange = true,
        supportsAzimuth = false,
        supportsElevation = false,
        supportsRespirationBpm = false,
        supportsAbsoluteCoordinates = false,
        nominalMaxRangeMeters = 40.0,
        notes = "Detects tapping/voices/seismic vibrations; azimuth is nullable when single probe used.",
    )
    override val protocolRequirement = "Requires geophone / acoustic array interface via Edge Gateway ADC."
    override fun diagnosticSummary(): String = "$integrationStatus — $protocolRequirement"
}

class RegisteredBleTagAdapter : SensorAdapter {
    override val adapterId = "adapter_registered_ble_tag"
    override val sensorType = SensorType.REGISTERED_BLE_TAG
    override val displayName = "Registered Team BLE Tracking Tag"
    override val integrationStatus = HardwareIntegrationStatus.AWAITING_HARDWARE_INTEGRATION
    override val capability = SensorCapability(
        sensorType = sensorType,
        supportsRange = true,
        supportsAzimuth = false,
        supportsElevation = false,
        supportsRespirationBpm = false,
        supportsAbsoluteCoordinates = false,
        nominalMaxRangeMeters = 50.0,
        notes = "Associates ONLY explicitly pre-registered team member BLE beacons; never invents GATT UUIDs.",
    )
    override val protocolRequirement = "Requires registered beacon profile & vendor GATT UUID specification."
    override fun diagnosticSummary(): String = "$integrationStatus — $protocolRequirement"
}

class UwbRangingTagAdapter : SensorAdapter {
    override val adapterId = "adapter_uwb_ranging_tag"
    override val sensorType = SensorType.UWB_RANGING_TAG
    override val displayName = "Precision UWB Peer Ranging Tag"
    override val integrationStatus = HardwareIntegrationStatus.AWAITING_HARDWARE_INTEGRATION
    override val capability = SensorCapability(
        sensorType = sensorType,
        supportsRange = true,
        supportsAzimuth = true,
        supportsElevation = false,
        supportsRespirationBpm = false,
        supportsAbsoluteCoordinates = false,
        nominalMaxRangeMeters = 80.0,
        notes = "High-precision short-range peer distance/AoA when GPS is degraded indoors.",
    )
    override val protocolRequirement = "Requires Android UWB Jetpack session profile & UWB peer responder hardware."
    override fun diagnosticSummary(): String = "$integrationStatus — $protocolRequirement"
}

object SensorArchitectureCatalog {
    private val adapters: List<SensorAdapter> = listOf(
        UwbLifeDetectionRadarAdapter(),
        FmcwOpenAreaRadarAdapter(),
        ThermalImagingSensorAdapter(),
        AcousticVibrationSensorAdapter(),
        RegisteredBleTagAdapter(),
        UwbRangingTagAdapter(),
    )

    fun descriptors(): List<SensorAdapterDescriptor> = adapters.map {
        SensorAdapterDescriptor(
            adapterId = it.adapterId,
            sensorType = it.sensorType,
            displayName = it.displayName,
            integrationStatus = it.integrationStatus,
            capability = it.capability,
            protocolRequirement = it.protocolRequirement,
            diagnosticMessage = it.diagnosticSummary(),
        )
    }
}
