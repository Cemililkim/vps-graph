# VPS Graph Release Hardening R1

R1 validates the existing local-first, read-only product. It adds no remote action, helper privilege, automatic installation, monitoring, or new discovery subsystem.

## Compatibility

- IDE: IntelliJ IDEA Community 2025.1.x, platform build `251.*`; verified build is 2025.1.7.2.
- Target focus: Debian 12/13 and Ubuntu 22.04/24.04. Other Linux distributions may provide usable host/Docker data, but are not release-tested targets.
- Authentication: public-key only, using the user's OpenSSH `known_hosts`; unknown and changed keys fail closed.
- Helper: schema version 1. Later Caddy/Systemd/listener fields are optional, so an older valid schema-1 helper remains parseable and absent capabilities degrade independently.

## Release test matrix

`C` means complete, `P` partial, `U` unavailable/unsupported, and `N/A` not reached.

| Scenario | Expected Host | Expected Docker | Expected Caddy | Expected Systemd | Expected Listeners | Expected UI state | Snapshot allowed? | Diff-removal confidence | Automated | Manual |
|---|---|---|---|---|---|---|---|---|---|---|
| Normal full Debian VPS | C | C | C | C | C | Connected, all workspaces | Yes | Confirmed per complete subsystem | Fixtures | Required |
| Debian 12 / 13 OS metadata | C | Optional | Optional | Optional | Optional | Connected | Yes | Per subsystem | Yes | Debian normal path |
| Ubuntu 22.04 / 24.04 metadata | C | Optional | Optional | Optional | Optional | Connected | Yes | Per subsystem | Yes | Optional |
| Helper missing/not executable/sudo denied | C | U | U | U | U | Host remains usable; setup guidance | Yes | Optional removals unconfirmed | Yes | Required |
| Old schema-1 helper, optional fields absent | C | C | U | U | U | Docker available; other sections unavailable | Yes | Only Docker complete | Yes | Optional |
| Helper malformed/empty/non-zero/oversized/timeout | C | U/P | U | U | U | Host remains usable; helper error | Yes | Optional removals unconfirmed | Yes | Optional |
| Docker absent or daemon stopped | C | U | U | Independent | Independent | Host/services remain usable | Yes | Docker/Caddy removals unconfirmed | Yes | Required |
| Docker zero containers | C | C | U | Independent | Independent | Intentional empty Applications/Storage | Yes | Docker absence confirmed | Yes | Optional |
| Docker plus no Caddy | C | C | U | C | C | Routing empty, other pages usable | Yes | Caddy removals unconfirmed | Yes | Required |
| Non-Systemd host | C | Independent | Independent | U | U/P | Services unsupported; graph usable | Yes | Service/listener removals unconfirmed | Yes | Optional |
| All optional subsystems partial | C | P | P | P | P | Partial guidance; validated data shown | Yes | Unconfirmed where evidence incomplete | Yes | Fixture |
| Empty/minimal VPS | C | U | U | C | C | Useful host/service overview | Yes | Per complete subsystem | Fixture | Required |
| 300 containers, 100 networks, 1000 ports/mounts | C | C | Optional | Optional | Optional | Grouped projections/search remain responsive | Yes | Per subsystem | Yes | No |
| Caddy absent/inaccessible/malformed/oversized | C | C | U/P | Independent | Independent | Routing unavailable/partial; Docker preserved | Yes | Caddy removals unconfirmed | Yes | Optional |
| Dynamic/unsupported Caddy route | C | C | P/C | Independent | Independent | Unsupported route remains unresolved | Yes | No guessed resolution | Yes | No |
| Escaped/template/vanished Systemd units | C | Independent | Independent | C/P | Independent | Valid units shown; incomplete batches labelled | Yes | Conditional | Yes | Debian regression |
| IPv4/IPv6/scoped listeners; unknown owner | C | Independent | Independent | Independent | C/P | Valid sockets shown without guessed owner | Yes | Conditional | Yes | Debian regression |
| First connection failure | N/A | N/A | N/A | N/A | N/A | Connection screen with actionable error | No | No diff | Yes | Required |
| Failed rescan after success | Previous C | Previous | Previous | Previous | Previous | Previous graph/history retained and labelled stale | No | No new diff | Yes | Required |
| Rapid duplicate rescan | Previous/current | Current | Current | Current | Current | Duplicate request disabled/rejected | Success only | One generation only | Code/test review | Required |
| Empty/one/50 snapshots and retention | C | As scanned | As scanned | As scanned | As scanned | History bounded and deterministic | Yes | Valid snapshots only | Yes | Optional |
| Corrupt/missing index or corrupt/orphan snapshot | C | As scanned | As scanned | As scanned | As scanned | Live graph usable; valid history rebuilt | Yes | Corrupt input excluded | Yes | Optional |
| Future/oversized snapshot | C | As scanned | As scanned | As scanned | As scanned | Unsupported history isolated, not deleted | Yes | No comparison | Yes | No |
| Missing/retention-deleted comparison target | Current C | Current | Current | Current | Current | Quiet typed Changes error | Current success only | No guessed diff | Yes | Optional |
| No baseline / no changes / many changes | C | As scanned | As scanned | As scanned | As scanned | Explicit Changes state | Yes/deduplicated | Evidence-qualified | Yes | Required |
| Malformed/unknown/oversized JCEF request | Current state | Current | Current | Current | Current | Request rejected; UI state preserved | No effect | No effect | Yes | No |
| Bundled frontend unavailable / JCEF unsupported | N/A | N/A | N/A | N/A | N/A | Native bounded Tool Window message | No | No diff | Code/test review | Packaging check |

## Non-destructive manual release checklist

- Launch the plugin in IntelliJ IDEA Community 2025.1.7.2 and confirm the Tool Window opens without external navigation or popups.
- With no remembered connection, verify host, port, username, key, `known_hosts`, and optional-helper prerequisites are understandable.
- On the existing Debian VPS, verify a successful scan, then an identical rescan reports no changes.
- Verify remembered connection prefill after IDE restart; confirm there is no auto-connect or auto-scan.
- Open Services, Routing, Network, Storage, and Changes. Confirm `monitor.example.net` resolves to `netdata.service` and Docker 80/443 bindings do not acquire Docker runtime ownership.
- Verify escaped Systemd units, scoped IPv6, long safe paths, Details navigation, and History after IDE restart.
- Trigger a safe connection failure after a successful scan and confirm the previous graph/history remain visible and no snapshot is added.
- Check dark/light themes and a 600 px-wide Tool Window for horizontal overflow, blank workspaces, unreadable errors, `NaN`, or `undefined` counts.
- Do not change the VPS, Docker daemon, sudoers, services, routes, firewall, files, or credentials during this checklist.

## Security and dependency audit

The remote allowlist, exact sudo helper command, argument-free helper, no-shell subprocess execution, no Docker-group access, known-host verification, sanitized optional schema, snapshot allowlists, and JCEF popup/navigation policy are unchanged. Python helper runtime imports remain standard-library only. Dependency versions are intentionally held stable unless a verified release incompatibility requires a scoped update.

## Intentional limitations

VPS Graph is Debian/Ubuntu focused, public-key SSH only, Systemd/cgroup-v2 oriented for host-service ownership, Caddy-only for reverse-proxy discovery, and local-only for history. It performs no firewall inspection, reachability/health verification, scheduled monitoring, restore, cloud sync, cross-server comparison, or infrastructure management.
