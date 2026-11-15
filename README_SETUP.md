# PROJECT SURAKSHA — Disaster Rescue & Real-Time Environmental Mapping Platform

**Suraksha** is an Android (Kotlin + Jetpack Compose + Material 3) disaster-rescue coordination platform built on Firebase Auth (Google Sign-In via Jetpack Credential Manager), Cloud Firestore, Firebase App Check, Firebase AI Logic (`gemini-3.8-flash`), Android FusedLocation, and Room SQLite offline caching.

---

## 1. Architecture Overview

- **Multi-Organization RBAC (`organizations/{orgId}/...`)**:
  - Roles: `org_admin`, `team_leader`, `operator`, `medic`, `observer` (`read-only`).
  - Organizations enforce invite-code onboarding (`invites/{inviteCode}`), admin-only role promotions, and strict member scoping in `firestore.rules`.
  - Legacy `/users/{uid}/rescue_*` records are preserved read-only for one-tap migration into the responder's active organization (`Settings -> Migrate Legacy Data`).
- **Continuous Consent-Gated GPS (`SurakshaLocationService` & `SurakshaLocationController`)**:
  - Uses Android `FusedLocationProviderClient` inside an explicit foreground service (`foregroundServiceType="location"`) with a persistent **Stop Sharing** notification action.
  - Battery-aware polling intervals: `>=50%` (10s High Accuracy), `20–49%` (25s Balanced Power), `<20%` (60s Low Power).
  - Never uses hardcoded fallback coordinates; displays `Location unavailable` with exact cause (`Permission denied`, `GPS disabled`, `Opted out`).
- **Live OpenStreetMap & OpenTopoMap Mapping (`OsmTileManager`, `IndiaInteractiveMap`)**:
  - Streams and disk-caches real Slippy Map raster tiles (`tile.openstreetmap.org` & `tile.opentopomap.org`).
  - Supports offline regional pack download, cancel, resume, and real byte/file deletion tracked in Room v2 (`cached_map_tiles` & `offline_map_regions`).
  - Includes an authoritative uncropped India administrative reference overlay (J&K & Ladakh uncropped, International Boundary, dashed Line of Control / LoC, dotted Line of Actual Control / LAC — visual reference only, not a legal Survey of India CAD/shapefile), Haversine ruler measurement, marker clustering, offline Gazetteer + online OSM Nominatim geocoding.
- **Modular Sensor & Environmental Mapping (`SensorAdapterArchitecture`)**:
  - Live `WifiHttpGatewayAdapter` polls FastAPI/REST edge gateways (`/api/v1/sensors`).
  - Live `BleGattSensorAdapter` checks Bluetooth adapter state and Android 12+ `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` runtime permissions.
  - Explicit `SimulatedSensorProvider` and `FutureContractSensorAdapter` stubs for ROS 2 / USB-Serial LiDAR & RTK GNSS rigs.
  - `SensorFusionProcessor` validates packets, deduplicates within 30s windows, marks readings older than 120s as `outdated`, and strictly prevents any `simulated` packet from becoming `field_verified`.
- **9-State SOS Incident Workflow**:
  - States: `created -> pending_delivery -> delivered -> acknowledged -> team_assigned -> responding -> resolved -> closed` (plus `failed`).
  - `closed` incidents are terminally locked by `firestore.rules`; every transition writes an append-only record to `audit_logs`.
- **Offline-First Sync Engine (`OfflineSyncManager` & Room v2 `MIGRATION_1_2`)**:
  - Queues offline writes idempotently in `pending_sync_operations` and auto-flushes via `ConnectivityManager.NetworkCallback` when validated connectivity returns.
- **Grounded AI Situation Triage (`SurakshaAiRepository`)**:
  - Uses Firebase AI Logic (`gemini-3.8-flash`) with `Schema.obj` structured JSON triage output and grounded operational Q&A.
  - Mandatory `requiresHumanApproval = true` banner and explicit disclosure of missing GPS, outdated signals, and simulated-only data.

---

## 2. Edge Sensor Gateway JSON Contract (`GET /api/v1/sensors`)

Hardware edge nodes (e.g., Jetson Orin Nano / Raspberry Pi 5 FastAPI bridge) can feed real `hardware` telemetry into Suraksha using this JSON shape:

```json
{
  "packets": [
    {
      "packetId": "edge_pkt_001",
      "deviceId": "jetson-orin-01",
      "signalType": "thermal_ir",
      "sectorLabel": "Collapsed Wing B2",
      "latitude": 30.5506,
      "longitude": 79.5660,
      "estimatedMinCount": 1,
      "estimatedMaxCount": 2,
      "confidencePercent": 78,
      "calibrationStatus": "calibrated",
      "sensorHealth": "healthy",
      "timestampMillis": 1750000000000,
      "dataSource": "Jetson FastAPI FLIR Lepton"
    }
  ]
}
```

Supported `signalType` values: `thermal_ir`, `radar_respiration`, `acoustic_vibration`, `co2_plume`, `lidar_void`.

---

## 3. Running Tests Locally

1. **Start Firebase Emulators** (Firestore + Auth):
   ```bash
   firebase emulators:start --only firestore,auth --project demo-no-project
   ```
2. **Run JS Firestore Security Rules Suite**:
   ```bash
   FIRESTORE_EMULATOR_HOST="127.0.0.1:8085" node --test firestore.test.js
   ```
3. **Run Android Kotlin Rule, Robolectric, & Compose UI Tests**:
   ```bash
   gradle :app:testDebugUnitTest
   ```

---

## 4. Honest Production Readiness Disclosures

- **Push Alerting (FCM / SMS)**: SOS creation writes directly to Cloud Firestore dispatch (`delivered` = server-saved). True wake-up push notifications or SMS fallbacks require deploying trusted Firebase Cloud Functions triggered by `sos_incidents` writes.
- **BLE Packet Streaming**: `BleGattSensorAdapter` verifies hardware/permissions and reports readiness/missing beacon honestly; custom manufacturer GATT characteristic parsing requires pairing a physical ESP32-S3 beacon implementing UUID `0000FE90-0000-1000-8000-00805F9B34FB`.
- **Boundary Layer**: The India international boundary, LoC, and LAC vectors in `IndiaInteractiveMap.kt` are stylized WGS84 reference overlays for humanitarian operational orientation, not official Survey of India GIS datasets.
