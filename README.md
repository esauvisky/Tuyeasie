# Tuyeasie

Tuyeasie pairs a Tuya device from an Android phone and adds it to Home Assistant. You put the device in pairing mode, tap it, and it shows up in the Tuya Local integration with its local key. You do not need the Smart Life app for a normal plug, bulb, or sensor.

[Pairing a camera into Home Assistant](demo/pairing.mp4) (1m18s, played at double speed).

## Download

The app is not on the Play Store. Download the APK from [GitHub Releases](https://github.com/esauvisky/Tuyeasie/releases) and install it. Android will ask you to allow installs from the browser.

The newest release is [v0.1.0](https://github.com/esauvisky/Tuyeasie/releases/tag/v0.1.0). The pairing screen in the video is newer than that APK and is only on `main` until the next release tag.

## Set it up

1. In Home Assistant, install Tuya Local. Stock Tuya Local is enough for plugs, bulbs, and sensors. Cameras need the patched copy, below.
2. Create an administrator long-lived access token: your Home Assistant profile, Security, Create token.
3. In the app, open Settings. Enter the Home Assistant URL (for example `http://homeassistant.local:8123`), the token, and the 2.4 GHz Wi-Fi the device should join.
4. Open Pair. Reset the device until its light blinks fast, then tap it.

The phone and Home Assistant have to be on the same network. Pairing needs internet. After that, control of the device is local.

## Limitations

- Android only.
- The device must be put in pairing mode. A device already in Smart Life has to be removed there first, which gives it a new local key.
- Cameras pair to a Tuya account you type in. If you lose that email and password, the camera cannot be paired again without a factory reset.
- A camera's ONVIF password starts as `admin`. The app replaces it with a generated one and stores it on the phone. A reboot does not clear it. Only a factory reset puts `admin` back, and that unpairs the camera.
- Tuya devices accept one local connection. Turning ONVIF on toggles the camera's IP mode, so its address can change. The app updates Tuya Local when it does.
- With stock Tuya Local, a camera's video is added as separate Generic Camera entries. The picture and the pan controls are not on the same device.

## Tuya security cameras

A camera's video only appears on its Tuya Local device if Home Assistant is running the patched integration from this repository. That copy adds the stream URLs to the device, so the main stream, the substream, and the controls (pan, privacy, motion) are one device. Stock Tuya Local does not have this, and the app falls back to Generic Camera.

Install the patched copy over the one you already have:

1. Copy `tuya-local/custom_components/tuya_local` from this repository to `config/custom_components/tuya_local` on the Home Assistant machine.
2. Restart Home Assistant.
3. Pair the camera in the app. ONVIF is set up during pairing. The username is `admin`.

The patched integration is based on tuya-local 2026.10.0. It keeps that project's license, in `tuya-local/LICENSE.md`.

## Build

Requirements: JDK 17, Android SDK 35, and a Tuya Smart Life App SDK key.

Create `android/local.properties`:

```properties
appKey=your_tuya_app_key
appSecret=your_tuya_app_secret
```

```bash
cd android
./gradlew assembleDebug
./gradlew installDebug
```

`local.properties`, signing material, and build output are not committed. Pushing a `v*` tag on `main` builds, signs, and attaches the APK to a GitHub Release. The workflow secrets are listed in `.github/workflows/release.yml`.

## License

Tuyeasie is released under the [MIT license](LICENSE). The bundled `tuya-local` integration keeps its own license, in `tuya-local/LICENSE.md`.
