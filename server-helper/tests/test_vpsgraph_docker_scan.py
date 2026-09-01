import contextlib
import io
import json
import os
import random
import string
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import vpsgraph_docker_scan as scan  # noqa: E402

ESCAPED_SYSTEMD_UNIT = r"systemd-fsck@dev-disk-by\x2duuid-352C\x2dFCAB.service"


def fixture(name: str):
    return json.loads((ROOT / "fixtures" / name).read_text(encoding="utf-8"))


def fixture_bytes(name: str) -> bytes:
    return (ROOT / "fixtures" / name).read_bytes()


def assert_status_reason_invariant(test: unittest.TestCase, summary: dict) -> None:
    reasons = summary["statusReasons"]
    if summary["status"] == "PARTIAL":
        test.assertTrue(reasons)
    if not reasons:
        test.assertNotEqual("PARTIAL", summary["status"])


class ParserRobustnessTest(unittest.TestCase):
    def test_bounded_arbitrary_text_fails_closed_without_parser_crashes(self):
        generator = random.Random(6101)
        alphabet = string.printable + "é\x00"
        for _ in range(200):
            raw = "".join(generator.choice(alphabet) for _ in range(generator.randrange(0, 2048)))
            self.assertIsInstance(scan.parse_unit_names(raw)[0], list)
            self.assertIsInstance(scan.parse_systemctl_show(raw)[0], list)
            self.assertIsInstance(scan.parse_ss_output(raw)[0], list)


def caddy_container(identifier: str, name: str, source: str | None = "/var/lib/docker/volumes/caddy/_data", image: str = "caddy:2.9"):
    mounts = [] if source is None else [{"Type": "volume", "Name": f"{name}-config", "Source": source, "Destination": "/config", "RW": True}]
    return {
        "Id": identifier * 64,
        "Name": f"/{name}",
        "Config": {"Image": image, "Labels": {}},
        "State": {"Status": "running"},
        "HostConfig": {"RestartPolicy": {"Name": "unless-stopped"}},
        "NetworkSettings": {"Ports": {"80/tcp": None, "443/tcp": None}, "Networks": {"web": {}}},
        "Mounts": mounts,
    }


