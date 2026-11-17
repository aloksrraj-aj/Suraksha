package com.example.data.hardware

import com.example.data.model.SensorDataValidity
import com.example.data.model.SensorDevice
import com.example.data.model.SensorEvent
import com.example.data.model.SensorType
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * Deterministic validator and parser for Suraksha SensorEvent Schema v1.0.0.
 * Protects against malformed JSON, replay duplicates, future clock skew, invalid geometry,
 * stale timestamps, low confidence, and non-humanitarian labels.
 */
class SensorEventV1Parser(
    private val staleThresholdMillis: Long = 180_000L,
    private val maxFutureSkewMillis: Long = 60_000L,
) : SensorDataParser {

    private val seenEventIds: MutableSet<String> =
        Collections.synchronizedSet(object : LinkedHashSet<String>() {})

    override fun parseSingleJsonEvent(
        rawJson: String,
        orgId: String,
        missionId: String,
        operatorUid: String,
        nowMillis: Long,
    ): Result<SensorEvent> = runCatching {
        val json = JSONObject(rawJson)
        parseJsonObject(json, orgId, missionId, operatorUid, nowMillis)
    }

    override fun parseBatchJsonEvents(
        rawJson: String,
        orgId: String,
        missionId: String,
        operatorUid: String,
        nowMillis: Long,
    ): List<Result<SensorEvent>> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()
        return if (trimmed.startsWith("[")) {
            val arr = runCatching { JSONArray(trimmed) }.getOrElse {
                return listOf(Result.failure(it))
            }
            (0 until arr.length()).map { idx ->
                runCatching {
                    parseJsonObject(arr.getJSONObject(idx), orgId, missionId, operatorUid, nowMillis)
                }
            }
        } else {
            val root = runCatching { JSONObject(trimmed) }.getOrElse {
                return listOf(Result.failure(it))
            }
            val arr = root.optJSONArray("events")
            if (arr != null) {
                (0 until arr.length()).map { idx ->
                    runCatching {
                        parseJsonObject(arr.getJSONObject(idx), orgId, missionId, operatorUid, nowMillis)
                    }
                }
            } else {
                listOf(runCatching { parseJsonObject(root, orgId, missionId, operatorUid, nowMillis) })
            }
        }
    }

    fun clearReplayCache() {
        seenEventIds.clear()
    }

    private fun parseJsonObject(
        obj: JSONObject,
        orgId: String,
        missionId: String,
        operatorUid: String,
        nowMillis: Long,
    ): SensorEvent {
        val schemaVersion = obj.optString("schemaVersion", "")
        require(schemaVersion == "1.0.0") { "Unsupported schemaVersion '$schemaVersion' (expected 1.0.0)" }

        val eventId = obj.optString("eventId", "").trim()
        require(eventId.length >= 3) { "Missing or invalid eventId" }
        require(seenEventIds.add("${missionId}_$eventId")) { "Duplicate replay eventId ignored: $eventId" }
        if (seenEventIds.size > 2000) {
            seenEventIds.clear()
            seenEventIds.add("${missionId}_$eventId")
        }

        val sensorId = obj.optString("sensorId", "").trim()
        require(sensorId.length >= 2) { "Missing or invalid sensorId" }

        val rawType = obj.optString("sensorType", "").trim()
        require(SensorType.entries.any { it.value == rawType }) { "Unsupported sensorType '$rawType'" }

        val timestampUtcMillis = obj.optLong("timestampUtcMillis", -1L)
        require(timestampUtcMillis > 0L) { "Invalid timestampUtcMillis" }
        require(timestampUtcMillis <= nowMillis + maxFutureSkewMillis) {
            "Event timestamp exceeds maximum future clock skew allowance"
        }

        val rawDetectionType = obj.optString("detectionType", SensorEvent.UNCLASSIFIED_PRESENCE_LABEL).trim()
        require(rawDetectionType.isNotEmpty()) { "Missing detectionType" }
        val normalizedDetectionType = sanitizeDetectionLabel(rawDetectionType)

        require(obj.has("confidence") && !obj.isNull("confidence")) { "Missing confidence" }
        val confidence = obj.getDouble("confidence")
        require(!confidence.isNaN() && confidence in 0.0..1.0) { "confidence out of range [0.0, 1.0]" }

        val signalQuality = obj.optString("signalQuality", "unusable").trim().lowercase()
        require(signalQuality in setOf("good", "fair", "poor", "unusable")) {
            "Invalid signalQuality '$signalQuality'"
        }

        val sourceDevice = obj.optString("sourceDevice", "").trim()
        require(sourceDevice.isNotEmpty()) { "Missing sourceDevice" }

        val rangeMeters = obj.optNullableDouble("estimatedRangeMeters")?.also {
            require(!it.isNaN() && it in 0.0..5000.0) { "estimatedRangeMeters out of bounds [0, 5000]" }
        }
        val azimuthDegrees = obj.optNullableDouble("estimatedAzimuthDegrees")?.also {
            require(!it.isNaN() && it in 0.0..360.0) { "estimatedAzimuthDegrees out of bounds [0, 360]" }
        }
        val elevationDegrees = obj.optNullableDouble("estimatedElevationDegrees")?.also {
            require(!it.isNaN() && it in -90.0..90.0) { "estimatedElevationDegrees out of bounds [-90, 90]" }
        }
        val estLat = obj.optNullableDouble("estimatedLatitude")?.also {
            require(!it.isNaN() && it in -90.0..90.0) { "estimatedLatitude out of bounds [-90, 90]" }
        }
        val estLon = obj.optNullableDouble("estimatedLongitude")?.also {
            require(!it.isNaN() && it in -180.0..180.0) { "estimatedLongitude out of bounds [-180, 180]" }
        }
        val posAcc = obj.optNullableDouble("positionAccuracyMeters")?.also {
            require(!it.isNaN() && it >= 0.0) { "positionAccuracyMeters must be non-negative" }
        }
        val durationSec = obj.optNullableDouble("detectionDurationSeconds")
        val respirationBpm = obj.optNullableDouble("respirationRateBpm")

        val isStale = (nowMillis - timestampUtcMillis) > staleThresholdMillis
        val isLowConf = confidence < 0.40 || signalQuality == "poor" || signalQuality == "unusable"
        val hasCoordinates = estLat != null && estLon != null
        val isMissingGeo = (rangeMeters == null || azimuthDegrees == null) && !hasCoordinates

        val dataValidity = when {
            isStale -> SensorDataValidity.STALE.value
            isLowConf -> SensorDataValidity.LOW_CONFIDENCE.value
            isMissingGeo -> SensorDataValidity.UNCERTAIN.value
            else -> SensorDataValidity.VALID.value
        }

        return SensorEvent(
            eventId = eventId,
            orgId = orgId,
            missionId = missionId,
            sensorId = sensorId,
            sensorType = rawType,
            timestampUtcMillis = timestampUtcMillis,
            detectionType = normalizedDetectionType,
            estimatedRangeMeters = rangeMeters,
            estimatedAzimuthDegrees = azimuthDegrees,
            estimatedElevationDegrees = elevationDegrees,
            estimatedLatitude = estLat,
            estimatedLongitude = estLon,
            positionAccuracyMeters = posAcc,
            confidence = confidence,
            signalQuality = signalQuality,
            detectionDurationSeconds = durationSec,
            respirationRateBpm = respirationBpm,
            sourceDevice = sourceDevice,
            calibrationVersion = obj.optString("calibrationVersion", "unknown"),
            rawDataReference = obj.optString("rawDataReference", ""),
            dataValidity = dataValidity,
            status = if (isStale) "stale" else "active",
            operatorUid = operatorUid,
        )
    }

    companion object {
        private val PROHIBITED_LABELS = listOf("hostile", "enemy", "threat", "target", "weapon", "combatant")

        fun sanitizeDetectionLabel(raw: String): String {
            val lower = raw.lowercase()
            return if (PROHIBITED_LABELS.any { lower.contains(it) }) {
                SensorEvent.UNCLASSIFIED_PRESENCE_LABEL
            } else {
                raw
            }
        }

        private fun JSONObject.optNullableDouble(key: String): Double? {
            if (!has(key) || isNull(key)) return null
            val v = optDouble(key, Double.NaN)
            return if (v.isNaN()) null else v
        }
    }
}

