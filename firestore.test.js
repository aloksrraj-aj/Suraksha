const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ADMIN_UID = "admin_100";
const TEAM_ADMIN_UID = "team_admin_200";
const SENSOR_OP_UID = "sensor_op_300";
const MEMBER_UID = "member_400";
const MEMBER_EMAIL = "member.responder@suraksha.org";
const BYPASS_UID = "member_bypass_401";
const OUTSIDER_UID = "outsider_999";
const ORG_ID = "org_ndrf_north";
const TEAM_ID = "team_alpha";
const MISSION_ID = "mission_joshimath_01";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function normalizeEmailKey(email) {
  return email.trim().toLowerCase().replace(/[^a-z0-9]/g, "_");
}

async function seedWorkspace() {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    const now = new Date();
    const futureExpiry = Date.now() + 24 * 3600 * 1000;
    const orgRef = db.collection("organizations").doc(ORG_ID);

    await orgRef.set({
      orgCode: "NDRF-N",
      orgName: "NDRF North Command",
      region: "Uttarakhand",
      allowMemberInvites: false,
      createdByUid: ADMIN_UID,
      createdAt: now,
      updatedAt: now,
    });

    const members = [
      [ADMIN_UID, "ORGANIZATION_ADMIN", "Cmdr. Admin", "ADM-01"],
      [TEAM_ADMIN_UID, "TEAM_ADMIN", "Lead Officer", "LDR-02"],
      [SENSOR_OP_UID, "SENSOR_OPERATOR", "Radar Tech", "SNS-03"],
      [MEMBER_UID, "TEAM_MEMBER", "Field Member", "MBR-04"],
    ];

    for (const [uid, role, name, callsign] of members) {
      await orgRef.collection("members").doc(uid).set({
        userId: uid,
        orgId: ORG_ID,
        displayName: name,
        callsign,
        photoUrl: "",
        role,
        teamId: TEAM_ID,
        status: "ACTIVE",
        availability: "available",
        inviteCode: "JOIN2026XK",
        revokedByUid: "",
        revokedAtMillis: 0,
        createdAt: now,
        updatedAt: now,
      });
    }

    await orgRef.collection("invitations").doc("JOIN2026XK").set({
      orgId: ORG_ID,
      inviteCode: "JOIN2026XK",
      assignedRole: "TEAM_MEMBER",
      targetTeamId: TEAM_ID,
      targetMissionId: MISSION_ID,
      targetEmail: "",
      status: "approved",
      active: true,
      expiresAtMillis: futureExpiry,
      maxUses: 10,
      usedCount: 0,
      createdByUid: ADMIN_UID,
      createdByRole: "ORGANIZATION_ADMIN",
      approvedByUid: ADMIN_UID,
      createdAt: now,
      updatedAt: now,
    });

    await orgRef.collection("invitations").doc("EXPIRED99").set({
      orgId: ORG_ID,
      inviteCode: "EXPIRED99",
      assignedRole: "TEAM_MEMBER",
      targetTeamId: TEAM_ID,
      targetMissionId: MISSION_ID,
      targetEmail: "",
      status: "approved",
      active: true,
      expiresAtMillis: Date.now() - 60_000,
      maxUses: 5,
      usedCount: 0,
      createdByUid: ADMIN_UID,
      createdByRole: "ORGANIZATION_ADMIN",
      approvedByUid: ADMIN_UID,
      createdAt: now,
      updatedAt: now,
    });

    await orgRef.collection("invitations").doc("MAXEDOUT1").set({
      orgId: ORG_ID,
      inviteCode: "MAXEDOUT1",
      assignedRole: "TEAM_MEMBER",
      targetTeamId: TEAM_ID,
      targetMissionId: MISSION_ID,
      targetEmail: "",
      status: "approved",
      active: true,
      expiresAtMillis: futureExpiry,
      maxUses: 1,
      usedCount: 1,
      createdByUid: ADMIN_UID,
      createdByRole: "ORGANIZATION_ADMIN",
      approvedByUid: ADMIN_UID,
      createdAt: now,
      updatedAt: now,
    });

    await orgRef.collection("teams").doc(TEAM_ID).set({
      orgId: ORG_ID,
      teamCode: "NDRF-A1",
      teamName: "Alpha Search Unit",
      teamAdminUid: TEAM_ADMIN_UID,
      allowMemberInvites: false,
      memberCount: 4,
      status: "deployed",
      createdByUid: ADMIN_UID,
      createdAt: now,
      updatedAt: now,
    });

    const missionRef = orgRef.collection("missions").doc(MISSION_ID);
    await missionRef.set({
      missionId: MISSION_ID,
      orgId: ORG_ID,
      teamId: TEAM_ID,
      teamCode: "NDRF-A1",
      title: "Sector 2 Collapsed Structure Search",
      description: "UWB radar perimeter sweep and responder tracking",
      environmentMode: "BUILDING_COLLAPSED",
      status: "active",
      separationAlertEnabled: true,
      separationThresholdMeters: 150,
      separationDelaySeconds: 20,
      centerLatitude: 30.556,
      centerLongitude: 79.566,
      leaderUid: TEAM_ADMIN_UID,
      createdByUid: ADMIN_UID,
      createdAt: now,
      updatedAt: now,
    });

    for (const [uid, role, name, callsign] of members) {
      await missionRef.collection("missionMembers").doc(uid).set({
        userId: uid,
        orgId: ORG_ID,
        missionId: MISSION_ID,
        teamId: TEAM_ID,
        displayName: name,
        callsign,
        photoUrl: "",
        role,
        status: "ACTIVE",
        sharingEnabled: true,
        createdAt: now,
        updatedAt: now,
      });
    }
  });
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) await testEnv.cleanup();
});

