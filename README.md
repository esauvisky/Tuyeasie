# Tuyeasie

Tuya will sell you a plug, then charge you a developer account, a cloud project, and a support ticket before it admits the thing has a local key. Tuyeasie skips that. Pair the device from your phone and it lands in Home Assistant, key included. No Smart Life account, no IoT portal, no `tinytuya wizard`.

https://github.com/user-attachments/assets/12284b7a-01b0-45f3-916b-752d80196c42

## Download

Grab the APK from [GitHub Releases](https://github.com/esauvisky/Tuyeasie/releases) and install it.

## Set it up

1. Install [Tuya Local](https://github.com/make-all/tuya-local) in Home Assistant. The stock one is what you want for plugs, bulbs, and sensors.
2. Make an administrator long-lived access token.
3. In the app, open Settings. Home Assistant URL, the token, and the 2.4 GHz Wi-Fi the device should join.
4. Open Pair. Reset the device until it blinks fast, then tap it.

The phone and Home Assistant need to be on the same network. Pairing uses the internet once. After that the device is controlled locally, which was the point.

## Limitations

- Android only.
- The device has to be in pairing mode. If it already lives in Smart Life, remove it there first.
- Someone has to hold the phone next to the device.

## Tuya security cameras

Stock Tuya Local will take the camera and its controls, but it will not play the video on that same HASS device. This repo contains a patched copy of Tuya Local that does: main stream, substream, and the pan controls, all in one device.

1. Copy `tuya-local/custom_components/tuya_local` to `config/custom_components/tuya_local` on the Home Assistant machine, over the copy you have.
2. Restart Home Assistant.
3. Pair the camera in the app.

The username is `admin`. The patched integration is tuya-local 2026.10.0 plus the stream wiring, and it keeps that project's license in `tuya-local/LICENSE.md`. Without the patch the app still adds the video but as separate Generic Camera entries.

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
