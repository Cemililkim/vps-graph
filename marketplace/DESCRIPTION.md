# VPS Graph

Explore what is running on your VPS and how it connects.

VPS Graph is a read-only infrastructure explorer for developers who manage a Linux VPS from IntelliJ IDEA. A manual scan connects directly over SSH and presents a local, navigable view of the host, Docker and Compose applications, Caddy routes, Systemd services, host listeners, networks, storage mounts, and infrastructure changes.

The plugin has no account, backend, telemetry, cloud sync, or remote-management controls. SSH host keys are verified against the user's OpenSSH `known_hosts` file, commands are fixed and read-only, and deeper discovery uses an optional argument-free helper with a narrow sudoers rule and strictly sanitized JSON output.

Requirements:

- IntelliJ IDEA 2025.1.x (build 251.*)
- a Debian or Ubuntu-focused Linux VPS reachable with public-key SSH
- an already verified host key in the local `known_hosts` file
- optional reviewed server-helper installation for Docker, Compose, Caddy, Systemd, and listener discovery

Scans are manual. VPS Graph is not real-time monitoring, a security scanner, or a complete inventory of every host resource.
