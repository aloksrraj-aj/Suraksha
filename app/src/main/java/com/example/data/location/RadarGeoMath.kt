package com.example.data.location

import com.example.data.model.MemberLocation
import com.example.data.model.RadarCategory
import com.example.data.model.RadarTarget
import com.example.data.model.SensorDataValidity
import com.example.data.model.SensorDevice
import com.example.data.model.SensorEvent
import com.example.data.model.SeparationWarningItem
import com.example.data.model.SosAlert
import com.example.data.model.SosAlertStatus
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object RadarGeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    const val LOCATION_STALE_MS = 120_000L
    const val SENSOR_STALE_MS = 180_000L
    const val MAX_SEPARATION_ACCURACY_METERS = 65.0

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2.0).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2.0).pow(2)
        val c = 2.0 * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
        return EARTH_RADIUS_METERS * c
    }

    fun initialBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        val deg = Math.toDegrees(atan2(y, x))
        return ((deg % 360.0) + 360.0) % 360.0
    }

    fun relativeBearingDegrees(trueBearing: Double, headingDegrees: Double, northUp: Boolean): Double {
        if (northUp) return ((trueBearing % 360.0) + 360.0) % 360.0
        return (((trueBearing - headingDegrees) % 360.0) + 360.0) % 360.0
    }

    /**
     * Projects range & azimuth from a known sensor station latitude/longitude into WGS84 coordinates.
     * Returns null if range or azimuth is absent or invalid.
     */
    fun projectFromStation(
        stationLat: Double,
        stationLon: Double,
        rangeMeters: Double?,
        azimuthDegrees: Double?,
    ): Pair<Double, Double>? {
        if (rangeMeters == null || azimuthDegrees == null) return null
        if (rangeMeters < 0.0 || rangeMeters > 5000.0) return null
        val angDist = rangeMeters / EARTH_RADIUS_METERS
        val brng = Math.toRadians(azimuthDegrees)
        val lat1 = Math.toRadians(stationLat)
        val lon1 = Math.toRadians(stationLon)
        val lat2 = asin(sin(lat1) * cos(angDist) + cos(lat1) * sin(angDist) * cos(brng))
        val lon2 = lon1 + atan2(
            sin(brng) * sin(angDist) * cos(lat1),
            cos(angDist) - sin(lat1) * sin(lat2),
        )
        return Math.toDegrees(lat2) to Math.toDegrees(lon2)
    }

    fun buildRadarTargets(
        selfUid: String,
        selfLat: Double?,
        selfLon: Double?,
        members: List<MemberLocation>,
        sensorDevices: List<SensorDevice>,
        sensorEvents: List<SensorEvent>,
        sosAlerts: List<SosAlert>,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<RadarTarget> {
        val out = mutableListOf<RadarTarget>()
        val openAlertSenders = sosAlerts
            .filter { SosAlertStatus.fromValue(it.status).isOpen() }
            .associateBy { it.senderUid }

        // 1. Open SOS & Separation Alerts -> RED
        sosAlerts.filter { SosAlertStatus.fromValue(it.status).isOpen() }.forEach { alert ->
            val dist = if (selfLat != null && selfLon != null && alert.hasLocation) {
                distanceMeters(selfLat, selfLon, alert.latitude, alert.longitude)
            } else null
            val brng = if (selfLat != null && selfLon != null && alert.hasLocation) {
                initialBearingDegrees(selfLat, selfLon, alert.latitude, alert.longitude)
            } else null
            out += RadarTarget(
                id = "sos_${alert.alertId}",
                category = RadarCategory.RED_EMERGENCY_ALERT,
                title = if (alert.alertType == "separation_alert") "SEPARATION: ${alert.senderName}" else "SOS: ${alert.senderName}",
                subtitle = alert.description,
                distanceMeters = dist,
                bearingDegrees = brng,
                hasDirection = brng != null && dist != null,
                confidence = 1.0,
                timestampMillis = alert.locationTimestampMillis,
                validityNote = if (alert.hasLocation) "Emergency location attached" else "No GPS fix on alert",
                sourceAlert = alert,
            )
        }

        // 2. Verified Registered Rescue Team Members -> GREEN (or GREY if stale/unavailable)
        members.filter { it.userId != selfUid }.forEach { member ->
            if (openAlertSenders.containsKey(member.userId)) return@forEach // Shown in RED above
            val ageMs = (nowMillis - member.locationTimestampMillis).coerceAtLeast(0L)
            val isStaleOrOffline = !member.hasValidLocation ||
                !member.sharingEnabled ||
                member.connectionStatus in setOf("offline", "stale", "gps_disabled", "permission_denied", "paused") ||
                ageMs > LOCATION_STALE_MS

            val dist = if (selfLat != null && selfLon != null && member.hasValidLocation) {
                distanceMeters(selfLat, selfLon, member.latitude, member.longitude)
            } else null
            val brng = if (selfLat != null && selfLon != null && member.hasValidLocation) {
                initialBearingDegrees(selfLat, selfLon, member.latitude, member.longitude)
            } else null

            out += RadarTarget(
                id = "member_${member.userId}",
                category = if (isStaleOrOffline) RadarCategory.GREY_STALE_OR_UNCERTAIN else RadarCategory.GREEN_TEAM_MEMBER,
                title = "${member.callsign.ifBlank { member.displayName }} (${member.displayName})",
                subtitle = if (isStaleOrOffline) {
                    member.unavailableReason.ifBlank { "Stale (${ageMs / 1000L}s ago)" }
                } else {
                    "Verified Team Member • ±${member.accuracyMeters.toInt()}m"
                },
                distanceMeters = dist,
                bearingDegrees = brng,
                hasDirection = dist != null && brng != null && !isStaleOrOffline,
                timestampMillis = member.locationTimestampMillis,
                validityNote = if (isStaleOrOffline) "Stale/Unavailable" else "Live",
                sourceMember = member,
            )
        }

        // 3. Unclassified Sensor Detections -> YELLOW (or GREY if stale / low confidence / missing geometry)
        val deviceMap = sensorDevices.associateBy { it.sensorId }
        sensorEvents.filter { it.status != "cleared" }.forEach { ev ->
            val ageMs = (nowMillis - ev.timestampUtcMillis).coerceAtLeast(0L)
            val isStale = ageMs > SENSOR_STALE_MS || ev.dataValidity == SensorDataValidity.STALE.value || ev.status == "stale"
            val isLowConf = ev.confidence < 0.40 || ev.signalQuality in setOf("poor", "unusable") || ev.dataValidity == SensorDataValidity.LOW_CONFIDENCE.value
            val station = deviceMap[ev.sensorId]

            var dist = ev.estimatedRangeMeters
            var brng = ev.estimatedAzimuthDegrees
            if ((dist == null || brng == null) && selfLat != null && selfLon != null && ev.estimatedLatitude != null && ev.estimatedLongitude != null) {
                dist = distanceMeters(selfLat, selfLon, ev.estimatedLatitude, ev.estimatedLongitude)
                brng = initialBearingDegrees(selfLat, selfLon, ev.estimatedLatitude, ev.estimatedLongitude)
            } else if ((ev.estimatedLatitude == null || ev.estimatedLongitude == null) && station != null && station.hasPosition && selfLat != null && selfLon != null && dist != null && brng != null) {
                val projected = projectFromStation(station.latitude, station.longitude, dist, brng)
                if (projected != null) {
                    dist = distanceMeters(selfLat, selfLon, projected.first, projected.second)
                    brng = initialBearingDegrees(selfLat, selfLon, projected.first, projected.second)
                }
            }

            val isUncertain = dist == null || brng == null || ev.dataValidity == SensorDataValidity.UNCERTAIN.value
            val category = if (isStale || isLowConf || isUncertain) {
                RadarCategory.GREY_STALE_OR_UNCERTAIN
            } else {
                RadarCategory.YELLOW_UNCLASSIFIED_PRESENCE
            }

            val note = buildString {
                if (isStale) append("Stale (${ageMs / 1000L}s ago) ")
                if (isLowConf) append("Low confidence (${(ev.confidence * 100).toInt()}%) ")
                if (brng == null) append("Missing bearing ")
                if (dist == null) append("Missing range ")
                if (isEmpty()) append("Valid external sensor reading")
            }.trim()

            out += RadarTarget(
                id = "event_${ev.eventId}",
                category = category,
                title = SensorEvent.UNCLASSIFIED_PRESENCE_LABEL,
                subtitle = "${ev.sourceDevice} • ${(ev.confidence * 100).toInt()}% (${ev.signalQuality})",
                distanceMeters = dist,
                bearingDegrees = brng,
                hasDirection = dist != null && brng != null,
                confidence = ev.confidence,
                timestampMillis = ev.timestampUtcMillis,
                validityNote = note,
                sourceEvent = ev,
            )
        }

        return out
    }
}

