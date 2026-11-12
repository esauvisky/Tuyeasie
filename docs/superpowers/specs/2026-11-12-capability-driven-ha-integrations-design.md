# Capability-driven Home Assistant integrations and device controls

## Context
Tuyeasie currently has a single Tuya Local import plus a camera-feed destination. This does not represent devices with different capabilities: `.13` can use Tuya Local and can enable ONVIF through Tuya data points; `.14` exposes ONVIF and RTSP but has no Tuya data points. Home Assistant's ONVIF flow timed out on `.14`; observed firmware returns malformed XML from `GetServices` (`</tds:GetServicesResponse` is missing its closing `>`), while Tuyeasie can repair that response and discover streams itself.

The change makes integration selection depend on a complete device scan, tracks multiple independent Home Assistant entries per device, provides schema-aware manual data-point access, and removes go2rtc and the backup passphrase UI.

## Approved decisions
- Use a capability catalog with independent integration options and HA entry state per physical device.
- After the scan and any required Tuya-controlled ONVIF setup, always show an integration chooser, even if there is one usable option. Include Skip; do not auto-add.
- List every detected scanner protocol/service. Enable Add only when HA reports a corresponding config-flow handler and Tuyeasie has a concrete adapter and sufficient configuration data. Unsupported detected labels stay visible and disabled with a reason. Never start config flows speculatively.
- Tuya Local requires Tuya data points/local protocol and a matching profile; ONVIF requires an ONVIF service; Generic Camera requires a verified RTSP URI.
- A physical device can have multiple independent HA integration entries. Each shows Add/Open/disabled/error state and its own entry ID. Open navigates to the corresponding HA device/entry page. Long-press on the physical device offers whole-device Unpair: remove all tracked HA entries, unpair from Tuya, then clear local state.
- Enable and probe Tuya-controlled ONVIF before the chooser whenever the device schema contains the dynamic ONVIF control codes. Pass discovered stream URLs to Tuya Local when creating/updating its entry.
- Remove go2rtc and the Camera feeds Settings card, feed/destination dialog, and singular Camera feed workflow.
- Device page sections: Home Assistant, Network, Details, Data Points. Put ONVIF service URL and detected main/substream RTSP URLs in collapsed Advanced as read-only copyable values; put editable ONVIF username/password there too.
- Data-point editors are type-aware. Only schema-writable points may be written. Read with `getDpList`; write with `publishDps`; after successful writes re-read the point.
- Backup export has no passphrase prompt. Use AES-256-GCM and one hardcoded app-wide key with fresh random IVs. Portable across installs, with the key extractable from the APK.

## Dynamic integration discovery and catalog

### Home Assistant handler discovery
- Query the authenticated REST endpoint `GET /api/config/config_entries/flow_handlers`; it lists available handlers without starting any flow.
- Match scanned protocol/service labels to installed HA handler domains through an explicit, maintained alias/catalog table. Examples: Tuya Local → `tuya_local`, ONVIF → `onvif`, RTSP → Generic Camera's `generic` handler.
- For any other detected service, display its scanner label and port. Offer Add only when a maintained adapter can build a valid HA flow request from known device/scan data and the required handler is installed. Otherwise show “Detected; no compatible Home Assistant setup flow available.”
- When Add is tapped, start only that selected handler's flow. Render the returned supported schema fields (text/password, boolean, number, select) and advance through the flow's returned steps. If a field/selector is unsupported or required data is unavailable, preserve the option as disabled with the reason rather than submitting fabricated defaults.
- Scanner labels are evidence of a detected service, not a guarantee that an HA integration exists or that its setup flow can succeed. In particular, a port number alone never enables Add.

### Persisted per-device integration records
For each `(deviceId, integrationKey)`, persist:
- handler/domain, label, matched service/port evidence, and availability plus reason;
- status: available, adding, added, failed, or removed;
- HA config-entry ID and HA device-registry ID when present;
- sanitized error and last attempt/update time.

Rows are independent. An error adding one integration must not block another option. Skip creates no entry and does not prevent adding later. Successful entries remain Open actions after rescanning/restarting. Whole-device Unpair deletes all associated entries before Tuya removal; if HA cleanup fails, retain Tuya and local state and report the error.

