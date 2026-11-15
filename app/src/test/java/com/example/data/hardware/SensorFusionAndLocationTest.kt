package com.example.data.hardware

import com.example.data.location.LocationFixState
import com.example.data.location.SurakshaLocationBus
import com.example.data.location.SurakshaLocationController
import com.google.android.gms.location.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SensorFusionAndLocationTest {

    @Test
    fun sensorFusionProcessor_enforcesValidationDedupStaleAndSimulatedInvariant() {
        val now = 1_750_000_000_000L
        val packets = listOf(
            // Invalid packet (blank deviceId) -> rejected
            SensorTelemetryPacket(
                packetId = "bad_1",
                deviceId = "",
                sensorType = "thermal_ir",
                sourceKind = "hardware",
                sectorLabel = "Sector A",
                latitude = 30.5,
                longitude = 79.5,
                hasCoordinates = true,
                estimatedMinCount = 1,
                estimatedMaxCount = 2,
                confidencePercent = 80,
                calibrationStatus = "calibrated",
                sensorHealth = "healthy",
                observationTimestampMillis = now,
                dataSource = "Gateway",
                rawPayloadJson = "{}",
            ),
            // Fresh simulated packet with 99% confidence -> MUST remain estimated & synthetic
            SensorTelemetryPacket(
                packetId = "sim_fresh",
                deviceId = "sim-node-1",
                sensorType = "thermal_ir",
                sourceKind = "simulated",
                sectorLabel = "Rubble Wing A",
                latitude = 0.0,
                longitude = 0.0,
                hasCoordinates = false,
                estimatedMinCount = 1,
                estimatedMaxCount = 2,
                confidencePercent = 99,
                calibrationStatus = "calibrated",
                sensorHealth = "healthy",
                observationTimestampMillis = now - 5_000L,
                dataSource = "Sim Node",
                rawPayloadJson = "{}",
            ),
            // Duplicate within 30s window -> deduplicated
            SensorTelemetryPacket(
                packetId = "sim_dup",
                deviceId = "sim-node-1",
                sensorType = "thermal_ir",
                sourceKind = "simulated",
                sectorLabel = "Rubble Wing A",
                latitude = 0.0,
                longitude = 0.0,
                hasCoordinates = false,
                estimatedMinCount = 1,
                estimatedMaxCount = 2,
                confidencePercent = 95,
                calibrationStatus = "calibrated",
                sensorHealth = "healthy",
                observationTimestampMillis = now - 15_000L,
                dataSource = "Sim Node",
                rawPayloadJson = "{}",
            ),
            // Stale hardware packet (>120s old) -> marked outdated, degraded, confidence capped <= 35
            SensorTelemetryPacket(
                packetId = "hw_stale",
                deviceId = "hw-uwb-1",
                sensorType = "radar_respiration",
                sourceKind = "hardware",
                sectorLabel = "Collapsed Basement",
                latitude = 30.55,
                longitude = 79.56,
                hasCoordinates = true,
                estimatedMinCount = 1,
                estimatedMaxCount = 1,
                confidencePercent = 88,
                calibrationStatus = "calibrated",
                sensorHealth = "healthy",
                observationTimestampMillis = now - 180_000L,
                dataSource = "UWB Edge Gateway",
                rawPayloadJson = "{}",
            ),
            // Fresh uncalibrated hardware packet -> unverified (never field_verified)
            SensorTelemetryPacket(
                packetId = "hw_unverified",
                deviceId = "hw-co2-2",
                sensorType = "co2_plume",
                sourceKind = "hardware",
                sectorLabel = "Stairwell B",
                latitude = 30.56,
                longitude = 79.57,
                hasCoordinates = true,
                estimatedMinCount = 0,
                estimatedMaxCount = 2,
                confidencePercent = 64,
                calibrationStatus = "needs_calibration",
                sensorHealth = "healthy",
                observationTimestampMillis = now - 2_000L,
                dataSource = "BME680 Hardware",
                rawPayloadJson = "{}",
            ),
        )

        val fused = SensorFusionProcessor.processPackets(
            packets = packets,
            orgId = "org_1",
            userId = "uid_1",
            nowMillis = now,
        )

        assertEquals(1, fused.rejectedPacketsCount)
        assertEquals(1, fused.deduplicatedCount)
        assertEquals(1, fused.staleCount)
        assertEquals(3, fused.signals.size)

        val simOut = fused.signals.first { it.id == "sim_fresh" }
        assertTrue(simOut.isSynthetic)
        assertEquals("simulated", simOut.sourceKind)
        assertEquals("estimated", simOut.verificationStatus)

        val staleOut = fused.signals.first { it.id == "hw_stale" }
        assertFalse(staleOut.isSynthetic)
        assertEquals("outdated", staleOut.verificationStatus)
        assertEquals("degraded", staleOut.sensorHealth)
        assertTrue(staleOut.confidencePercent <= 35L)

        val hwOut = fused.signals.first { it.id == "hw_unverified" }
        assertEquals("unverified", hwOut.verificationStatus)
        assertFalse(fused.signals.any { it.verificationStatus == "field_verified" })
    }

    @Test
    fun wifiHttpGatewayAdapter_parsesJsonAndRejectsMalformed() {
        val validJson = """
            {
              "packets": [
                {
                  "packetId": "edge_101",
                  "deviceId": "jetson-01",
                  "signalType": "thermal_ir",
                  "sectorLabel": "Sector C3",
                  "estimatedMinCount": 1,
                  "estimatedMaxCount": 3,
                  "confidencePercent": 78,
                  "calibrationStatus": "calibrated",
                  "sensorHealth": "healthy",
                  "dataSource": "Jetson FastAPI"
                }
              ]
            }
        """.trimIndent()
        val parsed = WifiHttpGatewayAdapter.parseGatewayPayloadJson(validJson)
        assertTrue(parsed.isSuccess)
        val packet = parsed.getOrThrow().single()
        assertEquals("hardware", packet.sourceKind)
        assertEquals("Sector C3", packet.sectorLabel)
        assertFalse(packet.hasCoordinates)

        val bad = WifiHttpGatewayAdapter.parseGatewayPayloadJson("{not_valid_json")
        assertTrue(bad.isFailure)
    }

    @Test
    fun locationController_batteryAwarePoliciesAndFreshnessLabels_accurate() {
        val high = SurakshaLocationController.computeBatteryAwarePolicy(85)
        assertEquals(10_000L, high.first)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, high.second)

        val med = SurakshaLocationController.computeBatteryAwarePolicy(32)
        assertEquals(25_000L, med.first)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, med.second)

        val low = SurakshaLocationController.computeBatteryAwarePolicy(12)
        assertEquals(60_000L, low.first)
        assertEquals(Priority.PRIORITY_LOW_POWER, low.second)

        SurakshaLocationBus.emit(LocationFixState.PermissionDenied())
        assertTrue(SurakshaLocationBus.freshnessLabel().contains("Permission denied"))

        val now = 1_750_000_000_000L
        SurakshaLocationBus.emit(
            LocationFixState.Available(
                latitude = 30.5506,
                longitude = 79.5660,
                accuracyMeters = 8.4f,
                speedMps = 1.2f,
                timestampMillis = now - 10_000L,
                intervalMillis = 10_000L,
                batteryPercent = 80,
            )
        )
        val liveLabel = SurakshaLocationBus.freshnessLabel(now)
        assertTrue(liveLabel.startsWith("Live (10s ago"))

        SurakshaLocationBus.emit(LocationFixState.OptedOut())
        assertTrue(SurakshaLocationBus.freshnessLabel(now).contains("Opted out"))
    }

    @Test
    fun slamExportFormatter_includesExplicitSimulationDisclaimer() {
        val frame = SensorArchitectureCatalog.generateSlamFrame(EnvScenario.COLLAPSED_URBAN, 0.75f)
        val json = SlamExportFormatter.toJson(frame)
        val csv = SlamExportFormatter.toCsv(frame)
        assertTrue(json.contains("\"isSimulated\": true"))
        assertTrue(csv.contains("SYNTHETIC SLAM EXPORT"))
    }
}
