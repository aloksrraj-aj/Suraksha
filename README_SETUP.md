# PROJECT SURAKSHA — Civilian Humanitarian Search-and-Rescue Radar & Coordination Platform

**Suraksha** is an Android (Kotlin + Jetpack Compose + Material 3) civilian search-and-rescue (SAR) hybrid radar, live responder tracking, and emergency coordination platform backed by Firebase Auth (Google Sign-In via Jetpack Credential Manager), Cloud Firestore, Firebase Cloud Messaging, Firebase App Check, Android FusedLocation, `SensorManager` compass fusion, and Room v4 SQLite offline caching.

---

## 1. Architecture Overview

- **Civilian Humanitarian Safeguard**:
  - Strictly civilian SAR coordination; never performs hostile classification, targeting, or weapon guidance.
  - Unknown non-responder detections are always labeled `UNCLASSIFIED POSSIBLE HUMAN PRESENCE`.
  - Strict 4-color radar classification (`RadarGeoMath`):
    - **GREEN**: Verified active rescue team member (`MemberLocation`)
    - **YELLOW**: `UNCLASSIFIED POSSIBLE HUMAN PRESENCE` (`SensorEvent` valid reading)
    - **RED**: Active `SOS` or Team Separation alert (`SosAlert`)
    - **GREY**: Stale, low-confidence, missing bearing/range, or temporarily unavailable signal
- **Multi-Organization RBAC & Revocation (`organizations/{orgId}/...`)**:
  - Roles: `ORGANIZATION_ADMIN`, `TEAM_ADMIN`, `TEAM_MEMBER`, `SENSOR_OPERATOR`.
  - Enforces invite-code onboarding (`invitations/{inviteCode}`) with `allowMemberInvites` toggle and mandatory admin approval for member requests.
  - Critical Membership-Removal Rule: removing a member marks `status = "REVOKED"`, writes `blockedMemberships` and `blockedEmails`, blocks mission access and email-bypass rejoins, and restricts reinstatement exclusively to an `ORGANIZATION_ADMIN`.
  - Immutable `auditLogs` preserve all membership, role, SOS, and mission actions.
- **Consent-Gated Foreground GPS & Compass (`SurakshaLocationService`, `SurakshaLocationController`, `CompassHeadingProvider`)**:
  - Uses `FusedLocationProviderClient` in an explicit `foregroundServiceType="location"` service with a persistent **Stop Sharing** notification action.
  - Battery-aware polling intervals: `>=50%` (10s High Accuracy), `20–49%` (25s Balanced Power), `<20%` (60s Low Power).
  - Real `SensorManager` (`TYPE_ROTATION_VECTOR` / accel+mag fallback) with smooth wrap-around and automatic North-Up degradation when compass hardware is unavailable or uncalibrated.
- **Modular External Sensor & Edge Gateway Abstraction (`data/hardware/`, `SensorEventV1Parser`)**:
  - Clean hardware interfaces (`SensorAdapter`, `SensorConnectionManager`, `SensorCapability`, `SensorHealthMonitor`, `SensorDataParser`, `SensorEventRepository`, `SensorCalibrationManager`, `SensorGatewayClient`) plus `HttpTlsGatewayClient` and `WebSocketTlsGatewayClient` implementing `docs/SENSOR_EVENT_SCHEMA_V1.json`.
  - Unpaired physical hardware adapters (`UwbThroughWallAdapter`, `FmcwOpenAreaAdapter`, `ThermalIrAdapter`, `AcousticVibrationAdapter`, `RegisteredBleTagAdapter`, `UwbRangingTagAdapter`) honestly report `AWAITING_HARDWARE_INTEGRATION` without fabricating live readings.
  - Development-only `DevDemoFixtureProvider` is strictly isolated in memory and never writes to production Firestore mission collections.
- **Offline-First Sync, Tile Cache & Encrypted Token Vault (`SurakshaLocalDb` v4, `OfflineSyncManager`, `OsmTileManager`, `SecureTokenStorage`)**:
  - Room v4 caches offline map regions/tiles, mission member locations, sensor health, sensor events, and idempotent retryable sync operations (`SurakshaSyncWorker`).
  - Hardware-backed Android Keystore AES-256-GCM storage (`SecureTokenStorage`) protects gateway bearer tokens.
- **Emergency SOS & Cloud Functions (`functions/`)**:
  - Mission SOS workflow (`active -> acknowledged -> responding -> resolved / cancelled`) with mandatory cancellation reason and terminal lock in `firestore.rules`.
  - Cloud Functions v2 handle FCM dispatch (`onMissionSosCreated`, `onMissionSosUpdated`), unacknowledged escalation (`escalateUnacknowledgedMissionSos`), gateway webhook ingestion (`ingestGatewaySensorEvent`), and command center health (`commandCenterStatus`).

---

## 2. Running Tests Locally

1. **Start Firebase Emulators & Run JS + Android Rule/Unit/UI Suites**:
   ```bash
   firebase emulators:exec --project demo-no-project --only auth,firestore \
     "FIRESTORE_EMULATOR_HOST=127.0.0.1:8085 node --test firestore.test.js && gradle :app:testDebugUnitTest"
   ```
2. **Run Cloud Functions Dispatch & Schema Validator Tests**:
   ```bash
   node --test functions/test/dispatchLogic.test.js
   ```
3. **Compile Android APK**:
   ```bash
   gradle :app:assembleDebug
   ```

---

## 3. Additional Documentation

- `docs/ARCHITECTURE.md` — Layered system architecture & Firebase resource IDs
- `docs/GATEWAY_INTEGRATION.md` — Edge Gateway HTTPS/WSS/MQTT integration guide
- `docs/HARDWARE_INTEGRATION_CHECKLIST.md` — Per-sensor integration checklist
- `docs/SENSOR_EVENT_SCHEMA_V1.json` — JSON Schema v1.0.0 contract
