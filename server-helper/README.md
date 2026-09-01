# VPS Graph Docker, Caddy, Systemd, and Listener Inspection Helper

This is the optional privileged server-side boundary for sanitized Docker, Compose, Caddy, Systemd, and host-listener metadata consumed by VPS Graph 0.1.0.

## Why this helper exists

Docker socket access is effectively a privileged capability. Adding the VPS Graph SSH user to the `docker` group, or granting it `sudo docker`, would let that account perform broad Docker operations and can lead to root-level host access.

Instead, the SSH user receives exactly one passwordless sudo command with no arguments:

```sudoers
vpsgraph ALL=(root) NOPASSWD: /usr/local/libexec/vpsgraph-docker-scan ""
```

The helper accepts no arguments, uses no user-controlled `PATH`, shell, container ID, filter, or command, and invokes only these fixed read-only Docker operations:

```text
docker version --format {{.Server.Version}}
docker ps -aq
docker inspect --type container <Docker-discovered IDs in batches>
```

It never adds users to groups, exposes the Docker socket, or runs Docker management commands.

Caddy discovery adds no executable, Docker command, sudoers entry, network request, or runtime argument. It only derives a trusted host path from the already-inspected Docker `/config` mount and reads the standard persisted active configuration described below.

Milestone 5A reuses this same historical helper name for optional host service and listener metadata. It adds no sudo rule, group membership, helper argument, service configuration read, log access, or generic `sudo systemctl`/`sudo ss` capability.

## Installation

