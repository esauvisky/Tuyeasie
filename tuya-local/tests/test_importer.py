import asyncio
import logging
from unittest.mock import AsyncMock, MagicMock

import pytest
import tinytuya
import voluptuous as vol
from homeassistant.const import CONF_HOST
from homeassistant.data_entry_flow import FlowResultType
from pytest_homeassistant_custom_component.common import MockConfigEntry
from tinytuya.core.error_helper import ERR_PAYLOAD, error_json

from custom_components.tuya_local import async_setup
from custom_components.tuya_local.const import (
    CONF_DEVICE_CID,
    CONF_DEVICE_ID,
    CONF_LOCAL_KEY,
    CONF_MANUFACTURER,
    CONF_MODEL,
    CONF_POLL_ONLY,
    CONF_PROTOCOL_VERSION,
    CONF_TYPE,
    DOMAIN,
)
from custom_components.tuya_local.importer import (
    IMPORT_TYPE,
    IMPORT_VERSION,
    DeviceImportError,
    VerifiedImport,
    async_import_device,
    async_verify_import,
    validate_import,
)


@pytest.fixture(autouse=True)
def enable_integrations(enable_custom_integrations):
    yield


VALID = {
    "import_version": IMPORT_VERSION,
    "device_id": "deviceid",
    "local_key": ")<jO<@)'P1|kR$Kd",
    "name": "Air monitor",
    "product_id": "productid",
}


def test_validate_import_defaults():
    request = validate_import(VALID)
    assert request.device_id == "deviceid"
    assert request.protocol_version == "auto"
    assert request.ip is None
    assert VALID["local_key"] not in repr(request)


def test_validate_import_normalizes_protocol_and_ip():
    request = validate_import(
        {**VALID, "protocol_version": "3.4", "ip": "192.168.1.22"}
    )
    assert request.protocol_version == 3.4
    assert request.ip == "192.168.1.22"


@pytest.mark.parametrize("field", ["device_id", "local_key", "name", "product_id"])
def test_validate_import_rejects_missing_fields(field):
    with pytest.raises(vol.MultipleInvalid):
        validate_import({key: value for key, value in VALID.items() if key != field})


def test_validate_import_rejects_future_version():
    with pytest.raises(DeviceImportError) as error:
        validate_import({**VALID, "import_version": 2})
    assert error.value.code == "unsupported_import_version"


def test_validate_import_rejects_unsupported_protocol():
    with pytest.raises(DeviceImportError) as error:
        validate_import({**VALID, "protocol_version": "9.9"})
    assert error.value.code == "unsupported_protocol"


def test_validate_import_rejects_boolean_version():
    with pytest.raises(vol.MultipleInvalid):
        validate_import({**VALID, "import_version": True})


@pytest.mark.parametrize("value", [None, True, 1, 1.5, [], {}])
@pytest.mark.parametrize("field", ["device_id", "local_key", "name", "product_id"])
def test_validate_import_rejects_non_string_required_fields(field, value):
    with pytest.raises(vol.MultipleInvalid):
        validate_import({**VALID, field: value})


def test_validate_import_keeps_protocol_coercion():
    request = validate_import({**VALID, "protocol_version": 3.4})
    assert request.protocol_version == 3.4


@pytest.fixture
def import_request():
    return validate_import(VALID)


@pytest.fixture
def device(mocker):
    result = MagicMock()
    result._protocol_configured = 3.4
    result._api.parent = None
    result.async_inferred_type = AsyncMock(return_value="kogan_kahtp_heater")
    return result


async def test_verify_discovers_and_matches(hass, mocker, import_request, device):
    mocker.patch(
        "custom_components.tuya_local.importer.scan_for_device",
        return_value={
            "ip": "192.168.1.22",
            "version": 3.4,
            "product_id": "localpid",
            "data": {"productKey": "nestedpid"},
        },
    )
    mocker.patch(
        "custom_components.tuya_local.importer.async_test_connection",
        new=AsyncMock(return_value=device),
    )
    verified = await async_verify_import(hass, import_request)
    assert verified.config[CONF_HOST] == "192.168.1.22"
    assert verified.config["type"] == "kogan_kahtp_heater"
    assert VALID["local_key"] not in repr(verified)
    device.set_detected_product_id.assert_any_call("productid")
    device.set_detected_product_id.assert_any_call("localpid")
    device._api.set_socketPersistent.assert_called_with(False)


