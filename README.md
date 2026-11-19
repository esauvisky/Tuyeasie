# Tuyeasie

Tuya will sell you a smart plug, then ask for a developer account, a cloud project and an expiring API trial before it admits the thing has a local key. Tuyeasie skips that part.

It is an Android app that pairs a Tuya device from your phone and adds it to Home Assistant with local control. No Smart Life account, no IoT portal, no `tinytuya wizard`.

<!-- VIDEO: paste the GitHub user-attachments link on the next line -->

## Quick start

1. Install [Tuya Local](https://github.com/make-all/tuya-local) in Home Assistant.
2. In Home Assistant, create a long-lived access token on an administrator account.
3. Install the APK from [GitHub Releases](https://github.com/esauvisky/Tuyeasie/releases).
4. In the app, open Settings and enter the Home Assistant URL, the token, and the 2.4 GHz Wi-Fi your devices should join.
5. Open Pair, reset the device until it blinks fast, tap it, and give it a name.
6. Tick the integrations you want and press Add.

The phone and Home Assistant must be on the same network. Pairing uses the internet once; after that the device is controlled locally.

## Features

- **Pair from your phone.** Bluetooth and Wi-Fi hotspot pairing, without Smart Life or a Tuya developer account. The app creates a Tuya account in the background, or asks for one when the device needs it.
- **Add to Home Assistant.** Works with devices supported by [Tuya Local](https://github.com/make-all/tuya-local), with additional ONVIF and RTSP support for cameras. The app checks both the device's capabilities and your installed integrations before offering setup options.
- **See what is on your network.** Automatic IP discovery, online status, and a full TCP port scan that identifies the services answering on each open port.
- **Read and write data points.** View the device's schema and live values, with controls for booleans, enums, numbers, bitmaps, strings and raw hex.
- **Access device information.** Copy local keys, device IDs, product IDs and network details without a separate tool.
- **Move settings between devices.** Export Tuya accounts, Home Assistant, Wi-Fi and camera settings, then import them on another phone or tablet. Secrets can be encrypted with your own passphrase; without one, the file uses an extractable app-wide key.

## Cameras

Tuyeasie is not just for cameras, but they get extra attention. It discovers ONVIF services and RTSP streams automatically, including main and substreams, and enables ONVIF during pairing when the camera exposes a supported switch for it.

Choose how each camera goes into Home Assistant:

- **Tuya Local** for device controls and settings.
- **ONVIF** for the camera's supported video and control features.
- **Generic Camera** for each verified RTSP stream, with entity IDs derived from the device name, such as `camera.garage_camera` and `camera.garage_camera_substream`.

These can be combined: use Tuya Local for controls and ONVIF or Generic Camera for video, or add several integrations for the same device.

## Limitations

- Android only.
- The device must be in pairing mode. If it is already in Smart Life, remove it there first.
- The phone must be near the device while pairing.
- Some camera firmware returns malformed ONVIF XML. The app repairs it for its own probing, but Home Assistant's ONVIF integration may still time out on it. Use Generic Camera for those cameras.
- The app includes a Tuya App SDK key, which Tuya's SDK requires.

## License

MIT. The overlay keeps tuya-local's license in `tuya-local/LICENSE.md`.