### Pair and scan order
1. Pair/activate and capture device identity, MAC, Tuya capability/schema, and local protocol capability.
2. Resolve the private LAN address. Devices with no Tuya DPs try ONVIF discovery/MAC matching first, even if the Tuya SDK says `hasLanCommunication=true`; data-point-capable Tuya devices use Tuya LAN discovery and can fall back to ONVIF MAC discovery.
3. When ONVIF control DPs exist, enable ONVIF and probe its streams before the integration chooser.
4. Complete the full TCP scan and service identification before presenting the chooser. Do not run the high-concurrency scan concurrently with Tuya Local's handshake.
5. Discover available HA handlers and determine Tuya Local profile support as applicable. If supported, its initial import/config entry receives detected streams.
6. Show the chooser for every device: available options have Add, unsupported detected options are disabled with explanations, and Skip is available.
7. Persist scan and integration states. Each Add updates only its integration row and entry association.

## Integration adapters and Home Assistant behavior
- Refactor the current stream destination path into adapters that add/find an HA entry and return its entry/device association. Remove the global single-destination enum.
- Tuya Local: use the existing overlay import/configure commands where installed and standard Tuya Local flow where not; provide IP, protocol, local key, and discovered stream URLs. A no-profile result disables/fails only Tuya Local.
- ONVIF: use HA's `onvif` config flow with the saved credentials. Surface its exact flow failure, including `.14` timeout/XML incompatibility, and leave Generic Camera available as fallback.
- Generic Camera: configure a verified RTSP URL and matched credentials. A detected RTSP port without a path verified by DESCRIBE is detected-only.
- Other adapters are added incrementally through the discovered-handler/schema mechanism; unsupported data requirements remain disabled. Do not assume every handler returned by HA can be configured generically.
- Open navigates to the associated HA device page when the device registry links it; if no HA device exists, use the integration entry page.
- Unpair obtains all per-integration entry IDs (including migrated legacy ONVIF, Generic Camera, and Tuya Local IDs), removes them from HA, then removes the Tuya device and clears device-specific cached scan/schema/integration/ONVIF state.
- The HA ONVIF timeout on `.14` is a known compatibility issue: Tuyeasie repairs the malformed local probe response, but HA's own parser may reject it. Keep that option's failure visible/retryable and Generic Camera usable when RTSP is verified.

## Device page and manual data points
- **Home Assistant:** one row per discovered integration with Add/Open/disabled/error. No Camera feed row.
- **Network:** LAN address and source, MAC, scan timestamp/progress, and open TCP ports with probe results.
- **Details:** read-only/copyable Tuya device ID, local key, product ID, category, and protocol version.
- **Data Points:** a standalone block listing DP ID, code, schema name, declared type/access mode, current value, and read/write state.
- Extend cached schema records with DP ID, code, name, mode, type, and property JSON. Do not infer writability or value type when schema metadata is absent/unknown.
- Read/re-read values with `IThingDevice.getDpList` and the SDK's current device DP map/listener.
- For writable supported types, use schema-derived controls: Boolean switch; enum selector from its declared range; integer/decimal editor constrained by min/max/scale; other types only when their property defines an unambiguous serialization. Set calls `publishDps` with typed JSON, displays any row-local callback error, and reads back on success. Unknown or read-only data points remain visible and non-editable.
- **Advanced**, collapsed by default: ONVIF service URL, main RTSP URL, substream URL as read-only copy fields; editable username and password. Saving credentials triggers targeted ONVIF/RTSP reprobe; use matched credentials on subsequent HA Add/reconfigure.

## Settings and encrypted backup
- Remove `StreamDestination`, default feed destination, go2rtc settings fields/state/client and all consumers, the destination chooser, and Camera feeds Settings card. Retain only settings required by the remaining setup flows.
- Remove passphrase state, validation, and dialog from `BackupCard`/`MyDevicesViewModel`.
- `BackupExporter` seals secrets with AES-256-GCM, a single hardcoded 256-bit application key, and a fresh random 12-byte IV per value. Keep Tuya account passwords, HA token, Wi-Fi password, camera credentials, credential-bearing stream URLs, and any exported writable DP values encrypted. Never put a local key in plaintext.
- Remove unused go2rtc settings from newly exported files. Existing in-app preferences remain protected by Android EncryptedSharedPreferences. The fixed export key is shared by app installations and extractable from the APK; the export UI must say it is portable app-level encryption, not a user-held recovery secret.
- Update export format/version and include per-integration HA entry associations and the expanded schema/integration state only when needed for restore; preserve JSON validity and unique nonces.

