package com.example.data.hardware

import android.location.Location
import com.example.data.location.CompassHeadingProvider
import com.example.data.location.RadarGeoMath
import com.example.data.location.SeparationAlertEvaluator
import com.example.data.location.SurakshaLocationController
import com.example.data.model.MemberLocation
import com.example.data.model.RadarCategory
import com.example.data.model.SensorDataValidity
import com.example.data.model.SensorEvent
import com.example.data.model.SosAlert
import com.example.data.model.SosAlertStatus
import com.google.android.gms.location.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SensorAndRadarEngineTest {

    @Test
    fun sensorEventV1Parser_validatesSchemaDedupesStaleLowConfUncertainAndLabels() {
        val parser = SensorEventV1Parser()
        val now = 1_750_000_000_000L

        val validJson = """
            {
              "schemaVersion": "1.0.0",
              "eventId": "evt_uwb_1001",
              "sensorId": "uwb_01",
              "sensorType": "uwb_life_detection_radar",
              "timestampUtcMillis": ${now - 5_000L},
              "detectionType": "UNCLASSIFIED POSSIBLE HUMAN PRESENCE",
              "estimatedRangeMeters": 14.5,
              "estimatedAzimuthDegrees": 72.0,
              "confidence": 0.86,
              "signalQuality": "good",
              "sourceDevice": "RubbleProbe UWB-1",
              "calibrationVersion": "cal-2.1"
            }
        """.trimIndent()

        val valid = parser.parseSingleJsonEvent(validJson, "org_1", "mission_1", "op_1", now)
        assertTrue(valid.isSuccess)
        val ev = valid.getOrThrow()
        assertEquals(SensorDataValidity.VALID.value, ev.dataValidity)
        assertEquals(SensorEvent.UNCLASSIFIED_PRESENCE_LABEL, ev.detectionType)
        assertEquals(14.5, ev.estimatedRangeMeters ?: 0.0, 0.01)

        // Replay duplicate rejected
        val replay = parser.parseSingleJsonEvent(validJson, "org_1", "mission_1", "op_1", now)
        assertTrue(replay.isFailure)

        // Malformed / future skew > 60s / out-of-range latitude rejected
        val badFutureJson = validJson
            .replace("evt_uwb_1001", "evt_future")
            .replace("${now - 5_000L}", "${now + 120_000L}")
        assertTrue(parser.parseSingleJsonEvent(badFutureJson, "org_1", "mission_1", "op_1", now).isFailure)

        val badLatJson = """
            {
              "schemaVersion": "1.0.0",
              "eventId": "evt_bad_lat",
              "sensorId": "uwb_01",
              "sensorType": "fmcw_radar",
              "timestampUtcMillis": ${now - 2_000L},
              "detectionType": "hostile target spotted",
              "estimatedLatitude": 99.5,
              "estimatedLongitude": 77.0,
              "confidence": 0.75,
              "signalQuality": "good",
              "sourceDevice": "FMCW-1"
            }
        """.trimIndent()
        assertTrue(parser.parseSingleJsonEvent(badLatJson, "org_1", "mission_1", "op_1", now).isFailure)

        // Batch parse: missing range/azimuth -> UNCERTAIN, low conf -> LOW_CONFIDENCE, stale -> STALE, prohibited label -> sanitized
        val batchJson = """
            {
              "events": [
                {
                  "schemaVersion": "1.0.0",
                  "eventId": "evt_uncertain",
                  "sensorId": "acoustic_01",
                  "sensorType": "acoustic_vibration",
                  "timestampUtcMillis": ${now - 4_000L},
                  "detectionType": "enemy combatant",
                  "estimatedRangeMeters": 18.0,
                  "confidence": 0.72,
                  "signalQuality": "fair",
                  "sourceDevice": "Acoustic-01"
                },
                {
                  "schemaVersion": "1.0.0",
                  "eventId": "evt_lowconf",
                  "sensorId": "thermal_01",
                  "sensorType": "thermal_imaging",
                  "timestampUtcMillis": ${now - 4_000L},
                  "detectionType": "thermal signature",
                  "estimatedRangeMeters": 25.0,
                  "estimatedAzimuthDegrees": 110.0,
                  "confidence": 0.25,
                  "signalQuality": "poor",
                  "sourceDevice": "Thermal-01"
                },
                {
                  "schemaVersion": "1.0.0",
                  "eventId": "evt_stale",
                  "sensorId": "fmcw_01",
                  "sensorType": "fmcw_radar",
                  "timestampUtcMillis": ${now - 250_000L},
                  "detectionType": "UNCLASSIFIED POSSIBLE HUMAN PRESENCE",
                  "estimatedRangeMeters": 40.0,
                  "estimatedAzimuthDegrees": 180.0,
                  "confidence": 0.90,
                  "signalQuality": "good",
                  "sourceDevice": "FMCW-01"
                }
              ]
            }
        """.trimIndent()

        val parsed = parser.parseBatchJsonEvents(batchJson, "org_1", "mission_1", "op_1", now)
        assertEquals(3, parsed.size)
        val e0 = parsed[0].getOrThrow()
        assertEquals(SensorDataValidity.UNCERTAIN.value, e0.dataValidity)
        assertEquals(SensorEvent.UNCLASSIFIED_PRESENCE_LABEL, e0.detectionType)

        val e1 = parsed[1].getOrThrow()
        assertEquals(SensorDataValidity.LOW_CONFIDENCE.value, e1.dataValidity)

        val e2 = parsed[2].getOrThrow()
        assertEquals(SensorDataValidity.STALE.value, e2.dataValidity)
        assertEquals("stale", e2.status)
    }

    @Test
    fun devDemoFixtures_areDistinctAndNonEmptyWhenRequestedInMemoryOnly() {
        val devs = DevDemoFixtureProvider.buildIsolatedFixtureDevices("org_demo", "mission_demo")
        val events = DevDemoFixtureProvider.buildIsolatedFixtureEvents("org_demo", "mission_demo", 1_750_000_000_000L)
        assertEquals(2, devs.size)
        assertEquals(3, events.size)
        assertTrue(events.all { it.operatorUid == "dev_demo_fixture" })
        assertTrue(events.all { it.detectionType == SensorEvent.UNCLASSIFIED_PRESENCE_LABEL })
    }

    @Test
    fun radarGeoMath_projectionDistanceBearingAndFourColorClassification() {
        val now = 1_750_000_000_000L
        val selfLat = 30.5506
        val selfLon = 79.5660

        val projected = RadarGeoMath.projectFromStation(selfLat, selfLon, 100.0, 90.0)
        assertNotNull(projected)
        val dist = RadarGeoMath.distanceMeters(selfLat, selfLon, projected!!.first, projected.second)
        assertEquals(100.0, dist, 1.0)
        val brng = RadarGeoMath.initialBearingDegrees(selfLat, selfLon, projected.first, projected.second)
        assertEquals(90.0, brng, 1.0)
        assertEquals(45.0, RadarGeoMath.relativeBearingDegrees(90.0, 45.0, northUp = false), 0.1)
        assertEquals(90.0, RadarGeoMath.relativeBearingDegrees(90.0, 45.0, northUp = true), 0.1)
        assertNull(RadarGeoMath.projectFromStation(selfLat, selfLon, null, 90.0))

        val teammate = MemberLocation(
            userId = "u2",
            orgId = "org_1",
            missionId = "m1",
            displayName = "Ravi",
            callsign = "ALP-2",
            sharingEnabled = true,
            hasValidLocation = true,
            latitude = projected.first,
            longitude = projected.second,
            accuracyMeters = 8.0,
            connectionStatus = "online",
            locationTimestampMillis = now - 3_000L,
        )
        val validPresence = SensorEvent(
            eventId = "ev_valid",
            orgId = "org_1",
            missionId = "m1",
            sensorId = "s1",
            timestampUtcMillis = now - 5_000L,
            estimatedRangeMeters = 25.0,
            estimatedAzimuthDegrees = 180.0,
            confidence = 0.85,
            signalQuality = "good",
            sourceDevice = "UWB-1",
            dataValidity = SensorDataValidity.VALID.value,
        )
        val uncertainPresence = SensorEvent(
            eventId = "ev_grey",
            orgId = "org_1",
            missionId = "m1",
            sensorId = "s1",
            timestampUtcMillis = now - 5_000L,
            estimatedRangeMeters = 30.0,
            estimatedAzimuthDegrees = null,
            confidence = 0.30,
            signalQuality = "poor",
            sourceDevice = "Acoustic-1",
            dataValidity = SensorDataValidity.LOW_CONFIDENCE.value,
        )
        val openSos = SosAlert(
            alertId = "sos_1",
            orgId = "org_1",
            missionId = "m1",
            senderUid = "u3",
            senderName = "Neha",
            status = SosAlertStatus.ACTIVE.value,
            hasLocation = true,
            latitude = selfLat + 0.001,
            longitude = selfLon,
            locationTimestampMillis = now - 2_000L,
            description = "Fallen beam",
        )

        val targets = RadarGeoMath.buildRadarTargets(
            selfUid = "u1",
            selfLat = selfLat,
            selfLon = selfLon,
            members = listOf(teammate),
            sensorDevices = emptyList(),
            sensorEvents = listOf(validPresence, uncertainPresence),
            sosAlerts = listOf(openSos),
            nowMillis = now,
        )

        assertEquals(RadarCategory.RED_EMERGENCY_ALERT, targets.first { it.id == "sos_sos_1" }.category)
        assertEquals(RadarCategory.GREEN_TEAM_MEMBER, targets.first { it.id == "member_u2" }.category)
        assertEquals(RadarCategory.YELLOW_UNCLASSIFIED_PRESENCE, targets.first { it.id == "event_ev_valid" }.category)
        assertEquals(RadarCategory.GREY_STALE_OR_UNCERTAIN, targets.first { it.id == "event_ev_grey" }.category)
    }

    @Test
    fun separationAlertEvaluator_appliesConsecutiveBreachDelayAndIgnoresInaccurateFixes() {
        val evaluator = SeparationAlertEvaluator()
        val now = 1_750_000_000_000L
        val centerLat = 30.5506
        val centerLon = 79.5660
        // ~222m north of center
        val farMember = MemberLocation(
            userId = "u_far",
            orgId = "org_1",
            missionId = "m1",
            displayName = "Far Responder",
            callsign = "FAR-1",
            sharingEnabled = true,
            hasValidLocation = true,
            latitude = centerLat + 0.002,
            longitude = centerLon,
            accuracyMeters = 12.0,
            connectionStatus = "online",
            locationTimestampMillis = now - 2_000L,
        )
        val noisyMember = farMember.copy(userId = "u_noisy", accuracyMeters = 120.0)

        // First evaluation -> 1st breach -> no warning yet
        assertTrue(evaluator.evaluate(centerLat, centerLon, listOf(farMember, noisyMember), 150.0, 20L, now).isEmpty())
        // 10s later -> 2nd breach, elapsed 10s < 20s delay -> still empty
        assertTrue(evaluator.evaluate(centerLat, centerLon, listOf(farMember, noisyMember), 150.0, 20L, now + 10_000L).isEmpty())
        // 25s later -> 3rd breach, elapsed 25s >= 20s delay -> warns ONLY u_far (ignores >65m inaccurate fix)
        val warnings = evaluator.evaluate(centerLat, centerLon, listOf(farMember, noisyMember), 150.0, 20L, now + 25_000L)
        assertEquals(1, warnings.size)
        assertEquals("u_far", warnings.first().memberUid)

        // Returning inside threshold clears tracker
        val backInside = farMember.copy(latitude = centerLat + 0.0002, locationTimestampMillis = now + 30_000L)
        assertTrue(evaluator.evaluate(centerLat, centerLon, listOf(backInside), 150.0, 20L, now + 30_000L).isEmpty())
    }

    @Test
    fun compassSmoothingAndLocationControllerPolicies_handleWraparoundAndAccuracy() {
        // Wrap-around from 355° toward 5° moves clockwise across 0°, not backward through 180°
        val smoothed = CompassHeadingProvider.smoothAngleDegrees(prev = 355.0, next = 5.0, alpha = 0.5)
        assertEquals(0.0, smoothed, 0.5)

        assertEquals(10_000L to Priority.PRIORITY_HIGH_ACCURACY, SurakshaLocationController.computeBatteryAwarePolicy(80))
        assertEquals(25_000L to Priority.PRIORITY_BALANCED_POWER_ACCURACY, SurakshaLocationController.computeBatteryAwarePolicy(35))
        assertEquals(60_000L to Priority.PRIORITY_LOW_POWER, SurakshaLocationController.computeBatteryAwarePolicy(12))

        val now = 1_750_000_000_000L
        val goodLoc = Location("gps").apply {
            latitude = 30.5506
            longitude = 79.5660
            accuracy = 14f
            time = now - 1_000L
        }
        val poorLoc = Location("gps").apply {
            latitude = 30.5506
            longitude = 79.5660
            accuracy = 450f
            time = now - 1_000L
        }
        assertTrue(SurakshaLocationController.shouldAcceptFix(goodLoc, null, now))
        assertFalse(SurakshaLocationController.shouldAcceptFix(poorLoc, null, now))
    }
}
