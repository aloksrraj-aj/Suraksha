const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function serverTimestamp(firebaseModule) {
  return firebaseModule.firestore.FieldValue.serverTimestamp();
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
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or list any rescue collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("rescue_teams").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("rescue_registry").get());
});

test("Authenticated user can create and read own responder profile", async () => {
  const aliceCtx = testEnv.authenticatedContext(ALICE_UID);
  const aliceDb = aliceCtx.firestore();
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Cmdr. Ananya",
      callsign: "NDRF-01",
      teamId: "ndrf-alpha",
      teamName: "NDRF Alpha",
      role: "coordinator",
      locationSharingEnabled: false,
      createdAt: new Date(),
      updatedAt: new Date(),
    })
  );
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).get());
});

test("Cross-user access to another responder's teams or registry is denied", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc(BOB_UID).collection("rescue_registry").doc("entry_1").set({
      userId: BOB_UID,
      category: "missing_person",
      title: "Person A",
      locationName: "Chamoli Sector",
      details: "Last seen near bridge",
      sensitiveContactMasked: "+91-98****210",
      rescueStatus: "open",
      verificationStatus: "unverified",
      informationSource: "Family Hotline",
      verificationNotes: "Pending field check",
      isSynthetic: true,
      createdAt: new Date(),
      updatedAt: new Date(),
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("rescue_registry").doc("entry_1").get());
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("rescue_registry").get());
});

test("Shadow update with ghost field or invalid enum is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("rescue_teams").doc("team_1").set({
      userId: ALICE_UID,
      teamCode: "NDRF-A1",
      teamName: "Alpha Unit",
      memberCount: 8,
      activeCount: 6,
      status: "active",
      locationSharingOptIn: true,
      latitude: 30.3165,
      longitude: 78.0322,
      batteryPercent: 84,
      connectionStatus: "online",
      isSynthetic: true,
      isAdminOverride: true,
      createdAt: new Date(),
      updatedAt: new Date(),
    })
  );

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("presence_signals").doc("sig_1").set({
      userId: ALICE_UID,
      locationId: "loc_1",
      sectorLabel: "Block B",
      signalType: "thermal_ir",
      estimatedMinCount: 2,
      estimatedMaxCount: 1, // invalid: max < min
      confidencePercent: 72,
      verificationStatus: "estimated",
      dataSource: "Simulated FLIR",
      isSynthetic: true,
      createdAt: new Date(),
      updatedAt: new Date(),
    })
  );
});

test("Valid rescue location, presence signal, registry entry, and alert succeed", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("rescue_locations").doc("loc_1").set({
      userId: ALICE_UID,
      name: "Joshimath Ridge Sector 4",
      region: "Uttarakhand",
      hazardType: "landslide",
      severity: "critical",
      latitude: 30.5506,
      longitude: 79.5660,
      verificationStatus: "verified",
      dataSource: "NDMA Field Feed (Demo)",
      isSynthetic: true,
      createdAt: new Date(),
      updatedAt: new Date(),
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("presence_signals").doc("sig_1").set({
      userId: ALICE_UID,
      locationId: "loc_1",
      sectorLabel: "Sector 4B Rubble",
      signalType: "radar_respiration",
      estimatedMinCount: 1,
      estimatedMaxCount: 2,
      confidencePercent: 76,
      verificationStatus: "estimated",
      dataSource: "Simulated UWB Radar",
      isSynthetic: true,
      createdAt: new Date(),
      updatedAt: new Date(),
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("presence_signals").doc("sig_1").update({
      verificationStatus: "verified",
      confidencePercent: 95,
      updatedAt: new Date(),
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("team_alerts").doc("alert_1").set({
      userId: ALICE_UID,
      teamCode: "NDRF-A1",
      priority: "urgent",
      message: "Thermal anomaly detected in Sector 4B, deploying medical unit.",
      channel: "mesh_radio",
      isSynthetic: true,
      createdAt: new Date(),
      updatedAt: new Date(),
    })
  );
});
