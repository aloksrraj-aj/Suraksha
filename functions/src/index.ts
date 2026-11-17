import * as crypto from "node:crypto";
import { initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { getAuth } from "firebase-admin/auth";
import {
  onDocumentCreated,
  onDocumentUpdated,
} from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { onRequest } from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import {
  buildDispatchIdempotencyKey,
  collectTargetFcmTokens,
  DeviceTokenRecord,
  filterEligibleRecipients,
  OrgMemberRecord,
  summarizeNotificationStatus,
  validateSensorEventV1Payload,
} from "./dispatchLogic";

initializeApp();

const DATABASE_ID = "ai-studio-android-suraksha-e0c70b4c-78bb-40fd-a0e7-58c89adbeb5c";
const db = getFirestore(DATABASE_ID);

async function dispatchMissionSosPush(params: {
  orgId: string;
  missionId: string;
  alertId: string;
  dispatchKey: string;
  eventKind: "sos_create" | "sos_update" | "sos_escalate";
  alertType: string;
  priority: string;
  senderUid: string;
  senderName: string;
  teamId: string;
  description: string;
  status: string;
  hasLocation: boolean;
  latitude: number;
  longitude: number;
  escalationOnly?: boolean;
}) {
  const orgRef = db.collection("organizations").doc(params.orgId);
  const missionRef = orgRef.collection("missions").doc(params.missionId);
  const dispatchRef = missionRef.collection("notificationDispatches").doc(params.dispatchKey);

  const claimed = await db.runTransaction(async (tx) => {
    const snap = await tx.get(dispatchRef);
    if (snap.exists) {
      return false;
    }
    tx.set(dispatchRef, {
      dispatchKey: params.dispatchKey,
      orgId: params.orgId,
      missionId: params.missionId,
      alertId: params.alertId,
      eventKind: params.eventKind,
      status: "dispatching",
      createdAt: FieldValue.serverTimestamp(),
    });
    return true;
  });

  if (!claimed) {
    logger.info("Duplicate dispatch skipped", { dispatchKey: params.dispatchKey });
    return;
  }

  try {
    const [memberSnap, blockedOrgSnap, blockedMissionSnap] = await Promise.all([
      orgRef.collection("members").get(),
      orgRef.collection("blockedMemberships").get(),
      missionRef.collection("blockedMemberships").get(),
    ]);

    const blockedUids = new Set<string>([
      ...blockedOrgSnap.docs.map((d) => d.id),
      ...blockedMissionSnap.docs.map((d) => d.id),
    ]);

    const members: OrgMemberRecord[] = memberSnap.docs.map((d) => ({
      userId: String(d.get("userId") || d.id),
      role: String(d.get("role") || "TEAM_MEMBER"),
      teamId: String(d.get("teamId") || ""),
      status: String(d.get("status") || "ACTIVE"),
      availability: String(d.get("availability") || "available"),
    }));

    const eligible = filterEligibleRecipients(
      members,
      blockedUids,
      params.senderUid,
      params.priority,
      Boolean(params.escalationOnly)
    );

    const deviceRecords: DeviceTokenRecord[] = [];
    for (const member of eligible) {
      const devSnap = await db.collection("users").doc(member.userId).collection("devices").get();
      for (const devDoc of devSnap.docs) {
        deviceRecords.push({
          userId: member.userId,
          deviceId: devDoc.id,
          fcmToken: String(devDoc.get("fcmToken") || ""),
          orgId: String(devDoc.get("orgId") || ""),
          notificationsEnabled: Boolean(devDoc.get("notificationsEnabled")),
        });
      }
    }

    const tokens = collectTargetFcmTokens(deviceRecords, params.orgId);
    let successCount = 0;
    let failureCount = 0;

    if (tokens.length > 0) {
      const titlePrefix = params.escalationOnly
        ? "ESCALATION: Unacknowledged SOS"
        : params.alertType === "separation_alert"
          ? "TEAM SEPARATION WARNING"
          : "CRITICAL RESCUE SOS";

      const locationText = params.hasLocation
        ? `Location: ${params.latitude.toFixed(5)}, ${params.longitude.toFixed(5)}`
        : "GPS unavailable";

      const response = await getMessaging().sendEachForMulticast({
        tokens,
        notification: {
          title: `${titlePrefix} — ${params.senderName}`,
          body: `${params.description.slice(0, 120)} (${locationText})`,
        },
        data: {
          orgId: params.orgId,
          missionId: params.missionId,
          alertId: params.alertId,
          alertType: params.alertType,
          priority: params.priority,
          status: params.status,
          teamId: params.teamId,
          eventKind: params.eventKind,
        },
        android: {
          priority: "high",
          notification: {
            channelId: "suraksha_sos_channel",
            priority: "max",
          },
        },
      });

      successCount = response.successCount;
      failureCount = response.failureCount;

      for (let i = 0; i < response.responses.length; i++) {
        const res = response.responses[i];
        if (!res.success && res.error?.code === "messaging/registration-token-not-registered") {
          const badToken = tokens[i];
          const match = deviceRecords.find((r) => r.fcmToken === badToken);
          if (match) {
            await db
              .collection("users")
              .doc(match.userId)
              .collection("devices")
              .doc(match.deviceId)
              .delete()
              .catch(() => undefined);
          }
        }
      }
    }

    const summary = summarizeNotificationStatus(tokens.length, successCount, failureCount);
    await dispatchRef.update({
      status: summary.notificationStatus,
      targetTokenCount: tokens.length,
      successCount,
      failureCount,
      completedAt: FieldValue.serverTimestamp(),
    });

    await missionRef.collection("sosAlerts").doc(params.alertId).update({
      notificationStatus: summary.notificationStatus,
      pushAttemptedCount: FieldValue.increment(1),
      deliveryNote: summary.deliveryNote,
      updatedAt: FieldValue.serverTimestamp(),
    });
  } catch (err) {
    logger.error("SOS dispatch failed", err);
    await dispatchRef.update({
      status: "dispatch_error",
      errorMessage: err instanceof Error ? err.message.slice(0, 200) : "Unknown dispatch error",
      completedAt: FieldValue.serverTimestamp(),
    }).catch(() => undefined);
  }
}

export const onMissionSosCreated = onDocumentCreated(
  {
    document: "organizations/{orgId}/missions/{missionId}/sosAlerts/{alertId}",
    database: DATABASE_ID,
    region: "asia-south1",
  },
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    const data = snap.data();
    await dispatchMissionSosPush({
      orgId: event.params.orgId,
      missionId: event.params.missionId,
      alertId: event.params.alertId,
      dispatchKey: buildDispatchIdempotencyKey("sos_create", event.params.alertId, "initial"),
      eventKind: "sos_create",
      alertType: String(data.alertType || "sos"),
      priority: String(data.priority || "critical"),
      senderUid: String(data.senderUid || ""),
      senderName: String(data.senderName || "Responder"),
      teamId: String(data.teamId || ""),
      description: String(data.description || ""),
      status: String(data.status || "active"),
      hasLocation: Boolean(data.hasLocation),
      latitude: Number(data.latitude || 0),
      longitude: Number(data.longitude || 0),
    });
  }
);