## Files likely affected
- `ui/OnboardingViewModel.kt`: scan-first chooser, per-integration state/actions, Tuya ONVIF-before-chooser sequence, manual DP read/write state, removal of single-feed/default-destination/go2rtc logic.
- `ui/DeviceScreen.kt`: per-integration Home Assistant block, separate Network/Details/Data Points cards, Advanced ONVIF URLs and editable credentials.
- `data/DeviceNetwork.kt`: full schema, integration catalog/status and option models.
- `data/TuyaAccountRepository.kt`: capture full Tuya schema metadata for all devices.
- `data/SecureSettingsStore.kt`: per-integration persistence, schema/current DP state as appropriate, account/device cache cleanup, unused go2rtc settings removal.
- `hass/HomeAssistantClient.kt`: config-flow-handler discovery, schema-driven selected flow, integration adapters, per-entry Open/remove and entry associations.
- `data/BackupExporter.kt`, `ui/MyDevicesViewModel.kt`, `ui/DevicesScreen.kt`, `ui/SettingsScreen.kt`: hardcoded-key export and remove passphrase/go2rtc UI/state.
- `data/Go2RtcClient.kt`: delete after removing all callers.
- `data/CameraOnvifRepository.kt`: ensure pre-chooser ONVIF setup and use discovered stream/credential state.
- `android/app/src/test/java/com/emi/tuyeasie/hass/HomeAssistantClientTest.kt` and existing applicable tests: handler mapping, schema-flow state, and integration-entry association tests.
- Modify the Tuya Local overlay (`tuya-local/custom_components/tuya_local/importer.py`) only if its initial import path must accept stream URLs; keep its existing command tests passing.

## Verification
1. Unit tests for protocol-to-handler option classification, flow-handler response mapping, integration state persistence, DP type conversion/read-only gating/write payloads, and AES-GCM roundtrip plus tamper rejection. Keep existing HA and Tuya Local tests green.
2. Compile/test and install debug APK on `.90`.
3. `.13`: verify scan and profile detection; ONVIF setup completes before chooser; Tuya Local import includes streams; ONVIF and Generic Camera can also be independently added; each becomes Open; Unpair removes all entries and then Tuya pairing.
4. `.14`: resolve by ONVIF MAC without waiting for Tuya UDP; scan confirms ONVIF `:8080` and RTSP `:6554`; chooser offers ONVIF and Generic Camera while Tuya Local is unavailable; HA ONVIF failure is row-local/retryable and Generic Camera adds verified `stream_0`/`stream_1`; Open targets the right HA device/entry.
5. Manual DP controls match schema type, property and `mode`; read-only/unknown points cannot be set; successful writes refresh values; failures stay on the relevant row.
6. Check immediate chooser always appears (including one option), Skip, disabled detected protocols, per-entry status persistence, Advanced collapse, copied IDs/local key/URLs, whole-device long-press Unpair, removed Camera feeds/go2rtc UI, cached device list, and passphrase-free portable export.
7. Do not run destructive HA entry deletion or Tuya unpair against the live phone/Home Assistant during implementation verification without a separate explicit user approval; use unit/local-flow tests for those paths.

## Security constraint
The hardcoded backup key is intentionally extractable from the APK and shared by installations. AES-GCM protects file integrity and contents only against readers who do not possess that key; it is not user-specific backup protection.

## Self-review notes
- Dynamic flow discovery does not mean arbitrary flow automation: the supported handler list gates explicit adapters, and unsupported schemas remain disabled instead of guessing required values.
- `.14` can be reported as ONVIF by Tuyeasie while Home Assistant's ONVIF integration still fails on malformed `GetServices`; verified RTSP Generic Camera remains an independent fallback.
- The old singular `OnvifRecord.destination/entryIds` can migrate into new per-integration records; do not delete legacy associations until all old IDs are copied, so Unpair still cleans up entries created by prior versions.
- Keep the per-device ONVIF password setter and generic ONVIF login credentials distinct in the view-model/API: the former changes a Tuya camera DP; the latter tells Tuyeasie what the standalone ONVIF server accepts.
- A manual DP write may affect device behavior. Surface the exact DP code/value and require an explicit Set action; do not auto-write during scan/import apart from the approved ONVIF setup sequence.
