# Tuyeasie

Local-first Tuya device onboarding for Home Assistant.

Tuyeasie pairs Tuya devices from an Android phone, extracts their local credentials, and imports them into the included `tuya-local` integration. It supports Wi-Fi access-point provisioning and Bluetooth-assisted pairing for Wi-Fi + BLE combo devices, without requiring the official Tuya app for normal setup.

## Features

- Guided Home Assistant and Wi-Fi setup.
- Combined Wi-Fi hotspot and Bluetooth device discovery.
- Product names and icons for discovered Bluetooth devices.
- AP and Bluetooth activation with a programmatically managed Tuya account.
- Authenticated `tuya-local` import with loaded-entry verification.
- Encrypted storage for Home Assistant, Wi-Fi, Tuya, and device credentials.
- Saved credentials and independent retry paths.
- Automatic camera account setup with a prominent recovery warning.
- ONVIF static-IP configuration and generated per-camera credentials.
- Main and substream RTSP registration through Generic Camera or go2rtc.
- Local camera settings through the included PTZ camera profile.

## Repository layout

- `android/`: native Kotlin and Jetpack Compose application.
- `tuya-local/`: extended Home Assistant integration and device profiles.
- `docs/`: product design and implementation plans.

## Build the Android app

Requirements: JDK 17, Android SDK 35, and a Tuya Smart Life App SDK key.

Create `android/local.properties`:

```properties
appKey=your_tuya_app_key
appSecret=your_tuya_app_secret
```

Then run:

```bash
cd android
./gradlew assembleDebug
```

Install on a connected device:

```bash
./gradlew installDebug
```

`local.properties`, signing material, build output, and virtual environments are not committed.

## Releases

Pushing a `v*` tag whose commit is contained in `main` builds, signs, and attaches the APK to a GitHub Release. The workflow requires the repository secrets documented in `.github/workflows/release.yml`.

## Home Assistant

The app expects a Home Assistant administrator long-lived access token and the extended `tuya-local` integration from this repository. Day-to-day device control after import is local; activation and camera stream registration can require internet access.
