const { test } = require("node:test");
const assert = require("node:assert/strict");

// Pure JS contract verification tests aligned with functions/src/dispatchLogic.ts

function filterEligibleRecipients(members, blockedUids, senderUid, priority, escalationOnly = false) {
  const allowedRoles = escalationOnly
    ? new Set(["ORGANIZATION_ADMIN", "TEAM_ADMIN"])
    : new Set(["ORGANIZATION_ADMIN", "TEAM_ADMIN", "TEAM_MEMBER", "SENSOR_OPERATOR"]);

  return members.filter((member) => {
    if (blockedUids.has(member.userId)) return false;
    if (member.status && member.status !== "ACTIVE") return false;
    if (!allowedRoles.has(member.role)) return false;
    if (!escalationOnly && priority !== "critical" && member.userId === senderUid) return false;
    return member.availability !== "offline" || priority === "critical";
  });
}

function validateSensorEventV1(raw, nowMillis) {
  if (!raw || typeof raw !== "object") return { valid: false, reason: "Not object" };
  if (raw.schemaVersion !== "1.0.0") return { valid: false, reason: "Bad version" };
  if (!raw.eventId || !raw.sensorId) return { valid: false, reason: "Missing IDs" };
  if (typeof raw.confidence !== "number" || raw.confidence < 0 || raw.confidence > 1) return { valid: false, reason: "Bad confidence" };
  if (typeof raw.timestampUtcMillis !== "number" || raw.timestampUtcMillis > nowMillis + 60000) return { valid: false, reason: "Future timestamp" };
  let dataValidity = "valid";
  if (nowMillis - raw.timestampUtcMillis > 180000) dataValidity = "stale";
  else if (raw.confidence < 0.4 || raw.signalQuality === "poor" || raw.signalQuality === "unusable") dataValidity = "low_confidence";
  else if (raw.estimatedRangeMeters == null && (raw.estimatedLatitude == null || raw.estimatedLongitude == null)) dataValidity = "uncertain";
  return { valid: true, dataValidity };
}

test("dispatchLogic filters out REVOKED and blocked members and deduplicates tokens", () => {
  const members = [
    { userId: "u_admin", role: "ORGANIZATION_ADMIN", status: "ACTIVE", availability: "available" },
    { userId: "u_revoked", role: "TEAM_MEMBER", status: "REVOKED", availability: "available" },
    { userId: "u_blocked", role: "SENSOR_OPERATOR", status: "ACTIVE", availability: "available" },
    { userId: "u_sender", role: "TEAM_MEMBER", status: "ACTIVE", availability: "available" },
    { userId: "u_offline", role: "TEAM_MEMBER", status: "ACTIVE", availability: "offline" },
  ];
  const blocked = new Set(["u_blocked"]);

  const criticalRecipients = filterEligibleRecipients(members, blocked, "u_sender", "critical", false);
  assert.deepEqual(
    criticalRecipients.map((m) => m.userId),
    ["u_admin", "u_sender", "u_offline"]
  );

  const escalatedRecipients = filterEligibleRecipients(members, blocked, "u_sender", "critical", true);
  assert.deepEqual(
    escalatedRecipients.map((m) => m.userId),
    ["u_admin"]
  );

  const rawTokens = [
    { userId: "u1", deviceId: "d1", fcmToken: "token_alpha_123456", orgId: "org1", notificationsEnabled: true },
    { userId: "u1", deviceId: "d2", fcmToken: "token_alpha_123456", orgId: "org1", notificationsEnabled: true },
    { userId: "u2", deviceId: "d3", fcmToken: "token_disabled_7890", orgId: "org1", notificationsEnabled: false },
    { userId: "u3", deviceId: "d4", fcmToken: "token_other_org_999", orgId: "org2", notificationsEnabled: true },
    { userId: "u4", deviceId: "d5", fcmToken: "token_bravo_987654", orgId: "org1", notificationsEnabled: true },
  ];

  const dedup = Array.from(
    new Set(
      rawTokens
        .filter((d) => d.notificationsEnabled && d.fcmToken.length >= 10 && (!d.orgId || d.orgId === "org1"))
        .map((d) => d.fcmToken)
    )
  );
  assert.deepEqual(dedup, ["token_alpha_123456", "token_bravo_987654"]);
});

test("SensorEvent v1 schema validator handles valid, stale, low-confidence, missing-range, and future events", () => {
  const now = 1_750_000_000_000;
  assert.equal(validateSensorEventV1({
    schemaVersion: "1.0.0",
    eventId: "evt_1",
    sensorId: "uwb_01",
    confidence: 0.85,
    signalQuality: "good",
    timestampUtcMillis: now - 5000,
    estimatedRangeMeters: 12.4,
    estimatedAzimuthDegrees: 42.0,
  }, now).dataValidity, "valid");

  assert.equal(validateSensorEventV1({
    schemaVersion: "1.0.0",
    eventId: "evt_stale",
    sensorId: "uwb_01",
    confidence: 0.85,
    signalQuality: "good",
    timestampUtcMillis: now - 240_000,
    estimatedRangeMeters: 12.4,
  }, now).dataValidity, "stale");

  assert.equal(validateSensorEventV1({
    schemaVersion: "1.0.0",
    eventId: "evt_low",
    sensorId: "uwb_01",
    confidence: 0.22,
    signalQuality: "poor",
    timestampUtcMillis: now - 1000,
    estimatedRangeMeters: 12.4,
  }, now).dataValidity, "low_confidence");

  assert.equal(validateSensorEventV1({
    schemaVersion: "1.0.0",
    eventId: "evt_missing_range",
    sensorId: "uwb_01",
    confidence: 0.75,
    signalQuality: "fair",
    timestampUtcMillis: now - 1000,
  }, now).dataValidity, "uncertain");

  assert.equal(validateSensorEventV1({
    schemaVersion: "1.0.0",
    eventId: "evt_future",
    sensorId: "uwb_01",
    confidence: 0.75,
    signalQuality: "good",
    timestampUtcMillis: now + 300_000,
  }, now).valid, false);
});
