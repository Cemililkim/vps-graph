#!/usr/bin/python3
"""Narrow, read-only Docker metadata helper for VPS Graph.

It also sanitizes optional Caddy routes, Systemd service state, and host listeners.
This program intentionally accepts no arguments and never exposes raw Docker
inspect data or raw Caddy configuration. It is designed to run only through the
matching sudoers rule.
"""

from __future__ import annotations

import ipaddress
import json
import os
import re
import stat
import subprocess
import sys
import threading
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
DOCKER_CANDIDATES = (Path("/usr/bin/docker"), Path("/usr/local/bin/docker"))
SYSTEMCTL_CANDIDATES = (Path("/usr/bin/systemctl"), Path("/bin/systemctl"))
SS_CANDIDATES = (Path("/usr/bin/ss"), Path("/bin/ss"))
DOCKER_TIMEOUT_SECONDS = 15
HOST_DISCOVERY_TIMEOUT_SECONDS = 15
INSPECT_BATCH_SIZE = 100
SYSTEMD_SHOW_BATCH_SIZE = 128
MAX_SYSTEMD_SERVICES = 2048
MAX_LISTENERS = 4096
MAX_COMMAND_OUTPUT_BYTES = 2 * 1024 * 1024
MAX_CGROUP_BYTES = 16 * 1024
MAX_CADDY_CONFIG_BYTES = 4 * 1024 * 1024
MAX_CADDY_ROUTE_DEPTH = 32
MAX_CADDY_ROUTES = 5000
MINIMAL_ENV = {"LANG": "C", "LC_ALL": "C", "PATH": "/usr/bin:/bin"}
DOCKER_ID_PATTERN = re.compile(r"[0-9a-f]{12,64}")
PORT_PATTERN = re.compile(r"(\d+)/(tcp|udp|sctp)")
SAFE_SYSTEMD_ESCAPE_PATTERN = r"\\x(?:[2-6][0-9A-Fa-f]|7[0-9A-Ea-e]|[89A-Fa-f][0-9A-Fa-f])"
UNIT_NAME_PATTERN = re.compile(rf"[A-Za-z0-9](?:[A-Za-z0-9@_.:-]|{SAFE_SYSTEMD_ESCAPE_PATTERN})*\.service")
SAFE_CGROUP_PATTERN = re.compile(rf"/(?:[A-Za-z0-9@_.:/-]|{SAFE_SYSTEMD_ESCAPE_PATTERN})+")
INTERFACE_SCOPE_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,14}")
SAFE_TOKEN_PATTERN = re.compile(r"[^\x00-\x1f\x7f]{1,128}")
PROCESS_PATTERN = re.compile(r'\("([^"\\\x00-\x1f]{1,128})",pid=(\d+)(?:,|\))')
SAFE_DIAL_HOST_PATTERN = re.compile(r"[a-z0-9](?:[a-z0-9_.-]{0,251}[a-z0-9])?", re.IGNORECASE)
SAFE_DOMAIN_PATTERN = re.compile(r"(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", re.IGNORECASE)
SECRET_MARKER_PATTERN = re.compile(
    r"database[_-]?url|password|api[_-]?key|jwt[_-]?secret|(?:cf|cloudflare)[_-]?api[_-]?token|"
    r"authorization|bearer|aws[_-]?secret[_-]?access[_-]?key|private[_-]?key",
    re.IGNORECASE,
)
CADDY_IMAGE_REPOSITORIES = {
    "caddy",
    "library/caddy",
    "docker.io/library/caddy",
    "index.docker.io/library/caddy",
    "registry-1.docker.io/library/caddy",
}
COMPOSE_LABELS = {
    "com.docker.compose.project": "project",
    "com.docker.compose.service": "service",
    "com.docker.compose.project.working_dir": "workingDirectory",
    "com.docker.compose.project.config_files": "configFiles",
}
CONTAINER_RUNTIME_UNITS = frozenset({"docker.service", "containerd.service"})
REASON_COMMAND_FAILED = "COMMAND_FAILED"
REASON_TRUNCATED = "TRUNCATED"
REASON_LIST_ROW_MALFORMED = "LIST_ROW_MALFORMED"
REASON_SHOW_RECORD_MISSING = "SHOW_RECORD_MISSING"
REASON_SHOW_RECORD_MALFORMED = "SHOW_RECORD_MALFORMED"
REASON_LISTENER_ROW_MALFORMED = "LISTENER_ROW_MALFORMED"
REASON_LISTENER_PROTOCOL_UNSUPPORTED = "LISTENER_PROTOCOL_UNSUPPORTED"
REASON_LISTENER_ADDRESS_UNSUPPORTED = "LISTENER_ADDRESS_UNSUPPORTED"
REASON_LISTENER_PORT_INVALID = "LISTENER_PORT_INVALID"


class HelperFailure(Exception):
    def __init__(self, code: str, message: str):
        self.code = code
        self.message = message
        super().__init__(code)


class CaddyConfigFailure(Exception):
    def __init__(self, status: str):
        self.status = status
        super().__init__(status)


@dataclass(frozen=True)
class CommandOutput:
    stdout: str
    stderr: str
    returncode: int
    timed_out: bool = False
    too_large: bool = False