export const onMissionSosUpdated = onDocumentUpdated(
  {
    document: "organizations/{orgId}/missions/{missionId}/sosAlerts/{alertId}",
    database: DATABASE_ID,
    region: "asia-south1",
  },
  async (event) => {
    const before = event.data?.before.data();
    const after = event.data?.after.data();
    if (!before || !after) return;
    if (before.status === after.status) return;
    if (!["acknowledged", "responding", "resolved", "cancelled"].includes(String(after.status))) return;

    await dispatchMissionSosPush({
      orgId: event.params.orgId,
      missionId: event.params.missionId,
      alertId: event.params.alertId,
      dispatchKey: buildDispatchIdempotencyKey("sos_update", event.params.alertId, String(after.status)),
      eventKind: "sos_update",
      alertType: String(after.alertType || "sos"),
      priority: String(after.priority || "urgent"),
      senderUid: String(after.acknowledgedByUid || after.cancelledByUid || after.senderUid || ""),
      senderName: String(after.senderName || "Responder"),
      teamId: String(after.teamId || ""),
      description: `Status -> ${after.status}: ${String(after.cancelReason || after.description || "")}`,
      status: String(after.status),
      hasLocation: Boolean(after.hasLocation),
      latitude: Number(after.latitude || 0),
      longitude: Number(after.longitude || 0),
    });
  }
);

