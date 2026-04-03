# 📡 ScreenMirror

> Native Android screen mirroring over local Wi-Fi — no external servers, no third-party SDKs.

## Overview

ScreenMirror is an Android app that discovers and streams your screen to nearby devices using only local network protocols. Built with **Kotlin** and **Jetpack Compose** following a clean architecture.

| Protocol | Discovery | Transport |
|---|---|---|
| **AirPlay** | mDNS / NSD (`_airplay._tcp`) | RTSP over TCP *(in progress)* |
| **Chromecast** | mDNS / NSD (`_googlecast._tcp`) | Cast V2 / WebSocket *(in progress)* |
| **Miracast** | Wi-Fi Direct / WifiP2pManager | Wi-Fi P2P *(in progress)* |

> ✅ Discovery is fully functional. Streaming transport layer is under active development.

---

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Architecture:** MVVM (ViewModel + StateFlow)
- **Discovery:**
  - `NsdManager` with `ServiceInfoCallback` (API 35+, no deprecated APIs)
  - `WifiP2pManager` for Miracast / Wi-Fi Direct
- **Screen capture:** `MediaProjection` + `MediaCodec` (H.264 AVC encoder)
- **Min SDK:** 35 · **Target SDK:** 36

---

## Project Structure

```
app/src/main/java/com/example/screenmirror/
├── MainActivity.kt                          # Entry point, permissions
├── data/
│   └── network/
│       ├── MirroringService.kt              # Foreground service: capture & encode
│       ├── nsd/
│       │   └── NetworkDiscoveryManager.kt   # AirPlay + Chromecast via NSD
│       └── miracast/
│           └── MiracastDiscoveryManager.kt  # Miracast via Wi-Fi Direct
├── domain/
│   └── models/
│       ├── DeviceProtocol.kt                # Enum: AIRPLAY, CHROMECAST, MIRACAST
│       └── MirrorRoute.kt                   # Discovered device model
└── ui/
    ├── screens/
    │   └── MainScreen.kt                    # Device list + scanning UI
    ├── viewmodel/
    │   └── MirroringViewModel.kt            # Discovery orchestration
    └── theme/
        ├── Color.kt
        ├── Theme.kt
        └── Type.kt
```

---

## Getting Started

### Prerequisites

- Android Studio Ladybug or newer
- Android device running **API 35+** (Android 15)
- All devices on the **same Wi-Fi network**

### Build & Run

```bash
# Debug APK
./gradlew assembleDebug

# Install directly on connected device
./gradlew installDebug
```

### Required Permissions

The app requests the following permissions at runtime:

| Permission | Purpose |
|---|---|
| `ACCESS_FINE_LOCATION` | Wi-Fi P2P peer discovery (API < 33) |
| `NEARBY_WIFI_DEVICES` | Wi-Fi P2P peer discovery (API 33+) |
| `CHANGE_WIFI_STATE` | Start Wi-Fi Direct scan |
| `CHANGE_WIFI_MULTICAST_STATE` | mDNS multicast for NSD |
| `BLUETOOTH_SCAN / CONNECT` | Optional BT-connected displays |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Screen capture service |

---

## Architecture

```
MainActivity
    └── MirroringViewModel
            ├── NetworkDiscoveryManager   (NSD — AirPlay + Chromecast)
            │       ├── _airplay._tcp     → ServiceInfoCallback
            │       └── _googlecast._tcp  → ServiceInfoCallback
            └── MiracastDiscoveryManager  (WifiP2pManager)
                    └── BroadcastReceiver → requestPeers()
```

**State flow:**
- NSD routes arrive individually → added to list if not already present
- Miracast peers arrive as a full list → replaces previous Miracast entries

---

## Roadmap

- [x] Device discovery (AirPlay, Chromecast, Miracast)
- [x] Screen capture pipeline (MediaProjection → MediaCodec)
- [ ] AirPlay streaming (RTSP/TCP)
- [ ] Chromecast streaming (Cast V2 WebSocket protocol)
- [ ] Miracast streaming (Wi-Fi Display / WFD)
- [ ] Connection status indicators in UI
- [ ] Reconnect on network change

---

## License

MIT License — see [LICENSE](LICENSE) for details.
