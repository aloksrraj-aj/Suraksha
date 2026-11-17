# Edge Gateway & Sensor Hub Integration Guide

External radar units (UWB through-wall life-detection radar, FMCW perimeter radar, thermal IR, and seismic/acoustic arrays) normally connect over Ethernet, USB, RS-485, Wi-Fi, or custom RF to a rugged field Raspberry Pi / Jetson / industrial Edge Gateway, rather than directly to a phone over standard BLE.

## 1. JSON Schema v1.0.0
Every gateway reading MUST conform to `docs/SENSOR_EVENT_SCHEMA_V1.json` (`schemaVersion: "1.0.0"`).
Optional geometric/biometric fields (`estimatedRangeMeters`, `estimatedAzimuthDegrees`, `estimatedLatitude`, `estimatedLongitude`, `respirationRateBpm`) MUST be `null` or omitted when unavailable—never fabricated.

## 2. Transport Options
| Transport | Status in App | Auth & Security |
| --- | --- | --- |
| HTTPS/TLS Polling (`GET /v1/missions/{missionId}/events`) | Implemented in `HttpTlsGatewayAdapter` | `Authorization: Bearer <token>` stored in AndroidKeyStore AES-GCM (`SecureTokenStorage`) |
| WebSocket/TLS (`wss://.../v1/stream`) | Implemented in `WebSocketGatewayAdapter` (OkHttp WebSocket) | Bearer token + TLS + sequence replay protection |
| MQTT over TLS (`ssl://...:8883`) | Contract documented in `MqttGatewayContract` | Awaits site broker credentials / TLS CA cert |
| Cloud Webhook (`ingestGatewaySensorEvent`) | Implemented in `functions/src/index.ts` | HMAC-SHA256 (`X-Suraksha-Signature`) or Firebase ID token |

## 3. Validation & Protection
Both the Cloud Function and Android `SensorEventV1Parser` reject or downgrade:
- Malformed JSON or unsupported `schemaVersion`
- Future timestamps > 60 seconds ahead of UTC clock
- Duplicate `eventId` (replay window protection)
- Out-of-bounds range (`< 0` or `> 5000m`), azimuth (`< 0` or `> 360°`), or coordinates
- Old timestamps (`> 180s`) → marked `stale` (`GREY` on Radar)
- Low confidence (`< 0.40`) or `poor`/`unusable` signal → marked `low_confidence` (`GREY` on Radar)
