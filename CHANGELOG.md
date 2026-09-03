# Changelog

All notable user-facing changes to VPS Graph are documented here. The 0.1.x line is a public beta and may evolve while preserving supported local history through explicit migrations where needed.

## [0.1.1] - 2026-09-03

### Fixed

- Added compatibility with IntelliJ Platform 2026.2.
- Fixed Tool Window initialization on newer JetBrains IDE versions.
- Fixed JCEF class visibility on IntelliJ Platform 2026.2.
- Verified compatibility with IntelliJ IDEA 2025.1, IntelliJ IDEA 2026.2, and PyCharm 2026.2.

## [0.1.0] - 2026-08-31

### Added

- Manual read-only SSH discovery for one Debian or Ubuntu-focused Linux VPS
- Docker and Compose exploration through an optional sanitized server helper
- Caddy domain, route, and upstream relationships
- Systemd service and host-listener views
- Overview, Applications, Routing, Services, Network, Storage, and Changes workspaces
- Local infrastructure snapshots and evidence-qualified history changes
- Remembered safe connection fields, first-run setup guidance, and local Disconnect

### Security

- Fail-closed OpenSSH `known_hosts` verification and public-key-only authentication
- Fixed remote command allowlist with finite timeouts
- Argument-free privileged helper with exact sudoers scope and strict output allowlisting
- Restricted JCEF bridge and navigation policy
- No account, backend, telemetry, cloud sync, or remote management surface

### Known limitations

- Supports one active server and IntelliJ IDEA build 251.*
- SSH passwords, agents, passphrase UX, OpenSSH config, ProxyJump, and bastions are not supported
- Discovery is manual and Debian/Ubuntu-focused; it is not monitoring or complete infrastructure coverage
- The optional helper must be reviewed and installed manually on the VPS