async def test_verify_local_product_id_selects_profile(
    hass, mocker, import_request, device
):
    mocker.patch(
        "custom_components.tuya_local.importer.scan_for_device",
        return_value={
            "ip": "192.168.1.22",
            "version": 3.4,
            "product_id": "localpid",
            "data": {"productKey": "nestedpid"},
        },
    )
    mocker.patch(
        "custom_components.tuya_local.importer.async_test_connection",
        new=AsyncMock(return_value=device),
    )

    async def inferred_type():
        selected = device.set_detected_product_id.call_args.args[0]
        return "local_profile" if selected == "localpid" else None

    device.async_inferred_type.side_effect = inferred_type
    verified = await async_verify_import(hass, import_request)
    assert verified.config[CONF_TYPE] == "local_profile"
    assert device.set_detected_product_id.call_args_list[0].args == ("productid",)
    assert device.set_detected_product_id.call_args_list[1].args == ("localpid",)


@pytest.mark.parametrize(
    "negotiate", [False, True], ids=["supplied_key", "negotiated_key"]
)
async def test_verify_suppresses_tinytuya_status_and_session_key_debug(
    hass, mocker, caplog, negotiate
):
    caplog.set_level(logging.DEBUG)
    caplog.set_level(logging.DEBUG, logger="tinytuya.core.XenonDevice")
    request = validate_import(
        {**VALID, "ip": "192.168.1.22", "protocol_version": "3.4"}
    )
    hass.data.setdefault(DOMAIN, {})
    negotiated_keys = []

    def status_error(api, *args, **kwargs):
        if negotiate:
            api.local_nonce = bytes(range(16))
            api.remote_nonce = bytes(range(16, 32))
            api._negotiate_session_key_generate_finalize()
            negotiated_keys.append(api.local_key)
        return error_json(ERR_PAYLOAD)

    mocker.patch.object(
        tinytuya.Device, "_send_receive", autospec=True, side_effect=status_error
    )

    with pytest.raises(DeviceImportError) as error:
        await async_verify_import(hass, request)

    assert error.value.code == "handshake_failed"
    assert VALID["local_key"] not in caplog.text
    assert repr(VALID["local_key"].encode()) not in caplog.text
    if negotiate:
        assert negotiated_keys
        assert repr(negotiated_keys[0]) not in caplog.text
    assert "Status request returned an error" not in caplog.text
    assert "Session key negotiate success" not in caplog.text


async def test_verify_does_not_hide_application_exception_logs(hass, mocker, caplog):
    caplog.set_level(logging.DEBUG)
    request = validate_import(
        {**VALID, "ip": "192.168.1.22", "protocol_version": "3.4"}
    )
    hass.data.setdefault(DOMAIN, {})
    mocker.patch.object(
        tinytuya.Device,
        "_send_receive",
        autospec=True,
        side_effect=RuntimeError("application failure"),
    )

    with pytest.raises(DeviceImportError):
        await async_verify_import(hass, request)

    assert "Failed to refresh device state" in caplog.text
    assert "application failure" in caplog.text


async def test_verify_rejects_unreachable_device(hass, mocker, import_request):
    mocker.patch(
        "custom_components.tuya_local.importer.scan_for_device",
        return_value={"ip": None},
    )
    with pytest.raises(DeviceImportError) as error:
        await async_verify_import(hass, import_request)
    assert error.value.code == "discovery_failed"


