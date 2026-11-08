# Tuyeasie

Tuya will sell you a plug, then charge you a developer account, a cloud project, and a support ticket before it admits the thing has a local key. Tuyeasie skips that. Pair the device from your phone and it lands in Home Assistant, key included. No Smart Life account, no IoT portal, no `tinytuya wizard`.

[Pairing a device](demo/pairing.mp4) (1m18s, played at double speed).

## Download

Not on the Play Store. Grab the APK from [GitHub Releases](https://github.com/esauvisky/Tuyeasie/releases) and install it. Android will complain about the browser. Let it.

The published APK is still [v0.1.0](https://github.com/esauvisky/Tuyeasie/releases/tag/v0.1.0). Version 1.0.0 is on `main` and ships when a `v1.0.0` tag is pushed.

## Set it up

1. Install [Tuya Local](https://github.com/make-all/tuya-local) in Home Assistant. The stock one is what you want for plugs, bulbs, and sensors.
2. Make an administrator long-lived access token. Profile, Security, Create token.
3. In the app, open Settings. Home Assistant URL, the token, and the 2.4 GHz Wi-Fi the device should join.
4. Open Pair. Reset the device until it blinks fast, then tap it.

The phone and Home Assistant need to be on the same network. Pairing uses the internet once. After that the device is controlled locally, which was the point.

## Limitations

- Android only. Someone has to hold the phone next to the device.
- The device has to be in pairing mode. If it already lives in Smart Life, remove it there first. That also mints a new local key, which is Tuya's idea of loyalty.
- Tuya devices take one local connection. A second integration talking to the same device will lose.
- Cameras are the awkward ones. They pair to a Tuya account you type in, and ONVIF keeps its own password. Lose either and the camera wants a factory reset. Details at the bottom.

## Tuya security cameras

Stock Tuya Local will take the camera and its controls. It will not play the video on that same device. This repo carries a patched copy that does: main stream, substream, and the pan controls, one device.

1. Copy `tuya-local/custom_components/tuya_local` to `config/custom_components/tuya_local` on the Home Assistant machine, over the copy you have.
2. Restart Home Assistant.
3. Pair the camera in the app.

The username is `admin`. The patched integration is tuya-local 2026.10.0 plus the stream wiring, and it keeps that project's license in `tuya-local/LICENSE.md`. Without the patch the app still adds the video, as separate Generic Camera entries.

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

`./gradlew assembleRelease` signs with that file when it exists and builds an unsigned APK when it does not. CI does the same thing: the keystore is stored base64 in the `ANDROID_KEYSTORE_BASE64` secret, with `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. The release workflow writes `keystore.properties` from those and runs the same Gradle task. One key, two places, nothing committed.

```bash
cd android
./gradlew assembleDebug
./gradlew installDebug
```

Pushing a `v*` tag on `main` builds the signed APK and attaches it to a GitHub Release.

## License

[MIT](LICENSE). The bundled tuya-local keeps its own license, in `tuya-local/LICENSE.md`.
