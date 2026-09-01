# Getting started

VPS Graph is a local-first, read-only JetBrains plugin. It connects directly from the IDE to one Linux VPS over SSH; it has no account, backend, telemetry, or cloud sync.

## 1. Requirements

- IntelliJ IDEA 2025.1
- a Linux VPS reachable over SSH
- an existing SSH public/private key pair accepted by the VPS
- the VPS host key in this computer's OpenSSH `known_hosts` file
- optionally, Docker and the VPS Graph server helper for Docker, Compose, Caddy, Systemd, and host-listener discovery

Password authentication, SSH agents, passphrase-protected keys, OpenSSH config parsing, ProxyJump, and bastion hosts are not supported in this release.

## 2. Install the plugin

Install the reviewed Marketplace release or locally built plugin ZIP in IntelliJ IDEA, restart if prompted, then open **VPS Graph** from the right Tool Window bar. Source is published at [github.com/cemililkim/vps-graph](https://github.com/cemililkim/vps-graph). A direct release download URL will be added only after the matching GitHub Release exists.

## 3. Prepare SSH public-key access

Use an existing Linux account that accepts your public key. A dedicated low-privilege account such as `vpsgraph` is recommended, but root is not required. VPS Graph reads the selected private key locally for authentication. The key remains on this computer, and its contents are never stored by the plugin.

The plugin does not create accounts, generate keys, upload public keys, or change server SSH configuration.

## 4. Verify the host identity

VPS Graph connects only to hosts already trusted in `${user.home}/.ssh/known_hosts`. Establish trust with your normal SSH client:

```sh
ssh <scan-user>@<host>
```

Compare the fingerprint shown by SSH with the fingerprint from your VPS provider or another trusted channel before accepting it. VPS Graph rejects unknown and changed keys, never edits `known_hosts`, and offers no bypass. `ssh-keyscan` output alone does not authenticate a server.

## 5. Connect

Open **VPS Graph** and enter:

- **Host:** hostname, IPv4 address, or IPv6 address
- **Port:** `22` unless the SSH service uses another port
- **Username:** the Linux account used for read-only discovery
- **Private key:** the existing local private-key file, selected with **Browse** or entered as a path

Choose **Remember this connection** only if you want the host, port, username, and key file path restored on restart. Key contents, passwords, and passphrases are never persisted. Remembering a connection never connects or scans automatically.

Select **Scan server**. Basic hostname, OS, kernel, and architecture discovery works without the optional helper.

## 6. Optional server helper

The helper is an argument-free executable invoked only during a scan. It enables sanitized Docker and Compose metadata, Caddy routes, Systemd services, and host listeners. It is not a daemon, background agent, or listening network service.

Review the `server-helper/` directory from the same tagged source release as the plugin, then copy that directory or the matching checksum-verified helper archive to the VPS. Run from that directory:

```sh
cd server-helper
sudo sh ./install.sh <scan-user>
```

The installer requires Linux, `/usr/bin/python3`, `visudo`, the target user, and a trusted root-owned Docker executable. It installs `/usr/local/libexec/vpsgraph-docker-scan` as `root:root 0755` and grants only that exact argument-free command through a `root:root 0440` file in `/etc/sudoers.d/`.

Verify the installed policy and execution:

```sh
sudo -l -U <scan-user>
sudo -u <scan-user> sudo -n /usr/local/libexec/vpsgraph-docker-scan
```

The scan user does not need unrestricted sudo, Docker-group membership, or Docker socket access. Return to VPS Graph and choose **Rescan**; there is no hidden retry or separate probe. See [the helper documentation](../server-helper/README.md) for installation, removal, sanitized schema, and manual security tests.

The SHA-256 file published beside a helper archive verifies download integrity only. It is not a cryptographic publisher signature.

## 7. First scan

A successful scan opens **Overview** and captures a local baseline. If optional discovery is unavailable, basic host data and existing local history remain usable. A failed rescan preserves the last successful graph and history.

## 8. Workspaces

- **Overview:** host identity, capability status, and a compact infrastructure summary.
- **Applications:** discovered Compose projects, standalone containers, and their services.
- **Services:** sanitized Systemd service state and listener ownership.
- **Routing:** validated Caddy domain-to-upstream relationships.
- **Network:** host listeners, Docker networks, and published container ports.
- **Storage:** allowlisted container mount metadata grouped by container.
- **Changes:** local snapshot history and evidence-qualified infrastructure differences.

VPS Graph observes sanitized metadata. It does not manage services or containers, run a remote terminal, restore snapshots, or modify the VPS.