async def test_verify_rejects_unsupported_profile(hass, mocker, import_request, device):
    device.async_inferred_type.return_value = None
    mocker.patch(
        "custom_components.tuya_local.importer.scan_for_device",
        return_value={"ip": "192.168.1.22", "version": 3.4},
    )
    mocker.patch(
        "custom_components.tuya_local.importer.async_test_connection",
        new=AsyncMock(return_value=device),
    )
    with pytest.raises(DeviceImportError) as error:
        await async_verify_import(hass, import_request)
    assert error.value.code == "unsupported_profile"
    assert error.value.product_id == "productid"


async def test_verify_manual_ip_skips_discovery(hass, mocker, device):
    import_request = validate_import({**VALID, "ip": "192.168.1.22"})
    discovery = mocker.patch("custom_components.tuya_local.importer.scan_for_device")
    mocker.patch(
        "custom_components.tuya_local.importer.async_test_connection",
        new=AsyncMock(return_value=device),
    )
    verified = await async_verify_import(hass, import_request)
    assert verified.config[CONF_HOST] == "192.168.1.22"
    discovery.assert_not_called()


async def test_verify_rejects_failed_handshake(hass, mocker, import_request):
    mocker.patch(
        "custom_components.tuya_local.importer.scan_for_device",
        return_value={"ip": "192.168.1.22", "version": 3.4},
    )
    mocker.patch(
        "custom_components.tuya_local.importer.async_test_connection",
        new=AsyncMock(return_value=None),
    )
    with pytest.raises(DeviceImportError) as error:
        await async_verify_import(hass, import_request)
    assert error.value.code == "handshake_failed"
    assert hass.config_entries.async_entries(DOMAIN) == []


@pytest.fixture
def bypass_setup(mocker):
    mocker.patch("custom_components.tuya_local.async_setup_entry", return_value=True)


def _data(key="key"):
    return {
        CONF_DEVICE_ID: "deviceid",
        CONF_HOST: "192.168.1.22",
        CONF_LOCAL_KEY: key,
        CONF_PROTOCOL_VERSION: 3.4,
        CONF_POLL_ONLY: False,
        CONF_TYPE: "kogan_kahtp_heater",
    }


async def test_import_adds_new_entry(hass, mocker, bypass_setup):
    mocker.patch(
        "custom_components.tuya_local.importer.async_verify_import",
        new=AsyncMock(return_value=VerifiedImport(_data("newkey"))),
    )
    result = await async_import_device(hass, VALID)
    assert result == {"device_id": "deviceid", "outcome": "added", "import_version": 1}
    assert len(hass.config_entries.async_entries(DOMAIN)) == 1


async def test_import_adds_new_entry_aborts_pending_discovery(
    hass, mocker, bypass_setup
):
    pending = await hass.config_entries.flow.async_init(
        DOMAIN,
        context={"source": "integration_discovery"},
        data={CONF_DEVICE_ID: "deviceid", CONF_HOST: "192.168.1.22"},
    )
    assert pending["type"] == FlowResultType.FORM
    mocker.patch(
        "custom_components.tuya_local.importer.async_verify_import",
        new=AsyncMock(return_value=VerifiedImport(_data("newkey"))),
    )

    result = await async_import_device(hass, VALID)

    assert result["outcome"] == "added"
    assert len(hass.config_entries.async_entries(DOMAIN)) == 1
    assert hass.config_entries.flow.async_progress_by_handler(DOMAIN) == []


async def test_import_updates_rotated_key(hass, mocker):
    entry = MockConfigEntry(
        domain=DOMAIN, unique_id="deviceid", title="Old name", data=_data("oldkey")
    )
    entry.add_to_hass(hass)
    reload = mocker.patch.object(hass.config_entries, "async_schedule_reload")
    mocker.patch(
        "custom_components.tuya_local.importer.async_verify_import",
        new=AsyncMock(return_value=VerifiedImport(_data("newkey"))),
    )
    result = await async_import_device(hass, VALID)
    assert result["outcome"] == "updated"
    assert entry.data[CONF_LOCAL_KEY] == "newkey"
    assert entry.title == "Air monitor"
    reload.assert_called_once_with(entry.entry_id)


