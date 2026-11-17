# Hardware Integration Checklist

All physical radar, thermal, acoustic, and tag adapters live in `app/src/main/java/com/example/data/hardware/` and report `AWAITING_HARDWARE_INTEGRATION` until physical hardware and vendor documentation are supplied.

## Checklist per Hardware Class

### 1. Mode A — Building / Collapsed Structure Through-Wall UWB Radar
- [ ] Identify hardware vendor & SDK / serial / Ethernet protocol (e.g., Xethru, Novelda, custom impulse-UWB DSP)
- [ ] Implement vendor packet parser mapping raw range/phase micro-Doppler bins to `SensorEvent` v1.0.0
- [ ] Calibrate wall attenuation & respiration BPM filter (`respirationRateBpm`)
- [ ] Verify stationary human presence inside rubble without fabricating coordinates

### 2. Mode B — Open Area FMCW Radar
- [ ] Connect FMCW baseband / point-cloud module via Edge Gateway
- [ ] Calibrate stationary azimuth (`orientationDegrees`) and sector aperture (`sectorWidthDegrees`)
- [ ] Verify range (`estimatedRangeMeters`) & azimuth (`estimatedAzimuthDegrees`) bounds

### 3. Thermal / IR Camera
- [ ] Integrate UVC/RTSP/FLIR Lepton or Edge Gateway thermal hotspot extractor
- [ ] Populate `estimatedAzimuthDegrees` + `confidence`; mark ambient solar glare filters

### 4. Acoustic / Vibration Rubble Sensor
- [ ] Connect geophone / piezoelectric array via Edge Gateway ADC
- [ ] Populate signal quality and `detectionDurationSeconds`; note angle may be `null` (`uncertain`)

### 5. Registered BLE Rescue Tag & 6. UWB Ranging Tag
- [ ] Obtain actual vendor GATT Service/Characteristic UUIDs or Android `androidx.core.uwb` session profile
- [ ] Map ONLY explicitly registered team member tag IDs to `OrgMember` / `MissionMember` (`GREEN` identity)
- [ ] Never classify unregistered BLE beacons as rescue team members
