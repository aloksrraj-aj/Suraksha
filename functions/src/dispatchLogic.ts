export interface OrgMemberRecord {
  userId: string;
  role: string;
  teamId?: string;
  availability?: string;
}

export interface DeviceTokenRecord {
  userId: string;
  deviceId: string;
  fcmToken: string;
  orgId: string;
  notificationsEnabled: boolean;
}

export function buildDispatchIdempotencyKey(
  eventType: "sos_create" | "sos_update" | "sos_escalate",
  incidentId: string,
  qualifier: string = "initial"
): string {
  const safeQualifier = qualifier.replace(/[^a-zA-Z0-9_-]/g, "_").slice(0, 40);
  return `${eventType}_${incidentId}_${safeQualifier}`;
}

export function filterEligibleRecipients(
  members: OrgMemberRecord[],
  senderUid: string,
  priority: string,
  escalationOnly: boolean = false
): OrgMemberRecord[] {
  const allowedRoles = escalationOnly
    ? new Set(["org_admin", "team_leader"])
    : new Set(["org_admin", "team_leader", "operator", "medic"]);

  return members.filter((member) => {
    if (!allowedRoles.has(member.role)) return false;
    if (!escalationOnly && priority !== "sos" && member.userId === senderUid) {
      return false;
    }
    return member.availability !== "offline" || priority === "sos";
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
      deliveryNote: "Saved to Firestore; 0 active FCM device tokens registered in org.",
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
