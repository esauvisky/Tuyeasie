# Tuyeasie

Local-first Tuya device onboarding for Home Assistant.

Tuyeasie pairs Tuya devices from an Android phone, extracts their local credentials, and imports them into the included `tuya-local` integration. It supports Wi-Fi access-point provisioning and Bluetooth-assisted pairing for Wi-Fi + BLE combo devices, without requiring the official Tuya app for normal setup.

[Pairing a camera into Home Assistant](demo/pairing.mp4) (2m36s). The clip starts on the Pair tab, pairs a camera over Bluetooth, and ends on the device page in Home Assistant with the Tuya Local controls.

## Features

- Pair, Devices and Settings tabs. Home Assistant and Wi-Fi are entered once, then pairing is scan and tap.
- Combined Wi-Fi hotspot and Bluetooth device discovery, with product names and icons.
- AP and Bluetooth activation. Cameras use a saved Tuya account. Other devices use a generated one.
- Authenticated `tuya-local` import, with the entry checked against Home Assistant's device registry.
- Encrypted storage for Home Assistant, Wi-Fi, Tuya and device credentials.
- Camera ONVIF setup on the account that owns the camera: the password is set before ONVIF is enabled, then the IP mode is toggled and restored so the camera starts RTSP. The factory password is `admin`.
- Main and substream registration on the Tuya Local device, with Generic Camera or go2rtc as alternatives.
- Local camera controls through the included PTZ camera profile.

## Repository layout

- `android/`: native Kotlin and Jetpack Compose application.
- `tuya-local/`: extended Home Assistant integration and device profiles.

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