@dataclass(frozen=True)
class SystemdService:
    unit: str
    description: str | None = None
    load_state: str | None = None
    active_state: str | None = None
    sub_state: str | None = None
    unit_file_state: str | None = None
    service_type: str | None = None
    user: str | None = None
    group: str | None = None
    main_pid: int | None = None
    control_group: str | None = None

    def json_value(self) -> dict[str, Any]:
        values = {
            "unit": self.unit,
            "description": self.description,
            "loadState": self.load_state,
            "activeState": self.active_state,
            "subState": self.sub_state,
            "unitFileState": self.unit_file_state,
            "serviceType": self.service_type,
            "user": self.user,
            "group": self.group,
            "controlGroup": self.control_group,
        }
        return {key: value for key, value in values.items() if value is not None}


@dataclass(frozen=True)
class ParsedListener:
    protocol: str
    local_address: str
    port: int
    wildcard: bool
    loopback: bool
    process_names: tuple[str, ...]
    pids: tuple[int, ...]


def failure(code: str, message: str) -> dict[str, Any]:
    return {"schemaVersion": SCHEMA_VERSION, "ok": False, "error": {"code": code, "message": message}}


def write_json(payload: dict[str, Any]) -> None:
    print(json.dumps(payload, sort_keys=True, separators=(",", ":")))


def trusted_executable(candidates: Iterable[Path]) -> Path | None:
    for candidate in candidates:
        try:
            details = candidate.stat()
        except OSError:
            continue
        mode = details.st_mode
        if (
            stat.S_ISREG(mode)
            and details.st_uid == 0
            and not mode & (stat.S_IWGRP | stat.S_IWOTH)
            and bool(mode & (stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH))
            and os.access(candidate, os.X_OK)
        ):
            return candidate
    return None


def trusted_docker_executable() -> Path:
    found_untrusted = False
    for candidate in DOCKER_CANDIDATES:
        try:
            details = candidate.stat()
        except FileNotFoundError:
            continue
        except OSError:
            found_untrusted = True
            continue
        mode = details.st_mode
        trusted = (
            stat.S_ISREG(mode)
            and details.st_uid == 0
            and not mode & (stat.S_IWGRP | stat.S_IWOTH)
            and bool(mode & (stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH))
            and os.access(candidate, os.X_OK)
        )
        if trusted:
            return candidate
        found_untrusted = True
    if found_untrusted:
        raise HelperFailure("UNTRUSTED_DOCKER_EXECUTABLE", "A trusted Docker executable was not found.")
    raise HelperFailure("DOCKER_UNAVAILABLE", "Docker is not available on this host.")


def trusted_systemctl_executable() -> Path | None:
    return trusted_executable(SYSTEMCTL_CANDIDATES)


def trusted_ss_executable() -> Path | None:
    return trusted_executable(SS_CANDIDATES)


