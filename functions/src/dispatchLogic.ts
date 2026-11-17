export interface OrgMemberRecord {
  userId: string;
  role: string;
  teamId?: string;
  status?: string;
  availability?: string;
}

export interface DeviceTokenRecord {
  userId: string;
  deviceId: string;
  fcmToken: string;
  orgId: string;
  notificationsEnabled: boolean;
}

export interface SensorEventV1Payload {
  schemaVersion: string;
  eventId: string;
  sensorId: string;
  sensorType: string;
  timestampUtcMillis: number;
  detectionType: string;
  confidence: number;
  signalQuality: string;
  sourceDevice: string;
  calibrationVersion: string;
  rawDataReference: string;
  estimatedRangeMeters?: number | null;
  estimatedAzimuthDegrees?: number | null;
  estimatedElevationDegrees?: number | null;
  estimatedLatitude?: number | null;
  estimatedLongitude?: number | null;
  positionAccuracyMeters?: number | null;
  detectionDurationSeconds?: number | null;
  respirationRateBpm?: number | null;
}

const VALID_SENSOR_TYPES = new Set([
  "uwb_life_detection_radar",
  "fmcw_radar",
  "thermal_imaging",
  "acoustic_vibration",
  "registered_ble_tag",
  "uwb_ranging_tag",
]);

const VALID_SIGNAL_QUALITY = new Set(["good", "fair", "poor", "unusable"]);

export function buildDispatchIdempotencyKey(
  eventType: "sos_create" | "sos_update" | "sos_escalate",
  alertId: string,
  qualifier: string = "initial"
): string {
  const safeQualifier = qualifier.replace(/[^a-zA-Z0-9_-]/g, "_").slice(0, 40);
  return `${eventType}_${alertId}_${safeQualifier}`;
}

export function filterEligibleRecipients(
  members: OrgMemberRecord[],
  blockedUids: Set<string>,
  senderUid: string,
  priority: string,
  escalationOnly: boolean = false
): OrgMemberRecord[] {
  const allowedRoles = escalationOnly
    ? new Set(["ORGANIZATION_ADMIN", "TEAM_ADMIN"])
    : new Set(["ORGANIZATION_ADMIN", "TEAM_ADMIN", "TEAM_MEMBER", "SENSOR_OPERATOR"]);

  return members.filter((member) => {
    if (blockedUids.has(member.userId)) return false;
    if (member.status && member.status !== "ACTIVE") return false;
    if (!allowedRoles.has(member.role)) return false;
    if (!escalationOnly && priority !== "critical" && member.userId === senderUid) {
      return false;
    }
    return member.availability !== "offline" || priority === "critical";
  });
}

export function collectTargetFcmTokens(
  devices: DeviceTokenRecord[],
  orgId: string
): string[] {
  const unique = new Set<string>();
  for (const dev of devices) {
    if (!dev.notificationsEnabled) continue;
    if (!dev.fcmToken || dev.fcmToken.length < 10) continue;
    if (dev.orgId && dev.orgId !== orgId) continue;
    unique.add(dev.fcmToken);
  }
  return Array.from(unique);
}

export function summarizeNotificationStatus(
  tokenCount: number,
  successCount: number,
  failureCount: number
): { notificationStatus: string; deliveryNote: string } {
  if (tokenCount === 0) {
    return {
      notificationStatus: "no_tokens",
      deliveryNote: "Saved to Firestore; 0 active FCM device tokens registered in mission/org.",
    };
  }
  if (successCount > 0) {
    return {
      notificationStatus: "fcm_accepted",
      deliveryNote: `FCM accepted for ${successCount}/${tokenCount} device(s); awaiting responder ACK.`,
    };
  }
  return {
    notificationStatus: "fcm_failed",
    deliveryNote: `FCM rejected all ${failureCount} token(s); verify device token freshness.`,
  };
}