Use the `server-helper/` directory from the same tagged source release as the plugin, or the matching `vps-graph-server-helper-0.1.0.tar.gz` release artifact. The source repository is [github.com/cemililkim/vps-graph](https://github.com/cemililkim/vps-graph); a direct archive URL will be published only with the matching GitHub Release. Verify the published SHA-256 file for download integrity, then extract and review every file before copying it to the VPS. The checksum detects download corruption; it is not a publisher signature. VPS Graph deliberately does not recommend `curl | sudo` installation.

Review these files on the target Linux VPS, then run explicitly as root through sudo:

```sh
cd server-helper
sudo sh ./install.sh vpsgraph
```

The installer verifies Linux, Python 3, the target user, a trusted root-owned non-group/world-writable Docker executable, and `visudo`. It installs:

```text
/usr/local/libexec/vpsgraph-docker-scan    root:root 0755
/etc/sudoers.d/vpsgraph-docker-scan-vpsgraph    root:root 0440
```

The sudoers file is syntax-checked by `visudo` before it replaces the active policy. The installer never modifies `/etc/sudoers`, installs packages, changes group membership, or restarts Docker.

Uninstall only the matching helper artifacts:

```sh
sudo sh ./uninstall.sh vpsgraph
```

The uninstaller refuses to delete a sudoers or helper file whose expected VPS Graph content is missing.

## Sanitized schema

Successful output is deterministic JSON:

```json
{
  "schemaVersion": 1,
  "ok": true,
  "engine": {"version": "28.3.2"},
  "containers": [],
  "composeProjects": [],
  "caddy": {
    "status": "NOT_DETECTED",
    "instances": []
  },
  "systemd": {
    "status": "DISCOVERED",
    "services": []
  },
  "listeners": {
    "status": "DISCOVERED",
    "items": []
  }
}
```

Each container may contain only `id`, `name`, `image`, `state`, `restartPolicy`, sanitized `ports`, sanitized `mounts`, sorted network names, and the four explicit Docker Compose labels: project, service, working directory, and config files. Compose project summaries are derived only from those labels.

Raw `docker inspect` data is transient within the helper process and is never printed, logged, saved, or returned. It deliberately drops environment variables, all non-Compose labels, commands, entrypoints, healthchecks, host configuration, logs, credentials, secret mounts, and unknown fields.

Failures are also JSON with `schemaVersion`, `ok: false`, and a non-sensitive error code such as `DOCKER_UNAVAILABLE`, `DOCKER_DAEMON_UNAVAILABLE`, `UNTRUSTED_DOCKER_EXECUTABLE`, `DOCKER_INSPECTION_FAILED`, or `INVALID_INVOCATION`.

## Optional Systemd and host-listener discovery

The helper may invoke only trusted root-owned, non-group/world-writable absolute binaries at `/usr/bin/systemctl` or `/bin/systemctl`, and `/usr/bin/ss` or `/bin/ss`. Their fixed commands are:

```text
systemctl --system --no-legend --no-pager --plain list-units --type=service --all
systemctl --system --no-pager show --property=<fixed allowlist> -- <systemctl-discovered service units in batches>
ss -H -lntup
```

The Systemd property allowlist is `Id`, `Description`, `LoadState`, `ActiveState`, `SubState`, `UnitFileState`, `MainPID`, `ControlGroup`, `Type`, `User`, and `Group`. `MainPID` is transient helper-only resolution data and is deliberately omitted from JSON. The serialized `systemd.services` entries contain only safe service identity/state fields and an optional `controlGroup`.

`listeners.items` contains only `protocol`, numeric `localAddress` and `port`, `wildcard`, `loopback`, an ownership state, and optional safe `systemdUnit`/short `processName`. TCP and UDP are separate. Wildcard binding does **not** imply public reachability; firewalling, routing, DNS, TLS, and reachability are not inspected or claimed.

Listener ownership is conservative: an `ss` PID is used only to read `/proc/<numeric-pid>/cgroup`, then matched to the longest discovered Systemd `ControlGroup`. Child workers can therefore map to their service without treating PIDs as identities. Missing/racing processes, cgroup v1 or unknown layouts, socket activation, PID 1, Docker scopes, and multiple services remain `UNRESOLVED` or `AMBIGUOUS`; they are never guessed.

Systemd and listener discovery are independent optional sections. Missing binaries, non-Systemd hosts, malformed/oversized output, command failures, and bounded truncation are reported through `NOT_AVAILABLE`, `NOT_SYSTEMD`, `COMMAND_FAILED`, or `PARTIAL` without changing successful Docker/Caddy output to `ok: false`.

Both optional sections always include a deterministic `statusReasons` array. `PARTIAL` is emitted only when that array is non-empty. Systemd reasons are limited to `COMMAND_FAILED`, `TRUNCATED`, `LIST_ROW_MALFORMED`, `SHOW_RECORD_MISSING`, and `SHOW_RECORD_MALFORMED`. Listener reasons are limited to `COMMAND_FAILED`, `TRUNCATED`, `LISTENER_ROW_MALFORMED`, `LISTENER_PROTOCOL_UNSUPPORTED`, `LISTENER_ADDRESS_UNSUPPORTED`, and `LISTENER_PORT_INVALID`. These are fixed diagnostic codes only: they never contain unit names, process names, paths, PIDs, command output, exceptions, or other host data. Skipped non-loaded Systemd rows and unresolved listener ownership do not make discovery partial.

Raw `systemctl`, `ss`, and cgroup output is transient only. The helper never reads unit files, drop-ins, configuration directories, journal logs, `/proc/*/environ`, `/proc/*/cmdline`, `/proc/*/mem`, or `ps`; it never requests `Environment`, `Exec*`, credential, bind-path, root-image, or other configuration properties. Environment values, command lines, service files, logs, and unknown fields are never serialized.

The plugin consumes only the sanitized fields documented here. It does not receive raw `systemctl`, `ss`, cgroup, Docker inspect, or Caddy configuration output.

## Caddy active configuration discovery

The helper conservatively recognizes official Caddy image references from trusted Docker inspect metadata; a container name alone is never sufficient. Zero, one, or multiple instances are supported. Each candidate must expose exactly one bind or named-volume mount at `/config`. The host-side source is accepted only from that Docker mount metadata.

For the official Caddy image layout, the helper reads only:

```text
<Docker-provided /config source>/caddy/autosave.json
```

This is Caddy's persisted active native JSON. VPS Graph deliberately does not:

- expose or call the Caddy Admin API;
- publish or require port `2019`;
- issue any network request to Caddy, domains, or upstreams;
- run `docker exec` or commands inside the container;
- read `/etc/caddy/Caddyfile` or maintain a custom Caddyfile parser.

The privileged Linux read is fail-closed. The mount source must be a normalized absolute path other than `/`. Every source component and the `caddy` directory is opened relative to a directory descriptor with `O_DIRECTORY | O_NOFOLLOW`; `autosave.json` is opened with `O_NOFOLLOW`, verified as a regular file, and read through the opened descriptor. Symlinked path components, final symlinks, missing/non-regular files, and unsupported platforms are rejected. Both `fstat` and a bounded read enforce a 4 MiB maximum.

Raw autosave JSON is transient, never logged or returned, and immediately projected into this allowlist:

- Caddy container ID, existing safe container name, and official image reference;
- normalized HTTP host matchers and optional path matchers;
- static reverse-proxy dial addresses in conventional `host:port`, IPv4, or bracketed IPv6 form;
- explicit hostless/catch-all, unresolved-placeholder, and unsupported-dynamic states.

Instances, routes, hosts, paths, and upstreams are sorted deterministically. Unknown Caddy modules and fields are dropped. Environment placeholders, request/response headers, raw labels, authentication hashes, TLS/DNS-provider configuration, tokens, credentials, certificate/private-key material, and arbitrary module configuration are never serialized.

Caddy discovery is optional. Its status is one of `NOT_DETECTED`, `DISCOVERED`, `CONFIG_MOUNT_MISSING`, `CONFIG_UNAVAILABLE`, `CONFIG_TOO_LARGE`, `CONFIG_INVALID`, `AMBIGUOUS`, or `PARTIAL`. A Caddy error leaves the top-level Docker result at `ok: true`.

## Local tests

No Docker daemon, root account, or VPS is required:

```sh
python3 -m unittest discover -s tests -v
python3 -m py_compile vpsgraph_docker_scan.py
sh -n install.sh
sh -n uninstall.sh
```

Parser, sanitizer, detection, deterministic-output, and secret-exclusion tests run cross-platform. Secure-open tests for normal, missing, oversized, symlinked, and non-regular autosave objects run only on Linux because Windows cannot faithfully exercise Linux `dir_fd` and `O_NOFOLLOW` behavior.

## Manual VPS security test plan

Do not run installation automatically from this repository. On a deliberately selected VPS after reviewing the helper, verify:

```sh
sudo -l -U vpsgraph
id -nG vpsgraph
sudo -u vpsgraph docker ps
sudo -u vpsgraph sudo -n docker ps
sudo -u vpsgraph sudo -n /usr/local/libexec/vpsgraph-docker-scan foo
sudo -u vpsgraph sudo -n /usr/local/libexec/vpsgraph-docker-scan
sudo -u vpsgraph test ! -r /var/lib/docker/volumes/<caddy-config-volume>/_data/caddy/autosave.json
```

Replace `<caddy-config-volume>` with the deliberately selected test VPS volume name; do not print the file while testing access controls.

Expected results:

- `vpsgraph` is not in the `docker` group.
- Direct `docker ps` fails unless the host independently grants Docker access.
- `sudo -n docker ps` fails.
- Supplying `foo` fails with `INVALID_INVOCATION`.
- The argument-free helper succeeds and returns the unchanged Docker fields plus an optional sanitized `caddy` object.
- Known domains and static reverse-proxy upstreams appear, but raw Caddy JSON, tokens, placeholders, headers, authentication data, and unrelated module fields do not.
- `vpsgraph` still cannot read the mounted autosave file or access Docker directly.
- No Caddy Admin API or port `2019` exposure is required.

After deliberately updating the already-installed helper, verify the additional sanitized sections without changing any service:

```sh
sudo -u vpsgraph sudo -n /usr/local/libexec/vpsgraph-docker-scan \
  | python3 -c '
import sys,json
d=json.load(sys.stdin)
for key in ("systemd","listeners"):
    x=d.get(key,{})
    print(key)
    print(" status:", x.get("status"))
    print(" reasons:", x.get("statusReasons"))
'
sudo -u vpsgraph sudo -n /usr/bin/systemctl list-units
sudo -u vpsgraph sudo -n /usr/bin/ss -lntp
```

The helper must remain the only accepted sudo command. The direct `sudo systemctl` and `sudo ss` commands must fail. Confirm the complete JSON contains no `Environment`, `ExecStart`, `DATABASE_URL`, `PASSWORD`, `TOKEN`, `API_KEY`, `PRIVATE_KEY`, or command-line arguments. If a known host-native service listens on a port, compare its unit, listener, and conservative ownership result without restarting or reconfiguring anything.

## MVP limitations

- Linux and `/usr/bin/python3` only; no pip dependencies.
- Docker CLI locations are limited to `/usr/bin/docker` and `/usr/local/bin/docker` and must be root-owned and non-group/world-writable.
- No Docker Compose command is executed; Compose metadata is label-derived only.
- Caddy detection intentionally recognizes only conservative official-image references and the standard `/config/caddy/autosave.json` layout. Custom images, nonstandard config homes, Caddyfile-only deployments, and dynamic upstream modules may be unavailable or unsupported.
- Systemd discovery is limited to system-level `.service` unit state and a fixed safe-property allowlist; it does not read unit files, service configuration, logs, or process command lines.
- Listener ownership requires reliable cgroup v2 evidence. Socket-activated services, cgroup v1, Docker scopes, disappeared PIDs, and multi-owner sockets may remain unresolved or ambiguous.
- The helper only exposes sanitized metadata. VPS Graph does not manage Docker or Caddy, expose either control interface, read environment variables, Compose files, unrelated mounted files, container logs, or secrets.