def run_docker(docker: Path, arguments: list[str], error_code: str) -> str:
    try:
        completed = subprocess.run(
            [str(docker), *arguments],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            env=MINIMAL_ENV,
            timeout=DOCKER_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired as error:
        raise HelperFailure(error_code, "Docker did not respond before the timeout.") from error
    except OSError as error:
        raise HelperFailure(error_code, "Docker is not available on this host.") from error
    if completed.returncode != 0:
        raise HelperFailure(error_code, "Docker inspection could not be completed.")
    return completed.stdout


def run_readonly_command(executable: Path, arguments: list[str]) -> CommandOutput:
    """Run a fixed local command with bounded, non-persistent output."""
    try:
        process = subprocess.Popen(
            [str(executable), *arguments],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=MINIMAL_ENV,
        )
    except OSError:
        return CommandOutput("", "", 1)

    streams: dict[str, bytes] = {"stdout": b"", "stderr": b""}
    too_large = threading.Event()

    def stop_process() -> None:
        try:
            process.kill()
        except ProcessLookupError:
            pass

    def drain(name: str, stream: Any) -> None:
        chunks: list[bytes] = []
        size = 0
        try:
            while chunk := stream.read(65536):
                size += len(chunk)
                if size > MAX_COMMAND_OUTPUT_BYTES:
                    too_large.set()
                    stop_process()
                    return
                chunks.append(chunk)
        finally:
            streams[name] = b"".join(chunks)

    stdout_thread = threading.Thread(target=drain, args=("stdout", process.stdout), daemon=True)
    stderr_thread = threading.Thread(target=drain, args=("stderr", process.stderr), daemon=True)
    stdout_thread.start()
    stderr_thread.start()
    timed_out = False
    try:
        returncode = process.wait(timeout=HOST_DISCOVERY_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired:
        timed_out = True
        stop_process()
        returncode = process.wait()
    stdout_thread.join()
    stderr_thread.join()
    return CommandOutput(
        streams["stdout"].decode("utf-8", "replace"),
        streams["stderr"].decode("utf-8", "replace"),
        returncode,
        timed_out=timed_out,
        too_large=too_large.is_set(),
    )


def as_text(value: Any) -> str | None:
    return value if isinstance(value, str) else None


def object_value(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def list_value(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def safe_text(value: Any, maximum: int = 512) -> str | None:
    if not isinstance(value, str) or not value or len(value) > maximum:
        return None
    return value if not any(ord(character) < 32 or ord(character) == 127 for character in value) else None


def safe_token(value: Any) -> str | None:
    return value if isinstance(value, str) and SAFE_TOKEN_PATTERN.fullmatch(value) else None


def safe_description(value: Any) -> str | None:
    text = safe_text(value)
    if text is None or SECRET_MARKER_PATTERN.search(text) or "secret" in text.lower():
        return None
    return text


def valid_systemd_unit(value: Any) -> str | None:
    if not isinstance(value, str) or len(value) > 255 or value.startswith("-") or not UNIT_NAME_PATTERN.fullmatch(value):
        return None
    return value


def safe_control_group(value: Any) -> str | None:
    if not isinstance(value, str) or value == "/" or len(value) > 512 or not SAFE_CGROUP_PATTERN.fullmatch(value):
        return None
    return value


def parse_unit_records(raw_output: str, status_reasons: set[str] | None = None) -> tuple[list[SystemdService], bool]:
    reasons = status_reasons if status_reasons is not None else set()
    units: dict[str, SystemdService] = {}
    for line in raw_output.splitlines():
        if not line.strip():
            continue
        fields = line.split(None, 4)
        unit = valid_systemd_unit(fields[0]) if fields else None
        if unit is None or len(fields) < 4:
            reasons.add(REASON_LIST_ROW_MALFORMED)
            continue
        if fields[1] != "loaded":
            continue
        units[unit] = SystemdService(
            unit=unit,
            description=safe_description(fields[4]) if len(fields) == 5 else None,
            load_state=safe_token(fields[1]),
            active_state=safe_token(fields[2]),
            sub_state=safe_token(fields[3]),
        )
    ordered = [units[unit] for unit in sorted(units)]
    if len(ordered) > MAX_SYSTEMD_SERVICES:
        reasons.add(REASON_TRUNCATED)
        ordered = ordered[:MAX_SYSTEMD_SERVICES]
    return ordered, bool(reasons)


def parse_unit_names(raw_output: str) -> tuple[list[str], bool]:
    services, partial = parse_unit_records(raw_output)
    return [service.unit for service in services], partial


SYSTEMD_PROPERTY_NAMES = (
    "Id",
    "Description",
    "LoadState",
    "ActiveState",
    "SubState",
    "UnitFileState",
    "MainPID",
    "ControlGroup",
    "Type",
    "User",
    "Group",
)


def systemd_service_from_properties(properties: dict[str, str]) -> SystemdService | None:
    unit = valid_systemd_unit(properties.get("Id"))
    if unit is None:
        return None
    main_pid_text = properties.get("MainPID")
    main_pid = int(main_pid_text) if main_pid_text is not None and main_pid_text.isdigit() and int(main_pid_text) > 0 else None
    return SystemdService(
        unit=unit,
        description=safe_description(properties.get("Description")),
        load_state=safe_token(properties.get("LoadState")),
        active_state=safe_token(properties.get("ActiveState")),
        sub_state=safe_token(properties.get("SubState")),
        unit_file_state=safe_token(properties.get("UnitFileState")),
        service_type=safe_token(properties.get("Type")),
        user=safe_token(properties.get("User")),
        group=safe_token(properties.get("Group")),
        main_pid=main_pid,
        control_group=safe_control_group(properties.get("ControlGroup")),
    )


def merge_systemd_service(base: SystemdService, enrichment: SystemdService) -> SystemdService:
    if base.unit != enrichment.unit:
        return base
    return SystemdService(
        unit=base.unit,
        description=enrichment.description or base.description,
        load_state=enrichment.load_state or base.load_state,
        active_state=enrichment.active_state or base.active_state,
        sub_state=enrichment.sub_state or base.sub_state,
        unit_file_state=enrichment.unit_file_state or base.unit_file_state,
        service_type=enrichment.service_type or base.service_type,
        user=enrichment.user or base.user,
        group=enrichment.group or base.group,
        main_pid=enrichment.main_pid or base.main_pid,
        control_group=enrichment.control_group or base.control_group,
    )


def parse_systemctl_show(raw_output: str, status_reasons: set[str] | None = None) -> tuple[list[SystemdService], bool]:
    reasons = status_reasons if status_reasons is not None else set()
    services: list[SystemdService] = []
    current: dict[str, str] = {}

    def finish() -> None:
        if not current:
            return
        service = systemd_service_from_properties(current)
        if service is None:
            reasons.add(REASON_SHOW_RECORD_MALFORMED)
        else:
            services.append(service)

    for line in raw_output.splitlines():
        if not line:
            finish()
            current = {}
            continue
        key, separator, value = line.partition("=")
        if not separator:
            reasons.add(REASON_SHOW_RECORD_MALFORMED)
            continue
        if key in SYSTEMD_PROPERTY_NAMES:
            if key in current:
                reasons.add(REASON_SHOW_RECORD_MALFORMED)
                continue
            current[key] = value
    finish()
    unique = {service.unit: service for service in services}
    return [unique[unit] for unit in sorted(unique)], bool(reasons)


def is_not_systemd(output: CommandOutput) -> bool:
    return "not been booted with systemd" in (output.stdout + output.stderr).lower()


def discover_systemd_services() -> tuple[dict[str, Any], list[SystemdService]]:
    systemctl = trusted_systemctl_executable()
    if systemctl is None:
        return {"status": "NOT_AVAILABLE", "statusReasons": [], "services": []}, []
    listing = run_readonly_command(
        systemctl,
        ["--system", "--no-legend", "--no-pager", "--plain", "list-units", "--type=service", "--all"],
    )
    if listing.timed_out or listing.too_large:
        return {"status": "COMMAND_FAILED", "statusReasons": [REASON_COMMAND_FAILED], "services": []}, []
    if listing.returncode != 0:
        return ({"status": "NOT_SYSTEMD", "statusReasons": [], "services": []} if is_not_systemd(listing) else {"status": "COMMAND_FAILED", "statusReasons": [REASON_COMMAND_FAILED], "services": []}), []
    reasons: set[str] = set()
    base_services, _partial = parse_unit_records(listing.stdout, reasons)
    base_by_unit = {service.unit: service for service in base_services}
    units = list(base_by_unit)
    services: list[SystemdService] = []
    for batch in chunks(units, SYSTEMD_SHOW_BATCH_SIZE):
        shown = run_readonly_command(
            systemctl,
            [
                "--system",
                "--no-pager",
                "show",
                *(f"--property={name}" for name in SYSTEMD_PROPERTY_NAMES),
                "--",
                *batch,
            ],
        )
        if shown.timed_out:
            reasons.add(REASON_COMMAND_FAILED)
            continue
        if shown.too_large:
            reasons.add(REASON_TRUNCATED)
            continue
        parsed, _malformed = parse_systemctl_show(shown.stdout, reasons)
        services.extend(merge_systemd_service(base_by_unit[service.unit], service) for service in parsed if service.unit in base_by_unit)
        enriched_units = {service.unit for service in parsed}
        if not set(batch).issubset(enriched_units):
            reasons.add(REASON_SHOW_RECORD_MISSING)
    unique = {service.unit: service for service in services}
    ordered = [unique[unit] for unit in sorted(unique)]
    return {
        "status": "PARTIAL" if reasons else "DISCOVERED",
        "statusReasons": sorted(reasons),
        "services": [service.json_value() for service in ordered],
    }, ordered


def parse_listener_address_with_reason(value: str) -> tuple[tuple[str, int, bool, bool] | None, str | None]:
    host: str
    port_text: str
    scope: str | None = None
    if value.startswith("["):
        closing = value.find("]")
        if closing < 0:
            return None, REASON_LISTENER_ADDRESS_UNSUPPORTED
        raw_host = value[1:closing]
        suffix = value[closing + 1:]
        if suffix.startswith("%"):
            scope, separator, port_text = suffix[1:].partition(":")
            if not separator:
                return None, REASON_LISTENER_ADDRESS_UNSUPPORTED
        elif suffix.startswith(":"):
            port_text = suffix[1:]
        else:
            return None, REASON_LISTENER_ADDRESS_UNSUPPORTED
        address, delimiter, embedded_scope = raw_host.partition("%")
        if delimiter:
            if scope is not None:
                return None, REASON_LISTENER_ADDRESS_UNSUPPORTED
            scope = embedded_scope
        try:
            host = ipaddress.IPv6Address(address).compressed
        except ValueError:
            return None, REASON_LISTENER_ADDRESS_UNSUPPORTED
    else:
        raw_host, delimiter, port_text = value.rpartition(":")
        if not delimiter or not raw_host:
            return None, REASON_LISTENER_ADDRESS_UNSUPPORTED
        if raw_host == "*":
            host = raw_host
        else:
            address, scope_delimiter, embedded_scope = raw_host.partition("%")
            try:
                host = str(ipaddress.IPv4Address(address))
            except ValueError:
                return None, REASON_LISTENER_ADDRESS_UNSUPPORTED
            if scope_delimiter:
                scope = embedded_scope
    if scope is not None:
        if not INTERFACE_SCOPE_PATTERN.fullmatch(scope):
            return None, REASON_LISTENER_ADDRESS_UNSUPPORTED
        host = f"{host}%{scope}"
    if not port_text.isdigit() or not 1 <= int(port_text) <= 65535:
        return None, REASON_LISTENER_PORT_INVALID
    address_without_scope = host.split("%", 1)[0]
    wildcard = address_without_scope in {"*", "0.0.0.0", "::"}
    loopback = False if wildcard else ipaddress.ip_address(address_without_scope).is_loopback
    return (host, int(port_text), wildcard, loopback), None


def parse_listener_address(value: str) -> tuple[str, int, bool, bool] | None:
    return parse_listener_address_with_reason(value)[0]


def safe_process_name(value: str) -> str | None:
    if not value or any(character.isspace() for character in value) or SECRET_MARKER_PATTERN.search(value) or "secret" in value.lower():
        return None
    return safe_text(value, 128)


def parse_ss_output(raw_output: str, status_reasons: set[str] | None = None) -> tuple[list[ParsedListener], bool]:
    reasons = status_reasons if status_reasons is not None else set()
    listeners: list[ParsedListener] = []
    for line in raw_output.splitlines():
        fields = line.split(maxsplit=6)
        if len(fields) < 6:
            reasons.add(REASON_LISTENER_ROW_MALFORMED)
            continue
        if fields[0] not in {"tcp", "udp"}:
            reasons.add(REASON_LISTENER_PROTOCOL_UNSUPPORTED)
            continue
        address, address_reason = parse_listener_address_with_reason(fields[4])
        if address is None:
            reasons.add(address_reason or REASON_LISTENER_ADDRESS_UNSUPPORTED)
            continue
        process_data = fields[6] if len(fields) == 7 else ""
        pairs = PROCESS_PATTERN.findall(process_data)
        names = sorted({safe for name, _pid in pairs if (safe := safe_process_name(name)) is not None})
        pids = sorted({int(pid) for _name, pid in pairs if 0 < int(pid) <= 4_294_967_295})
        host, port, wildcard, loopback = address
        listeners.append(ParsedListener(fields[0], host, port, wildcard, loopback, tuple(names), tuple(pids)))
    listeners.sort(key=lambda item: (item.protocol, item.local_address, item.port, item.process_names))
    if len(listeners) > MAX_LISTENERS:
        reasons.add(REASON_TRUNCATED)
        listeners = listeners[:MAX_LISTENERS]
    return listeners, bool(reasons)


def cgroup_v2_path(raw_output: str) -> str | None:
    for line in raw_output.splitlines():
        hierarchy, delimiter, path = line.partition("::")
        if hierarchy == "0" and delimiter == "::" and (safe := safe_control_group(path)) is not None:
            return safe
    return None


def read_process_cgroup(pid: int) -> str | None:
    if not isinstance(pid, int) or not 0 < pid <= 4_294_967_295:
        return None
    try:
        with Path("/proc").joinpath(str(pid), "cgroup").open("r", encoding="utf-8", errors="replace") as stream:
            raw = stream.read(MAX_CGROUP_BYTES + 1)
    except OSError:
        return None
    return None if len(raw) > MAX_CGROUP_BYTES else cgroup_v2_path(raw)


def systemd_service_for_cgroup(cgroup: str | None, services: Iterable[SystemdService]) -> SystemdService | None:
    if cgroup is None:
        return None
    matches = [
        service
        for service in services
        if service.control_group is not None and (cgroup == service.control_group or cgroup.startswith(f"{service.control_group}/"))
    ]
    return max(matches, key=lambda service: len(service.control_group or ""), default=None)


def is_container_runtime_service(service: SystemdService) -> bool:
    return service.unit in CONTAINER_RUNTIME_UNITS


def listener_json(listener: ParsedListener, services: Iterable[SystemdService]) -> dict[str, Any]:
    owner_units: set[str] = set()
    unresolved_owner = not listener.pids
    for pid in listener.pids:
        service = systemd_service_for_cgroup(read_process_cgroup(pid), services)
        if service is None:
            unresolved_owner = True
        elif is_container_runtime_service(service):
            unresolved_owner = True
        else:
            owner_units.add(service.unit)
    ownership = "UNRESOLVED"
    systemd_unit: str | None = None
    if len(owner_units) > 1:
        ownership = "AMBIGUOUS"
    elif len(owner_units) == 1 and not unresolved_owner:
        ownership = "SYSTEMD_SERVICE"
        systemd_unit = next(iter(owner_units))
    result: dict[str, Any] = {
        "protocol": listener.protocol,
        "localAddress": listener.local_address,
        "port": listener.port,
        "wildcard": listener.wildcard,
        "loopback": listener.loopback,
        "ownershipState": ownership,
    }
    if systemd_unit is not None:
        result["systemdUnit"] = systemd_unit
    if len(listener.process_names) == 1:
        result["processName"] = listener.process_names[0]
    return result


def discover_listeners(services: Iterable[SystemdService]) -> dict[str, Any]:
    ss = trusted_ss_executable()
    if ss is None:
        return {"status": "NOT_AVAILABLE", "statusReasons": [], "items": []}
    result = run_readonly_command(ss, ["-H", "-lntup"])
    if result.timed_out or result.too_large or result.returncode != 0:
        return {"status": "COMMAND_FAILED", "statusReasons": [REASON_COMMAND_FAILED], "items": []}
    reasons: set[str] = set()
    parsed, _partial = parse_ss_output(result.stdout, reasons)
    items = [listener_json(listener, services) for listener in parsed]
    items.sort(key=lambda item: (item["protocol"], item["localAddress"], item["port"], item.get("systemdUnit", ""), item.get("processName", "")))
    return {"status": "PARTIAL" if reasons else "DISCOVERED", "statusReasons": sorted(reasons), "items": items}


def host_discovery() -> dict[str, Any]:
    try:
        systemd, services = discover_systemd_services()
    except Exception:
        systemd, services = {"status": "COMMAND_FAILED", "statusReasons": [REASON_COMMAND_FAILED], "services": []}, []
    try:
        listeners = discover_listeners(services)
    except Exception:
        listeners = {"status": "COMMAND_FAILED", "statusReasons": [REASON_COMMAND_FAILED], "items": []}
    return {"systemd": systemd, "listeners": listeners}


def image_repository(image: Any) -> str | None:
    value = as_text(image)
    if value is None or not value or len(value) > 512 or any(character.isspace() or ord(character) < 32 for character in value):
        return None
    repository = value.lower().split("@", 1)[0]
    slash = repository.rfind("/")
    colon = repository.rfind(":")
    if colon > slash:
        repository = repository[:colon]
    return repository


def is_caddy_image(image: Any) -> bool:
    repository = image_repository(image)
    return repository in CADDY_IMAGE_REPOSITORIES or repository == "ghcr.io/caddyserver/caddy"


def valid_mount_source(source: Any) -> str | None:
    value = as_text(source)
    if value is None or len(value) > 4096 or not value.startswith("/") or value == "/" or "\x00" in value:
        return None
    parts = value.split("/")[1:]
    return value if parts and all(part not in {"", ".", ".."} for part in parts) else None


def secure_read_caddy_autosave(mount_source: str) -> bytes:
    source = valid_mount_source(mount_source)
    required_flags = ("O_CLOEXEC", "O_DIRECTORY", "O_NOFOLLOW")
    if sys.platform != "linux" or source is None or any(not hasattr(os, flag) for flag in required_flags):
        raise CaddyConfigFailure("CONFIG_UNAVAILABLE")

    directory_flags = os.O_RDONLY | os.O_CLOEXEC | os.O_DIRECTORY | os.O_NOFOLLOW
    file_flags = os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW | getattr(os, "O_NONBLOCK", 0)
    directory_fd = -1
    file_fd = -1
    try:
        directory_fd = os.open("/", directory_flags)
        for part in source.split("/")[1:] + ["caddy"]:
            next_fd = os.open(part, directory_flags, dir_fd=directory_fd)
            os.close(directory_fd)
            directory_fd = next_fd
        file_fd = os.open("autosave.json", file_flags, dir_fd=directory_fd)
        details = os.fstat(file_fd)
        if not stat.S_ISREG(details.st_mode):
            raise CaddyConfigFailure("CONFIG_UNAVAILABLE")
        if details.st_size > MAX_CADDY_CONFIG_BYTES:
            raise CaddyConfigFailure("CONFIG_TOO_LARGE")
        with os.fdopen(file_fd, "rb", closefd=True) as stream:
            file_fd = -1
            content = stream.read(MAX_CADDY_CONFIG_BYTES + 1)
        if len(content) > MAX_CADDY_CONFIG_BYTES:
            raise CaddyConfigFailure("CONFIG_TOO_LARGE")
        return content
    except CaddyConfigFailure:
        raise
    except OSError as error:
        raise CaddyConfigFailure("CONFIG_UNAVAILABLE") from error
    finally:
        if file_fd >= 0:
            os.close(file_fd)
        if directory_fd >= 0:
            os.close(directory_fd)


def safe_host(value: Any) -> str | None:
    host = as_text(value)
    if host is None or not host or len(host) > 253 or SECRET_MARKER_PATTERN.search(host):
        return None
    if any(ord(character) < 32 or character.isspace() for character in host) or any(character in host for character in "{}$/\\@"):
        return None
    wildcard = host.startswith("*.")
    candidate = host[2:] if wildcard else host
    candidate = candidate.rstrip(".").lower()
    try:
        normalized = str(ipaddress.ip_address(candidate))
    except ValueError:
        if not SAFE_DOMAIN_PATTERN.fullmatch(candidate):
            return None
        normalized = candidate
    return f"*.{normalized}" if wildcard else normalized


def safe_path_matcher(value: Any) -> str | None:
    path = as_text(value)
    if path is None or not path.startswith("/") or len(path) > 2048 or SECRET_MARKER_PATTERN.search(path):
        return None
    if any(ord(character) < 32 for character in path) or any(character in path for character in "{}$"):
        return None
    return path


def safe_dial(value: Any) -> str | None:
    dial = as_text(value)
    if dial is None or not dial or len(dial) > 255 or SECRET_MARKER_PATTERN.search(dial):
        return None
    if any(character.isspace() or ord(character) < 32 for character in dial) or any(character in dial for character in "{}$/\\@"):
        return None
    host: str
    port_text: str
    if dial.startswith("["):
        closing = dial.find("]")
        if closing < 0 or closing + 1 >= len(dial) or dial[closing + 1] != ":":
            return None
        try:
            host = f"[{ipaddress.IPv6Address(dial[1:closing]).compressed}]"
        except ValueError:
            return None
        port_text = dial[closing + 2:]
    else:
        if dial.count(":") != 1:
            return None
        raw_host, port_text = dial.rsplit(":", 1)
        if not raw_host:
            return None
        try:
            host = str(ipaddress.IPv4Address(raw_host))
        except ValueError:
            if not SAFE_DIAL_HOST_PATTERN.fullmatch(raw_host):
                return None
            host = raw_host.lower()
    if not port_text.isdigit() or not 1 <= int(port_text) <= 65535:
        return None
    return f"{host}:{int(port_text)}"


def route_match_context(route: dict[str, Any], key: str, sanitizer: Any) -> tuple[list[str], bool]:
    values: set[str] = set()
    present = False
    for raw_match in list_value(route.get("match")):
        match = object_value(raw_match)
        present = present or key in match
        for raw_value in list_value(match.get(key)):
            if (value := sanitizer(raw_value)) is not None:
                values.add(value)
    return sorted(values), present


def sanitized_upstreams(handler: dict[str, Any]) -> tuple[list[dict[str, str]], bool]:
    upstreams: list[dict[str, str]] = []
    for raw_upstream in list_value(handler.get("upstreams")):
        dial = safe_dial(object_value(raw_upstream).get("dial"))
        upstreams.append({"dial": dial} if dial is not None else {"status": "UNRESOLVED"})
    unique = {json.dumps(upstream, sort_keys=True, separators=(",", ":")): upstream for upstream in upstreams}
    return [unique[key] for key in sorted(unique)], handler.get("dynamic") is not None


def sanitize_caddy_config(raw_config: bytes | str) -> list[dict[str, Any]]:
    try:
        config = json.loads(raw_config)
    except (json.JSONDecodeError, UnicodeDecodeError, RecursionError, TypeError) as error:
        raise CaddyConfigFailure("CONFIG_INVALID") from error
    if not isinstance(config, dict):
        raise CaddyConfigFailure("CONFIG_INVALID")

    servers = object_value(object_value(object_value(config.get("apps")).get("http")).get("servers"))
    routes: list[dict[str, Any]] = []
    visited = 0

    def walk(raw_routes: Any, inherited_hosts: list[str], inherited_host_unresolved: bool, inherited_paths: list[str], depth: int) -> None:
        nonlocal visited
        if depth > MAX_CADDY_ROUTE_DEPTH:
            raise CaddyConfigFailure("CONFIG_INVALID")
        for raw_route in list_value(raw_routes):
            visited += 1
            if visited > MAX_CADDY_ROUTES:
                raise CaddyConfigFailure("CONFIG_INVALID")
            route = object_value(raw_route)
            own_hosts, has_host_matcher = route_match_context(route, "host", safe_host)
            own_paths, has_path_matcher = route_match_context(route, "path", safe_path_matcher)
            hosts = own_hosts if has_host_matcher else inherited_hosts
            host_unresolved = (has_host_matcher and not own_hosts) or (not has_host_matcher and inherited_host_unresolved)
            paths = own_paths if has_path_matcher else inherited_paths
            for raw_handler in list_value(route.get("handle")):
                handler = object_value(raw_handler)
                handler_name = as_text(handler.get("handler"))
                if handler_name == "reverse_proxy":
                    upstreams, dynamic = sanitized_upstreams(handler)
                    sanitized: dict[str, Any] = {
                        "hosts": sorted(set(hosts)),
                        "hostless": not hosts and not host_unresolved,
                        "upstreams": upstreams,
                    }
                    if host_unresolved:
                        sanitized["hostMatcher"] = "UNRESOLVED"
                    if paths:
                        sanitized["paths"] = sorted(set(paths))
                    if dynamic:
                        sanitized["dynamicUpstreams"] = "UNSUPPORTED"
                    routes.append(sanitized)
                elif handler_name == "subroute":
                    walk(handler.get("routes"), hosts, host_unresolved, paths, depth + 1)

    for server_name in sorted(servers):
        walk(object_value(servers.get(server_name)).get("routes"), [], False, [], 0)
    unique = {json.dumps(route, sort_keys=True, separators=(",", ":")): route for route in routes}
    return [unique[key] for key in sorted(unique)]


def caddy_instance(raw: Any) -> dict[str, Any] | None:
    container = object_value(raw)
    config = object_value(container.get("Config"))
    image = as_text(config.get("Image"))
    if not is_caddy_image(image):
        return None
    container_id = as_text(container.get("Id"))
    if container_id is None or not DOCKER_ID_PATTERN.fullmatch(container_id):
        return None
    name = (as_text(container.get("Name")) or container_id[:12]).lstrip("/")
    instance: dict[str, Any] = {"containerId": container_id, "containerName": name, "image": image, "routes": []}
    config_mounts = [object_value(mount) for mount in list_value(container.get("Mounts")) if object_value(mount).get("Destination") == "/config"]
    if not config_mounts:
        instance["status"] = "CONFIG_MOUNT_MISSING"
        return instance
    if len(config_mounts) != 1:
        instance["status"] = "AMBIGUOUS"
        return instance
    mount = config_mounts[0]
    if as_text(mount.get("Type")) not in {"bind", "volume"} or (source := valid_mount_source(mount.get("Source"))) is None:
        instance["status"] = "CONFIG_UNAVAILABLE"
        return instance
    try:
        instance["routes"] = sanitize_caddy_config(secure_read_caddy_autosave(source))
        instance["status"] = "DISCOVERED"
    except CaddyConfigFailure as error:
        instance["status"] = error.status
    except Exception:
        instance["status"] = "CONFIG_UNAVAILABLE"
    return instance


def caddy_summary_from_instances(instances: list[dict[str, Any]]) -> dict[str, Any]:
    instances.sort(key=lambda instance: (instance["containerName"], instance["containerId"]))
    if not instances:
        return {"status": "NOT_DETECTED", "instances": []}
    statuses = {instance["status"] for instance in instances}
    status = next(iter(statuses)) if len(statuses) == 1 else "PARTIAL"
    return {"status": status, "instances": instances}


def caddy_summary(raw_containers: Iterable[Any]) -> dict[str, Any]:
    return caddy_summary_from_instances([instance for raw in raw_containers if (instance := caddy_instance(raw)) is not None])


def compose_metadata(labels: Any) -> dict[str, str]:
    label_map = object_value(labels)
    return {
        output_name: value
        for label_name, output_name in COMPOSE_LABELS.items()
        if (value := as_text(label_map.get(label_name))) is not None
    }


def sanitize_ports(ports: Any) -> list[dict[str, str]]:
    sanitized: list[dict[str, str]] = []
    for port_name, bindings in object_value(ports).items():
        match = PORT_PATTERN.fullmatch(port_name) if isinstance(port_name, str) else None
        if not match:
            continue
        base = {"containerPort": match.group(1), "protocol": match.group(2)}
        if bindings is None:
            sanitized.append(base)
            continue
        for binding in list_value(bindings):
            binding_map = object_value(binding)
            entry = dict(base)
            host_ip = as_text(binding_map.get("HostIp"))
            host_port = as_text(binding_map.get("HostPort"))
            if host_ip is not None:
                entry["hostIp"] = host_ip
            if host_port is not None:
                entry["hostPort"] = host_port
            sanitized.append(entry)
    return sorted(sanitized, key=lambda item: (item["containerPort"], item["protocol"], item.get("hostIp", ""), item.get("hostPort", "")))


def is_secret_mount(mount: dict[str, Any]) -> bool:
    destination = as_text(mount.get("Destination")) or ""
    mount_type = as_text(mount.get("Type")) or ""
    return mount_type == "secret" or destination == "/run/secrets" or destination.startswith("/run/secrets/")


def sanitize_mounts(mounts: Any) -> list[dict[str, Any]]:
    sanitized: list[dict[str, Any]] = []
    for raw_mount in list_value(mounts):
        mount = object_value(raw_mount)
        mount_type = as_text(mount.get("Type"))
        destination = as_text(mount.get("Destination"))
        if mount_type not in {"bind", "volume", "tmpfs"} or destination is None or is_secret_mount(mount):
            continue
        entry: dict[str, Any] = {
            "type": mount_type,
            "destination": destination,
            "readOnly": mount.get("RW") is False,
        }
        source = as_text(mount.get("Source"))
        if source is not None:
            entry["source"] = source
        if mount_type == "volume":
            name = as_text(mount.get("Name"))
            if name is not None:
                entry["volumeName"] = name
        sanitized.append(entry)
    return sorted(sanitized, key=lambda item: (item["destination"], item["type"], item.get("source", "")))


def sanitize_container(raw: Any) -> dict[str, Any]:
    container = object_value(raw)
    container_id = as_text(container.get("Id"))
    if container_id is None or not DOCKER_ID_PATTERN.fullmatch(container_id):
        raise HelperFailure("DOCKER_INSPECTION_FAILED", "Docker inspection returned malformed container data.")
    config = object_value(container.get("Config"))
    state = object_value(container.get("State"))
    host_config = object_value(container.get("HostConfig"))
    restart_policy = object_value(host_config.get("RestartPolicy"))
    network_settings = object_value(container.get("NetworkSettings"))
    name = (as_text(container.get("Name")) or container_id[:12]).lstrip("/")
    result: dict[str, Any] = {
        "id": container_id,
        "name": name,
        "image": as_text(config.get("Image")),
        "state": as_text(state.get("Status")),
        "restartPolicy": as_text(restart_policy.get("Name")),
        "ports": sanitize_ports(network_settings.get("Ports")),
        "mounts": sanitize_mounts(container.get("Mounts")),
        "networks": sorted(name for name in object_value(network_settings.get("Networks")).keys() if isinstance(name, str)),
    }
    compose = compose_metadata(config.get("Labels"))
    if compose:
        result["compose"] = compose
    return result


def compose_projects(containers: Iterable[dict[str, Any]]) -> list[dict[str, str]]:
    projects: dict[str, dict[str, str]] = {}
    for container in containers:
        compose = object_value(container.get("compose"))
        project = as_text(compose.get("project"))
        if project is None:
            continue
        summary = projects.setdefault(project, {"project": project})
        for key in ("workingDirectory", "configFiles"):
            value = as_text(compose.get(key))
            if value is not None and key not in summary:
                summary[key] = value
    return [projects[name] for name in sorted(projects)]


def snapshot_from_containers(
    engine_version: str,
    containers: list[dict[str, Any]],
    caddy: dict[str, Any] | None = None,
    systemd: dict[str, Any] | None = None,
    listeners: dict[str, Any] | None = None,
) -> dict[str, Any]:
    containers.sort(key=lambda item: (item["name"], item["id"]))
    snapshot: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "ok": True,
        "engine": {"version": engine_version},
        "containers": containers,
        "composeProjects": compose_projects(containers),
        "caddy": caddy or {"status": "NOT_DETECTED", "instances": []},
    }
    if systemd is not None:
        snapshot["systemd"] = systemd
    if listeners is not None:
        snapshot["listeners"] = listeners
    return snapshot


def sanitized_snapshot(engine_version: str, raw_containers: Iterable[Any]) -> dict[str, Any]:
    raw = list(raw_containers)
    return snapshot_from_containers(engine_version, [sanitize_container(container) for container in raw], caddy_summary(raw))


def chunks(items: list[str], size: int) -> Iterable[list[str]]:
    for index in range(0, len(items), size):
        yield items[index:index + size]


def collect_snapshot() -> dict[str, Any]:
    docker = trusted_docker_executable()
    version = run_docker(docker, ["version", "--format", "{{.Server.Version}}"], "DOCKER_DAEMON_UNAVAILABLE")
    engine_version = version.strip()
    if not engine_version:
        raise HelperFailure("DOCKER_DAEMON_UNAVAILABLE", "Docker is not available on this host.")
    discovered_ids = [line.strip() for line in run_docker(docker, ["ps", "-aq"], "DOCKER_INSPECTION_FAILED").splitlines() if line.strip()]
    if any(not DOCKER_ID_PATTERN.fullmatch(container_id) for container_id in discovered_ids):
        raise HelperFailure("DOCKER_INSPECTION_FAILED", "Docker inspection returned malformed container data.")
    containers: list[dict[str, Any]] = []
    caddy_instances: list[dict[str, Any]] = []
    for batch in chunks(discovered_ids, INSPECT_BATCH_SIZE):
        inspect_output = run_docker(docker, ["inspect", "--type", "container", *batch], "DOCKER_INSPECTION_FAILED")
        try:
            inspected = json.loads(inspect_output)
        except json.JSONDecodeError:
            raise HelperFailure("DOCKER_INSPECTION_FAILED", "Docker inspection returned invalid data.")
        if not isinstance(inspected, list):
            raise HelperFailure("DOCKER_INSPECTION_FAILED", "Docker inspection returned invalid data.")
        containers.extend(sanitize_container(raw) for raw in inspected)
        caddy_instances.extend(instance for raw in inspected if (instance := caddy_instance(raw)) is not None)
    optional_discovery = host_discovery()
    return snapshot_from_containers(
        engine_version,
        containers,
        caddy_summary_from_instances(caddy_instances),
        systemd=optional_discovery["systemd"],
        listeners=optional_discovery["listeners"],
    )


def main(arguments: list[str] | None = None) -> int:
    if arguments is None:
        arguments = sys.argv[1:]
    if arguments:
        write_json(failure("INVALID_INVOCATION", "This helper does not accept arguments."))
        return 2
    try:
        write_json(collect_snapshot())
        return 0
    except HelperFailure as error:
        write_json(failure(error.code, error.message))
        return 1
    except Exception:
        write_json(failure("INTERNAL_ERROR", "Docker inspection could not be completed."))
        return 1


if __name__ == "__main__":
    sys.exit(main())