export function validateSensorEventV1Payload(
  raw: unknown,
  nowMillis: number = Date.now(),
  maxFutureSkewMillis: number = 60_000,
  staleThresholdMillis: number = 180_000
): {
  valid: boolean;
  reason?: string;
  dataValidity?: "valid" | "low_confidence" | "stale" | "uncertain";
  payload?: SensorEventV1Payload;
} {
  if (!raw || typeof raw !== "object") {
    return { valid: false, reason: "Payload must be a JSON object." };
  }
  const obj = raw as Record<string, unknown>;
  if (obj.schemaVersion !== "1.0.0") {
    return { valid: false, reason: "Unsupported schemaVersion; expected 1.0.0." };
  }
  if (typeof obj.eventId !== "string" || obj.eventId.trim().length < 3) {
    return { valid: false, reason: "Invalid or missing eventId." };
  }
  if (typeof obj.sensorId !== "string" || obj.sensorId.trim().length < 2) {
    return { valid: false, reason: "Invalid or missing sensorId." };
  }
  if (typeof obj.sensorType !== "string" || !VALID_SENSOR_TYPES.has(obj.sensorType)) {
    return { valid: false, reason: "Unknown sensorType." };
  }
  if (typeof obj.timestampUtcMillis !== "number" || !Number.isFinite(obj.timestampUtcMillis) || obj.timestampUtcMillis <= 0) {
    return { valid: false, reason: "Invalid timestampUtcMillis." };
  }
  if (obj.timestampUtcMillis > nowMillis + maxFutureSkewMillis) {
    return { valid: false, reason: "Event timestamp exceeds future clock skew allowance." };
  }
  if (typeof obj.detectionType !== "string" || obj.detectionType.trim().length === 0) {
    return { valid: false, reason: "Missing detectionType." };
  }
  if (typeof obj.confidence !== "number" || !Number.isFinite(obj.confidence) || obj.confidence < 0 || obj.confidence > 1) {
    return { valid: false, reason: "confidence must be in [0.0, 1.0]." };
  }
  if (typeof obj.signalQuality !== "string" || !VALID_SIGNAL_QUALITY.has(obj.signalQuality)) {
    return { valid: false, reason: "Invalid signalQuality." };
  }
  if (typeof obj.sourceDevice !== "string" || obj.sourceDevice.trim().length === 0) {
    return { valid: false, reason: "Missing sourceDevice." };
  }
  const range = obj.estimatedRangeMeters;
  if (range !== undefined && range !== null && (typeof range !== "number" || range < 0 || range > 5000)) {
    return { valid: false, reason: "estimatedRangeMeters out of bounds [0, 5000]." };
  }
  const azimuth = obj.estimatedAzimuthDegrees;
  if (azimuth !== undefined && azimuth !== null && (typeof azimuth !== "number" || azimuth < 0 || azimuth > 360)) {
    return { valid: false, reason: "estimatedAzimuthDegrees out of bounds [0, 360]." };
  }
  const lat = obj.estimatedLatitude;
  const lon = obj.estimatedLongitude;
  if (lat !== undefined && lat !== null && (typeof lat !== "number" || lat < -90 || lat > 90)) {
    return { valid: false, reason: "estimatedLatitude out of bounds [-90, 90]." };
  }
  if (lon !== undefined && lon !== null && (typeof lon !== "number" || lon < -180 || lon > 180)) {
    return { valid: false, reason: "estimatedLongitude out of bounds [-180, 180]." };
  }

  let dataValidity: "valid" | "low_confidence" | "stale" | "uncertain" = "valid";
  if (nowMillis - obj.timestampUtcMillis > staleThresholdMillis) {
    dataValidity = "stale";
  } else if (obj.confidence < 0.4 || obj.signalQuality === "poor" || obj.signalQuality === "unusable") {
    dataValidity = "low_confidence";
  } else if ((range === undefined || range === null) && (lat === undefined || lat === null || lon === undefined || lon === null)) {
    dataValidity = "uncertain";
  }

  return {
    valid: true,
    dataValidity,
    payload: obj as unknown as SensorEventV1Payload,
  };
}
