# Suraksha Hybrid Rescue Radar — System Architecture

## 1. Civilian Humanitarian Scope
Suraksha is strictly a civilian humanitarian search-and-rescue (SAR) and emergency coordination platform.
- Never performs hostile classification, targeting, or weapon guidance.
- Unknown non-responder sensor hits are classified neutrally as **`UNCLASSIFIED POSSIBLE HUMAN PRESENCE`**.

## 2. Layered Architecture
1. **Android Application (`com.example` / `com.aistudio.suraksha.xkmqzp`)**:
   - **UI Layer (Jetpack Compose M3)**: 4 adaptive tabs (`Missions`, `Radar`, `Sensors`, `Team`) + `Settings & Profile` + Emergency `SOS` overlay.
   - **Radar & Geo Engine**: Color-separated classification:
     - `GREEN`: Verified active rescue team member
     - `YELLOW`: `UNCLASSIFIED POSSIBLE HUMAN PRESENCE` from external sensor
     - `RED`: Active `SOS` or Team Separation alert
     - `GREY`: Stale, low-confidence, missing bearing/range, or temporarily unavailable
   - **Real Sensors & Foreground GPS**: FusedLocation foreground service (`SurakshaLocationService`) + `CompassHeadingProvider` (`SensorManager`) + explicit user consent + boot notification reminder (`SurakshaBootReceiver`).
   - **Offline Persistence**: Room SQLite (`SurakshaLocalDb`) + WorkManager (`SurakshaSyncWorker`) + OSM/OpenTopoMap tile cache (`OsmTileManager`) + Keystore AES-256-GCM token vault (`SecureTokenStorage`).
2. **Modular Hardware & Gateway Abstraction (`data/hardware/`)**:
   - Interfaces: `SensorAdapter`, `SensorConnectionManager`, `SensorCapability`, `SensorHealthMonitor`, `SensorDataParser`, `SensorEventRepository`, `SensorCalibrationManager`, `SensorGatewayClient`.
   - Future manufacturer hardware classes cleanly report `AWAITING_HARDWARE_INTEGRATION` with zero fabricated GATT UUIDs or simulated live detections.
   - Edge Gateway transport supports HTTPS/TLS polling, WebSocket/TLS streaming, and documented MQTT-over-TLS interface using `docs/SENSOR_EVENT_SCHEMA_V1.json`.
   - Development-only demo mode uses `DevDemoFixtureProvider` and is strictly isolated from Firestore production missions.
3. **Firebase Cloud Backend (`disha-ai-c80ba` / DB `ai-studio-android-suraksha-e0c70b4c-78bb-40fd-a0e7-58c89adbeb5c`)**:
   - Authentication: Google Sign-In via Jetpack `CredentialManager`.
   - Database: Cloud Firestore (multi-org RBAC, blocked membership & email-bypass protection, mission isolation, immutable audit logs) + RTDB (`database.rules.json`) for high-frequency mission location sync.
   - Server Dispatch: Cloud Functions v2 (`onMissionSosCreated`, `onMissionSosUpdated`, `escalateUnacknowledgedMissionSos`, `ingestGatewaySensorEvent`, `commandCenterStatus`).
