import asyncio
import ipaddress
import logging
from contextlib import contextmanager
from dataclasses import dataclass, field
from typing import Any

import voluptuous as vol
from homeassistant.components import websocket_api
from homeassistant.const import CONF_HOST, CONF_NAME
from homeassistant.data_entry_flow import FlowResultType

from .config_flow import async_test_connection, scan_for_device
from .const import (
    API_PROTOCOL_VERSIONS,
    CONF_DEVICE_ID,
    CONF_LOCAL_KEY,
    CONF_MANUFACTURER,
    CONF_MODEL,
    CONF_POLL_ONLY,
    CONF_PROTOCOL_VERSION,
    CONF_TYPE,
    DOMAIN,
)

IMPORT_VERSION = 1
IMPORT_TYPE = "tuya_local/import_device"
DATA_IMPORT_LOCKS = "import_locks"
IMPORTER_OPTION_KEYS = {
    CONF_DEVICE_ID,
    CONF_HOST,
    CONF_LOCAL_KEY,
    CONF_PROTOCOL_VERSION,
    CONF_POLL_ONLY,
    CONF_TYPE,
    CONF_MANUFACTURER,
    CONF_MODEL,
}
_tinytuya_debug_handlers = set()
_tinytuya_debug_suppressions = 0


class _TinyTuyaDebugFilter(logging.Filter):
    def filter(self, record):
        return record.levelno != logging.DEBUG or not (
            record.name == "tinytuya" or record.name.startswith("tinytuya.")
        )


_TINYTYUA_DEBUG_FILTER = _TinyTuyaDebugFilter()


@contextmanager
def _suppress_tinytuya_debug():
    """Suppress dependency DEBUG records that can contain encryption keys."""
    global _tinytuya_debug_handlers, _tinytuya_debug_suppressions
    if _tinytuya_debug_suppressions == 0:
        handlers = set(logging.getLogger().handlers)
        for logger in logging.root.manager.loggerDict.values():
            if isinstance(logger, logging.Logger):
                handlers.update(logger.handlers)
        for handler in handlers:
            handler.addFilter(_TINYTYUA_DEBUG_FILTER)
        _tinytuya_debug_handlers = handlers
    _tinytuya_debug_suppressions += 1
    try:
        yield
    finally:
        _tinytuya_debug_suppressions -= 1
        if _tinytuya_debug_suppressions == 0:
            for handler in _tinytuya_debug_handlers:
                handler.removeFilter(_TINYTYUA_DEBUG_FILTER)
            _tinytuya_debug_handlers = set()


class DeviceImportError(Exception):
    def __init__(self, code: str, message: str, product_id: str | None = None):
        super().__init__(message)
        self.code = code
        self.product_id = product_id


@dataclass(frozen=True)
class ImportRequest:
    device_id: str
    local_key: str = field(repr=False)
    name: str
    product_id: str
    protocol_version: str | float
    category: str | None
    ip: str | None


def _version(value: Any) -> int:
    if type(value) is not int:
        raise vol.Invalid("import_version must be an integer")
    return value


def _nonempty(value: Any) -> str:
    if type(value) is not str:
        raise vol.Invalid("value must be a string")
    return vol.Length(min=1)(value)


def _ip(value: Any) -> str:
    text = _nonempty(value)
    try:
        ipaddress.ip_address(text)
    except ValueError as error:
        raise vol.Invalid("Invalid IP address") from error
    return text


IMPORT_DATA_SCHEMA = vol.Schema(
    {
        vol.Required("import_version"): _version,
        vol.Required("device_id"): _nonempty,
        vol.Required("local_key"): _nonempty,
        vol.Required("name"): _nonempty,
        vol.Required("product_id"): _nonempty,
        vol.Optional("protocol_version", default="auto"): vol.All(
            vol.Coerce(str), vol.Length(min=1)
        ),
        vol.Optional("category"): _nonempty,
        vol.Optional("ip"): _ip,
    },
    extra=vol.PREVENT_EXTRA,
)


def validate_import(data: dict[str, Any]) -> ImportRequest:
    values = IMPORT_DATA_SCHEMA(data)
    if values["import_version"] != IMPORT_VERSION:
        raise DeviceImportError(
            "unsupported_import_version",
            f"Import version {values['import_version']} is not supported",
        )
    protocol = values["protocol_version"]
    supported_protocols = ["auto"] + [str(v) for v in API_PROTOCOL_VERSIONS]
    if protocol not in supported_protocols:
        raise DeviceImportError(
            "unsupported_protocol", f"Protocol {protocol} is not supported"
        )
    return ImportRequest(
        device_id=values["device_id"],
        local_key=values["local_key"],
        name=values["name"],
        product_id=values["product_id"],
        protocol_version="auto" if protocol == "auto" else float(protocol),
        category=values.get("category"),
        ip=values.get("ip"),
    )


