# VPS Graph

VPS Graph is a local-first, read-only JetBrains plugin for exploring what is running on one Linux VPS and how it connects.

Source: [github.com/cemililkim/vps-graph](https://github.com/cemililkim/vps-graph)

> Screenshot preview: final sanitized Marketplace screenshots are listed in [the release screenshot manifest](marketplace/SCREENSHOTS.md).

## What it does

- explores Docker and Compose applications
- maps Caddy domains, routes, and upstreams
- shows sanitized Systemd services and host listeners
- relates containers, ports, networks, mounts, services, and routes
- records local infrastructure snapshots and evidence-qualified Changes history

## Why VPS Graph

VPS Graph connects directly from the IDE to the selected VPS. It has no account, backend, telemetry, analytics, cloud sync, or remote-management controls. Scans are manual, SSH commands are fixed and read-only, and the optional privileged helper returns only strictly allowlisted metadata.

## Requirements

- IntelliJ IDEA 2025.1.x (build 251.*)
- a Debian or Ubuntu-focused Linux VPS reachable over SSH
- public-key authentication and a locally readable private-key file
- the verified VPS host key in the local OpenSSH `known_hosts` file
- optional reviewed server helper for Docker, Compose, Caddy, Systemd, and listener discovery

SSH passwords, agents, passphrase UX, OpenSSH config parsing, ProxyJump, and bastion hosts are not supported in the 0.1.x line.

## Quick start

1. Build or install the VPS Graph plugin ZIP.
2. Open the **VPS Graph** Tool Window.
3. Enter the host, port, username, and private-key path.
4. Select **Scan server**.

Read [Getting started](docs/GETTING_STARTED.md) before connecting to a VPS.

## Server helper

Basic host discovery does not need elevated access. Deeper Docker, Compose, Caddy, Systemd, and listener discovery requires the optional [server helper](server-helper/README.md). It exists because direct Docker socket access is effectively privileged. The helper is transparent Python standard-library source, accepts no runtime arguments, and is granted only one exact sudo command.

Review the source before copying it to a VPS. VPS Graph does not install it automatically and does not recommend `curl | sudo` installation. See the [security model](docs/SECURITY_MODEL.md) for the complete boundary.

### Recommended SSH user setup

VPS Graph does not require a dedicated SSH username, but using a separate,
unprivileged account such as `vpsgraph` is recommended for better privilege
isolation.

An existing SSH account is also supported. The optional server helper does not
create users, add them to the `docker` group, or grant unrestricted sudo access.
It only grants the selected SSH user permission to run the exact, argument-free
VPS Graph inspection helper.

See [`server-helper/README.md`](server-helper/README.md) for the complete setup
and security model.

## Screenshots

Final public screenshots must use sanitized example infrastructure. The required views and privacy checks are in [marketplace/SCREENSHOTS.md](marketplace/SCREENSHOTS.md).

## Current limitations

- one active server at a time
- IntelliJ IDEA build 251.* only
- manual scans, not monitoring
- Debian and Ubuntu-focused discovery
- no SSH password, agent, passphrase, ProxyJump, or bastion support
- no remote terminal, service management, container management, or infrastructure writes
- discovery is intentionally allowlisted, not complete host inventory

## Development

Requirements: JDK 21, Node.js with npm, and Python 3.

```text
npm --prefix graph-ui ci
npm --prefix graph-ui test
npm --prefix graph-ui run build
./gradlew :scanner-core:test
./gradlew :intellij-plugin:test
./gradlew :intellij-plugin:buildPlugin
./gradlew :intellij-plugin:verifyPlugin
python -m unittest discover -s server-helper/tests -v
```

Use `gradlew.bat` on Windows. `./gradlew releaseArtifacts` produces the plugin ZIP plus a deterministic helper archive and SHA-256 integrity checksum.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security reports must follow [SECURITY.md](SECURITY.md).

## Privacy

See [PRIVACY.md](PRIVACY.md) for the local data boundary.

## License

VPS Graph is released under the [Apache License 2.0](LICENSE). Publisher and copyright holder: Cemil İlkim Teke. Third-party license details are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