async def test_import_rotated_key_overrides_conflicting_options(hass, mocker):
    entry = MockConfigEntry(
        domain=DOMAIN,
        unique_id="deviceid",
        title="Air monitor",
        data=_data("oldkey"),
        options={CONF_LOCAL_KEY: "staleoption", "unrelated": "preserved"},
    )
    entry.add_to_hass(hass)
    mocker.patch.object(hass.config_entries, "async_schedule_reload")
    mocker.patch(
        "custom_components.tuya_local.importer.async_verify_import",
        new=AsyncMock(return_value=VerifiedImport(_data("newkey"))),
    )

    result = await async_import_device(hass, VALID)

    assert result["outcome"] == "updated"
    assert entry.data[CONF_LOCAL_KEY] == "newkey"
    assert entry.options == {"unrelated": "preserved"}


async def test_import_host_change_overrides_conflicting_options(hass, mocker):
    entry = MockConfigEntry(
        domain=DOMAIN,
        unique_id="deviceid",
        title="Air monitor",
        data=_data(),
        options={CONF_HOST: "192.168.1.99", CONF_POLL_ONLY: True},
    )
    entry.add_to_hass(hass)
    mocker.patch.object(hass.config_entries, "async_schedule_reload")
    mocker.patch(
        "custom_components.tuya_local.importer.async_verify_import",
        new=AsyncMock(return_value=VerifiedImport(_data())),
    )

    result = await async_import_device(hass, VALID)

    assert result["outcome"] == "updated"
    assert {**entry.data, **entry.options}[CONF_HOST] == "192.168.1.22"
    assert {**entry.data, **entry.options}[CONF_POLL_ONLY] is False
    assert CONF_HOST not in entry.options
    assert CONF_POLL_ONLY not in entry.options


async def test_import_after_conflicting_option_update_is_idempotent(hass, mocker):
    entry = MockConfigEntry(
        domain=DOMAIN,
        unique_id="deviceid",
        title="Air monitor",
        data=_data("oldkey"),
        options={CONF_LOCAL_KEY: "staleoption"},
    )
    entry.add_to_hass(hass)
    mocker.patch.object(hass.config_entries, "async_schedule_reload")
    verifier = mocker.patch(
        "custom_components.tuya_local.importer.async_verify_import",
        new=AsyncMock(return_value=VerifiedImport(_data("newkey"))),
    )

    first = await async_import_device(hass, VALID)
    second = await async_import_device(hass, VALID)

    assert first["outcome"] == "updated"
    assert second["outcome"] == "already_present"
    assert verifier.await_count == 2
    assert entry.options == {}


async def test_import_profile_correction_clears_stale_metadata(hass, mocker):
    corrected = {**_data(), CONF_TYPE: "different_profile"}
    entry = MockConfigEntry(
        domain=DOMAIN,
        unique_id="deviceid",
        title="Air monitor",
        data={
            **_data(),
            CONF_MANUFACTURER: "Old manufacturer",
            CONF_MODEL: "Old model",
        },
        options={CONF_TYPE: "old_option_profile"},
    )
    entry.add_to_hass(hass)
    mocker.patch.object(hass.config_entries, "async_schedule_reload")
    mocker.patch(
        "custom_components.tuya_local.importer.async_verify_import",
        new=AsyncMock(return_value=VerifiedImport(corrected)),
    )

    result = await async_import_device(hass, VALID)

    assert result["outcome"] == "updated"
    assert entry.data[CONF_TYPE] == "different_profile"
    assert CONF_MANUFACTURER not in entry.data
    assert CONF_MODEL not in entry.data
    assert CONF_TYPE not in entry.options


async def test_import_update_with_listener_does_not_reload_directly(hass, mocker):
    entry = MockConfigEntry(
        domain=DOMAIN, unique_id="deviceid", title="Old name", data=_data("oldkey")
    )
    entry.add_to_hass(hass)
    listener = AsyncMock()
    entry.add_update_listener(listener)
    schedule = mocker.patch.object(hass.config_entries, "async_schedule_reload")
    reload = mocker.patch.object(hass.config_entries, "async_reload", new=AsyncMock())
    mocker.patch(
        "custom_components.tuya_local.importer.async_verify_import",
        new=AsyncMock(return_value=VerifiedImport(_data("newkey"))),
    )
    result = await async_import_device(hass, VALID)
    await hass.async_block_till_done()
    assert result["outcome"] == "updated"
    listener.assert_awaited_once()
    schedule.assert_not_called()
    reload.assert_not_awaited()