/**
 * Strictly isolated development/testing fixture provider.
 * NEVER writes to production Firestore mission collections and is only surfaced when Dev Demo Mode is explicitly toggled ON.
 */
object DevDemoFixtureProvider {
    fun buildIsolatedFixtureDevices(orgId: String, missionId: String): List<SensorDevice> = listOf(
        SensorDevice(
            sensorId = "demo_uwb_wall_01",
            orgId = orgId,
            missionId = missionId,
            sensorType = SensorType.UWB_LIFE_DETECTION_RADAR.value,
            displayName = "[DEMO FIXTURE] UWB Rubble Radar",
            manufacturerModel = "Test Fixture UWB-01",
            transportType = "gateway_http_tls",
            connectionStatus = "connected",
            hasPosition = true,
            latitude = 30.5562,
            longitude = 79.5662,
            orientationDegrees = 40.0,
            sectorWidthDegrees = 110.0,
            maxRangeMeters = 30.0,
            calibrationVersion = "fixture-1.0",
            lastCalibrationMillis = System.currentTimeMillis(),
            operatorUid = "dev_demo_fixture",
        ),
        SensorDevice(
            sensorId = "demo_fmcw_open_02",
            orgId = orgId,
            missionId = missionId,
            sensorType = SensorType.FMCW_RADAR.value,
            displayName = "[DEMO FIXTURE] FMCW Open-Area Radar",
            manufacturerModel = "Test Fixture FMCW-02",
            transportType = "gateway_ws_tls",
            connectionStatus = "connected",
            hasPosition = true,
            latitude = 30.5562,
            longitude = 79.5662,
            orientationDegrees = 210.0,
            sectorWidthDegrees = 360.0,
            maxRangeMeters = 200.0,
            calibrationVersion = "fixture-1.0",
            lastCalibrationMillis = System.currentTimeMillis(),
            operatorUid = "dev_demo_fixture",
        ),
    )

