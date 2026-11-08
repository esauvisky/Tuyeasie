# Patched Tuya Local

Only the files Tuyeasie changes, to overlay on a stock [Tuya Local](https://github.com/make-all/tuya-local) install. Not a full integration on its own.

## What changes

- `custom_components/tuya_local/importer.py` (new): `tuya_local/import_device` and `tuya_local/configure_streams` WebSocket commands. The app uses these to import a device with its local key and to write camera stream URLs.
- `custom_components/tuya_local/__init__.py`: registers the importer.
- `custom_components/tuya_local/config_flow.py`: `async_step_import`, the flow the importer creates entries through.
- `custom_components/tuya_local/camera.py`: camera entities read `main_stream_url` and `sub_stream_url` from the config entry and play them.
- `custom_components/tuya_local/const.py`: the two stream URL keys.
- `custom_components/tuya_local/devices/tuya_ptz_camera.yaml` (new): the PTZ camera profile.
- `tests/test_importer.py`: tests for the importer. These run inside upstream's test suite, not standalone.

Based on Tuya Local 2026.10.0. Everything else comes from upstream.

## Install

Install Tuya Local first, then copy these files over it:

```bash
cp -r custom_components/tuya_local/* /config/custom_components/tuya_local/
```

`/config` is the Home Assistant config directory. Restart Home Assistant afterwards.

To update the overlay later, replace these same files again. To track upstream, install a newer Tuya Local and re-apply the overlay. If upstream changes one of these files, the overlay has to be rebased onto it.

Without the overlay the app still works: it falls back to the normal config flow for devices and to Generic Camera entries for streams. The overlay is only needed to have the streams and the controls on the same Tuya Local device.

## License

Upstream's license applies, in `LICENSE.md`.