export const escalateUnacknowledgedMissionSos = onSchedule(
  {
    schedule: "every 5 minutes",
    region: "asia-south1",
  },
  async () => {
    const fiveMinutesAgo = new Date(Date.now() - 5 * 60 * 1000);
    const orgsSnap = await db.collection("organizations").limit(50).get();
    for (const orgDoc of orgsSnap.docs) {
      const missionsSnap = await orgDoc.ref.collection("missions").where("status", "==", "active").limit(20).get();
      for (const missionDoc of missionsSnap.docs) {
        const staleAlerts = await missionDoc.ref
          .collection("sosAlerts")
          .where("status", "==", "active")
          .where("createdAt", "<=", fiveMinutesAgo)
          .limit(20)
          .get();

        for (const alertDoc of staleAlerts.docs) {
          const d = alertDoc.data();
          await dispatchMissionSosPush({
            orgId: orgDoc.id,
            missionId: missionDoc.id,
            alertId: alertDoc.id,
            dispatchKey: buildDispatchIdempotencyKey("sos_escalate", alertDoc.id, "5min"),
            eventKind: "sos_escalate",
            alertType: String(d.alertType || "sos"),
            priority: String(d.priority || "critical"),
            senderUid: String(d.senderUid || ""),
            senderName: String(d.senderName || "Responder"),
            teamId: String(d.teamId || ""),
            description: `UNACKNOWLEDGED >5m: ${String(d.description || "")}`,
            status: String(d.status || "active"),
            hasLocation: Boolean(d.hasLocation),
            latitude: Number(d.latitude || 0),
            longitude: Number(d.longitude || 0),
            escalationOnly: true,
          });
        }
      }
    }
  }
);

