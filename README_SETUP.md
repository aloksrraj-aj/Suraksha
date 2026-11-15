# PROJECT SURAKSHA — Android Application & Architecture Setup

**Project Suraksha** is an AI-assisted disaster rescue coordination, real-time environmental mapping, human presence estimation, and rescue registry Android application built with **Kotlin**, **Jetpack Compose (Material 3)**, **Cloud Firestore**, **Firebase Auth (Google Sign-In)**, **Firebase AI Logic (Gemini)**, and **Room SQLite** for offline caching.

## 1. Core Modules Implemented

1. **Overview**: Operational KPIs (registered vs. active team members, estimated presence signals, registered disaster sites), sensor connection strip, national map preview, recent updates, and **Gemini 3.8 Flash** AI Rescue Triage Briefing.
2. **Live Map (Authoritative Open-Source India Map)**:
   - Complete Indian geographical boundaries including **Jammu & Kashmir and Ladakh** without cropping.
   - Distinct cartographic styling for **International Boundaries**, **Line of Control (LoC)** (dashed), and **Line of Actual Control (LAC)** (dotted).
   - Toggleable layers: Roads, Satellite/Terrain contours, Buildings, Parks & Forests, and Teams/Sites.
   - Local **SQLite Room** offline vector region cache (`OfflineCacheRepository`) with Offline Map Mode toggle.
   - Open-source attribution (`Survey of India Ref. • OpenStreetMap (ODbL)`) with zero paid map API dependencies.
3. **Environment Mapping (500m × 500m Synthetic Simulation)**:
   - Interactive 2D SLAM occupancy grid with tap-to-measure distance tool.
   - Rotatable 3D point-cloud projection with thermal anomaly markers.
   - Clearly labeled synthetic simulation data noting real-world coverage constraints.
4. **Human Presence Estimation**:
   - Tracks sensor signals (Thermal IR, UWB Micro-Doppler, Acoustic, CO₂) with confidence %, timestamps, data source, and verification status (`Estimated`, `Unverified`, `Verified`, `Outdated`).
   - Strictly separates estimated sensor indications from field-verified survivor counts.
5. **Rescue Teams**:
   - Team registration, status, active counts, battery %, connection status, emergency communication dispatch (`SOS` / `Urgent` / `Routine`), and **explicit opt-in Voluntary Location Sharing** (with runtime `ACCESS_FINE_LOCATION` consent).
6. **Rescue Registry**:
   - Disaster sites, missing persons, verified survivors, and medical assistance requests with PII contact masking, information source tracking, and verification audit notes.
7. **Sensor Status (Hardware Integration Architecture)**:
   - Modular `SurakshaSensorAdapter` contract separating **Simulated Providers** from **Future Hardware Interfaces** (Raspberry Pi 5, NVIDIA Jetson Orin Nano, ESP32-S3, RPLIDAR A3 / Livox Mid-360, u-blox ZED-F9P GNSS + BNO085 IMU) for ROS 2 / FastAPI / MQTT integration.
8. **Settings**:
   - Demo Mode indicator toggle, Offline Map Mode, PII visibility controls, synthetic dataset seeding, and Sign Out.

## 2. Local Development & Testing

- **Build APK**: `gradle :app:assembleDebug`
- **Run Firestore Security Rules Tests (JS)**:
  ```bash
  firebase emulators:start --only firestore,auth --project demo-no-project
  FIRESTORE_EMULATOR_HOST="127.0.0.1:8085" node --test firestore.test.js
  ```
- **Run Android Unit & Robolectric Tests**:
  `gradle :app:testDebugUnitTest`
