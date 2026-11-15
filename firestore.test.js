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
const LEADER_UID = "leader_200";
const OPERATOR_UID = "operator_300";
const OBSERVER_UID = "observer_400";
const OUTSIDER_UID = "outsider_999";
const ORG_ID = "org_ndrf_north";
const OTHER_ORG_ID = "org_sdrf_south";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

async function seedOrgWithMembers() {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    const now = new Date();
    await db.collection("organizations").doc(ORG_ID).set({
      orgCode: "NDRF-N",
      orgName: "NDRF North Command",
      region: "Uttarakhand",
      createdByUid: ADMIN_UID,
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("organizations").doc(ORG_ID).collection("invites").doc("JOIN2026").set({
      orgId: ORG_ID,
      inviteCode: "JOIN2026",
      defaultRole: "operator",
      active: true,
      createdByUid: ADMIN_UID,
      createdAt: now,
      updatedAt: now,
    });
    const members = [
      [ADMIN_UID, "org_admin", "Cmdr. Admin", "ADM-01"],
      [LEADER_UID, "team_leader", "Lead Officer", "LDR-02"],
      [OPERATOR_UID, "operator", "Field Scout", "OPS-03"],
      [OBSERVER_UID, "observer", "Observer", "OBS-04"],
    ];
    for (const [uid, role, name, callsign] of members) {
      await db.collection("organizations").doc(ORG_ID).collection("members").doc(uid).set({
        userId: uid,
        orgId: ORG_ID,
        displayName: name,
        callsign,
        role,
        teamId: "team_alpha",
        availability: "available",
        inviteCode: "JOIN2026",
        createdAt: now,
        updatedAt: now,
      });
    }
    await db.collection("organizations").doc(OTHER_ORG_ID).set({
      orgCode: "SDRF-S",
      orgName: "SDRF South",
      region: "Kerala",
      createdByUid: OUTSIDER_UID,
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("organizations").doc(OTHER_ORG_ID).collection("members").doc(OUTSIDER_UID).set({
      userId: OUTSIDER_UID,
      orgId: OTHER_ORG_ID,
      displayName: "Outsider Admin",
      callsign: "EXT-01",
      role: "org_admin",
      teamId: "team_south",
      availability: "available",
      inviteCode: "",
      createdAt: now,
      updatedAt: now,
    });
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

test("1. Unauthenticated users are denied across all org collections", async () => {
  await seedOrgWithMembers();
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("organizations").doc(ORG_ID).get());
  await assertFails(unauthDb.collection("organizations").doc(ORG_ID).collection("teams").get());
  await assertFails(unauthDb.collection("organizations").doc(ORG_ID).collection("sos_incidents").get());
  await assertFails(unauthDb.collection("organizations").doc(ORG_ID).collection("registry").get());
});

test("2. Authorized org members can read org data; outsiders are rejected (cross-org isolation)", async () => {
  await seedOrgWithMembers();
  const operatorDb = testEnv.authenticatedContext(OPERATOR_UID).firestore();
  const outsiderDb = testEnv.authenticatedContext(OUTSIDER_UID).firestore();

  await assertSucceeds(operatorDb.collection("organizations").doc(ORG_ID).get());
  await assertSucceeds(operatorDb.collection("organizations").doc(ORG_ID).collection("teams").get());

  await assertFails(outsiderDb.collection("organizations").doc(ORG_ID).get());
  await assertFails(outsiderDb.collection("organizations").doc(ORG_ID).collection("teams").get());
  await assertFails(outsiderDb.collection("organizations").doc(ORG_ID).collection("sos_incidents").get());
});

test("3. Users cannot self-escalate privileges or join org as org_admin via invite", async () => {
  await seedOrgWithMembers();
  const outsiderDb = testEnv.authenticatedContext(OUTSIDER_UID).firestore();
  const operatorDb = testEnv.authenticatedContext(OPERATOR_UID).firestore();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const now = new Date();

  // Outsider trying to join ORG_ID as org_admin via invite -> MUST FAIL
  await assertFails(
    outsiderDb.collection("organizations").doc(ORG_ID).collection("members").doc(OUTSIDER_UID).set({
      userId: OUTSIDER_UID,
      orgId: ORG_ID,
      displayName: "Rogue Admin",
      callsign: "ROG-01",
      role: "org_admin",
      teamId: "team_alpha",
      availability: "available",
      inviteCode: "JOIN2026",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Outsider joining as operator with valid invite -> SUCCEEDS
  await assertSucceeds(
    outsiderDb.collection("organizations").doc(ORG_ID).collection("members").doc(OUTSIDER_UID).set({
      userId: OUTSIDER_UID,
      orgId: ORG_ID,
      displayName: "Joined Operator",
      callsign: "JND-09",
      role: "operator",
      teamId: "team_alpha",
      availability: "available",
      inviteCode: "JOIN2026",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Operator trying to promote themselves to org_admin -> MUST FAIL
  await assertFails(
    operatorDb.collection("organizations").doc(ORG_ID).collection("members").doc(OPERATOR_UID).update({
      role: "org_admin",
      updatedAt: now,
    })
  );

  // Org admin promoting operator to team_leader -> SUCCEEDS
  await assertSucceeds(
    adminDb.collection("organizations").doc(ORG_ID).collection("members").doc(OPERATOR_UID).update({
      role: "team_leader",
      updatedAt: now,
    })
  );
});

test("4. Team leaders & admins can create teams; observers cannot write", async () => {
  await seedOrgWithMembers();
  const leaderDb = testEnv.authenticatedContext(LEADER_UID).firestore();
  const observerDb = testEnv.authenticatedContext(OBSERVER_UID).firestore();
  const now = new Date();

  const teamPayload = {
    orgId: ORG_ID,
    userId: LEADER_UID,
    leaderUid: LEADER_UID,
    teamCode: "NDRF-A1",
    teamName: "Alpha Swift Rescue",
    memberCount: 10,
    activeCount: 8,
    status: "deployed",
    locationSharingOptIn: false,
    hasValidLocation: false,
    latitude: 0,
    longitude: 0,
    accuracyMeters: 0,
    locationTimestampMillis: 0,
    locationUnavailableReason: "Sharing disabled",
    batteryPercent: 90,
    connectionStatus: "online",
    isSynthetic: false,
    createdAt: now,
    updatedAt: now,
  };

  await assertSucceeds(
    leaderDb.collection("organizations").doc(ORG_ID).collection("teams").doc("team_a1").set(teamPayload)
  );
  await assertFails(
    observerDb.collection("organizations").doc(ORG_ID).collection("teams").doc("team_obs").set({
      ...teamPayload,
      userId: OBSERVER_UID,
    })
  );
});

test("5. Invalid coordinates and future timestamps are rejected", async () => {
  await seedOrgWithMembers();
  const operatorDb = testEnv.authenticatedContext(OPERATOR_UID).firestore();
  const now = new Date();
  const futureDate = new Date(Date.now() + 3600 * 1000);

  await assertFails(
    operatorDb.collection("organizations").doc(ORG_ID).collection("locations").doc("bad_lat").set({
      orgId: ORG_ID,
      userId: OPERATOR_UID,
      name: "Invalid Latitude Site",
      region: "North",
      hazardType: "flood",
      severity: "high",
      latitude: 120.5, // invalid > 90
      longitude: 78.0,
      radiusMeters: 500,
      verificationStatus: "unverified",
      dataSource: "Field",
      isSynthetic: false,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertFails(
    operatorDb.collection("organizations").doc(ORG_ID).collection("locations").doc("future_ts").set({
      orgId: ORG_ID,
      userId: OPERATOR_UID,
      name: "Future Timestamp Site",
      region: "North",
      hazardType: "flood",
      severity: "high",
      latitude: 30.5,
      longitude: 78.0,
      radiusMeters: 500,
      verificationStatus: "unverified",
      dataSource: "Field",
      isSynthetic: false,
      createdAt: futureDate,
      updatedAt: futureDate,
    })
  );
});

test("6. Simulated presence signals cannot be created directly as field_verified", async () => {
  await seedOrgWithMembers();
  const operatorDb = testEnv.authenticatedContext(OPERATOR_UID).firestore();
  const now = new Date();

  await assertFails(
    operatorDb.collection("organizations").doc(ORG_ID).collection("presence_signals").doc("fake_verif").set({
      orgId: ORG_ID,
      userId: OPERATOR_UID,
      locationId: "loc_1",
      sectorLabel: "Sector B2",
      signalType: "radar_respiration",
      sourceKind: "simulated",
      deviceId: "sim-uwb-1",
      calibrationStatus: "nominal",
      sensorHealth: "healthy",
      latitude: 30.5,
      longitude: 79.5,
      hasCoordinates: true,
      estimatedMinCount: 1,
      estimatedMaxCount: 2,
      confidencePercent: 80,
      verificationStatus: "field_verified", // MUST FAIL on create!
      dataSource: "Sim",
      verifiedByUid: OPERATOR_UID,
      verificationNotes: "Auto",
      observationTimestampMillis: Date.now(),
      isSynthetic: true,
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("7. SOS incident workflow enforces authorized transitions, audit logs, and closed lock", async () => {
  await seedOrgWithMembers();
  const operatorDb = testEnv.authenticatedContext(OPERATOR_UID).firestore();
  const leaderDb = testEnv.authenticatedContext(LEADER_UID).firestore();
  const outsiderDb = testEnv.authenticatedContext(OUTSIDER_UID).firestore();
  const now = new Date();

  const sosRef = operatorDb.collection("organizations").doc(ORG_ID).collection("sos_incidents").doc("sos_001");
  await assertSucceeds(
    sosRef.set({
      incidentId: "sos_001",
      orgId: ORG_ID,
      senderUid: OPERATOR_UID,
      senderName: "Field Scout",
      teamCode: "NDRF-A1",
      priority: "sos",
      description: "Wall collapse near Sector 2 culvert.",
      status: "delivered",
      hasLocation: false,
      latitude: 0,
      longitude: 0,
      accuracyMeters: 0,
      assignedTeamId: "",
      assignedTeamCode: "",
      acknowledgedByUid: "",
      acknowledgedAtMillis: 0,
      deliveryNote: "Saved to Firestore",
      isSynthetic: false,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Outsider cannot read or update SOS
  await assertFails(outsiderDb.collection("organizations").doc(ORG_ID).collection("sos_incidents").doc("sos_001").get());
  await assertFails(
    outsiderDb.collection("organizations").doc(ORG_ID).collection("sos_incidents").doc("sos_001").update({
      status: "acknowledged",
      updatedAt: now,
    })
  );

  // Operator cannot close SOS directly (requires team_leader or org_admin)
  await assertFails(sosRef.update({ status: "closed", updatedAt: now }));

  // Team leader acknowledges and closes SOS
  const leaderSosRef = leaderDb.collection("organizations").doc(ORG_ID).collection("sos_incidents").doc("sos_001");
  await assertSucceeds(
    leaderSosRef.update({
      status: "acknowledged",
      acknowledgedByUid: LEADER_UID,
      acknowledgedAtMillis: Date.now(),
      updatedAt: now,
    })
  );
  await assertSucceeds(
    leaderSosRef.collection("audit_logs").doc("log_1").set({
      orgId: ORG_ID,
      incidentId: "sos_001",
      actorUid: LEADER_UID,
      fromStatus: "delivered",
      toStatus: "acknowledged",
      note: "Acknowledged by leader",
      createdAt: now,
    })
  );
  await assertSucceeds(leaderSosRef.update({ status: "closed", updatedAt: now }));

  // Closed SOS cannot be reopened
  await assertFails(leaderSosRef.update({ status: "responding", updatedAt: now }));
});

test("8. Observer cannot create SOS or registry entries, and simulated signal cannot be promoted to field_verified", async () => {
  await seedOrgWithMembers();
  const observerDb = testEnv.authenticatedContext(OBSERVER_UID).firestore();
  const operatorDb = testEnv.authenticatedContext(OPERATOR_UID).firestore();
  const now = new Date();

  await assertFails(
    observerDb.collection("organizations").doc(ORG_ID).collection("sos_incidents").doc("sos_obs").set({
      incidentId: "sos_obs",
      orgId: ORG_ID,
      senderUid: OBSERVER_UID,
      senderName: "Observer",
      teamCode: "OBS-04",
      priority: "sos",
      description: "Observer attempt",
      status: "delivered",
      hasLocation: false,
      latitude: 0,
      longitude: 0,
      accuracyMeters: 0,
      assignedTeamId: "",
      assignedTeamCode: "",
      acknowledgedByUid: "",
      acknowledgedAtMillis: 0,
      deliveryNote: "Attempt",
      isSynthetic: false,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Create a simulated signal validly as estimated
  const simRef = operatorDb.collection("organizations").doc(ORG_ID).collection("presence_signals").doc("sim_01");
  await assertSucceeds(
    simRef.set({
      orgId: ORG_ID,
      userId: OPERATOR_UID,
      locationId: "loc_1",
      sectorLabel: "Sector C",
      signalType: "thermal_ir",
      sourceKind: "simulated",
      deviceId: "sim-1",
      calibrationStatus: "nominal",
      sensorHealth: "healthy",
      latitude: 30.5,
      longitude: 79.5,
      hasCoordinates: true,
      estimatedMinCount: 1,
      estimatedMaxCount: 2,
      confidencePercent: 72,
      verificationStatus: "estimated",
      dataSource: "Sim FLIR",
      verifiedByUid: "",
      verificationNotes: "",
      observationTimestampMillis: Date.now(),
      isSynthetic: true,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Attempting to mark simulated signal as field_verified MUST FAIL
  await assertFails(
    simRef.update({
      verificationStatus: "field_verified",
      verifiedByUid: OPERATOR_UID,
      verificationNotes: "Trying to bypass",
      updatedAt: now,
    })
  );
});

test("9. Legacy user-scoped data remains readable only by owner for migration", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc(OPERATOR_UID).collection("rescue_locations").doc("leg_1").set({
      name: "Legacy Ridge",
    });
  });
  const operatorDb = testEnv.authenticatedContext(OPERATOR_UID).firestore();
  const outsiderDb = testEnv.authenticatedContext(OUTSIDER_UID).firestore();
  await assertSucceeds(operatorDb.collection("users").doc(OPERATOR_UID).collection("rescue_locations").get());
  await assertFails(outsiderDb.collection("users").doc(OPERATOR_UID).collection("rescue_locations").get());
});