export const ingestGatewaySensorEvent = onRequest(
  {
    region: "asia-south1",
    cors: false,
  },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).json({ error: "Method not allowed; use POST." });
      return;
    }

    const gatewaySecret = process.env.SURAKSHA_GATEWAY_INGEST_SECRET || "";
    const authHeader = req.headers.authorization || "";
    const signatureHeader = String(req.headers["x-suraksha-signature"] || "");

    // Allow either authenticated org SENSOR_OPERATOR/ADMIN Firebase ID token OR valid gateway HMAC signature
    let operatorUid = "";
    let orgId = String(req.body?.orgId || "").trim();
    let missionId = String(req.body?.missionId || "").trim();

    if (!orgId || !missionId) {
      res.status(400).json({ error: "orgId and missionId are required." });
      return;
    }

    if (gatewaySecret && signatureHeader) {
      const expected = crypto
        .createHmac("sha256", gatewaySecret)
        .update(JSON.stringify(req.body))
        .digest("hex");
      const a = Buffer.from(signatureHeader, "utf8");
      const b = Buffer.from(expected, "utf8");
      if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
        res.status(401).json({ error: "Invalid gateway HMAC signature." });
        return;
      }
      operatorUid = String(req.body?.operatorUid || "edge_gateway");
    } else if (authHeader.startsWith("Bearer ")) {
      try {
        const decoded = await getAuth().verifyIdToken(authHeader.slice(7));
        const blockedSnap = await db.collection("organizations").doc(orgId).collection("blockedMemberships").doc(decoded.uid).get();
        const memberSnap = await db.collection("organizations").doc(orgId).collection("members").doc(decoded.uid).get();
        const role = String(memberSnap.get("role") || "");
        const status = String(memberSnap.get("status") || "");
        if (blockedSnap.exists || !memberSnap.exists || status !== "ACTIVE" || !["ORGANIZATION_ADMIN", "TEAM_ADMIN", "SENSOR_OPERATOR"].includes(role)) {
          res.status(403).json({ error: "Forbidden: caller lacks active sensor operator or admin permission." });
          return;
        }
        operatorUid = decoded.uid;
      } catch {
        res.status(401).json({ error: "Invalid Firebase ID token." });
        return;
      }
    } else {
      res.status(401).json({ error: "Missing Authorization Bearer token or gateway HMAC signature." });
      return;
    }

    const validation = validateSensorEventV1Payload(req.body?.event);
    if (!validation.valid || !validation.payload) {
      res.status(400).json({ error: validation.reason || "Invalid SensorEvent v1 schema." });
      return;
    }

    const payload = validation.payload;
    const eventDocRef = db
      .collection("organizations")
      .doc(orgId)
      .collection("missions")
      .doc(missionId)
      .collection("sensorEvents")
      .doc(payload.eventId);

    // Replay protection: check if eventId already stored
    const created = await db.runTransaction(async (tx) => {
      const existingSnap = await tx.get(eventDocRef);
      if (existingSnap.exists) {
        return false;
      }
      tx.set(eventDocRef, {
        eventId: payload.eventId,
        orgId,
        missionId,
        sensorId: payload.sensorId,
        sensorType: payload.sensorType,
        timestampUtcMillis: payload.timestampUtcMillis,
        detectionType: payload.detectionType,
        estimatedRangeMeters: payload.estimatedRangeMeters ?? null,
        estimatedAzimuthDegrees: payload.estimatedAzimuthDegrees ?? null,
        estimatedElevationDegrees: payload.estimatedElevationDegrees ?? null,
        estimatedLatitude: payload.estimatedLatitude ?? null,
        estimatedLongitude: payload.estimatedLongitude ?? null,
        positionAccuracyMeters: payload.positionAccuracyMeters ?? null,
        confidence: payload.confidence,
        signalQuality: payload.signalQuality,
        detectionDurationSeconds: payload.detectionDurationSeconds ?? null,
        respirationRateBpm: payload.respirationRateBpm ?? null,
        sourceDevice: payload.sourceDevice,
        calibrationVersion: payload.calibrationVersion,
        rawDataReference: payload.rawDataReference,
        dataValidity: validation.dataValidity || "valid",
        status: validation.dataValidity === "stale" ? "stale" : "active",
        operatorUid,
        createdAt: FieldValue.serverTimestamp(),
      });
      return true;
    });

    if (!created) {
      res.status(409).json({ status: "duplicate_ignored", eventId: payload.eventId });
      return;
    }

    res.status(201).json({
      status: "accepted",
      eventId: payload.eventId,
      dataValidity: validation.dataValidity,
    });
  }
);

export const commandCenterStatus = onRequest(
  {
    region: "asia-south1",
    cors: false,
  },
  async (req, res) => {
    const authHeader = req.headers.authorization || "";
    if (!authHeader.startsWith("Bearer ")) {
      res.status(401).json({ error: "Missing Firebase ID token." });
      return;
    }
    try {
      const decoded = await getAuth().verifyIdToken(authHeader.slice(7));
      const orgId = String(req.query.orgId || "").trim();
      const missionId = String(req.query.missionId || "").trim();
      if (!orgId || !missionId) {
        res.status(400).json({ error: "orgId and missionId query parameters are required." });
        return;
      }
      const blockedSnap = await db.collection("organizations").doc(orgId).collection("blockedMemberships").doc(decoded.uid).get();
      const memberSnap = await db.collection("organizations").doc(orgId).collection("members").doc(decoded.uid).get();
      if (blockedSnap.exists || !memberSnap.exists || memberSnap.get("status") !== "ACTIVE") {
        res.status(403).json({ error: "Forbidden: caller is blocked or not an active org member." });
        return;
      }
      const openSos = await db
        .collection("organizations")
        .doc(orgId)
        .collection("missions")
        .doc(missionId)
        .collection("sosAlerts")
        .where("status", "in", ["active", "acknowledged", "responding"])
        .get();
      res.status(200).json({
        orgId,
        missionId,
        callerUid: decoded.uid,
        role: memberSnap.get("role"),
        activeSosCount: openSos.size,
        timestampMillis: Date.now(),
      });
    } catch {
      res.status(401).json({ error: "Invalid Firebase ID token." });
    }
  }
);