async def test_import_does_not_merge_into_subdevice_entry(hass, mocker, bypass_setup):
    child = MockConfigEntry(
        domain=DOMAIN,
        unique_id="deviceid/child",
        title="Child",
        data={**_data("childkey"), CONF_DEVICE_CID: "child"},
    )
    child.add_to_hass(hass)
    mocker.patch(
        "custom_components.tuya_local.importer.async_verify_import",
        new=AsyncMock(return_value=VerifiedImport(_data("newkey"))),
    )
    result = await async_import_device(hass, VALID)
    assert result["outcome"] == "added"
    assert child.data[CONF_LOCAL_KEY] == "childkey"
    assert child.title == "Child"
    assert len(hass.config_entries.async_entries(DOMAIN)) == 2


async def test_import_existing_unchanged_is_idempotent(hass, mocker):
    data = _data()
    entry = MockConfigEntry(
        domain=DOMAIN, unique_id="deviceid", title="Air monitor", data=data
    )
    entry.add_to_hass(hass)
    reload = mocker.patch.object(hass.config_entries, "async_reload", new=AsyncMock())
    mocker.patch(
        "custom_components.tuya_local.importer.async_verify_import",
        new=AsyncMock(return_value=VerifiedImport(data)),
    )
    result = await async_import_device(hass, VALID)
    assert result["outcome"] == "already_present"
    assert len(hass.config_entries.async_entries(DOMAIN)) == 1
    reload.assert_not_awaited()


async def test_concurrent_imports_serialize(hass, mocker, bypass_setup):
    data = _data()

    async def verify(*args):
        await asyncio.sleep(0)
        return VerifiedImport(data)

    verifier = mocker.patch(
        "custom_components.tuya_local.importer.async_verify_import",
        side_effect=verify,
    )
    results = await asyncio.gather(
        async_import_device(hass, VALID), async_import_device(hass, VALID)
    )
    # verification is mandatory for every import, including duplicates
    assert verifier.call_count == 2
    assert len(hass.config_entries.async_entries(DOMAIN)) == 1
    assert [result["outcome"] for result in results] == ["added", "already_present"]


async def test_failed_verification_preserves_existing_entry(hass, mocker):
    entry = MockConfigEntry(
        domain=DOMAIN,
        unique_id="deviceid",
        title="Air monitor",
        data={CONF_DEVICE_ID: "deviceid", CONF_LOCAL_KEY: "workingkey"},
    )
    entry.add_to_hass(hass)
    mocker.patch(
        "custom_components.tuya_local.importer.async_verify_import",
        new=AsyncMock(side_effect=DeviceImportError("handshake_failed", "Failed")),
    )
    with pytest.raises(DeviceImportError):
        await async_import_device(hass, VALID)
    assert entry.data[CONF_LOCAL_KEY] == "workingkey"


async def test_websocket_returns_import_result(hass, hass_ws_client, mocker):
    await async_setup(hass, {})
    result = {"device_id": "deviceid", "outcome": "added", "import_version": 1}
    importer = mocker.patch(
        "custom_components.tuya_local.importer.async_import_device",
        new=AsyncMock(return_value=result),
    )
    client = await hass_ws_client(hass)
    await client.send_json({"id": 7, "type": IMPORT_TYPE, **VALID})
    response = await client.receive_json()
    assert response["success"] is True
    assert response["result"] == result
    assert "local_key" not in response["result"]
    imported_data = importer.await_args.args[1]
    assert "id" not in imported_data
    assert "type" not in imported_data