/**
 * Evaluates responder separation distance against threshold with jitter/noise protection:
 * - Ignores offline, stale, or inaccurate (>65m) GPS fixes
 * - Requires consecutive out-of-range evaluations AND elapsed breach delay before raising warning
 */
class SeparationAlertEvaluator {
    private data class BreachTracker(
        val firstBreachMillis: Long,
        val consecutiveCount: Int,
    )

    private val trackers = mutableMapOf<String, BreachTracker>()

    fun evaluate(
        leaderOrCenterLat: Double,
        leaderOrCenterLon: Double,
        members: List<MemberLocation>,
        thresholdMeters: Double,
        delaySeconds: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<SeparationWarningItem> {
        val activeWarnings = mutableListOf<SeparationWarningItem>()
        val seenUids = mutableSetOf<String>()

        for (member in members) {
            seenUids.add(member.userId)
            val ageMs = (nowMillis - member.locationTimestampMillis).coerceAtLeast(0L)
            val usableFix = member.sharingEnabled &&
                member.hasValidLocation &&
                member.connectionStatus == "online" &&
                ageMs <= RadarGeoMath.LOCATION_STALE_MS &&
                member.accuracyMeters <= RadarGeoMath.MAX_SEPARATION_ACCURACY_METERS

            if (!usableFix) {
                trackers.remove(member.userId)
                continue
            }

            val distMeters = RadarGeoMath.distanceMeters(
                leaderOrCenterLat,
                leaderOrCenterLon,
                member.latitude,
                member.longitude,
            )

            if (distMeters > thresholdMeters) {
                val prev = trackers[member.userId]
                val updated = if (prev == null) {
                    BreachTracker(firstBreachMillis = nowMillis, consecutiveCount = 1)
                } else {
                    prev.copy(consecutiveCount = prev.consecutiveCount + 1)
                }
                trackers[member.userId] = updated
                val elapsedSec = ((nowMillis - updated.firstBreachMillis) / 1000L).coerceAtLeast(0L)
                if (updated.consecutiveCount >= 2 && elapsedSec >= delaySeconds) {
                    activeWarnings += SeparationWarningItem(
                        memberUid = member.userId,
                        memberName = member.displayName,
                        callsign = member.callsign,
                        distanceMeters = distMeters,
                        thresholdMeters = thresholdMeters,
                        continuousBreachSeconds = elapsedSec,
                        accuracyMeters = member.accuracyMeters,
                    )
                }
            } else {
                trackers.remove(member.userId)
            }
        }

        trackers.keys.retainAll(seenUids)
        return activeWarnings
    }

    fun reset() {
        trackers.clear()
    }
}
