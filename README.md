# Tuyeasie

Tuya will sell you a plug, then charge you a developer account, a cloud project, and a support ticket before it admits the thing has a local key. Tuyeasie skips that. Pair the device from your phone, pick how it goes into Home Assistant, and it lands there, key included. No Smart Life account, no IoT portal, no `tinytuya wizard`.

https://github.com/user-attachments/assets/b059be73-5cae-42ae-8090-d10219f59be8

## Download

Grab the APK from [GitHub Reeleases](https://github.com/esauvisky/Tuyeasie/releases) and install it.

## Set it up

1. Install [Tuya Local](https://github.com/make-all/tuya-local) in Home Assistant. The stock one is what you want for plugs, bulbs, and sensors.
2. Make an administrator long-lived access token.
3. In the app, open Settings. Home Assistant URL, the token, and the 2.4 GHz Wi-Fi the device should join.
4. Open Pair. Reset the device until it blinks fast, then tap it.
5. Wait for the scan, then pick the integrations you want. Skip is fine; you can add them later from the device screen.

The phone and Home Assistant need to be on the same network. Pairing uses the internet once. After that the device is controlled locally, which was the point.

## What it does after pairing

The app looks at what the device actually is before offering anything. It reads the Tuya data point schema, finds the device on the LAN, checks all 65535 TCP ports, and probes ONVIF and RTSP. Then it asks Home Assistant which config flows are installed and offers only what both sides can do:

- **Tuya Local** when the device speaks the local Tuya protocol and has data points.
- **ONVIF** when an ONVIF service answers.
- **Generic Camera**, one per verified RTSP stream, main and substream separately.

Tick the ones you want and add them in one go. Anything that cannot be added is simply not offered. One device can have several integrations at once, each with its own Add, Open, or error state. Open jumps to it in Home Assistant.

The device screen also has:

- **Data points.** Every DP in the schema with its type and mode. Read any of them; write the writable ones with an editor that matches the type (switch, enum list, number within min and max, text).
- **Network.** LAN address, MAC, and each open port with what answered on it.
- **Details.** Device ID, local key, product ID, category, protocol version. All copyable.
- **Advanced.** ONVIF service URL, RTSP URLs, and the ONVIF login the app should use.

The Devices tab lists everything on every Tuya account saved on the phone. Long-press a device to copy its ID or local key, or to unpair it. Unpair removes every Home Assistant entry the device has, then removes it from Tuya.

Settings has an encrypted backup of accounts, Home Assistant, Wi-Fi, devices, and camera logins. Set a passphrase to make it yours. Leave it empty and it is sealed with a key built into the app, which anyone with the APK can pull out.

## Limitations

- Android only.
- The device has to be in pairing mode. If it already lives in Smart Life, remove it there first.
- Someone has to hold the phone next to the device.
- Some camera firmware sends broken ONVIF XML. The app repairs it for its own probe, but Home Assistant's ONVIF integration may still time out on it. Use Generic Camera for those.

## Tuya security cameras

Stock Tuya Local will take the camera and its controls, but it will not play the video on that same HASS device. This repo contains an overlay that adds it: main stream, substream, and the pan controls, all in one device.

1. Install stock [Tuya Local](https://github.com/make-all/tuya-local) first. The overlay is only the changed files, not a full integration.
2. Copy `tuya-local/custom_components/tuya_local/` from this repo over `config/custom_components/tuya_local/` on the Home Assistant machine.
3. Restart Home Assistant.
4. Pair the camera in the app and pick Tuya Local. The ONVIF streams the scan found are passed along with it.

Cameras that have ONVIF behind Tuya data points get it switched on during pairing, before the scan, so ONVIF and RTSP show up as options too. The default login is `admin`/`admin`; a new ONVIF password can be set from the device screen under Advanced.

The overlay is based on tuya-local 2026.10.0 and keeps that project's license in `tuya-local/LICENSE.md`. Details in `tuya-local/README.md`. Without it, add the video with ONVIF or Generic Camera instead.

## Build

JDK 17, Android SDK 35, and a Tuya Smart Life App SDK key. That key is the one piece of Tuya's process this app cannot dodge, and it is baked into the APK, not typed in by whoever installs it.

`android/local.properties`:

```properties
appKey=your_tuya_app_key
appSecret=your_tuya_app_secret
```

Release signing uses the same keystore locally and in CI. Create it once:

```bash
keytool -genkeypair -v -keystore android/release.keystore -alias tuyeasie \
  -keyalg RSA -keysize 2048 -validity 10000
```

`android/keystore.properties`, which is gitignored, next to it:

```properties
storeFile=release.keystore
storePassword=your_store_password
keyAlias=tuyeasie
keyPassword=your_key_password
```

```bash
cd android
./gradlew assembleDebug
./gradlew installDebug
```

## License

[MIT](LICENSE).