@dataclass(frozen=True)
class VerifiedImport:
    config: dict[str, Any] = field(repr=False)


async def async_verify_import(hass, request: ImportRequest) -> VerifiedImport:
    host = request.ip
    local_product_id = None
    protocol_version = request.protocol_version
    if host is None:
        try:
            local = await hass.async_add_executor_job(
                scan_for_device, request.device_id
            )
        except OSError as error:
            raise DeviceImportError("discovery_failed", str(error)) from error
        host = local.get("ip")
        local_product_id = local.get("product_id")
        if protocol_version == "auto":
            local_version = local.get("version")
            if local_version is not None:
                supported_protocols = {str(v): v for v in API_PROTOCOL_VERSIONS}
                matched = supported_protocols.get(str(local_version))
                if matched is not None:
                    protocol_version = matched
        if not host:
            raise DeviceImportError(
                "discovery_failed", f"Could not discover {request.device_id} on the LAN"
            )

    config = {
        CONF_DEVICE_ID: request.device_id,
        CONF_HOST: host,
        CONF_LOCAL_KEY: request.local_key,
        CONF_PROTOCOL_VERSION: protocol_version,
        CONF_POLL_ONLY: False,
    }
    with _suppress_tinytuya_debug():
        device = await async_test_connection(config, hass)
    if device is None:
        raise DeviceImportError(
            "handshake_failed", "The device did not return state with these credentials"
        )

    try:
        device.set_detected_product_id(request.product_id)
        if local_product_id is not None:
            device.set_detected_product_id(local_product_id)
        config_type = await device.async_inferred_type()
        if config_type is None:
            raise DeviceImportError(
                "unsupported_profile",
                "No tuya-local device profile matched",
                product_id=request.product_id,
            )
        config[CONF_TYPE] = config_type
        if protocol_version == "auto":
            config[CONF_PROTOCOL_VERSION] = device._protocol_configured
        return VerifiedImport(config)
    finally:
        device._api.set_socketPersistent(False)
        if device._api.parent:
            device._api.parent.set_socketPersistent(False)


async def async_import_device(hass, data: dict[str, Any]) -> dict[str, Any]:
    request = validate_import(data)
    domain_data = hass.data.setdefault(DOMAIN, {})
    locks = domain_data.setdefault(DATA_IMPORT_LOCKS, {})
    lock = locks.setdefault(request.device_id, asyncio.Lock())
    async with lock:
        verified = await async_verify_import(hass, request)
        config = verified.config
        existing = hass.config_entries.async_entry_for_domain_unique_id(
            DOMAIN, request.device_id
        )
        if existing is None:
            result = await hass.config_entries.flow.async_init(
                DOMAIN,
                context={"source": "import"},
                data={**config, CONF_NAME: request.name},
            )
            if result["type"] != FlowResultType.CREATE_ENTRY:
                raise RuntimeError(
                    f"Verified import did not create an entry: {result['type']}"
                )
            outcome = "added"
        else:
            effective = {**existing.data, **existing.options}
            unchanged = existing.title == request.name and all(
                effective.get(key) == value for key, value in config.items()
            )
            if unchanged:
                outcome = "already_present"
            else:
                data = dict(existing.data)
                if effective.get(CONF_TYPE) != config[CONF_TYPE]:
                    data.pop(CONF_MANUFACTURER, None)
                    data.pop(CONF_MODEL, None)
                options = {
                    key: value
                    for key, value in existing.options.items()
                    if key not in IMPORTER_OPTION_KEYS
                }
                hass.config_entries.async_update_entry(
                    existing,
                    title=request.name,
                    data={**data, **config},
                    options=options,
                )
                if not existing.update_listeners:
                    hass.config_entries.async_schedule_reload(existing.entry_id)
                outcome = "updated"
        return {
            "device_id": request.device_id,
            "outcome": outcome,
            "import_version": IMPORT_VERSION,
        }


def async_register_importer(hass) -> None:
    websocket_api.async_register_command(hass, websocket_import_device)


@websocket_api.websocket_command(
    {**IMPORT_DATA_SCHEMA.schema, vol.Required("type"): IMPORT_TYPE}
)
@websocket_api.require_admin
@websocket_api.async_response
async def websocket_import_device(hass, connection, msg):
    data = {key: value for key, value in msg.items() if key not in {"id", "type"}}
    try:
        result = await async_import_device(hass, data)
    except DeviceImportError as error:
        message = str(error)
        if error.product_id is not None:
            message = f"{message}: {error.product_id}"
        connection.send_error(msg["id"], error.code, message)
    else:
        connection.send_result(msg["id"], result)