beforeEach(async () => {
  if (testEnv) await testEnv.clearFirestore();
});

test("1. Unauthenticated users and outsiders are denied across org and mission collections", async () => {
  await seedWorkspace();
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const outsiderDb = testEnv.authenticatedContext(OUTSIDER_UID).firestore();
  const missionRefUnauth = unauthDb.collection("organizations").doc(ORG_ID).collection("missions").doc(MISSION_ID);
  const missionRefOut = outsiderDb.collection("organizations").doc(ORG_ID).collection("missions").doc(MISSION_ID);

  await assertFails(unauthDb.collection("organizations").doc(ORG_ID).get());
  await assertFails(missionRefUnauth.collection("locations").get());
  await assertFails(missionRefUnauth.collection("sensorEvents").get());
  await assertFails(missionRefUnauth.collection("sosAlerts").get());

  await assertFails(outsiderDb.collection("organizations").doc(ORG_ID).get());
  await assertFails(missionRefOut.collection("locations").get());
  await assertFails(missionRefOut.collection("sensorEvents").get());
  await assertFails(missionRefOut.collection("sosAlerts").get());
});

test("2. RBAC prevents self-escalation, enforces allowMemberInvites toggle & admin invite approval", async () => {
  await seedWorkspace();
  const memberDb = testEnv.authenticatedContext(MEMBER_UID).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const outsiderDb = testEnv.authenticatedContext(OUTSIDER_UID, { email: "newjoiner@suraksha.org", email_verified: true }).firestore();
  const now = new Date();
  const orgRefMember = memberDb.collection("organizations").doc(ORG_ID);
  const orgRefAdmin = adminDb.collection("organizations").doc(ORG_ID);

  // Member cannot self-promote to ORGANIZATION_ADMIN
  await assertFails(orgRefMember.collection("members").doc(MEMBER_UID).update({ role: "ORGANIZATION_ADMIN", updatedAt: now }));

  // Member cannot create invites while org.allowMemberInvites == false
  await assertFails(
    orgRefMember.collection("invitations").doc("MEMBINV01").set({
      orgId: ORG_ID,
      inviteCode: "MEMBINV01",
      assignedRole: "TEAM_MEMBER",
      targetTeamId: TEAM_ID,
      targetMissionId: MISSION_ID,
      targetEmail: "",
      status: "pending_approval",
      active: false,
      expiresAtMillis: Date.now() + 3600_000,
      maxUses: 1,
      usedCount: 0,
      createdByUid: MEMBER_UID,
      createdByRole: "TEAM_MEMBER",
      approvedByUid: "",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Admin enables allowMemberInvites
  await assertSucceeds(orgRefAdmin.update({ allowMemberInvites: true, updatedAt: now }));

  // Member still cannot create an immediately approved invite
  await assertFails(
    orgRefMember.collection("invitations").doc("MEMBINV02").set({
      orgId: ORG_ID,
      inviteCode: "MEMBINV02",
      assignedRole: "TEAM_MEMBER",
      targetTeamId: TEAM_ID,
      targetMissionId: MISSION_ID,
      targetEmail: "",
      status: "approved",
      active: true,
      expiresAtMillis: Date.now() + 3600_000,
      maxUses: 1,
      usedCount: 0,
      createdByUid: MEMBER_UID,
      createdByRole: "TEAM_MEMBER",
      approvedByUid: MEMBER_UID,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Member CAN create pending_approval invite when enabled
  await assertSucceeds(
    orgRefMember.collection("invitations").doc("MEMBINV03").set({
      orgId: ORG_ID,
      inviteCode: "MEMBINV03",
      assignedRole: "TEAM_MEMBER",
      targetTeamId: TEAM_ID,
      targetMissionId: MISSION_ID,
      targetEmail: "",
      status: "pending_approval",
      active: false,
      expiresAtMillis: Date.now() + 3600_000,
      maxUses: 1,
      usedCount: 0,
      createdByUid: MEMBER_UID,
      createdByRole: "TEAM_MEMBER",
      approvedByUid: "",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Pending invite cannot be redeemed before admin approval
  await assertFails(
    outsiderDb.collection("organizations").doc(ORG_ID).collection("members").doc(OUTSIDER_UID).set({
      userId: OUTSIDER_UID,
      orgId: ORG_ID,
      displayName: "Unapproved Joiner",
      callsign: "UNP-01",
      photoUrl: "",
      role: "TEAM_MEMBER",
      teamId: TEAM_ID,
      status: "ACTIVE",
      availability: "available",
      inviteCode: "MEMBINV03",
      revokedByUid: "",
      revokedAtMillis: 0,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Admin approves invite, then outsider redeems via atomic batch
  await assertSucceeds(
    orgRefAdmin.collection("invitations").doc("MEMBINV03").update({
      status: "approved",
      active: true,
      approvedByUid: ADMIN_UID,
      updatedAt: now,
    })
  );

  const batch = outsiderDb.batch();
  batch.set(outsiderDb.collection("organizations").doc(ORG_ID).collection("members").doc(OUTSIDER_UID), {
    userId: OUTSIDER_UID,
    orgId: ORG_ID,
    displayName: "Approved Joiner",
    callsign: "APR-01",
    photoUrl: "",
    role: "TEAM_MEMBER",
    teamId: TEAM_ID,
    status: "ACTIVE",
    availability: "available",
    inviteCode: "MEMBINV03",
    revokedByUid: "",
    revokedAtMillis: 0,
    createdAt: now,
    updatedAt: now,
  });
  batch.update(outsiderDb.collection("organizations").doc(ORG_ID).collection("invitations").doc("MEMBINV03"), {
    usedCount: 1,
    updatedAt: now,
  });
  await assertSucceeds(batch.commit());
});

test("3. Expired and exhausted invitations are rejected", async () => {
  await seedWorkspace();
  const outsiderDb = testEnv.authenticatedContext(OUTSIDER_UID).firestore();
  const now = new Date();

  for (const code of ["EXPIRED99", "MAXEDOUT1"]) {
    await assertFails(
      outsiderDb.collection("organizations").doc(ORG_ID).collection("members").doc(OUTSIDER_UID).set({
        userId: OUTSIDER_UID,
        orgId: ORG_ID,
        displayName: "Bad Invite",
        callsign: "BAD-01",
        photoUrl: "",
        role: "TEAM_MEMBER",
        teamId: TEAM_ID,
        status: "ACTIVE",
        availability: "available",
        inviteCode: code,
        revokedByUid: "",
        revokedAtMillis: 0,
        createdAt: now,
        updatedAt: now,
      })
    );
  }
});

test("4. Critical Membership-Removal Rule: removed member is immediately revoked, blocked across UID & email, cannot self-reinstate, and only Admin can reinstate", async () => {
  await seedWorkspace();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const memberDb = testEnv.authenticatedContext(MEMBER_UID, { email: MEMBER_EMAIL, email_verified: true }).firestore();
  const bypassDb = testEnv.authenticatedContext(BYPASS_UID, { email: MEMBER_EMAIL, email_verified: true }).firestore();
  const now = new Date();
  const emailKey = normalizeEmailKey(MEMBER_EMAIL);

  // Confirm member initially can write own location in mission
  const memberLocRef = memberDb.collection("organizations").doc(ORG_ID).collection("missions").doc(MISSION_ID).collection("locations").doc(MEMBER_UID);
  await assertSucceeds(memberLocRef.set({
    userId: MEMBER_UID,
    orgId: ORG_ID,
    missionId: MISSION_ID,
    teamId: TEAM_ID,
    displayName: "Field Member",
    callsign: "MBR-04",
    role: "TEAM_MEMBER",
    sharingEnabled: true,
    hasValidLocation: true,
    latitude: 30.556,
    longitude: 79.566,
    accuracyMeters: 5.0,
    headingDegrees: 90.0,
    headingReliable: true,
    batteryPercent: 88,
    connectionStatus: "online",
    locationTimestampMillis: Date.now(),
    unavailableReason: "",
    updatedAt: now,
  }));

  // Non-admin (TEAM_MEMBER) cannot block or revoke another member
  await assertFails(
    memberDb.collection("organizations").doc(ORG_ID).collection("blockedMemberships").doc(SENSOR_OP_UID).set({
      userId: SENSOR_OP_UID,
      orgId: ORG_ID,
      teamId: TEAM_ID,
      missionId: MISSION_ID,
      blockedByUid: MEMBER_UID,
      reason: "Unauthorized",
      blockedEmailNormalized: "",
      blockedAtMillis: Date.now(),
      createdAt: now,
    })
  );

  // Organization Admin removes MEMBER_UID (marks REVOKED + writes blockedMemberships + blockedEmails + immutable audit log)
  const removeBatch = adminDb.batch();
  const adminOrgRef = adminDb.collection("organizations").doc(ORG_ID);
  removeBatch.update(adminOrgRef.collection("members").doc(MEMBER_UID), {
    status: "REVOKED",
    revokedByUid: ADMIN_UID,
    revokedAtMillis: Date.now(),
    updatedAt: now,
  });
  removeBatch.set(adminOrgRef.collection("blockedMemberships").doc(MEMBER_UID), {
    userId: MEMBER_UID,
    orgId: ORG_ID,
    teamId: TEAM_ID,
    missionId: MISSION_ID,
    blockedByUid: ADMIN_UID,
    reason: "Misconduct during rescue drill",
    blockedEmailNormalized: emailKey,
    blockedAtMillis: Date.now(),
    createdAt: now,
  });
  removeBatch.set(adminOrgRef.collection("blockedEmails").doc(emailKey), {
    orgId: ORG_ID,
    emailKey,
    blockedUid: MEMBER_UID,
    blockedByUid: ADMIN_UID,
    createdAt: now,
  });
  removeBatch.set(adminOrgRef.collection("auditLogs").doc("audit_remove_1"), {
    orgId: ORG_ID,
    missionId: MISSION_ID,
    teamId: TEAM_ID,
    actorUid: ADMIN_UID,
    actorName: "Cmdr. Admin",
    category: "removal",
    action: "REMOVE_MEMBER",
    targetUid: MEMBER_UID,
    summary: "Removed and blocked member_400",
    createdAt: now,
  });
  await assertSucceeds(removeBatch.commit());

  // Immediately invalidated: MEMBER_UID cannot read mission/team/org or update locations or create SOS
  await assertFails(memberDb.collection("organizations").doc(ORG_ID).collection("missions").doc(MISSION_ID).get());
  await assertFails(memberLocRef.set({
    userId: MEMBER_UID,
    orgId: ORG_ID,
    missionId: MISSION_ID,
    teamId: TEAM_ID,
    displayName: "Field Member",
    callsign: "MBR-04",
    role: "TEAM_MEMBER",
    sharingEnabled: true,
    hasValidLocation: true,
    latitude: 30.557,
    longitude: 79.567,
    accuracyMeters: 5.0,
    headingDegrees: 90.0,
    headingReliable: true,
    batteryPercent: 87,
    connectionStatus: "online",
    locationTimestampMillis: Date.now(),
    unavailableReason: "",
    updatedAt: now,
  }));

  // Removed member CANNOT reinstate themselves or delete block docs
  await assertFails(memberDb.collection("organizations").doc(ORG_ID).collection("blockedMemberships").doc(MEMBER_UID).delete());
  await assertFails(memberDb.collection("organizations").doc(ORG_ID).collection("members").doc(MEMBER_UID).update({ status: "ACTIVE", updatedAt: now }));

  // Removed member CANNOT rejoin using valid JOIN2026XK invite code
  await assertFails(
    memberDb.collection("organizations").doc(ORG_ID).collection("members").doc(MEMBER_UID).set({
      userId: MEMBER_UID,
      orgId: ORG_ID,
      displayName: "Rejoin Attempt",
      callsign: "REJ-01",
      photoUrl: "",
      role: "TEAM_MEMBER",
      teamId: TEAM_ID,
      status: "ACTIVE",
      availability: "available",
      inviteCode: "JOIN2026XK",
      revokedByUid: "",
      revokedAtMillis: 0,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Alternate account sharing same verified email CANNOT bypass block
  await assertFails(
    bypassDb.collection("organizations").doc(ORG_ID).collection("members").doc(BYPASS_UID).set({
      userId: BYPASS_UID,
      orgId: ORG_ID,
      displayName: "Bypass Account",
      callsign: "BYP-01",
      photoUrl: "",
      role: "TEAM_MEMBER",
      teamId: TEAM_ID,
      status: "ACTIVE",
      availability: "available",
      inviteCode: "JOIN2026XK",
      revokedByUid: "",
      revokedAtMillis: 0,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Admin cannot mark member ACTIVE while blockedMemberships still exists
  await assertFails(adminOrgRef.collection("members").doc(MEMBER_UID).update({ status: "ACTIVE", updatedAt: now }));

  // Authorized Org Admin deletes blocks, sets ACTIVE, and logs reinstatement audit
  await assertSucceeds(adminOrgRef.collection("blockedMemberships").doc(MEMBER_UID).delete());
  await assertSucceeds(adminOrgRef.collection("blockedEmails").doc(emailKey).delete());
  await assertSucceeds(
    adminOrgRef.collection("members").doc(MEMBER_UID).update({
      status: "ACTIVE",
      revokedByUid: "",
      revokedAtMillis: 0,
      updatedAt: now,
    })
  );
  await assertSucceeds(
    adminOrgRef.collection("auditLogs").doc("audit_reinstate_1").set({
      orgId: ORG_ID,
      missionId: MISSION_ID,
      teamId: TEAM_ID,
      actorUid: ADMIN_UID,
      actorName: "Cmdr. Admin",
      category: "reinstatement",
      action: "REINSTATE_MEMBER",
      targetUid: MEMBER_UID,
      summary: "Reinstated member_400 after review",
      createdAt: now,
    })
  );

  // Access restored after Admin reinstatement!
  await assertSucceeds(memberDb.collection("organizations").doc(ORG_ID).collection("missions").doc(MISSION_ID).get());
});

test("5. Sensor RBAC and SOS lifecycle + cancellation audit + immutable logs", async () => {
  await seedWorkspace();
  const memberDb = testEnv.authenticatedContext(MEMBER_UID).firestore();
  const sensorOpDb = testEnv.authenticatedContext(SENSOR_OP_UID).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const now = new Date();

  const missionMember = memberDb.collection("organizations").doc(ORG_ID).collection("missions").doc(MISSION_ID);
  const missionSensor = sensorOpDb.collection("organizations").doc(ORG_ID).collection("missions").doc(MISSION_ID);
  const missionAdmin = adminDb.collection("organizations").doc(ORG_ID).collection("missions").doc(MISSION_ID);

  // TEAM_MEMBER cannot register sensorDevices or publish sensorEvents
  await assertFails(
    missionMember.collection("sensorDevices").doc("uwb_01").set({
      sensorId: "uwb_01",
      orgId: ORG_ID,
      missionId: MISSION_ID,
      sensorType: "uwb_life_detection_radar",
      displayName: "Sector 2 UWB",
      manufacturerModel: "UWB Wall Penetration Radar",
      transportType: "gateway_http_tls",
      connectionStatus: "connected",
      hasPosition: true,
      latitude: 30.556,
      longitude: 79.566,
      orientationDegrees: 45,
      sectorWidthDegrees: 90,
      maxRangeMeters: 30,
      calibrationVersion: "cal-v1",
      lastCalibrationMillis: Date.now(),
      operatorUid: MEMBER_UID,
      updatedAt: now,
    })
  );

  // SENSOR_OPERATOR CAN register sensorDevice and ingest valid SensorEvent
  await assertSucceeds(
    missionSensor.collection("sensorDevices").doc("uwb_01").set({
      sensorId: "uwb_01",
      orgId: ORG_ID,
      missionId: MISSION_ID,
      sensorType: "uwb_life_detection_radar",
      displayName: "Sector 2 UWB",
      manufacturerModel: "UWB Wall Penetration Radar",
      transportType: "gateway_http_tls",
      connectionStatus: "connected",
      hasPosition: true,
      latitude: 30.556,
      longitude: 79.566,
      orientationDegrees: 45,
      sectorWidthDegrees: 90,
      maxRangeMeters: 30,
      calibrationVersion: "cal-v1",
      lastCalibrationMillis: Date.now(),
      operatorUid: SENSOR_OP_UID,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    missionSensor.collection("sensorEvents").doc("evt_uwb_001").set({
      eventId: "evt_uwb_001",
      orgId: ORG_ID,
      missionId: MISSION_ID,
      sensorId: "uwb_01",
      sensorType: "uwb_life_detection_radar",
      timestampUtcMillis: Date.now() - 1000,
      detectionType: "UNCLASSIFIED POSSIBLE HUMAN PRESENCE",
      estimatedRangeMeters: 8.4,
      estimatedAzimuthDegrees: 42.0,
      estimatedElevationDegrees: null,
      estimatedLatitude: 30.5561,
      estimatedLongitude: 79.5661,
      positionAccuracyMeters: 1.5,
      confidence: 0.82,
      signalQuality: "good",
      detectionDurationSeconds: 12.0,
      respirationRateBpm: 16.0,
      sourceDevice: "EdgeGateway-Alpha",
      calibrationVersion: "cal-v1",
      rawDataReference: "frame://gw01/8821",
      dataValidity: "valid",
      status: "active",
      operatorUid: SENSOR_OP_UID,
      createdAt: now,
    })
  );

  // Active member triggers SOS alert
  const sosRef = missionMember.collection("sosAlerts").doc("sos_alpha_01");
  await assertSucceeds(
    sosRef.set({
      alertId: "sos_alpha_01",
      orgId: ORG_ID,
      missionId: MISSION_ID,
      senderUid: MEMBER_UID,
      senderName: "Field Member",
      teamId: TEAM_ID,
      alertType: "sos",
      priority: "critical",
      status: "active",
      hasLocation: true,
      latitude: 30.556,
      longitude: 79.566,
      accuracyMeters: 6.0,
      locationTimestampMillis: Date.now(),
      description: "Structural shift blocked exit tunnel",
      acknowledgedByUid: "",
      acknowledgedAtMillis: 0,
      cancelledByUid: "",
      cancelReason: "",
      notificationStatus: "pending_dispatch",
      deliveryNote: "Saved to Firestore",
      pushAttemptedCount: 0,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Cancellation without reason fails; cancellation with audit reason succeeds
  await assertFails(sosRef.update({ status: "cancelled", cancelledByUid: MEMBER_UID, cancelReason: "", updatedAt: now }));
  await assertSucceeds(
    sosRef.update({
      status: "cancelled",
      cancelledByUid: MEMBER_UID,
      cancelReason: "False trigger inside harness",
      updatedAt: now,
    })
  );

  // Cancelled SOS cannot reopen
  await assertFails(
    missionAdmin.collection("sosAlerts").doc("sos_alpha_01").update({
      status: "responding",
      updatedAt: now,
    })
  );

  // Audit logs cannot be modified or deleted once created
  await assertSucceeds(
    adminDb.collection("organizations").doc(ORG_ID).collection("auditLogs").doc("audit_sos_1").set({
      orgId: ORG_ID,
      missionId: MISSION_ID,
      teamId: TEAM_ID,
      actorUid: ADMIN_UID,
      actorName: "Cmdr. Admin",
      category: "sos",
      action: "TEST_AUDIT",
      targetUid: MEMBER_UID,
      summary: "Verified SOS audit trail",
      createdAt: now,
    })
  );
  await assertFails(adminDb.collection("organizations").doc(ORG_ID).collection("auditLogs").doc("audit_sos_1").update({ summary: "Tampered" }));
  await assertFails(adminDb.collection("organizations").doc(ORG_ID).collection("auditLogs").doc("audit_sos_1").delete());
});