async def test_websocket_returns_sanitized_error(hass, hass_ws_client, mocker):
    await async_setup(hass, {})
    mocker.patch(
        "custom_components.tuya_local.importer.async_import_device",
        new=AsyncMock(side_effect=DeviceImportError("handshake_failed", "Failed")),
    )
    client = await hass_ws_client(hass)
    await client.send_json({"id": 8, "type": IMPORT_TYPE, **VALID})
    response = await client.receive_json()
    assert response["success"] is False
    assert response["error"]["code"] == "handshake_failed"
    assert VALID["local_key"] not in str(response)


async def test_websocket_error_includes_product_id(
    hass, hass_ws_client, mocker, caplog
):
    caplog.set_level("DEBUG")
    await async_setup(hass, {})
    mocker.patch(
        "custom_components.tuya_local.importer.async_import_device",
        new=AsyncMock(
            side_effect=DeviceImportError(
                "unsupported_profile", "Unsupported", "productid"
            )
        ),
    )
    client = await hass_ws_client(hass)
    await client.send_json({"id": 10, "type": IMPORT_TYPE, **VALID})
    response = await client.receive_json()
    assert response["error"]["code"] == "unsupported_profile"
    assert response["error"]["message"] == "Unsupported: productid"
    assert VALID["local_key"] not in str(response)
    # Only integration-controlled records are checked: Home Assistant core logs
    # raw inbound websocket frames at DEBUG (see README, Android device import).
    integration_logs = "\n".join(
        record.getMessage()
        for record in caplog.records
        if record.name.startswith("custom_components.tuya_local")
    )
    assert VALID["local_key"] not in integration_logs


async def test_websocket_requires_admin(hass, hass_ws_client, hass_admin_user, mocker):
    hass_admin_user.is_admin = False
    await async_setup(hass, {})
    importer = mocker.patch(
        "custom_components.tuya_local.importer.async_import_device",
        new=AsyncMock(),
    )
    client = await hass_ws_client(hass)
    await client.send_json({"id": 9, "type": IMPORT_TYPE, **VALID})
    response = await client.receive_json()
    assert response["success"] is False
    assert response["error"]["code"] == "unauthorized"
    importer.assert_not_awaited()


@pytest.mark.parametrize(
    "payload",
    [
        {k: v for k, v in VALID.items() if k != "device_id"},
        {**VALID, "ip": "not-an-ip"},
    ],
    ids=["missing_device_id", "invalid_ip"],
)
async def test_websocket_rejects_malformed_payload(
    hass, hass_ws_client, mocker, payload
):
    await async_setup(hass, {})
    importer = mocker.patch(
        "custom_components.tuya_local.importer.async_import_device",
        new=AsyncMock(),
    )
    client = await hass_ws_client(hass)
    await client.send_json({"id": 11, "type": IMPORT_TYPE, **payload})
    response = await client.receive_json()
    assert response["success"] is False
    assert response["error"]["code"] == "invalid_format"
    assert VALID["local_key"] not in str(response)
    importer.assert_not_awaited()


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("device_id", 1),
        ("local_key", None),
        ("name", True),
        ("product_id", ["productid"]),
    ],
)
async def test_websocket_rejects_non_string_required_fields(
    hass, hass_ws_client, mocker, field, value
):
    await async_setup(hass, {})
    importer = mocker.patch(
        "custom_components.tuya_local.importer.async_import_device",
        new=AsyncMock(),
    )
    client = await hass_ws_client(hass)
    await client.send_json({"id": 12, "type": IMPORT_TYPE, **VALID, field: value})
    response = await client.receive_json()
    assert response["success"] is False
    assert response["error"]["code"] == "invalid_format"
    importer.assert_not_awaited()


async def test_success_does_not_log_key(hass, mocker, bypass_setup, caplog):
    caplog.set_level("DEBUG")
    mocker.patch(
        "custom_components.tuya_local.importer.async_verify_import",
        new=AsyncMock(return_value=VerifiedImport(_data(VALID["local_key"]))),
    )
    result = await async_import_device(hass, VALID)
    assert result["outcome"] == "added"
    assert VALID["local_key"] not in caplog.text
    assert VALID["local_key"] not in str(result)
