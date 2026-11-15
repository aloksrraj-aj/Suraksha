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
} from "./dispatchLogic";

initializeApp();

const DATABASE_ID = "ai-studio-android-suraksha-e0c70b4c-78bb-40fd-a0e7-58c89adbeb5c";
const db = getFirestore(DATABASE_ID);

async function dispatchSosPush(params: {
  orgId: string;
  incidentId: string;
  dispatchKey: string;
  eventKind: "sos_create" | "sos_update" | "sos_escalate";
  priority: string;
  senderUid: string;
  senderName: string;
  teamCode: string;
  description: string;
  status: string;
  hasLocation: boolean;
  latitude: number;
  longitude: number;
  escalationOnly?: boolean;
}) {
  const orgRef = db.collection("organizations").doc(params.orgId);
  const dispatchRef = orgRef.collection("notification_dispatches").doc(params.dispatchKey);

  // 1. Idempotency transaction: ensure duplicate Cloud Function retries never spam responders
  const claimed = await db.runTransaction(async (tx) => {
    const snap = await tx.get(dispatchRef);
    if (snap.exists) {
      return false;
    }
    tx.set(dispatchRef, {
      dispatchKey: params.dispatchKey,
      orgId: params.orgId,
      incidentId: params.incidentId,
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
    // 2. Load authorized org members
    const memberSnap = await orgRef.collection("members").get();
    const members: OrgMemberRecord[] = memberSnap.docs.map((d) => ({
      userId: String(d.get("userId") || d.id),
      role: String(d.get("role") || "observer"),
      teamId: String(d.get("teamId") || ""),
      availability: String(d.get("availability") || "available"),
    }));

    const eligible = filterEligibleRecipients(
      members,
      params.senderUid,
      params.priority,
      Boolean(params.escalationOnly)
    );

    // 3. Fetch registered FCM device tokens for eligible members
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
        : params.priority === "sos"
          ? "CRITICAL SOS ALERT"
          : `URGENT DISPATCH (${params.teamCode})`;

      const locationText = params.hasLocation
        ? `Location: ${params.latitude.toFixed(4)}, ${params.longitude.toFixed(4)}`
        : "GPS unavailable";

      const response = await getMessaging().sendEachForMulticast({
        tokens,
        notification: {
          title: `${titlePrefix} — ${params.teamCode}`,
          body: `${params.description.slice(0, 120)} (${locationText})`,
        },
        data: {
          orgId: params.orgId,
          incidentId: params.incidentId,
          priority: params.priority,
          status: params.status,
          teamCode: params.teamCode,
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

      // Prune stale invalid FCM tokens
      for (let i = 0; i < response.responses.length; i++) {
        const res = response.responses[i];
        if (
          !res.success &&
          res.error?.code === "messaging/registration-token-not-registered"
        ) {
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

    // 4. Optional SMS webhook bridge (only when explicitly configured via env)
    let smsDispatched = false;
    const smsWebhookUrl = process.env.SURAKSHA_SMS_WEBHOOK_URL;
    if (smsWebhookUrl && params.priority === "sos") {
      try {
        const resp = await fetch(smsWebhookUrl, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Authorization": `Bearer ${process.env.SURAKSHA_SMS_WEBHOOK_TOKEN || ""}`,
          },
          body: JSON.stringify({
            orgId: params.orgId,
            incidentId: params.incidentId,
            teamCode: params.teamCode,
            priority: params.priority,
            summary: params.description.slice(0, 140),
          }),
        });
        smsDispatched = resp.ok;
      } catch (smsErr) {
        logger.warn("Optional SMS gateway call failed", smsErr);
      }
    }

    const summary = summarizeNotificationStatus(tokens.length, successCount, failureCount);
    await dispatchRef.update({
      status: summary.notificationStatus,
      targetTokenCount: tokens.length,
      successCount,
      failureCount,
      smsDispatched,
      completedAt: FieldValue.serverTimestamp(),
    });

    await orgRef.collection("sos_incidents").doc(params.incidentId).update({
      notificationStatus: smsDispatched && successCount === 0 ? "sms_dispatched" : summary.notificationStatus,
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

export const onSosIncidentCreated = onDocumentCreated(
  {
    document: "organizations/{orgId}/sos_incidents/{incidentId}",
    database: DATABASE_ID,
    region: "asia-south1",
  },
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    const data = snap.data();
    await dispatchSosPush({
      orgId: event.params.orgId,
      incidentId: event.params.incidentId,
      dispatchKey: buildDispatchIdempotencyKey("sos_create", event.params.incidentId, "initial"),
      eventKind: "sos_create",
      priority: String(data.priority || "sos"),
      senderUid: String(data.senderUid || ""),
      senderName: String(data.senderName || "Responder"),
      teamCode: String(data.teamCode || "FIELD"),
      description: String(data.description || ""),
      status: String(data.status || "delivered"),
      hasLocation: Boolean(data.hasLocation),
      latitude: Number(data.latitude || 0),
      longitude: Number(data.longitude || 0),
    });
  }
);

export const onSosIncidentUpdated = onDocumentUpdated(
  {
    document: "organizations/{orgId}/sos_incidents/{incidentId}",
    database: DATABASE_ID,
    region: "asia-south1",
  },
  async (event) => {
    const before = event.data?.before.data();
    const after = event.data?.after.data();
    if (!before || !after) return;

    const statusChanged = before.status !== after.status;
    const teamChanged = before.assignedTeamCode !== after.assignedTeamCode;
    if (!statusChanged && !teamChanged) return;
    if (!["acknowledged", "team_assigned", "responding", "resolved"].includes(String(after.status))) return;

    await dispatchSosPush({
      orgId: event.params.orgId,
      incidentId: event.params.incidentId,
      dispatchKey: buildDispatchIdempotencyKey(
        "sos_update",
        event.params.incidentId,
        `${after.status}_${after.assignedTeamCode || "none"}`
      ),
      eventKind: "sos_update",
      priority: String(after.priority || "urgent"),
      senderUid: String(after.acknowledgedByUid || after.senderUid || ""),
      senderName: String(after.senderName || "Command"),
      teamCode: String(after.assignedTeamCode || after.teamCode || "FIELD"),
      description: `Status -> ${after.status}: ${String(after.deliveryNote || after.description || "")}`,
      status: String(after.status),
      hasLocation: Boolean(after.hasLocation),
      latitude: Number(after.latitude || 0),
      longitude: Number(after.longitude || 0),
    });
  }
);

export const escalateUnacknowledgedSos = onSchedule(
  {
    schedule: "every 5 minutes",
    region: "asia-south1",
  },
  async () => {
    const fiveMinutesAgo = new Date(Date.now() - 5 * 60 * 1000);
    const orgsSnap = await db.collection("organizations").limit(100).get();
    for (const orgDoc of orgsSnap.docs) {
      const staleSos = await orgDoc.ref
        .collection("sos_incidents")
        .where("status", "in", ["created", "pending_delivery", "delivered"])
        .where("createdAt", "<=", fiveMinutesAgo)
        .limit(20)
        .get();

      for (const sosDoc of staleSos.docs) {
        const d = sosDoc.data();
        await dispatchSosPush({
          orgId: orgDoc.id,
          incidentId: sosDoc.id,
          dispatchKey: buildDispatchIdempotencyKey("sos_escalate", sosDoc.id, "5min"),
          eventKind: "sos_escalate",
          priority: String(d.priority || "sos"),
          senderUid: String(d.senderUid || ""),
          senderName: String(d.senderName || "Responder"),
          teamCode: String(d.teamCode || "FIELD"),
          description: `UNACKNOWLEDGED >5m: ${String(d.description || "")}`,
          status: String(d.status || "delivered"),
          hasLocation: Boolean(d.hasLocation),
          latitude: Number(d.latitude || 0),
          longitude: Number(d.longitude || 0),
          escalationOnly: true,
        });
      }
    }
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
      if (!orgId) {
        res.status(400).json({ error: "orgId query parameter is required." });
        return;
      }
      const memberSnap = await db
        .collection("organizations")
        .doc(orgId)
        .collection("members")
        .doc(decoded.uid)
        .get();
      if (!memberSnap.exists) {
        res.status(403).json({ error: "Forbidden: caller is not an active member of this organization." });
        return;
      }
      const openSos = await db
        .collection("organizations")
        .doc(orgId)
        .collection("sos_incidents")
        .where("status", "in", ["created", "pending_delivery", "delivered", "acknowledged", "team_assigned", "responding"])
        .get();
      res.status(200).json({
        orgId,
        callerUid: decoded.uid,
        role: memberSnap.get("role"),
        activeSosCount: openSos.size,
        timestampMillis: Date.now(),
      });
    } catch (err) {
      res.status(401).json({ error: "Invalid Firebase ID token." });
    }
  }
);