class DockerSanitizationTest(unittest.TestCase):
    def setUp(self):
        self.snapshot = scan.sanitized_snapshot("28.3.2", fixture("inspect-containers.json"))

    def test_schema_and_deterministic_container_and_project_order(self):
        self.assertEqual(1, self.snapshot["schemaVersion"])
        self.assertTrue(self.snapshot["ok"])
        self.assertEqual(["compose-api", "compose-web", "standalone-worker"], [item["name"] for item in self.snapshot["containers"]])
        self.assertEqual(["alpha", "beta"], [item["project"] for item in self.snapshot["composeProjects"]])

    def test_secrets_and_unapproved_labels_never_serialize(self):
        serialized = json.dumps(self.snapshot)
        for forbidden in (
            "DATABASE_URL",
            "API_KEY",
            "JWT_SECRET",
            "PASSWORD",
            "super-secret",
            "private.label",
            "UnknownFutureField",
            "private.example.test",
            "never expose this",
        ):
            self.assertNotIn(forbidden, serialized)

    def test_compose_container_exposes_only_allowlisted_metadata(self):
        web = next(item for item in self.snapshot["containers"] if item["name"] == "compose-web")
        self.assertEqual(
            {"project": "alpha", "service": "web", "workingDirectory": "/srv/alpha", "configFiles": "/srv/alpha/compose.yml"},
            web["compose"],
        )
        self.assertEqual(["backend", "frontend"], web["networks"])
        self.assertEqual(
            [
                {"containerPort": "443", "protocol": "tcp"},
                {"containerPort": "80", "protocol": "tcp", "hostIp": "0.0.0.0", "hostPort": "8080"},
            ],
            web["ports"],
        )
        self.assertEqual(2, len(web["mounts"]))
        self.assertNotIn("/run/secrets/app", json.dumps(web["mounts"]))
        volume = next(item for item in web["mounts"] if item["type"] == "volume")
        self.assertEqual("alpha-cache", volume["volumeName"])
        self.assertTrue(volume["readOnly"])

    def test_stopped_standalone_container_and_no_ports_are_preserved(self):
        worker = next(item for item in self.snapshot["containers"] if item["name"] == "standalone-worker")
        self.assertEqual("exited", worker["state"])
        self.assertEqual([], worker["ports"])
        self.assertNotIn("compose", worker)

    def test_zero_containers_is_a_successful_empty_result(self):
        result = scan.sanitized_snapshot("28.3.2", [])
        self.assertTrue(result["ok"])
        self.assertEqual([], result["containers"])
        self.assertEqual([], result["composeProjects"])

    def test_missing_optional_fields_are_safe(self):
        result = scan.sanitize_container(fixture("minimal-container.json"))
        self.assertIsNone(result["image"])
        self.assertIsNone(result["state"])
        self.assertIsNone(result["restartPolicy"])
        self.assertEqual([], result["ports"])
        self.assertEqual([], result["mounts"])
        self.assertEqual([], result["networks"])

    def test_malformed_inspect_object_is_rejected(self):
        with self.assertRaises(scan.HelperFailure) as error:
            scan.sanitize_container({"Id": "not-a-docker-id"})
        self.assertEqual("DOCKER_INSPECTION_FAILED", error.exception.code)

    def test_unknown_fields_are_discarded(self):
        result = scan.sanitize_container({
            "Id": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
            "Name": "/unknowns",
            "Config": {"Image": "safe", "Env": ["PASSWORD=hidden"], "Labels": {"random": "hidden"}},
            "State": {"Status": "running", "Pid": 1234},
            "HostConfig": {"RestartPolicy": {"Name": "no"}, "Privileged": True},
            "NetworkSettings": {"Networks": {"safe": {"IPAddress": "172.1.2.3"}}},
            "Mounts": [{"Type": "secret", "Destination": "/run/secrets/key", "Source": "hidden"}],
            "FutureField": "hidden",
        })
        serialized = json.dumps(result)
        self.assertNotIn("PASSWORD", serialized)
        self.assertNotIn("Privileged", serialized)
        self.assertNotIn("FutureField", serialized)
        self.assertNotIn("hidden", serialized)

    def test_runtime_arguments_are_rejected_without_docker(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            exit_code = scan.main(["foo"])
        result = json.loads(output.getvalue())
        self.assertEqual(2, exit_code)
        self.assertEqual("INVALID_INVOCATION", result["error"]["code"])

    def test_collection_uses_fixed_commands_and_one_batched_inspect(self):
        raw = fixture("inspect-containers.json")
        calls = []

        def docker_output(_docker, arguments, _error_code):
            calls.append(arguments)
            if arguments[:1] == ["version"]:
                return "28.3.2\n"
            if arguments == ["ps", "-aq"]:
                return "\n".join(item["Id"] for item in raw) + "\n"
            if arguments[:3] == ["inspect", "--type", "container"]:
                return json.dumps(raw)
            self.fail(f"unexpected Docker invocation: {arguments}")

        with patch.object(scan, "trusted_docker_executable", return_value=Path("/usr/bin/docker")), patch.object(scan, "run_docker", side_effect=docker_output), patch.object(scan, "host_discovery", return_value={"systemd": {"status": "NOT_AVAILABLE", "services": []}, "listeners": {"status": "NOT_AVAILABLE", "items": []}}):
            result = scan.collect_snapshot()

        self.assertTrue(result["ok"])
        self.assertEqual("NOT_AVAILABLE", result["systemd"]["status"])
        self.assertEqual("NOT_AVAILABLE", result["listeners"]["status"])
        self.assertEqual(3, len(calls))
        self.assertEqual(["version", "--format", "{{.Server.Version}}"], calls[0])
        self.assertEqual(["ps", "-aq"], calls[1])
        self.assertEqual(["inspect", "--type", "container", *(item["Id"] for item in raw)], calls[2])

    def test_zero_discovery_never_invokes_inspect(self):
        calls = []

        def docker_output(_docker, arguments, _error_code):
            calls.append(arguments)
            return "28.3.2\n" if arguments[:1] == ["version"] else ""

        with patch.object(scan, "trusted_docker_executable", return_value=Path("/usr/bin/docker")), patch.object(scan, "run_docker", side_effect=docker_output), patch.object(scan, "host_discovery", return_value={"systemd": {"status": "NOT_AVAILABLE", "services": []}, "listeners": {"status": "NOT_AVAILABLE", "items": []}}):
            result = scan.collect_snapshot()

        self.assertEqual([], result["containers"])
        self.assertEqual([["version", "--format", "{{.Server.Version}}"], ["ps", "-aq"]], calls)


class CaddySanitizationTest(unittest.TestCase):
    def test_native_routes_cover_hosts_upstreams_subroutes_and_catch_all(self):
        routes = scan.sanitize_caddy_config(fixture_bytes("caddy-routes.json"))
        self.assertEqual(6, len(routes))

        normal = next(route for route in routes if route["hosts"] == ["example.com", "www.example.com"])
        self.assertEqual([{"dial": "localhost:9000"}, {"dial": "portfolio-web:3000"}], normal["upstreams"])
        wildcard = next(route for route in routes if route["hosts"] == ["*.example.net"])
        self.assertEqual([{"dial": "127.0.0.1:8080"}, {"dial": "[::1]:8080"}], wildcard["upstreams"])
        nested = next(route for route in routes if route["hosts"] == ["nested.example.org"])
        self.assertEqual(["/api/*"], nested["paths"])
        self.assertEqual([{"dial": "nested-api:4000"}], nested["upstreams"])
        hostless = next(route for route in routes if route["hostless"])
        self.assertEqual([], hostless["hosts"])
        self.assertEqual([{"dial": "catch-all:8081"}], hostless["upstreams"])

    def test_dynamic_and_placeholder_upstreams_do_not_expose_configuration(self):
        routes = scan.sanitize_caddy_config(fixture_bytes("caddy-routes.json"))
        dynamic = next(route for route in routes if route["hosts"] == ["dynamic.example.org"])
        self.assertEqual("UNSUPPORTED", dynamic["dynamicUpstreams"])
        self.assertEqual([], dynamic["upstreams"])
        placeholder = next(route for route in routes if route["hosts"] == ["placeholder.example.org"])
        self.assertEqual([{"status": "UNRESOLVED"}], placeholder["upstreams"])
        serialized = json.dumps(routes)
        self.assertNotIn("UPSTREAM", serialized)
        self.assertNotIn("must-not-escape", serialized)

    def test_missing_http_and_no_reverse_proxy_are_valid_empty_results(self):
        self.assertEqual([], scan.sanitize_caddy_config(fixture_bytes("caddy-missing-http.json")))
        self.assertEqual([], scan.sanitize_caddy_config(fixture_bytes("caddy-no-proxy.json")))

    def test_malformed_json_is_classified_without_raw_content(self):
        with self.assertRaises(scan.CaddyConfigFailure) as error:
            scan.sanitize_caddy_config(fixture_bytes("caddy-malformed.json"))
        self.assertEqual("CONFIG_INVALID", error.exception.status)
        self.assertEqual("CONFIG_INVALID", str(error.exception))

    def test_suspicious_dial_values_become_unresolved(self):
        suspicious_values = ["a" * 300 + ":443", "user:pass@example.com:443", "backend:8080\nAuthorization"]
        raw = {"apps": {"http": {"servers": {"srv": {"routes": [{"handle": [{"handler": "reverse_proxy", "upstreams": [{"dial": value} for value in suspicious_values]}]}]}}}}}
        routes = scan.sanitize_caddy_config(json.dumps(raw))
        self.assertEqual([{"status": "UNRESOLVED"}], routes[0]["upstreams"])
        serialized = json.dumps(routes)
        for suspicious in suspicious_values:
            self.assertNotIn(suspicious, serialized)

    def test_final_serialized_result_excludes_secret_names_and_values(self):
        routes = scan.sanitize_caddy_config(fixture_bytes("caddy-secret-heavy.json"))
        serialized = json.dumps({"status": "DISCOVERED", "routes": routes}, sort_keys=True)
        self.assertIn("example.com", serialized)
        self.assertIn("portfolio-web:3000", serialized)
        unresolved = next(route for route in routes if route.get("hostMatcher") == "UNRESOLVED")
        self.assertFalse(unresolved["hostless"])
        for forbidden in (
            "DATABASE_URL", "PASSWORD", "API_KEY", "JWT_SECRET", "CF_API_TOKEN", "CLOUDFLARE_API_TOKEN",
            "Authorization", "Bearer", "super-secret", "AWS_SECRET_ACCESS_KEY", "basic_auth", "private_key",
            "never-return", "admin",
            "SECRET_DOMAIN",
        ):
            self.assertNotIn(forbidden, serialized)

    def test_detection_uses_image_metadata_not_container_name(self):
        named_caddy = caddy_container("a", "caddy", image="example/not-caddy:1")
        self.assertEqual({"status": "NOT_DETECTED", "instances": []}, scan.caddy_summary([named_caddy]))

    def test_one_and_multiple_instances_are_deterministic(self):
        first = caddy_container("a", "z-caddy", "/var/lib/docker/volumes/z/_data")
        second = caddy_container("b", "a-caddy", "/var/lib/docker/volumes/a/_data", "docker.io/library/caddy:2.9")
        with patch.object(scan, "secure_read_caddy_autosave", return_value=fixture_bytes("caddy-routes.json")):
            one = scan.caddy_summary([first])
            multiple = scan.caddy_summary([first, second])
        self.assertEqual("DISCOVERED", one["status"])
        self.assertEqual(["a-caddy", "z-caddy"], [instance["containerName"] for instance in multiple["instances"]])
        self.assertTrue(all(instance["status"] == "DISCOVERED" for instance in multiple["instances"]))

    def test_optional_caddy_failure_keeps_docker_snapshot_successful(self):
        missing_mount = caddy_container("c", "caddy-without-config", source=None)
        snapshot = scan.sanitized_snapshot("28.3.2", [missing_mount])
        self.assertEqual(1, snapshot["schemaVersion"])
        self.assertTrue(snapshot["ok"])
        self.assertEqual("CONFIG_MOUNT_MISSING", snapshot["caddy"]["status"])
        self.assertEqual(["caddy-without-config"], [container["name"] for container in snapshot["containers"]])

    def test_multiple_config_mounts_are_ambiguous(self):
        raw = caddy_container("d", "ambiguous")
        raw["Mounts"].append({"Type": "bind", "Source": "/srv/caddy-config", "Destination": "/config", "RW": True})
        summary = scan.caddy_summary([raw])
        self.assertEqual("AMBIGUOUS", summary["status"])
        self.assertEqual("AMBIGUOUS", summary["instances"][0]["status"])

    def test_config_read_failures_are_optional_and_non_sensitive(self):
        raw = caddy_container("e", "unavailable")
        for status in ("CONFIG_UNAVAILABLE", "CONFIG_TOO_LARGE", "CONFIG_INVALID"):
            with self.subTest(status=status), patch.object(scan, "secure_read_caddy_autosave", side_effect=scan.CaddyConfigFailure(status)):
                summary = scan.caddy_summary([raw])
            self.assertEqual(status, summary["status"])
            self.assertEqual([], summary["instances"][0]["routes"])
            self.assertNotIn("/var/lib/docker", json.dumps(summary))

    def test_mixed_instance_results_are_partial(self):
        discovered = caddy_container("f", "discovered")
        missing = caddy_container("1", "missing", source=None)
        with patch.object(scan, "secure_read_caddy_autosave", return_value=fixture_bytes("caddy-routes.json")):
            summary = scan.caddy_summary([missing, discovered])
        self.assertEqual("PARTIAL", summary["status"])
        self.assertEqual(["CONFIG_MOUNT_MISSING", "DISCOVERED"], sorted(instance["status"] for instance in summary["instances"]))


class SystemdSanitizationTest(unittest.TestCase):
    def test_escaped_systemd_units_are_valid_bounded_and_canonical(self):
        valid = (
            ESCAPED_SYSTEMD_UNIT,
            r"foo@bar\x2dbaz.service",
            r"foo\x41.service",
            r"foo\x2dbar\x2ebaz.service",
        )
        invalid = (
            r"foo\x.service",
            r"foo\x2.service",
            r"foo\xGG.service",
            r"foo\.service",
            r"foo\bar.service",
            r"foo\x00.service",
            "foo\nbar.service",
            "-foo.service",
            "foo.socket",
            f"{'a' * 248}\\x2d.service",
        )
        for unit in valid:
            with self.subTest(unit=unit):
                self.assertEqual(unit, scan.valid_systemd_unit(unit))
        for unit in invalid:
            with self.subTest(unit=unit):
                self.assertIsNone(scan.valid_systemd_unit(unit))

    def test_real_debian_escaped_unit_is_sorted_enriched_and_one_argv_value(self):
        listing = scan.CommandOutput((ROOT / "fixtures" / "systemctl-list-units-debian-real.txt").read_text(encoding="utf-8"), "", 0)
        shown = scan.CommandOutput((ROOT / "fixtures" / "systemctl-show-debian-real.txt").read_text(encoding="utf-8"), "", 0)
        calls = []

        def command(_path, arguments):
            calls.append(arguments)
            return listing if "list-units" in arguments else shown

        with patch.object(scan, "trusted_systemctl_executable", return_value=Path("/usr/bin/systemctl")), patch.object(scan, "run_readonly_command", side_effect=command):
            summary, services = scan.discover_systemd_services()
        self.assertEqual("DISCOVERED", summary["status"])
        self.assertEqual([], summary["statusReasons"])
        expected_units = ["docker.service", "netdata.service", "ssh.service", ESCAPED_SYSTEMD_UNIT]
        self.assertEqual(expected_units, [service.unit for service in services])
        escaped = next(service for service in services if service.unit == ESCAPED_SYSTEMD_UNIT)
        self.assertEqual(f"/system.slice/{ESCAPED_SYSTEMD_UNIT}", escaped.control_group)
        self.assertEqual(ESCAPED_SYSTEMD_UNIT, scan.systemd_service_for_cgroup(escaped.control_group, services).unit)
        show_argv = next(arguments for arguments in calls if "show" in arguments)
        self.assertEqual(1, show_argv.count(ESCAPED_SYSTEMD_UNIT))
        self.assertEqual(ESCAPED_SYSTEMD_UNIT, summary["services"][3]["unit"])
        self.assertEqual(f"/system.slice/{ESCAPED_SYSTEMD_UNIT}", summary["services"][3]["controlGroup"])
        assert_status_reason_invariant(self, summary)

    def test_list_and_show_are_allowlisted_sorted_and_secret_free(self):
        units, listing_partial = scan.parse_unit_names((ROOT / "fixtures" / "systemctl-list-units.txt").read_text(encoding="utf-8"))
        services, show_partial = scan.parse_systemctl_show((ROOT / "fixtures" / "systemctl-show.txt").read_text(encoding="utf-8"))
        serialized = json.dumps({"services": [service.json_value() for service in services]})
        self.assertEqual(["alpha.service", "disabled.service", "failed.service"], units)
        self.assertTrue(listing_partial)
        self.assertFalse(show_partial)
        self.assertEqual(["alpha.service", "disabled.service"], [service.unit for service in services])
        self.assertEqual("active", services[0].active_state)
        self.assertEqual("enabled", services[0].unit_file_state)
        self.assertNotIn("mainPid", serialized)
        for forbidden in ("Environment", "ExecStart", "DATABASE_URL", "API_KEY", "super-secret", "--password", "never-return"):
            self.assertNotIn(forbidden, serialized)

    def test_debian_show_records_keep_enrichment_when_id_is_first_middle_or_last(self):
        services, partial = scan.parse_systemctl_show((ROOT / "fixtures" / "systemctl-show-debian-multi.txt").read_text(encoding="utf-8"))
        by_unit = {service.unit: service for service in services}
        self.assertFalse(partial)
        netdata = by_unit["netdata.service"]
        self.assertEqual("/system.slice/netdata.service", netdata.control_group)
        self.assertEqual(1714490, netdata.main_pid)
        self.assertEqual("simple", netdata.service_type)
        self.assertEqual("root", netdata.user)
        self.assertEqual("netdata", netdata.group)
        self.assertEqual("/system.slice/ssh.service", by_unit["ssh.service"].control_group)
        self.assertIsNone(by_unit["ssh.service"].group)
        self.assertEqual("/system.slice/middle.service", by_unit["middle.service"].control_group)

    def test_base_service_and_show_enrichment_merge_before_ownership_resolution(self):
        base = scan.SystemdService("netdata.service", active_state="active", sub_state="running")
        enriched = scan.systemd_service_from_properties({
            "Id": "netdata.service",
            "ControlGroup": "/system.slice/netdata.service",
            "MainPID": "1714490",
            "Type": "simple",
            "User": "root",
            "Group": "netdata",
        })
        merged = scan.merge_systemd_service(base, enriched)
        self.assertEqual("active", merged.active_state)
        self.assertEqual("running", merged.sub_state)
        self.assertEqual("/system.slice/netdata.service", merged.control_group)
        self.assertEqual(1714490, merged.main_pid)
        self.assertEqual("simple", merged.service_type)
        self.assertEqual("root", merged.user)
        self.assertEqual("netdata", merged.group)
        listener = scan.ParsedListener("tcp", "127.0.0.1", 19999, False, True, ("netdata",), (1714490,))
        with patch.object(scan, "read_process_cgroup", return_value="/system.slice/netdata.service"):
            resolved = scan.listener_json(listener, [merged])
        self.assertEqual("netdata.service", resolved["systemdUnit"])

    def test_missing_optional_fields_controls_and_oversized_values_are_dropped(self):
        service = scan.systemd_service_from_properties({
            "Id": "minimal.service",
            "Description": "x" * 513,
            "User": "bad\nuser",
            "ControlGroup": "/system.slice/minimal.service\x00",
            "MainPID": "0",
        })
        self.assertEqual({"unit": "minimal.service"}, service.json_value())
        self.assertIsNone(service.main_pid)

    def test_empty_malformed_and_bounded_unit_lists_are_partial_when_needed(self):
        self.assertEqual(([], False), scan.parse_unit_names(""))
        self.assertEqual(([], True), scan.parse_unit_names("not output\n"))
        self.assertEqual(([], False), scan.parse_unit_names("missing.service not-found inactive dead Missing\n"))
        raw = "\n".join(f"svc{index:04d}.service loaded active running" for index in range(scan.MAX_SYSTEMD_SERVICES + 5))
        units, partial = scan.parse_unit_names(raw)
        self.assertTrue(partial)
        self.assertEqual(scan.MAX_SYSTEMD_SERVICES, len(units))
        self.assertEqual(sorted(units), units)

    def test_optional_systemd_degrades_without_binary_or_manager(self):
        with patch.object(scan, "trusted_systemctl_executable", return_value=None):
            summary, services = scan.discover_systemd_services()
        self.assertEqual({"status": "NOT_AVAILABLE", "statusReasons": [], "services": []}, summary)
        self.assertEqual([], services)
        not_systemd = scan.CommandOutput("", "System has not been booted with systemd as init system", 1)
        with patch.object(scan, "trusted_systemctl_executable", return_value=Path("/usr/bin/systemctl")), patch.object(scan, "run_readonly_command", return_value=not_systemd):
            summary, services = scan.discover_systemd_services()
        self.assertEqual("NOT_SYSTEMD", summary["status"])
        self.assertEqual([], summary["statusReasons"])
        self.assertEqual([], services)

    def test_discovery_uses_fixed_property_allowlist_and_batches(self):
        calls = []
        listing = scan.CommandOutput("zeta.service loaded active running\nalpha.service loaded active running\n", "", 0)
        shown = scan.CommandOutput("Id=alpha.service\nActiveState=active\n\nId=zeta.service\nActiveState=failed\n", "", 0)

        def command(_path, arguments):
            calls.append(arguments)
            return listing if "list-units" in arguments else shown

        with patch.object(scan, "trusted_systemctl_executable", return_value=Path("/usr/bin/systemctl")), patch.object(scan, "run_readonly_command", side_effect=command):
            summary, services = scan.discover_systemd_services()
        self.assertEqual("DISCOVERED", summary["status"])
        self.assertEqual([], summary["statusReasons"])
        assert_status_reason_invariant(self, summary)
        self.assertEqual(["alpha.service", "zeta.service"], [service.unit for service in services])
        self.assertEqual(["--system", "--no-legend", "--no-pager", "--plain", "list-units", "--type=service", "--all"], calls[0])
        self.assertIn("--", calls[1])
        self.assertEqual([f"--property={name}" for name in scan.SYSTEMD_PROPERTY_NAMES], [item for item in calls[1] if item.startswith("--property=")])

    def test_not_found_listing_entries_do_not_discard_loaded_service_enrichment(self):
        listing = scan.CommandOutput(
            "netdata.service loaded active running Netdata\nmissing.service not-found inactive dead Missing\n",
            "",
            0,
        )
        shown = scan.CommandOutput((ROOT / "fixtures" / "systemctl-netdata-show.txt").read_text(encoding="utf-8"), "", 0)
        with patch.object(scan, "trusted_systemctl_executable", return_value=Path("/usr/bin/systemctl")), patch.object(scan, "run_readonly_command", side_effect=[listing, shown]):
            summary, services = scan.discover_systemd_services()
        self.assertEqual("DISCOVERED", summary["status"])
        self.assertEqual([], summary["statusReasons"])
        self.assertEqual(["netdata.service"], [service.unit for service in services])
        self.assertEqual("/system.slice/netdata.service", services[0].control_group)
        self.assertEqual("netdata", services[0].group)

    def test_nonzero_batch_with_valid_stdout_keeps_valid_enrichment_and_is_partial_only_when_missing(self):
        listing = scan.CommandOutput("alpha.service loaded active running Alpha\nmissing.service loaded inactive dead Missing\n", "", 0)
        shown = scan.CommandOutput("Id=alpha.service\nActiveState=active\nControlGroup=/system.slice/alpha.service\n", "unit missing", 1)
        with patch.object(scan, "trusted_systemctl_executable", return_value=Path("/usr/bin/systemctl")), patch.object(scan, "run_readonly_command", side_effect=[listing, shown]):
            summary, services = scan.discover_systemd_services()
        self.assertEqual("PARTIAL", summary["status"])
        self.assertEqual([scan.REASON_SHOW_RECORD_MISSING], summary["statusReasons"])
        assert_status_reason_invariant(self, summary)
        self.assertEqual(["alpha.service"], [service.unit for service in services])
        self.assertEqual("/system.slice/alpha.service", services[0].control_group)

    def test_systemd_partial_reasons_cover_supported_incomplete_discovery(self):
        reasons: set[str] = set()
        scan.parse_unit_records("malformed\n", reasons)
        scan.parse_systemctl_show("Description=missing id\n", reasons)
        raw = "\n".join(f"svc{index:04d}.service loaded active running" for index in range(scan.MAX_SYSTEMD_SERVICES + 1))
        scan.parse_unit_records(raw, reasons)
        self.assertEqual(
            {scan.REASON_LIST_ROW_MALFORMED, scan.REASON_SHOW_RECORD_MALFORMED, scan.REASON_TRUNCATED},
            reasons,
        )

        listing = scan.CommandOutput("alpha.service loaded active running Alpha\n", "", 0)
        for output, expected in (
            (scan.CommandOutput("", "", 1, timed_out=True), scan.REASON_COMMAND_FAILED),
            (scan.CommandOutput("", "", 1, too_large=True), scan.REASON_TRUNCATED),
        ):
            with self.subTest(expected=expected), patch.object(scan, "trusted_systemctl_executable", return_value=Path("/usr/bin/systemctl")), patch.object(scan, "run_readonly_command", side_effect=[listing, output]):
                summary, _services = scan.discover_systemd_services()
            self.assertEqual("PARTIAL", summary["status"])
            self.assertIn(expected, summary["statusReasons"])
            assert_status_reason_invariant(self, summary)

    def test_nonloaded_units_are_skipped_without_partial_status(self):
        listing = scan.CommandOutput("missing.service not-found inactive dead Missing\nalpha.service loaded active running Alpha\n", "", 0)
        shown = scan.CommandOutput("Id=alpha.service\nActiveState=active\n", "", 0)
        with patch.object(scan, "trusted_systemctl_executable", return_value=Path("/usr/bin/systemctl")), patch.object(scan, "run_readonly_command", side_effect=[listing, shown]):
            summary, services = scan.discover_systemd_services()
        self.assertEqual("DISCOVERED", summary["status"])
        self.assertEqual([], summary["statusReasons"])
        self.assertEqual(["alpha.service"], [service.unit for service in services])
        assert_status_reason_invariant(self, summary)


class ListenerSanitizationTest(unittest.TestCase):
    def services(self):
        return [
            scan.SystemdService("alpha.service", control_group="/system.slice/alpha.service"),
            scan.SystemdService("alpha-worker.service", control_group="/system.slice/alpha.service/worker"),
            scan.SystemdService("beta.service", control_group="/system.slice/beta.service"),
            scan.SystemdService("docker.service", control_group="/system.slice/docker.service"),
        ]

    def test_fixture_parses_ipv4_ipv6_loopback_udp_and_distinct_protocols(self):
        listeners, partial = scan.parse_ss_output((ROOT / "fixtures" / "ss-listeners.txt").read_text(encoding="utf-8"))
        by_address = {(item.protocol, item.local_address, item.port): item for item in listeners}
        self.assertFalse(partial)
        self.assertTrue(by_address[("tcp", "0.0.0.0", 19999)].wildcard)
        self.assertTrue(by_address[("tcp", "::", 443)].wildcard)
        self.assertTrue(by_address[("tcp", "127.0.0.1", 631)].loopback)
        self.assertTrue(by_address[("udp", "::1", 5353)].loopback)
        self.assertIn(("udp", "0.0.0.0", 53), by_address)
        same_port, partial = scan.parse_ss_output("tcp LISTEN 0 1 0.0.0.0:53 0.0.0.0:*\nudp UNCONN 0 0 0.0.0.0:53 0.0.0.0:*")
        self.assertFalse(partial)
        self.assertEqual(["tcp", "udp"], [item.protocol for item in same_port])
        self.assertEqual(("fe80::1%eth0", 443, False, False), scan.parse_listener_address("[fe80::1%eth0]:443"))
        self.assertEqual(("127.0.0.53%lo", 53, False, True), scan.parse_listener_address("127.0.0.53%lo:53"))

    def test_scoped_ipv6_after_bracket_is_sanitized_and_deterministic(self):
        exact = "[fe80::eac9:da0a:d32:2190]%eth0:546"
        self.assertEqual(("fe80::eac9:da0a:d32:2190%eth0", 546, False, False), scan.parse_listener_address(exact))
        for scope in ("eth0", "ens3", "enp1s0", "lo", "docker0", "br-abc123", "veth1234"):
            with self.subTest(scope=scope):
                expected = (f"fe80::1%{scope}", 546, False, False)
                self.assertEqual(expected, scan.parse_listener_address(f"[fe80::1]%{scope}:546"))
                self.assertEqual(expected, scan.parse_listener_address(f"[fe80::1]%{scope}:546"))

        malformed = (
            "[fe80::1]%:546",
            "[fe80::1]%eth0",
            "[fe80::1]eth0:546",
            "[fe80::1]%../../etc:546",
            "[fe80::1]%eth 0:546",
            "[fe80::1]%eth0%foo:546",
            "[fe80::1%eth0]%ens3:546",
            "[fe80::1]%eth0:0",
            "[fe80::1]%eth0:70000",
            f"[fe80::1]%{'a' * 16}:546",
            "[fe80::1%eth0:546",
        )
        for value in malformed:
            with self.subTest(value=value):
                self.assertIsNone(scan.parse_listener_address(value))

    def test_malformed_addresses_ports_processes_and_limits_are_partial(self):
        raw = "\n".join((
            "tcp LISTEN 0 1 0.0.0.0:0 0.0.0.0:*",
            "udp UNCONN 0 0 0.0.0.0:70000 0.0.0.0:*",
            "tcp LISTEN 0 1 [::1]:80 [::]:* users:((\"app\",pid=bad,fd=1))",
        ))
        listeners, partial = scan.parse_ss_output(raw)
        self.assertEqual(1, len(listeners))
        self.assertEqual((), listeners[0].pids)
        self.assertTrue(partial)
        many = "\n".join(f"tcp LISTEN 0 1 127.0.0.1:{index + 1} 0.0.0.0:*" for index in range(scan.MAX_LISTENERS + 3))
        listeners, partial = scan.parse_ss_output(many)
        self.assertTrue(partial)
        self.assertEqual(scan.MAX_LISTENERS, len(listeners))

    def test_listener_partial_reasons_cover_supported_socket_loss(self):
        reasons: set[str] = set()
        raw = "\n".join((
            "short row",
            "sctp LISTEN 0 1 0.0.0.0:80 0.0.0.0:*",
            "tcp LISTEN 0 1 hostname:80 0.0.0.0:*",
            "udp UNCONN 0 0 0.0.0.0:0 0.0.0.0:*",
            *(f"tcp LISTEN 0 1 127.0.0.1:{index + 1} 0.0.0.0:*" for index in range(scan.MAX_LISTENERS + 1)),
        ))
        scan.parse_ss_output(raw, reasons)
        self.assertEqual(
            {
                scan.REASON_LISTENER_ROW_MALFORMED,
                scan.REASON_LISTENER_PROTOCOL_UNSUPPORTED,
                scan.REASON_LISTENER_ADDRESS_UNSUPPORTED,
                scan.REASON_LISTENER_PORT_INVALID,
                scan.REASON_TRUNCATED,
            },
            reasons,
        )

        with patch.object(scan, "trusted_ss_executable", return_value=Path("/usr/bin/ss")), patch.object(scan, "run_readonly_command", return_value=scan.CommandOutput("", "", 1)):
            summary = scan.discover_listeners([])
        self.assertEqual("COMMAND_FAILED", summary["status"])
        self.assertEqual([scan.REASON_COMMAND_FAILED], summary["statusReasons"])
        assert_status_reason_invariant(self, summary)

    def test_unparsed_process_metadata_keeps_a_valid_listener_discovered(self):
        listeners, partial = scan.parse_ss_output("tcp LISTEN 0 1 127.0.0.1:8080 0.0.0.0:* users:((\"app\",pid=bad,fd=1))")
        self.assertFalse(partial)
        self.assertEqual(1, len(listeners))
        self.assertEqual((), listeners[0].pids)

        command = scan.CommandOutput("tcp LISTEN 0 1 127.0.0.1:8080 0.0.0.0:* users:((\"app\",pid=123,fd=1))", "", 0)
        with patch.object(scan, "trusted_ss_executable", return_value=Path("/usr/bin/ss")), patch.object(scan, "run_readonly_command", return_value=command), patch.object(scan, "read_process_cgroup", return_value=None):
            summary = scan.discover_listeners([])
        self.assertEqual("DISCOVERED", summary["status"])
        self.assertEqual([], summary["statusReasons"])
        self.assertEqual("UNRESOLVED", summary["items"][0]["ownershipState"])
        assert_status_reason_invariant(self, summary)

    def test_cgroup_v2_resolution_is_longest_and_v1_and_docker_scope_do_not_guess(self):
        services = self.services()
        self.assertEqual("alpha-worker.service", scan.systemd_service_for_cgroup("/system.slice/alpha.service/worker/task", services).unit)
        self.assertIsNone(scan.systemd_service_for_cgroup("/system.slice/docker-123.scope", services))
        self.assertEqual("/system.slice/alpha.service", scan.cgroup_v2_path("0::/system.slice/alpha.service"))
        self.assertIsNone(scan.cgroup_v2_path("11:cpu:/system.slice/alpha.service"))

    def test_child_workers_multiple_owners_and_pid_races_are_conservative(self):
        listener = scan.ParsedListener("tcp", "0.0.0.0", 8080, True, False, ("alpha",), (11, 12))
        services = self.services()
        with patch.object(scan, "read_process_cgroup", side_effect=["/system.slice/alpha.service/worker/a", "/system.slice/alpha.service/worker/b"]):
            resolved = scan.listener_json(listener, services)
        self.assertEqual("SYSTEMD_SERVICE", resolved["ownershipState"])
        self.assertEqual("alpha-worker.service", resolved["systemdUnit"])
        with patch.object(scan, "read_process_cgroup", side_effect=["/system.slice/alpha.service", "/system.slice/beta.service"]):
            ambiguous = scan.listener_json(listener, services)
        self.assertEqual("AMBIGUOUS", ambiguous["ownershipState"])
        with patch.object(scan, "read_process_cgroup", side_effect=["/system.slice/alpha.service", None]):
            unresolved = scan.listener_json(listener, services)
        self.assertEqual("UNRESOLVED", unresolved["ownershipState"])

    def test_final_optional_output_never_serializes_process_or_systemd_secret_sentinels(self):
        service = scan.systemd_service_from_properties({"Id": "safe.service", "Description": "API_KEY=super-secret", "Environment": "API_KEY=super-secret"})
        listener = scan.ParsedListener("tcp", "127.0.0.1", 8080, False, True, ("API_KEY", "super-secret"), (99,))
        with patch.object(scan, "read_process_cgroup", return_value="/system.slice/safe.service"):
            payload = {"systemd": {"services": [service.json_value()]}, "listeners": {"items": [scan.listener_json(listener, [scan.SystemdService("safe.service", control_group="/system.slice/safe.service")]) ]}}
        serialized = json.dumps(payload)
        for forbidden in ("DATABASE_URL", "PASSWORD", "API_KEY", "JWT_SECRET", "TOKEN", "AWS_SECRET_ACCESS_KEY", "PRIVATE_KEY", "super-secret", "--password"):
            self.assertNotIn(forbidden, serialized)

    def test_listener_discovery_is_fixed_and_independent(self):
        command = scan.CommandOutput("tcp LISTEN 0 1 0.0.0.0:80 0.0.0.0:*\n", "", 0)
        with patch.object(scan, "trusted_ss_executable", return_value=Path("/usr/bin/ss")), patch.object(scan, "run_readonly_command", return_value=command) as runner:
            summary = scan.discover_listeners([])
        self.assertEqual("DISCOVERED", summary["status"])
        self.assertEqual([], summary["statusReasons"])
        assert_status_reason_invariant(self, summary)
        self.assertEqual(["-H", "-lntup"], runner.call_args.args[1])

    def test_real_netdata_shape_resolves_two_listeners_from_the_same_cgroup_evidence(self):
        services, partial = scan.parse_systemctl_show((ROOT / "fixtures" / "systemctl-netdata-show.txt").read_text(encoding="utf-8"))
        listeners, listener_partial = scan.parse_ss_output((ROOT / "fixtures" / "ss-netdata-listeners.txt").read_text(encoding="utf-8"))
        cgroup = scan.cgroup_v2_path((ROOT / "fixtures" / "proc-netdata-cgroup.txt").read_text(encoding="utf-8"))
        self.assertFalse(partial)
        self.assertFalse(listener_partial)
        self.assertEqual("/system.slice/netdata.service", cgroup)
        with patch.object(scan, "read_process_cgroup", return_value=cgroup):
            resolved = [scan.listener_json(listener, services) for listener in listeners]
        self.assertEqual(["127.0.0.1", "172.17.0.1"], [item["localAddress"] for item in resolved])
        self.assertTrue(all(item["port"] == 19999 for item in resolved))
        self.assertTrue(all(item["ownershipState"] == "SYSTEMD_SERVICE" for item in resolved))
        self.assertTrue(all(item["systemdUnit"] == "netdata.service" for item in resolved))

    def test_real_netdata_pipeline_is_discovered_without_partial_status(self):
        listing = scan.CommandOutput("netdata.service loaded active running Netdata\nmissing.service not-found inactive dead Missing\n", "", 0)
        shown = scan.CommandOutput((ROOT / "fixtures" / "systemctl-netdata-show.txt").read_text(encoding="utf-8"), "", 0)
        sockets = scan.CommandOutput((ROOT / "fixtures" / "ss-netdata-listeners.txt").read_text(encoding="utf-8"), "", 0)

        def command(_path, arguments):
            return listing if "list-units" in arguments else shown if "show" in arguments else sockets

        with patch.object(scan, "trusted_systemctl_executable", return_value=Path("/usr/bin/systemctl")), patch.object(scan, "trusted_ss_executable", return_value=Path("/usr/bin/ss")), patch.object(scan, "run_readonly_command", side_effect=command), patch.object(scan, "read_process_cgroup", return_value="/system.slice/netdata.service"):
            result = scan.host_discovery()
        self.assertEqual("DISCOVERED", result["systemd"]["status"])
        self.assertEqual("DISCOVERED", result["listeners"]["status"])
        self.assertEqual([], result["systemd"]["statusReasons"])
        self.assertEqual([], result["listeners"]["statusReasons"])
        self.assertEqual(["netdata.service"], [service["unit"] for service in result["systemd"]["services"]])
        self.assertTrue(all(item["systemdUnit"] == "netdata.service" for item in result["listeners"]["items"]))

    def test_real_debian_fixture_is_complete_and_preserves_ownership(self):
        listing = scan.CommandOutput((ROOT / "fixtures" / "systemctl-list-units-debian-real.txt").read_text(encoding="utf-8"), "", 0)
        shown = scan.CommandOutput((ROOT / "fixtures" / "systemctl-show-debian-real.txt").read_text(encoding="utf-8"), "", 0)
        sockets = scan.CommandOutput((ROOT / "fixtures" / "ss-listeners-debian-real.txt").read_text(encoding="utf-8"), "", 0)

        def command(_path, arguments):
            return listing if "list-units" in arguments else shown if "show" in arguments else sockets

        cgroups = {
            402: "/system.slice/ssh.service",
            700: "/system.slice/docker.service",
            701: "/system.slice/docker.service",
            1714490: "/system.slice/netdata.service",
        }
        with patch.object(scan, "trusted_systemctl_executable", return_value=Path("/usr/bin/systemctl")), patch.object(scan, "trusted_ss_executable", return_value=Path("/usr/bin/ss")), patch.object(scan, "run_readonly_command", side_effect=command), patch.object(scan, "read_process_cgroup", side_effect=lambda pid: cgroups.get(pid)):
            result = scan.host_discovery()
        self.assertEqual(("DISCOVERED", []), (result["systemd"]["status"], result["systemd"]["statusReasons"]))
        self.assertEqual(("DISCOVERED", []), (result["listeners"]["status"], result["listeners"]["statusReasons"]))
        self.assertIn(ESCAPED_SYSTEMD_UNIT, [service["unit"] for service in result["systemd"]["services"]])
        listeners = {(item["protocol"], item["localAddress"], item["port"]): item for item in result["listeners"]["items"]}
        self.assertEqual("ssh.service", listeners[("tcp", "0.0.0.0", 22)]["systemdUnit"])
        self.assertEqual("netdata.service", listeners[("tcp", "127.0.0.1", 19999)]["systemdUnit"])
        self.assertEqual("netdata.service", listeners[("tcp", "172.17.0.1", 19999)]["systemdUnit"])
        self.assertEqual("UNRESOLVED", listeners[("tcp", "0.0.0.0", 80)]["ownershipState"])
        self.assertEqual("UNRESOLVED", listeners[("tcp", "::", 443)]["ownershipState"])
        self.assertNotIn("systemdUnit", listeners[("tcp", "0.0.0.0", 80)])
        self.assertNotIn("systemdUnit", listeners[("tcp", "::", 443)])
        self.assertEqual("UNRESOLVED", listeners[("udp", "fe80::eac9:da0a:d32:2190%eth0", 546)]["ownershipState"])

    def test_container_runtime_services_never_become_application_listener_owners(self):
        services = [
            scan.SystemdService("docker.service", control_group="/system.slice/docker.service"),
            scan.SystemdService("containerd.service", control_group="/system.slice/containerd.service"),
        ]
        for cgroup in ("/system.slice/docker.service", "/system.slice/containerd.service"):
            with self.subTest(cgroup=cgroup), patch.object(scan, "read_process_cgroup", return_value=cgroup):
                item = scan.listener_json(scan.ParsedListener("tcp", "0.0.0.0", 80, True, False, ("docker-proxy",), (100,)), services)
            self.assertEqual("UNRESOLVED", item["ownershipState"])
            self.assertNotIn("systemdUnit", item)

    def test_ssh_service_remains_a_valid_systemd_owner(self):
        service = scan.SystemdService("ssh.service", control_group="/system.slice/ssh.service")
        for address in ("0.0.0.0", "::"):
            with self.subTest(address=address), patch.object(scan, "read_process_cgroup", return_value="/system.slice/ssh.service"):
                item = scan.listener_json(scan.ParsedListener("tcp", address, 22, address in {"0.0.0.0", "::"}, False, ("sshd",), (22,)), [service])
            self.assertEqual("SYSTEMD_SERVICE", item["ownershipState"])
            self.assertEqual("ssh.service", item["systemdUnit"])

    def test_docker_runtime_suppression_is_exact_and_keeps_ipv4_ipv6_listener_discovery_complete(self):
        services = [
            scan.SystemdService("docker.service", control_group="/system.slice/docker.service"),
            scan.SystemdService("docker-metrics.service", control_group="/system.slice/docker-metrics.service"),
        ]
        output = "\n".join((
            "tcp LISTEN 0 1 0.0.0.0:80 0.0.0.0:* users:((\"docker-proxy\",pid=100,fd=4))",
            "tcp LISTEN 0 1 [::]:443 [::]:* users:((\"docker-proxy\",pid=101,fd=4))",
            "tcp LISTEN 0 1 127.0.0.1:9090 0.0.0.0:* users:((\"docker-metrics\",pid=102,fd=4))",
        ))
        command = scan.CommandOutput(output, "", 0)
        cgroups = {100: "/system.slice/docker.service", 101: "/system.slice/docker.service", 102: "/system.slice/docker-metrics.service"}
        with patch.object(scan, "trusted_ss_executable", return_value=Path("/usr/bin/ss")), patch.object(scan, "run_readonly_command", return_value=command), patch.object(scan, "read_process_cgroup", side_effect=lambda pid: cgroups[pid]):
            summary = scan.discover_listeners(services)
        self.assertEqual("DISCOVERED", summary["status"])
        self.assertEqual([], summary["statusReasons"])
        assert_status_reason_invariant(self, summary)
        by_address = {item["localAddress"]: item for item in summary["items"]}
        self.assertEqual("UNRESOLVED", by_address["0.0.0.0"]["ownershipState"])
        self.assertEqual("UNRESOLVED", by_address["::"]["ownershipState"])
        self.assertEqual("SYSTEMD_SERVICE", by_address["127.0.0.1"]["ownershipState"])
        self.assertEqual("docker-metrics.service", by_address["127.0.0.1"]["systemdUnit"])


@unittest.skipUnless(sys.platform == "linux", "secure dirfd/O_NOFOLLOW behavior requires Linux")
class CaddyFilesystemSecurityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.mount = Path(self.temporary.name) / "config-volume"
        (self.mount / "caddy").mkdir(parents=True)

    def tearDown(self):
        self.temporary.cleanup()

    def test_normal_autosave_read(self):
        expected = fixture_bytes("caddy-routes.json")
        (self.mount / "caddy" / "autosave.json").write_bytes(expected)
        self.assertEqual(expected, scan.secure_read_caddy_autosave(str(self.mount)))

    def test_missing_file_is_unavailable(self):
        with self.assertRaises(scan.CaddyConfigFailure) as error:
            scan.secure_read_caddy_autosave(str(self.mount))
        self.assertEqual("CONFIG_UNAVAILABLE", error.exception.status)

    def test_oversized_file_is_rejected(self):
        with (self.mount / "caddy" / "autosave.json").open("wb") as stream:
            stream.truncate(scan.MAX_CADDY_CONFIG_BYTES + 1)
        with self.assertRaises(scan.CaddyConfigFailure) as error:
            scan.secure_read_caddy_autosave(str(self.mount))
        self.assertEqual("CONFIG_TOO_LARGE", error.exception.status)

    def test_final_symlink_is_rejected(self):
        outside = Path(self.temporary.name) / "outside.json"
        outside.write_text("private_key", encoding="utf-8")
        os.symlink(outside, self.mount / "caddy" / "autosave.json")
        with self.assertRaises(scan.CaddyConfigFailure) as error:
            scan.secure_read_caddy_autosave(str(self.mount))
        self.assertEqual("CONFIG_UNAVAILABLE", error.exception.status)

    def test_intermediate_caddy_symlink_is_rejected(self):
        (self.mount / "caddy").rmdir()
        outside = Path(self.temporary.name) / "outside"
        outside.mkdir()
        (outside / "autosave.json").write_text("private_key", encoding="utf-8")
        os.symlink(outside, self.mount / "caddy", target_is_directory=True)
        with self.assertRaises(scan.CaddyConfigFailure) as error:
            scan.secure_read_caddy_autosave(str(self.mount))
        self.assertEqual("CONFIG_UNAVAILABLE", error.exception.status)

    def test_non_regular_file_is_rejected(self):
        (self.mount / "caddy" / "autosave.json").mkdir()
        with self.assertRaises(scan.CaddyConfigFailure) as error:
            scan.secure_read_caddy_autosave(str(self.mount))
        self.assertEqual("CONFIG_UNAVAILABLE", error.exception.status)


if __name__ == "__main__":
    unittest.main()
