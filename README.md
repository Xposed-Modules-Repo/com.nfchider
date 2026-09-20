# NFC Hider

[![Build](https://github.com/Xposed-Modules-Repo/com.nfchider/actions/workflows/build.yml/badge.svg)](https://github.com/Xposed-Modules-Repo/com.nfchider/actions/workflows/build.yml)

An Xposed module that hides NFC (Near Field Communication) from selected apps on Android, and simulates GPS location with map trajectory playback.

**Current version: 1.2**

## Features

### NFC hiding
Hooks NFC-related framework APIs so scoped apps believe the device has no NFC hardware.

### Location simulation (new in 1.2)
- Draw a route on the map (OpenStreetMap via osmdroid, no API key needed) or import a GPX track
- Playback modes: loop / ping-pong / one-shot, speed from 1 km/h up to 300 km/h
- Scoped apps see a realistic, time-based moving GPS fix:
  - `LocationManager`: `getLastKnownLocation`, `getCurrentLocation`, `requestLocationUpdates` / `requestSingleUpdate` (listener, executor and PendingIntent variants), provider enabled state
  - Google Play services `FusedLocationProviderClient`: `getLastLocation`, `getCurrentLocation`, `requestLocationUpdates`, mock APIs
  - Delivered `Location` objects carry no mock flag (`isMock()` / `isFromMockProvider()` return false)
- Config is shared through libxposed remote preferences (push updates) with an exported ContentProvider fallback

## Usage

1. Install the module in Xposed Installer / LSPosed
2. Enable the module for the target apps
3. NFC will be hidden from those apps
4. (Optional) Open **NFC Hider** → 位置模拟, draw a route and press 开始模拟, then restart the target app
5. The simulated position keeps moving even when the module app is killed; press 停止模拟 to stop

## Compatibility

- Android 10+ (API 29+)
- Xposed Framework / LSPosed (libxposed API 101+, target 102)

## Known limitations

- AMap / Baidu LBS SDK network locations are not covered (their GPS part is)
- PendingIntent variants of the fused API are left untouched
- Apps running on frameworks without remote-preferences support fall back to a polled config channel, which may not be reachable on Android 11+ due to package visibility rules

## Build

```bash
git clone https://github.com/Xposed-Modules-Repo/com.nfchider.git
cd com.nfchider
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/NfcHider-debug.apk`.

> **Note:** The project uses Jetpack Compose for the settings UI. Building requires:
> - JDK 17+
> - Android SDK 37+

## Download

Pre-built APKs are available from the [Releases](https://github.com/Xposed-Modules-Repo/com.nfchider/releases) page.

## License

This project is licensed under the MIT License.
