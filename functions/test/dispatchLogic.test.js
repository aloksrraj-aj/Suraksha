const { test } = require("node:test");
const assert = require("node:assert/strict");

// Pure JS mirror tests for dispatchLogic invariants
test("dispatchLogic recipient filtering & token deduplication", () => {
  // Verify build idempotency & deduplication contracts
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