    fun buildIsolatedFixtureEvents(
        orgId: String,
        missionId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<SensorEvent> = listOf(
        SensorEvent(
            eventId = "demo_evt_uwb_1",
            orgId = orgId,
            missionId = missionId,
            sensorId = "demo_uwb_wall_01",
            sensorType = SensorType.UWB_LIFE_DETECTION_RADAR.value,
            timestampUtcMillis = nowMillis - 4_000L,
            detectionType = SensorEvent.UNCLASSIFIED_PRESENCE_LABEL,
            estimatedRangeMeters = 11.5,
            estimatedAzimuthDegrees = 42.0,
            estimatedElevationDegrees = -1.5,
            confidence = 0.84,
            signalQuality = "good",
            detectionDurationSeconds = 18.0,
            respirationRateBpm = 16.2,
            sourceDevice = "DEV DEMO FIXTURE • UWB-01",
            calibrationVersion = "fixture-1.0",
            rawDataReference = "fixture://uwb/01",
            dataValidity = SensorDataValidity.VALID.value,
            status = "active",
            operatorUid = "dev_demo_fixture",
        ),
        SensorEvent(
            eventId = "demo_evt_fmcw_2",
            orgId = orgId,
            missionId = missionId,
            sensorId = "demo_fmcw_open_02",
            sensorType = SensorType.FMCW_RADAR.value,
            timestampUtcMillis = nowMillis - 8_000L,
            detectionType = SensorEvent.UNCLASSIFIED_PRESENCE_LABEL,
            estimatedRangeMeters = 74.0,
            estimatedAzimuthDegrees = 228.0,
            confidence = 0.68,
            signalQuality = "fair",
            detectionDurationSeconds = 9.0,
            sourceDevice = "DEV DEMO FIXTURE • FMCW-02",
            calibrationVersion = "fixture-1.0",
            rawDataReference = "fixture://fmcw/02",
            dataValidity = SensorDataValidity.VALID.value,
            status = "active",
            operatorUid = "dev_demo_fixture",
        ),
        SensorEvent(
            eventId = "demo_evt_lowconf_3",
            orgId = orgId,
            missionId = missionId,
            sensorId = "demo_uwb_wall_01",
            sensorType = SensorType.ACOUSTIC_VIBRATION.value,
            timestampUtcMillis = nowMillis - 12_000L,
            detectionType = SensorEvent.UNCLASSIFIED_PRESENCE_LABEL,
            estimatedRangeMeters = 26.0,
            estimatedAzimuthDegrees = null, // Deliberately missing azimuth -> renders GREY Uncertain
            confidence = 0.34,
            signalQuality = "poor",
            detectionDurationSeconds = 3.0,
            sourceDevice = "DEV DEMO FIXTURE • Acoustic-03",
            calibrationVersion = "fixture-1.0",
            rawDataReference = "fixture://acoustic/03",
            dataValidity = SensorDataValidity.LOW_CONFIDENCE.value,
            status = "active",
            operatorUid = "dev_demo_fixture",
        ),
    )
}
